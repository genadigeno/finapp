package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplAccounts;
import com.finapp.bnpl.BnplCapital;
import com.finapp.bnpl.BnplHeadroom;
import com.finapp.bnpl.BnplLedgerAccounts;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.AcceptanceEvidence;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanAcceptances;
import com.finapp.lending.LoanOfferId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * bnpl's capital port against lending's real acceptance, on the one capital row (`P12-TSK-005`; owner decision B2,
 * ADR-0104 decisions 3 to 5, PHASE_12_PLAN.md sections 7.2, 7.3(f) and 12.9; {@code INV-BNPL-10}, {@code INV-LND-13}):
 * the wired {@link BnplCapital} - {@code app}'s {@code LendingBnplCapital} over {@link LendingCapitalHeadroom#underLock} -
 * and a loan's acceptance through the real door or the real {@link LoanAcceptances}, credit's decision and consumption
 * beneath.
 *
 * <ul>
 *   <li>{@code #aBnplJudgementAndALoanAcceptanceSerialiseOnTheCapitalRow} - both orders, the waiting statement asserted:
 *       a BNPL judgement holding L6 (and the BNPL principal it funds, uncommitted) makes the acceptance wait on the
 *       capital's {@code ledger_account} row and then refuses it {@code 422 lending.CapitalUnavailable}; an acceptance
 *       holding L6 (its loan born, uncommitted) makes the BNPL judgement wait on the same row and then find nothing.
 *   <li>{@code #theLastCapitalAdmitsOne} - five BNPL judgements and five loan acceptances racing for capital that fits
 *       one: exactly one admitted, headroom exactly zero, never below.
 * </ul>
 *
 * <p><strong>The BNPL principal is a journal fixture</strong>, the `P11-TSK-013` precedent: no purchase exists until
 * `P12-TSK-013`, so a BNPL obligation's six accounts are opened by bnpl's real {@link BnplAccounts} and its principal
 * posted through the ledger's own {@link PostingService} ({@code DR BNPL_PRINCIPAL / CR BNPL_CREDIT_BALANCE} of the same
 * obligation, balanced, no cash) in the judging transaction - re-asserted through the real purchase by `-013`. The
 * capital is planted as {@code LoanAccountDatabaseTest} and {@code LendingCreditExposureDatabaseTest} plant it, which is
 * why the suite runs in a database of its own ({@code own-container}): its capital lines are no recognition's, so the
 * capital proof is not this suite's to read.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("bnpl's capital port and lending's acceptance serialise on the one capital row (P12-TSK-005)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class BnplCapitalDatabaseTest {

    private static final String APPLICATIONS = "/v1/me/loan-applications";
    private static final String OFFERS = "/v1/me/loan-offers";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Clock CLOCK = Clock.systemUTC();

    @LocalServerPort private int port;
    @Autowired private BnplCapital bnplCapital;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanAcceptances acceptances;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private IdGenerator ids;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;

    private CreditFixtures customers;
    private LoanOriginationSchedule sweep;

    @BeforeEach
    void creditAndTermsInForce() {
        customers = new CreditFixtures(port);
        CreditFixtures.seedsInForce();
        lendingTransactions.inTransaction(uow -> {
            if (termsStore.active(uow, LoanProduct.PERSONAL_LOAN).isEmpty()) {
                var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(LoanProduct.PERSONAL_LOAN),
                        "the suite's terms", employee(), CorrelationId.generate(ids));
                termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
            }
            return null;
        });
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50,
                Duration.ofHours(1));
    }

    // ================================================================== L6, both orders

    @Test
    @DisplayName("a BNPL judgement and a loan acceptance serialise on the capital row, in both orders: the one holding L6"
            + " takes the last capital, the other waits on LENDING_CAPITAL's ledger_account row and then finds nothing -"
            + " the acceptance refused 422 lending.CapitalUnavailable, the BNPL judgement not admitted")
    void aBnplJudgementAndALoanAcceptanceSerialiseOnTheCapitalRow() throws Exception {
        for (boolean bnplFirst : List.of(true, false)) {
            CreditFixtures.Customer customer = customers.consentingCustomer();
            String offer = offered(customer, loan("8000.00"));
            String hash = field(get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
            headroomExactly(eur(8_000_00));
            Money bnplBefore = principalOf(AccountPurpose.BNPL_PRINCIPAL, AccountPurpose.BNPL_PRINCIPAL_DUE);
            Money committedBefore = headroomNow().committed();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                    SecurityContext.Scope system = SecurityContext.enterSystem();
                    Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                if (bnplFirst) {
                    BnplHeadroom judged = bnplCapital.underLock(holder, EUR);
                    assertThat(judged.admits(eur(8_000_00))).as("the BNPL judgement sees the last capital").isTrue();
                    financeBnpl(holder, eur(8_000_00));
                    Future<HttpResponse<String>> accepting = pool.submit(() -> accept(customer.token(), offer, hash));
                    String waiting = awaitStatementBlockedBy(pid(holder));
                    assertThat(waiting).as("the acceptance waits on the capital row (L6) behind the BNPL judgement")
                            .contains("ledger.ledger_account");
                    assertThat(isWaiting(pid(holder))).as("the holder waits on nothing the acceptance holds").isFalse();
                    holder.commit();
                    HttpResponse<String> refused = accepting.get(2, TimeUnit.MINUTES);
                    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
                    assertThat(refused.body()).contains("lending.CapitalUnavailable");
                    assertThat(principalOf(AccountPurpose.BNPL_PRINCIPAL, AccountPurpose.BNPL_PRINCIPAL_DUE))
                            .isEqualTo(bnplBefore.plus(eur(8_000_00)));
                    assertThat(headroomNow().committed()).as("no loan committed").isEqualTo(committedBefore);
                } else {
                    LoanAcceptances.Accepted accepted = acceptances.accept(holder,
                            LoanOfferId.of(UUID.fromString(offer)), customer.party(), acceptance(hash),
                            new Actor(customer.party().toString(), ActorType.CUSTOMER), CorrelationId.generate(ids));
                    assertThat(accepted.loan().status()).isEqualTo(LoanStatus.PENDING_DISBURSEMENT);
                    Future<Boolean> judging = pool.submit(() -> judgedAndFinanced(eur(1_00)));
                    String waiting = awaitStatementBlockedBy(pid(holder));
                    assertThat(waiting).as("the BNPL judgement waits on the capital row (L6) behind the acceptance")
                            .contains("ledger.ledger_account");
                    assertThat(isWaiting(pid(holder))).as("the holder waits on nothing the judgement holds").isFalse();
                    holder.commit();
                    assertThat(judging.get(2, TimeUnit.MINUTES))
                            .as("after the acceptance commits, not one minor unit is left for BNPL").isFalse();
                    assertThat(principalOf(AccountPurpose.BNPL_PRINCIPAL, AccountPurpose.BNPL_PRINCIPAL_DUE))
                            .as("nothing financed").isEqualTo(bnplBefore);
                    assertThat(headroomNow().committed()).isEqualTo(committedBefore.plus(eur(8_000_00)));
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(headroomNow().headroom().minorUnits())
                    .as("INV-LND-13 / INV-BNPL-10: the last capital taken once, never over-deployed").isZero();
        }
    }

    // ================================================================== the last capital

    @Test
    @DisplayName("the last capital admits one: five BNPL judgements and five loan acceptances racing for capital that fits"
            + " one - exactly one admitted, the other nine refused, headroom exactly zero")
    void theLastCapitalAdmitsOne() throws Exception {
        List<CreditFixtures.Customer> borrowers = new ArrayList<>();
        List<String> offers = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CreditFixtures.Customer borrower = customers.consentingCustomer();
            String offer = offered(borrower, loan("8000.00"));
            borrowers.add(borrower);
            offers.add(offer);
            hashes.add(field(get(OFFERS + "/" + offer, borrower.token()).body(), "termsSha256"));
        }
        headroomExactly(eur(8_000_00));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> racers = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                racers.add(pool.submit(() -> {
                    start.await();
                    return judgedAndFinanced(eur(8_000_00)) ? "ADMITTED" : "CAPITAL_UNAVAILABLE";
                }));
                int index = i;
                racers.add(pool.submit(() -> {
                    start.await();
                    HttpResponse<String> answer = accept(borrowers.get(index).token(), offers.get(index), hashes.get(index));
                    if (answer.statusCode() == 201) {
                        return "ADMITTED";
                    }
                    assertThat(answer.statusCode()).as(answer.body()).isEqualTo(422);
                    assertThat(answer.body()).contains("lending.CapitalUnavailable");
                    return "CAPITAL_UNAVAILABLE";
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> racer : racers) {
                outcomes.add(racer.get(3, TimeUnit.MINUTES));
            }
            assertThat(outcomes).as("exactly one consumer - loan or BNPL - takes the last capital")
                    .filteredOn("ADMITTED"::equals).hasSize(1);
            assertThat(outcomes).filteredOn("CAPITAL_UNAVAILABLE"::equals).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(headroomNow().headroom().minorUnits()).as("INV-LND-13: never over-deployed").isZero();
    }

    // ================================================================== fixtures

    /**
     * What a BNPL purchase's capital step will do (`P12-TSK-013`): in one transaction, the headroom judged under L6
     * through bnpl's port and - when it admits {@code gross} - the BNPL principal financed before the commit.
     */
    private boolean judgedAndFinanced(Money gross) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope system = SecurityContext.enterSystem();
                Connection uow = DatabaseRoles.application()) {
            uow.setAutoCommit(false);
            try {
                if (!bnplCapital.underLock(uow, EUR).admits(gross)) {
                    uow.rollback();
                    return false;
                }
                financeBnpl(uow, gross);
                uow.commit();
                return true;
            } catch (RuntimeException failure) {
                uow.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A fresh obligation's six accounts by bnpl's opener, and its principal posted: DR BNPL_PRINCIPAL / CR its credit balance. */
    private void financeBnpl(Connection uow, Money gross) {
        BnplLedgerAccounts obligation = new BnplAccounts(ledgerAccountStore, ids, CLOCK)
                .openFor(uow, BnplObligationId.next(ids), EUR);
        post(uow, List.of(new JournalLine(obligation.principal().id(), Direction.DEBIT, gross),
                new JournalLine(obligation.creditBalance().id(), Direction.CREDIT, gross)));
    }

    /** The currency's headroom set to exactly {@code target}: capital planted when short, BNPL principal when over. */
    private void headroomExactly(Money target) {
        Money free = headroomNow().headroom();
        if (free.compareTo(target) < 0) {
            Money missing = target.minus(free);
            lendingTransactions.inTransaction(uow -> {
                LedgerAccountId cash = ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR)
                        .orElseThrow().id();
                post(uow, List.of(new JournalLine(cash, Direction.DEBIT, missing),
                        new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, missing)));
                return null;
            });
        } else if (free.compareTo(target) > 0) {
            Money surplus = free.minus(target);
            lendingTransactions.inTransaction(uow -> {
                try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                        SecurityContext.Scope system = SecurityContext.enterSystem()) {
                    financeBnpl(uow, surplus);
                }
                return null;
            });
        }
        assertThat(headroomNow().headroom()).as("the last capital, exactly").isEqualTo(target);
    }

    private void post(Connection uow, List<JournalLine> lines) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            postingService.post(uow, new PostingCommand("p12t5:" + UUID.randomUUID(), today, today,
                    "the BNPL capital suite's fixture", lines));
        }
    }

    private LendingCapitalHeadroom.Headroom headroomNow() {
        return lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR));
    }

    /** DR-CR over every account of {@code purposes} in EUR, summed in SQL by the migrator - an independent reading. */
    private static Money principalOf(AccountPurpose... purposes) throws SQLException {
        StringBuilder names = new StringBuilder();
        for (AccountPurpose purpose : purposes) {
            names.append(names.isEmpty() ? "" : ",").append('\'').append(purpose.name()).append('\'');
        }
        try (Connection owner = DatabaseRoles.migrator(); Statement select = owner.createStatement();
                ResultSet row = select.executeQuery("SELECT coalesce(sum(CASE l.direction WHEN 'DEBIT' THEN"
                        + " l.amount_minor ELSE -l.amount_minor END), 0) FROM ledger.journal_line l"
                        + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE a.currency = 'EUR' AND a.purpose IN (" + names + ")")) {
            row.next();
            return eur(row.getLong(1));
        }
    }

    /** {@code body} applied for, approved by credit, offered by the sweep; the offer's id. */
    private String offered(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = post(APPLICATIONS, body, customer.token());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow, request));
        sweep.sweepOnce();
        return field(get(APPLICATIONS + "/" + field(submitted.body(), "applicationId"), customer.token()).body(), "offerId");
    }

    private HttpResponse<String> accept(String token, String offer, String hash) throws Exception {
        return post(OFFERS + "/" + offer + "/acceptance", "{\"termsSha256\":\"" + hash + "\"}", token);
    }

    private static LoanAcceptances.Acceptance acceptance(String hash) {
        return new LoanAcceptances.Acceptance(hash, Optional.empty(), new AcceptanceEvidence(UUID.randomUUID(),
                UUID.randomUUID(), "MULTI_FACTOR", Optional.of("203.0.113.7"), Optional.of("finapp-suite/1.0")));
    }

    private static String loan(String amount) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"termMonths\":24,"
                + "\"repaymentDay\":10,\"repaymentMethod\":\"WALLET\",\"payoutPreference\":\"WALLET\","
                + "\"declaredMonthlyIncome\":\"3200.00\",\"declaredMonthlyExpenditure\":\"1400.00\"}";
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build());
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** The statement of the session blocked behind {@code blocker} - active and waiting on a lock - awaited 30 s. */
    private static String awaitStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind session " + blocker);
    }

    private static boolean isWaiting(int pid) throws SQLException {
        try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                ResultSet row = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = " + pid
                        + " AND wait_event_type = 'Lock'")) {
            row.next();
            return row.getLong(1) > 0;
        }
    }

    private Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p12t5"));
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
