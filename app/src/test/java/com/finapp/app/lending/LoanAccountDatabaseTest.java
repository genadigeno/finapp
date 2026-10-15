package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.LendingCreditExposure;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The acceptance end to end - credit's real decision consumed through credit's real port, under credit's real profile
 * lock, and the loan account read back (`P11-TSK-012`; {@code PHASE_11_PLAN.md} sections 7.1 T-acc, 9 and 12.10,
 * {@code LENDING_LIFECYCLES.md} sections 4.4 and 4.5; {@code INV-LND-06}, {@code INV-CRD-09}, {@code INV-IDEM-01}): an
 * offer accepted over HTTP from a {@code MULTI_FACTOR} session, replayed by its key and refused under another; credit's
 * consumption naming the agreement and credit's audit beside lending's; the acceptance waiting on credit's own profile
 * row before any lending row; a lost standing refused; the owner's reads and nobody else's. And the disbursement
 * (`P11-TSK-014`) through the real ports: the sweep posting to the borrower's own wallet opened through accounts' door,
 * credit's real profile row taken before the loan, the schedule read. And the accrual (`P11-TSK-016`) through the wired
 * servicing sweep and its meter, and the billing (`P11-TSK-017`) through the same sweep and the schedule read. And a line's
 * draw (`P11-TSK-021`) through the real door, wallet port and meter. And a loan's repayment (`P11-TSK-018`) through
 * the real door, wallet port, meter and credit exposure. The races and the database's ranks are
 * {@code lending}'s {@code LoanAcceptanceDatabaseTest}'s, {@code LoanDisbursementDatabaseTest}'s,
 * {@code InstalmentBillingDatabaseTest}'s and {@code CreditLineDrawDatabaseTest}'s.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}, X-TSK-016): the suite brings credit's seeded policies
 * into force, activates both products' lending terms and plants EUR lending capital by a posting from
 * {@code CASH_AT_BANK} - the fixture's, never a path the platform has (bank recognition is the one poster, proven by
 * {@link LendingCapitalDatabaseTest}) - so the capital proof would not hold here, and nothing here asks it to.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the acceptance, the disbursement, the accrual, the billing, the draw, the statement, the repayment,"
        + " auto-collection and the payoff end to end (P11-TSK-012, P11-TSK-014, P11-TSK-016, P11-TSK-017, P11-TSK-021,"
        + " P11-TSK-022, P11-TSK-018, P11-TSK-019, P11-TSK-024, P11-TSK-026)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LoanAccountDatabaseTest {

    private static final String OFFERS = "/v1/me/loan-offers";
    private static final String LOANS = "/v1/me/loans";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @LocalServerPort private int port;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdGenerator ids;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanDisbursements disbursements;
    @Autowired private LoanDisbursementMetrics disbursementMetrics;
    @Autowired private com.finapp.lending.LoanServicing loanServicing;
    @Autowired private LoanServicingMetrics servicingMetrics;
    @Autowired private LendingCreditExposure creditExposure;
    @Autowired private Authorization authorization;
    @Autowired private com.finapp.app.credit.ExposureCensusMetrics exposureCensus;
    @Autowired private com.finapp.lending.LoanWallets loanWallets;

    private LoanApplicationClient client;
    private CreditFixtures customers;
    private LoanOriginationSchedule sweep;

    @BeforeEach
    void creditTermsAndCapitalInForce() {
        client = new LoanApplicationClient(port);
        customers = new CreditFixtures(port);
        CreditFixtures.seedsInForce();
        for (LoanProduct product : LoanProduct.values()) {
            lendingTransactions.inTransaction(uow -> {
                if (termsStore.active(uow, product).isEmpty()) {
                    var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(product),
                            "the suite's terms", employee(), CorrelationId.generate(ids));
                    termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50,
                Duration.ofHours(1));
        Money free = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        if (free.minorUnits() < 50_000_00) {
            plantCapital(Money.ofMinorUnits(100_000_00, EUR));
        }
    }

    @Test
    @DisplayName("an offer is accepted end to end: 201 with the loan account; credit's consumption names the agreement and"
            + " credit's audit stands beside lending's; the key replays the receipt byte for byte, another key is 409"
            + " lending.OfferNotAcceptable; the owner reads the loan PENDING_DISBURSEMENT with its principal, its"
            + " agreement's hash and zero balances, and lists it")
    void anOfferIsAcceptedEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("8000.00", 24, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        double acceptedBefore = accepted(LoanProduct.PERSONAL_LOAN);
        String acceptKey = key();
        HttpResponse<String> accepted = accept(customer.token(), offer, hash, null, acceptKey);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        assertThat(accepted(LoanProduct.PERSONAL_LOAN)).as("counted once, after the commit").isEqualTo(acceptedBefore + 1);
        String loanId = field(accepted.body(), "loanId");
        String agreementId = field(accepted.body(), "agreementId");
        assertThat(accepted.body()).contains("\"status\":\"PENDING_DISBURSEMENT\"", "\"kind\":\"INSTALMENT\"",
                "\"agreementVersion\":1", "\"termsSha256\":\"" + hash + "\"");
        assertThat(accept(customer.token(), offer, hash, null, acceptKey).body())
                .as("a lost answer replayed byte for byte").isEqualTo(accepted.body());
        assertThat(accepted(LoanProduct.PERSONAL_LOAN)).as("a replay counts nothing").isEqualTo(acceptedBefore + 1);
        HttpResponse<String> again = accept(customer.token(), offer, hash, null, key());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(again.body()).contains("lending.OfferNotAcceptable");

        assertThat(scalar("SELECT consumer_kind || ':' || consumer_ref FROM credit.credit_decision_consumption c"
                + " JOIN lending.loan l ON l.decision_id = c.decision_id WHERE l.id = ?::uuid", loanId))
                .as("credit's consumption names the agreement (INV-LND-06)").isEqualTo("LOAN_AGREEMENT:" + agreementId);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionConsumed'"
                + " AND correlation_id = (SELECT correlation_id FROM lending.loan WHERE id = ?::uuid)", loanId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.OfferAccepted'"
                + " AND target_id = ?", loanId)).isEqualTo(1);
        assertThat(scalar("SELECT assurance_level FROM lending.loan_acceptance WHERE loan_id = ?::uuid", loanId))
                .isIn("MULTI_FACTOR", "STRONG");

        HttpResponse<String> read = client.get(LOANS + "/" + loanId, customer.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"status\":\"PENDING_DISBURSEMENT\"", "\"principal\":\"8000.00\"",
                "\"limit\":null", "\"termMonths\":24", "\"repaymentDay\":10", "\"termsSha256\":\"" + hash + "\"",
                "\"principalOutstanding\":\"0.00\"", "\"creditBalance\":\"0.00\"")
                .doesNotContain("partyId", customer.party().toString());
        HttpResponse<String> listed = client.get(LOANS, customer.token());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(loanId);
    }

    @Test
    @DisplayName("ten acceptances under one key, released at once, open one account: every answer the one receipt or the"
            + " key's in-progress refusal, one loan, one consumption, counted once")
    void tenAcceptancesUnderOneKeyOneLoan() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, line("3100.00"));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String sharedKey = key();
        double acceptedBefore = accepted(LoanProduct.CREDIT_LINE);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> racing = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return accept(customer.token(), offer, hash, null, sharedKey);
                }));
            }
            start.countDown();
            java.util.Set<String> receipts = new java.util.HashSet<>();
            for (Future<HttpResponse<String>> answer : racing) {
                HttpResponse<String> response = answer.get(2, TimeUnit.MINUTES);
                assertThat(response.statusCode()).as(response.body()).isIn(201, 409);
                if (response.statusCode() == 201) {
                    receipts.add(response.body());
                } else {
                    assertThat(response.body()).contains("api.IdempotencyInProgress");
                }
            }
            assertThat(receipts).as("one receipt, however many answered it").hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?::uuid", offer)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN lending.loan_offer o"
                + " ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", offer)).isEqualTo(1);
        assertThat(accepted(LoanProduct.CREDIT_LINE)).as("the one execution counted").isEqualTo(acceptedBefore + 1);
    }

    @Test
    @DisplayName("a line is accepted ACTIVE with its limit and no disbursement; another party's offer and loan are the"
            + " uniform 404 to accept and to read - nothing written")
    void aLineIsAcceptedAndOnlyItsOwnerSeesIt() throws Exception {
        CreditFixtures.Customer owner = customers.consentingCustomer();
        String offer = offered(owner, line("2500.00"));
        String hash = field(client.get(OFFERS + "/" + offer, owner.token()).body(), "termsSha256");
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = accept(stranger.token(), offer, hash, null, key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound");
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", offer)).isEqualTo("OFFERED");

        HttpResponse<String> accepted = accept(owner.token(), offer, hash, null, key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String loanId = field(accepted.body(), "loanId");
        assertThat(accepted.body()).contains("\"status\":\"ACTIVE\"", "\"kind\":\"REVOLVING\"");
        assertThat(count("SELECT count(*) FROM lending.loan_disbursement WHERE loan_id = ?::uuid", loanId)).isZero();
        assertThat(client.get(LOANS + "/" + loanId, owner.token()).body())
                .contains("\"limit\":\"2500.00\"", "\"principal\":null");
        HttpResponse<String> peek = client.get(LOANS + "/" + loanId, stranger.token());
        assertThat(peek.statusCode()).isEqualTo(404);
        assertThat(peek.body()).contains("lending.NotFound");
        assertThat(client.get(LOANS, stranger.token()).body()).doesNotContain(loanId);
    }

    @Test
    @DisplayName("the acceptance waits on credit's own profile row (L0) before any lending row: behind a transaction"
            + " holding the party's credit_profile it waits holding no offer lock, and completes once it is released")
    void acceptanceWaitsOnCreditsProfileLock() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, line("1800.00"));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT 1 FROM credit.credit_profile WHERE party_id = ? FOR UPDATE")) {
                lock.setObject(1, customer.party());
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).as("the decided party has its profile").isTrue();
                }
            }
            Future<HttpResponse<String>> accepting = pool.submit(() -> accept(customer.token(), offer, hash, null, key()));
            String waiting = awaitAStatementBlockedBy(pid(holder));
            assertThat(waiting).as("the acceptance waits on credit's profile row").contains("credit_profile");
            assertThat(offerLockableNoWait(offer)).as("holding no offer lock while it waits: L0 before L2").isTrue();
            holder.commit();
            HttpResponse<String> accepted = accepting.get(60, TimeUnit.SECONDS);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a lost standing refuses the acceptance 409 credit.ApplicantNotEligible, nothing written; an external"
            + " payout naming no verifiable bank account is 422 lending.PayoutDestinationInvalid")
    void standingLostAndAnUnverifiedDestinationRefuse() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, line("1300.00"));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        // The party's own customer record suspended - the standing credit and lending read authoritatively.
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.executeUpdate("UPDATE party.customer SET status = 'SUSPENDED', status_changed_at = GREATEST(now(),"
                    + " status_changed_at) WHERE party_id = '" + customer.party() + "'");
        }
        HttpResponse<String> refused = accept(customer.token(), offer, hash, null, key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("credit.ApplicantNotEligible");
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", offer)).isEqualTo("OFFERED");
        assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?::uuid", offer)).isZero();
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN lending.loan_offer o"
                + " ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", offer)).isZero();

        CreditFixtures.Customer external = customers.consentingCustomer();
        String externalOffer = offered(external, "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"6000.00\","
                + "\"currency\":\"EUR\",\"termMonths\":24,\"repaymentDay\":5,\"repaymentMethod\":\"WALLET\","
                + "\"payoutPreference\":\"EXTERNAL_ACCOUNT\",\"declaredMonthlyIncome\":\"3200.00\","
                + "\"declaredMonthlyExpenditure\":\"1400.00\"}");
        String externalHash = field(client.get(OFFERS + "/" + externalOffer, external.token()).body(), "termsSha256");
        for (String method : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> unverified = accept(external.token(), externalOffer, externalHash, method, key());
            assertThat(unverified.statusCode()).as(unverified.body()).isEqualTo(422);
            assertThat(unverified.body()).contains("lending.PayoutDestinationInvalid");
        }
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", externalOffer)).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("an offer that expires while its acceptance waits on the capital row (L6) is 409"
            + " lending.OfferNotAcceptable over the door, never 500 api.InternalError: nothing written, credit's"
            + " decision not consumed, and the same key retried is the same 409 (P11-TSK-034)")
    void anOfferExpiringWhileTheAcceptanceWaitsIsA409() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("2200.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        // The fixture's clock: the offer due to expire three seconds from now on the database clock (the bootstrap
        // owner, triggers suspended - no writer can move an expiry).
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE lending.loan_offer SET expires_at = statement_timestamp() + interval '10 seconds',"
                        + " next_step_at = statement_timestamp() + interval '10 seconds' WHERE id = '" + offer + "'");
            }
            owner.commit();
        }
        String acceptKey = key();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            headroom.underLock(holder, EUR);
            Future<HttpResponse<String>> accepting = pool.submit(() -> accept(customer.token(), offer, hash, null, acceptKey));
            String waiting = awaitAStatementBlockedBy(pid(holder));
            assertThat(waiting).as("past its expiry check and credit's consumption, waiting on L6")
                    .contains("ledger_account");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!"t".equals(scalar("SELECT expires_at <= statement_timestamp() FROM lending.loan_offer"
                    + " WHERE id = ?::uuid", offer))) {
                assertThat(System.nanoTime()).as("the offer's instant passes on the database clock").isLessThan(deadline);
                Thread.sleep(5);
            }
            holder.commit();
            HttpResponse<String> refused = accepting.get(60, TimeUnit.SECONDS);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(refused.body()).contains("lending.OfferNotAcceptable", "the offer has expired");
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM lending.loan WHERE offer_id = ?::uuid", offer)).isZero();
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN lending.loan_offer o"
                + " ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", offer)).as("the consumption rolled back").isZero();
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", offer)).isIn("OFFERED", "EXPIRED");
        HttpResponse<String> retried = accept(customer.token(), offer, hash, null, acceptKey);
        assertThat(retried.statusCode()).as("a refusal stores nothing under the key: " + retried.body()).isEqualTo(409);
        assertThat(retried.body()).contains("lending.OfferNotAcceptable");
    }

    // ------------------------------------------------------------------ the disbursement (P11-TSK-014)

    @Test
    @DisplayName("a loan is disbursed to the borrower's wallet end to end (P11-TSK-014): no schedule while pending (404);"
            + " the sweep disburses it once under the system actor - the customer's CUSTOMER_WALLET, opened through"
            + " accounts' door, credited the principal, the loan ACTIVE with its principal a ledger balance; the schedule"
            + " read 200 - latest and version 1 - another version and another party 404; counted once; a second sweep"
            + " posts nothing")
    void aLoanIsDisbursedToTheBorrowersWalletEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("5000.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        HttpResponse<String> accepted = accept(customer.token(), offer, hash, null, key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String loanId = field(accepted.body(), "loanId");
        HttpResponse<String> none = client.get(LOANS + "/" + loanId + "/schedule", customer.token());
        assertThat(none.statusCode()).as("no schedule before the disbursement").isEqualTo(404);
        assertThat(none.body()).contains("lending.NotFound");

        double postedBefore = posted();
        LoanDisbursementSchedule sweep = new LoanDisbursementSchedule(disbursements, lendingTransactions,
                disbursementMetrics, Duration.ofSeconds(5), 100, Duration.ofMinutes(1));
        assertThat(sweep.sweepOnce()).as("the accepted loan was due at once").isPositive();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        assertThat(posted()).as("counted after the commit").isGreaterThanOrEqualTo(postedBefore + 1);
        assertThat(scalar("SELECT coalesce(sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor"
                + " ELSE -l.amount_minor END), 0)::text FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " JOIN accounts.customer_account c ON c.id = a.owner_ref"
                + " JOIN party.customer pc ON pc.id = c.customer_id"
                + " WHERE a.purpose = 'CUSTOMER_WALLET' AND a.currency = 'EUR' AND pc.party_id = ?::uuid",
                customer.party().toString())).as("the borrower's own wallet credited the principal").isEqualTo("500000");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.LoanDisbursed'"
                + " AND target_id = ? AND actor_type = 'SYSTEM'", loanId)).isEqualTo(1);
        assertThat(scalar("SELECT e.correlation_id FROM ledger.journal_entry e JOIN lending.loan_disbursement d"
                + " ON d.journal_entry_id = e.id WHERE d.loan_id = ?::uuid", loanId))
                .as("one flow from the acceptance to the money")
                .isEqualTo(scalar("SELECT correlation_id FROM lending.loan WHERE id = ?::uuid", loanId));
        assertThat(client.get(LOANS + "/" + loanId, customer.token()).body())
                .contains("\"status\":\"ACTIVE\"", "\"principalNotDue\":\"5000.00\"");

        HttpResponse<String> latest = client.get(LOANS + "/" + loanId + "/schedule", customer.token());
        assertThat(latest.statusCode()).as(latest.body()).isEqualTo(200);
        assertThat(latest.body()).contains("\"scheduleVersion\":1", "\"agreementVersion\":1",
                "\"principal\":\"5000.00\"", "\"termMonths\":12", "\"sequence\":12", "\"closingPrincipal\":\"0.00\"")
                .doesNotContain("\"sequence\":13");
        assertThat(client.get(LOANS + "/" + loanId + "/schedule?version=1", customer.token()).body())
                .isEqualTo(latest.body());
        assertThat(client.get(LOANS + "/" + loanId + "/schedule?version=2", customer.token()).statusCode()).isEqualTo(404);
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> peek = client.get(LOANS + "/" + loanId + "/schedule", stranger.token());
        assertThat(peek.statusCode()).isEqualTo(404);
        assertThat(peek.body()).contains("lending.NotFound").doesNotContain("5000.00");

        sweep.sweepOnce();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.disbursement:" + loanId)).as("one entry, however often swept").isEqualTo(1);
    }

    @Test
    @DisplayName("the disbursement waits on credit's own profile row (L0) before the loan: behind a transaction holding the"
            + " party's credit_profile it waits holding no loan lock, and posts once released")
    void disbursementWaitsOnCreditsProfileLock() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("4000.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT 1 FROM credit.credit_profile WHERE party_id = ? FOR UPDATE")) {
                lock.setObject(1, customer.party());
                lock.executeQuery().close();
            }
            Future<LoanDisbursements.Outcome> disbursing = pool.submit(() -> {
                try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                    return lendingTransactions.inTransaction(uow -> disbursements.disburse(uow,
                            LoanId.of(UUID.fromString(loanId)), SecurityContext.require()));
                }
            });
            String waiting = awaitAStatementBlockedBy(pid(holder));
            assertThat(waiting).as("the disbursement waits on credit's profile row").contains("credit_profile");
            assertThat(loanLockableNoWait(loanId)).as("holding no loan lock while it waits: L0 before L3").isTrue();
            holder.commit();
            assertThat(disbursing.get(60, TimeUnit.SECONDS).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the accrual (P11-TSK-016)

    @Test
    @DisplayName("a disbursed loan accrues through the wired servicing sweep (P11-TSK-016): two elapsed dates accrued once"
            + " under the system actor, each posting rule 2 dated its date under the loan's own correlation, counted after"
            + " the commit, the borrower's read showing the interest accrued; a second sweep accrues nothing")
    void aDisbursedLoanAccruesThroughTheWiredSweep() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3650.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        // The fixture's clock: the loan's dated facts two days into the past (the bootstrap owner, triggers suspended).
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE ledger.journal_entry SET posting_date = posting_date - 2, value_date = value_date"
                        + " - 2 WHERE id = (SELECT journal_entry_id FROM lending.loan_disbursement WHERE loan_id = '"
                        + loanId + "'); UPDATE lending.accrual_start SET start_date = start_date - 2 WHERE loan_id = '"
                        + loanId + "'; UPDATE lending.repayment_schedule SET start_date = start_date - 2 WHERE loan_id = '"
                        + loanId + "'; UPDATE lending.loan_instalment SET period_start = period_start - 2, due_date ="
                        + " due_date - 2 WHERE loan_id = '" + loanId + "'");
            }
            owner.commit();
        }
        double before = accrued("posted");
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid", loanId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e"
                + " ON e.id = a.journal_entry_id JOIN lending.loan l ON l.id = a.loan_id WHERE a.loan_id = ?::uuid"
                + " AND e.value_date = a.accrual_date AND e.correlation_id = l.correlation_id",
                loanId)).as("rule 2 dated its date, in the loan's own flow").isEqualTo(2);
        assertThat(accrued("posted")).as("counted after the commit").isGreaterThanOrEqualTo(before + 2);
        // 3,650.00 at the product's rate: two days' interest, rounded once within the instalment period.
        String expected = scalar("SELECT to_char(round(sum(posted_minor) / 100.0, 2), 'FM999990.00')"
                + " FROM lending.interest_accrual WHERE loan_id = ?::uuid", loanId);
        assertThat(client.get(LOANS + "/" + loanId, customer.token()).body())
                .contains("\"interestAccrued\":\"" + expected + "\"");
        servicing.sweepOnce();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE ?",
                "ledger.post:lending.accrual:" + loanId + ":%")).as("one entry a date, however often swept").isEqualTo(2);
    }

    // ------------------------------------------------------------------ the billing (P11-TSK-017)

    @Test
    @DisplayName("a loan is billed on its due date through the wired servicing sweep (P11-TSK-017): the first instalment"
            + " billed once under the system actor - interest actually accrued, posting rule 3 dated the day under the"
            + " loan's own correlation, lending.InstalmentBilled in the outbox, counted after the commit - and the"
            + " borrower's schedule read shows it DUE beside its projection, every later instalment UNBILLED")
    void aLoanIsBilledOnItsDueDateThroughTheWiredSweep() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3650.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        long days = count("SELECT i.due_date - s.start_date FROM lending.loan_instalment i JOIN lending.accrual_start s"
                + " ON s.loan_id = i.loan_id WHERE i.loan_id = ?::uuid AND i.sequence = 1", loanId);
        // The fixture's clock: the loan's dated facts moved back until its first due date is today.
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE ledger.journal_entry SET posting_date = posting_date - " + days + ", value_date ="
                        + " value_date - " + days + " WHERE id = (SELECT journal_entry_id FROM lending.loan_disbursement"
                        + " WHERE loan_id = '" + loanId + "'); UPDATE lending.loan_disbursement SET value_date = value_date"
                        + " - " + days + " WHERE loan_id = '" + loanId + "'; UPDATE lending.accrual_start SET start_date ="
                        + " start_date - " + days + " WHERE loan_id = '" + loanId + "'; UPDATE lending.repayment_schedule"
                        + " SET start_date = start_date - " + days + " WHERE loan_id = '" + loanId + "';"
                        + " UPDATE lending.loan_instalment SET period_start = period_start - " + days + ", due_date ="
                        + " due_date - " + days + " WHERE loan_id = '" + loanId + "'");
            }
            owner.commit();
        }
        double before = billed();
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing b JOIN ledger.journal_entry e"
                + " ON e.id = b.journal_entry_id JOIN lending.loan l ON l.id = b.loan_id WHERE b.loan_id = ?::uuid"
                + " AND e.value_date = b.due_date AND e.posting_date = b.due_date AND e.correlation_id = l.correlation_id"
                + " AND b.interest_due_minor = (SELECT sum(a.posted_minor) FROM lending.interest_accrual a"
                + " WHERE a.loan_id = b.loan_id AND a.period_end = b.due_date)",
                loanId)).as("rule 3 dated the due date - today - in the loan's own flow, billing what accrued").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?::uuid AND event_type ="
                + " 'lending.InstalmentBilled'", loanId)).isEqualTo(1);
        assertThat(billed()).as("counted after the commit").isGreaterThanOrEqualTo(before + 1);
        String schedule = client.get(LOANS + "/" + loanId + "/schedule", customer.token()).body();
        String interest = scalar("SELECT to_char(interest_due_minor / 100.0, 'FM999990.00') FROM lending.instalment_billing"
                + " WHERE loan_id = ?::uuid", loanId);
        assertThat(schedule).contains("\"status\":\"DUE\"").contains("\"interestBilled\":\"" + interest + "\"")
                .contains("\"postMaturityBillings\":[]");
        assertThat(schedule.split("\"status\":\"UNBILLED\"", -1)).as("eleven instalments still unbilled").hasSize(12);
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        assertThat(client.get(LOANS + "/" + loanId + "/schedule", stranger.token()).statusCode())
                .as("another party's schedule and its bill: the same 404 as an unknown loan").isEqualTo(404);
        servicing.sweepOnce();
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?::uuid", loanId))
                .as("billed once, however often swept").isEqualTo(1);
    }

    @Test
    @DisplayName("a line is drawn to the borrower's own wallet end to end (P11-TSK-021): 201 with the draw from a"
            + " MULTI_FACTOR session; the key replays its receipt byte for byte and refuses another amount 409; past the"
            + " available limit 422 lending.LimitExceeded; the read derives the limit left; the wallet opened through"
            + " accounts' door credited once; another party's line the uniform 404; each counted after its commit")
    void aLineIsDrawnToTheBorrowersWalletEndToEnd() throws Exception {
        CreditFixtures.Customer owner = customers.consentingCustomer();
        String offer = offered(owner, line("2500.00"));
        String hash = field(client.get(OFFERS + "/" + offer, owner.token()).body(), "termsSha256");
        String loanId = field(accept(owner.token(), offer, hash, null, key()).body(), "loanId");
        assertThat(client.get(LOANS + "/" + loanId, owner.token()).body()).contains("\"availableLimit\":\"2500.00\"");

        double drawnBefore = drawn("drawn");
        double exceededBefore = drawn("limit_exceeded");
        String key = key();
        HttpResponse<String> drawn = draw(owner.token(), loanId, "1000.00", key);
        assertThat(drawn.statusCode()).as(drawn.body()).isEqualTo(201);
        assertThat(drawn.body()).contains("\"loanId\":\"" + loanId + "\"", "\"amount\":\"1000.00\"",
                "\"currency\":\"EUR\"", "\"agreementVersion\":1");
        String drawId = field(drawn.body(), "drawId");
        HttpResponse<String> replay = draw(owner.token(), loanId, "1000.00", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).as("the receipt, byte for byte").isEqualTo(drawn.body());
        HttpResponse<String> reused = draw(owner.token(), loanId, "1000.01", key);
        assertThat(reused.statusCode()).as(reused.body()).isEqualTo(409);
        assertThat(reused.body()).contains("api.Conflict");
        HttpResponse<String> otherCurrency = client.post(LOANS + "/" + loanId + "/draws",
                "{\"amount\":\"10.00\",\"currency\":\"USD\"}", owner.token(), key());
        assertThat(otherCurrency.statusCode()).as("not the line's currency").isEqualTo(422);
        assertThat(otherCurrency.body()).contains("api.ValidationFailed");
        HttpResponse<String> beyond = draw(owner.token(), loanId, "1500.01", key());
        assertThat(beyond.statusCode()).as(beyond.body()).isEqualTo(422);
        assertThat(beyond.body()).contains("lending.LimitExceeded").doesNotContain("1500");
        assertThat(drawn("drawn")).as("counted after the commit, the replay not").isEqualTo(drawnBefore + 1);
        assertThat(drawn("limit_exceeded")).isEqualTo(exceededBefore + 1);

        assertThat(client.get(LOANS + "/" + loanId, owner.token()).body())
                .contains("\"availableLimit\":\"1500.00\"", "\"principalNotDue\":\"1000.00\"");
        assertThat(scalar("SELECT coalesce(sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor"
                + " ELSE -l.amount_minor END), 0)::text FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " JOIN accounts.customer_account c ON c.id = a.owner_ref"
                + " JOIN party.customer pc ON pc.id = c.customer_id"
                + " WHERE a.purpose = 'CUSTOMER_WALLET' AND a.currency = 'EUR' AND pc.party_id = ?::uuid",
                owner.party().toString())).as("the borrower's own wallet credited once").isEqualTo("100000");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.draw:" + drawId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);

        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = draw(stranger.token(), loanId, "10.00", key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound");
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a line is repaid and closed end to end (P11-TSK-023): over the door a payment beyond what is due pays"
            + " principal down (NOT_DUE_PRINCIPAL), restoring the available limit, counted paid_down; the closure door from"
            + " a MULTI_FACTOR session moves the line CLOSING (201, outcome CLOSING), its key replaying the receipt - the"
            + " credit exposure falls from the limit to the drawn principal without the profile lock and the census holds;"
            + " a draw is then 409 lending.LineNotDrawable; the last payment closes it CLOSED_BY_CUSTOMER and refunds the"
            + " excess, counted closed; a second request is 409 lending.LoanClosed, another party's the uniform 404, an"
            + " instalment loan's 409 lending.LineNotClosable")
    void aLineIsRepaidAndClosedEndToEnd() throws Exception {
        CreditFixtures.Customer owner = customers.consentingCustomer();
        String offer = offered(owner, line("2500.00"));
        String hash = field(client.get(OFFERS + "/" + offer, owner.token()).body(), "termsSha256");
        String loanId = field(accept(owner.token(), offer, hash, null, key()).body(), "loanId");
        assertThat(draw(owner.token(), loanId, "1000.00", key()).statusCode()).isEqualTo(201);

        double paidDownBefore = repayments("paid_down");
        HttpResponse<String> paidDown = repay(owner.token(), loanId, "400.00", key());
        assertThat(paidDown.statusCode()).as(paidDown.body()).isEqualTo(201);
        assertThat(paidDown.body()).contains("\"target\":\"NOT_DUE_PRINCIPAL\"", "\"amount\":\"400.00\"",
                "\"closure\":null");
        assertThat(repayments("paid_down")).as("the paydown component counted").isEqualTo(paidDownBefore + 1);
        assertThat(client.get(LOANS + "/" + loanId, owner.token()).body())
                .contains("\"availableLimit\":\"1900.00\"", "\"status\":\"ACTIVE\"");
        Money exposureOpen = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow, owner.party(),
                EUR));
        assertThat(exposureOpen).as("an open line counts its limit").isEqualTo(Money.ofMinorUnits(2_500_00, EUR));

        String key = key();
        HttpResponse<String> closing = client.post(LOANS + "/" + loanId + "/closure", null, owner.token(), key);
        assertThat(closing.statusCode()).as(closing.body()).isEqualTo(201);
        assertThat(closing.body()).contains("\"loanId\":\"" + loanId + "\"", "\"outcome\":\"CLOSING\"",
                "\"closure\":null");
        HttpResponse<String> replay = client.post(LOANS + "/" + loanId + "/closure", null, owner.token(), key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).as("the receipt, byte for byte").isEqualTo(closing.body());
        assertThat(client.get(LOANS + "/" + loanId, owner.token()).body())
                .contains("\"status\":\"CLOSING\"", "\"availableLimit\":\"0.00\"");
        Money exposureClosing = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                owner.party(), EUR));
        assertThat(exposureClosing).as("a closing line counts its drawn principal").isEqualTo(Money.ofMinorUnits(600_00,
                EUR));
        assertThat(exposureCensus.readNow().failingParties()).as("the census holds the fallen figure")
                .doesNotContain(owner.party());
        HttpResponse<String> refused = draw(owner.token(), loanId, "10.00", key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("lending.LineNotDrawable");

        double closedBefore = repayments("closed");
        HttpResponse<String> last = repay(owner.token(), loanId, "600.00", key());
        assertThat(last.statusCode()).as(last.body()).isEqualTo(201);
        assertThat(last.body()).contains("\"reason\":\"CLOSED_BY_CUSTOMER\"", "\"refunded\":\"0.00\"");
        assertThat(repayments("closed")).isEqualTo(closedBefore + 1);
        assertThat(client.get(LOANS + "/" + loanId, owner.token()).body()).contains("\"status\":\"CLOSED\"");
        Money exposureClosed = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                owner.party(), EUR));
        assertThat(exposureClosed).isEqualTo(Money.ofMinorUnits(0, EUR));
        HttpResponse<String> again = client.post(LOANS + "/" + loanId + "/closure", null, owner.token(), key());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(again.body()).contains("lending.LoanClosed");
        HttpResponse<String> closedRepay = repay(owner.token(), loanId, "1.00", key());
        assertThat(closedRepay.statusCode()).as(closedRepay.body()).isEqualTo(409);
        assertThat(closedRepay.body()).contains("lending.LoanNotRepayable");

        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = client.post(LOANS + "/" + loanId + "/closure", null, stranger.token(), key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound");
        String loanOffer = offered(owner, loan("1500.00", 12, 10));
        String loanHash = field(client.get(OFFERS + "/" + loanOffer, owner.token()).body(), "termsSha256");
        String instalment = field(accept(owner.token(), loanOffer, loanHash, null, key()).body(), "loanId");
        HttpResponse<String> notALine = client.post(LOANS + "/" + instalment + "/closure", null, owner.token(), key());
        assertThat(notALine.statusCode()).as(notALine.body()).isEqualTo(409);
        assertThat(notALine.body()).contains("lending.LineNotClosable");
        assertThat(count("SELECT count(*) FROM lending.line_closure_request WHERE loan_id = ?::uuid", loanId))
                .isEqualTo(1);
        assertThat(meterRegistry.find(LoanCountMetrics.LOANS).tag("product", "credit_line").tag("status", "closing")
                .gauge()).as("finapp.lending.loans registered for a closing line").isNotNull();
    }

    @Test
    @DisplayName("a line is stated through the wired servicing sweep (P11-TSK-022): drawn over the door, its statement date"
            + " come, the sweep states the cycle once under the system actor - the cycle's accrued interest and the"
            + " minimum payment's principal part made due by rule 3 under lending.statement:<line>:<date> in the line's"
            + " own correlation, lending.CreditLineStatementIssued in the outbox, counted after the commit"
            + " (finapp.lending.billing{kind=statement}); the borrower's statements read shows it with the balances derived"
            + " from the ledger; another party's line and an instalment loan are the uniform 404; a second sweep states"
            + " nothing")
    void aLineIsStatedThroughTheWiredSweep() throws Exception {
        CreditFixtures.Customer owner = customers.consentingCustomer();
        String offer = offered(owner, line("2500.00"));
        String hash = field(client.get(OFFERS + "/" + offer, owner.token()).body(), "termsSha256");
        String loanId = field(accept(owner.token(), offer, hash, null, key()).body(), "loanId");
        assertThat(draw(owner.token(), loanId, "1000.00", key()).statusCode()).isEqualTo(201);
        assertThat(client.get(LOANS + "/" + loanId + "/statements", owner.token()).body())
                .as("not yet stated").contains("\"statements\":[]");
        // The fixture's clock: the line opened twenty days before its latest statement date (day 5), drawn the next day.
        LocalDate today = LocalDate.parse(scalar("SELECT lending.business_date(statement_timestamp(), 'UTC')::text"));
        LocalDate statementDate = today.getDayOfMonth() >= 5 ? today.withDayOfMonth(5)
                : today.minusMonths(1).withDayOfMonth(5);
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            bootstrap.setAutoCommit(false);
            try (Statement statement = bootstrap.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE lending.loan SET opened_at = TIMESTAMPTZ '" + statementDate.minusDays(20)
                        + " 01:00:00+00' WHERE id = '" + loanId + "'; UPDATE ledger.journal_entry SET posting_date = DATE '"
                        + statementDate.minusDays(19) + "', value_date = DATE '" + statementDate.minusDays(19)
                        + "' WHERE id = (SELECT journal_entry_id FROM lending.credit_line_draw WHERE loan_id = '" + loanId
                        + "'); UPDATE lending.credit_line_draw SET value_date = DATE '" + statementDate.minusDays(19)
                        + "' WHERE loan_id = '" + loanId + "'");
            }
            bootstrap.commit();
        }
        double before = stated();
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ?::uuid", loanId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement s JOIN ledger.journal_entry e"
                + " ON e.id = s.journal_entry_id JOIN lending.loan l ON l.id = s.loan_id WHERE s.loan_id = ?::uuid"
                + " AND e.idempotency_scope = 'ledger.post:lending.statement:' || s.loan_id || ':' || s.cycle_end"
                + " AND e.correlation_id = l.correlation_id AND s.cycle_end = ?::date AND s.principal_billed_minor = 3000"
                + " AND s.interest_billed_minor = (SELECT sum(a.posted_minor) FROM lending.interest_accrual a"
                + " WHERE a.loan_id = s.loan_id AND a.period_end = s.cycle_end)", loanId, statementDate.toString()))
                .as("rule 3 under the statement's key in the line's own flow: the cycle's interest and 3 % of 1,000.00")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?::uuid AND event_type ="
                + " 'lending.CreditLineStatementIssued'", loanId)).isEqualTo(1);
        assertThat(stated()).as("counted after the commit").isGreaterThanOrEqualTo(before + 1);
        String statements = client.get(LOANS + "/" + loanId + "/statements", owner.token()).body();
        String interest = scalar("SELECT to_char(interest_billed_minor / 100.0, 'FM999990.00')"
                + " FROM lending.credit_line_statement WHERE loan_id = ?::uuid", loanId);
        assertThat(statements).contains("\"statementDate\":\"" + statementDate + "\"",
                "\"dueDate\":\"" + statementDate.plusDays(25) + "\"", "\"status\":\"DUE\"",
                "\"interestBilled\":\"" + interest + "\"", "\"principalBilled\":\"30.00\"",
                "\"openingBalance\":\"0.00\"", "\"feesCarried\":\"0.00\"", "\"statementEngineVersion\":1");
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = client.get(LOANS + "/" + loanId + "/statements", stranger.token());
        assertThat(foreign.statusCode()).as("another party's line: the same 404 as an unknown one").isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound").doesNotContain(interest);
        assertThat(client.get(LOANS + "/" + UUID.randomUUID() + "/statements", owner.token()).statusCode())
                .isEqualTo(404);
        servicing.sweepOnce();
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ?::uuid", loanId))
                .as("stated once, however often swept").isEqualTo(1);
    }

    private double stated() {
        return meterRegistry.find(LoanServicingMetrics.BILLING).tag("kind", "statement").tag("outcome", "billed")
                .counter().count();
    }

    @Test
    @DisplayName("a loan is repaid from the borrower's wallet end to end (P11-TSK-018): billed through the wired sweep, then"
            + " repaid over the door - 201 with the allocation (interest, principal, the rest held), the key replaying"
            + " its receipt byte for byte and refusing another amount 409; the schedule read shows the instalment PAID; the"
            + " repayments read lists it; more than the wallet holds 422 lending.InsufficientFunds; another party's loan"
            + " the uniform 404 both ways; the platform's credit exposure falls by the principal repaid; each counted after"
            + " its commit")
    void aLoanIsRepaidFromTheBorrowersWalletEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3650.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        toFirstDueDate(loanId);
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        long interest = count("SELECT interest_due_minor FROM lending.instalment_billing WHERE loan_id = ?::uuid", loanId);
        long principal = count("SELECT principal_due_minor FROM lending.instalment_billing WHERE loan_id = ?::uuid",
                loanId);
        Money exposureBefore = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                customer.party(), EUR));

        double repaidBefore = repayments("repaid");
        double insufficientBefore = repayments("insufficient_funds");
        String amount = Money.ofMinorUnits(interest + principal + 10_00, EUR).toBigDecimal().toPlainString();
        String key = key();
        HttpResponse<String> repaid = repay(customer.token(), loanId, amount, key);
        assertThat(repaid.statusCode()).as(repaid.body()).isEqualTo(201);
        assertThat(repaid.body()).contains("\"loanId\":\"" + loanId + "\"", "\"kind\":\"CUSTOMER\"",
                "\"status\":\"ALLOCATED\"", "\"amount\":\"" + amount + "\"", "\"target\":\"CREDIT_BALANCE\"",
                "\"component\":\"INTEREST\"", "\"component\":\"PRINCIPAL\"", "\"amount\":\"10.00\"",
                "\"closure\":null");
        HttpResponse<String> replay = repay(customer.token(), loanId, amount, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).as("the receipt, byte for byte").isEqualTo(repaid.body());
        HttpResponse<String> reused = repay(customer.token(), loanId, "1.00", key);
        assertThat(reused.statusCode()).as(reused.body()).isEqualTo(409);
        assertThat(reused.body()).contains("api.Conflict");
        HttpResponse<String> beyond = repay(customer.token(), loanId, "99999.00", key());
        assertThat(beyond.statusCode()).as(beyond.body()).isEqualTo(422);
        assertThat(beyond.body()).contains("lending.InsufficientFunds").doesNotContain("99999");
        assertThat(repayments("repaid")).as("counted after the commit, the replay not").isEqualTo(repaidBefore + 1);
        assertThat(repayments("insufficient_funds")).isEqualTo(insufficientBefore + 1);

        assertThat(client.get(LOANS + "/" + loanId + "/schedule", customer.token()).body())
                .contains("\"status\":\"PAID\"");
        String listed = client.get(LOANS + "/" + loanId + "/repayments", customer.token()).body();
        assertThat(listed).contains(field(repaid.body(), "repaymentId"));
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);
        Money exposureAfter = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                customer.party(), EUR));
        assertThat(exposureAfter).as("exposure falls by the principal repaid (-013's carried case, a real repayment)")
                .isEqualTo(exposureBefore.minus(Money.ofMinorUnits(principal, EUR)));

        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = repay(stranger.token(), loanId, "1.00", key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound");
        assertThat(client.get(LOANS + "/" + loanId + "/repayments", stranger.token()).statusCode()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the payoff (P11-TSK-026)

    @Test
    @DisplayName("a loan is paid off end to end (P11-TSK-026): billed and ten days into its second period through the wired"
            + " sweep, quoted over the door - 201, its key replaying byte for byte - then paid off from a MULTI_FACTOR"
            + " session - 201 CLOSED (PAID_OFF_EARLY), the key replaying, another key on the quote converging on the one"
            + " payoff; the loan read CLOSED with every balance zero; the platform's credit exposure zero and the capital"
            + " headroom restored by the principal; a new quote 409 lending.LoanNotRepayable; another party's loan 404; each"
            + " counted after its commit")
    void aLoanIsPaidOffEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3650.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        toFirstDueDate(loanId, 10);
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);
        topUpWallet(customer.party(), Money.ofMinorUnits(500_00, EUR));

        double quotedBefore = payoffs("quoted");
        double paidOffBefore = payoffs("paid_off");
        String quoteKey = key();
        HttpResponse<String> quoted = client.post(LOANS + "/" + loanId + "/payoff-quotes", "{}", customer.token(),
                quoteKey);
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(201);
        assertThat(quoted.body()).contains("\"loanId\":\"" + loanId + "\"", "\"refund\":\"0.00\"", "\"currency\":\"EUR\"",
                "\"projectedDays\":0");
        HttpResponse<String> quotedAgain = client.post(LOANS + "/" + loanId + "/payoff-quotes", "{}", customer.token(),
                quoteKey);
        assertThat(quotedAgain.body()).as("the receipt, byte for byte").isEqualTo(quoted.body());
        String quoteId = field(quoted.body(), "quoteId");
        String amount = field(quoted.body(), "amount");
        long principal = Money.of(new java.math.BigDecimal(field(quoted.body(), "principal")), EUR).minorUnits()
                + Money.of(new java.math.BigDecimal(field(quoted.body(), "principalDue")), EUR).minorUnits();
        Money headroomBefore = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        assertThat(lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow, customer.party(), EUR))
                .isPositive()).isTrue();

        String payoffKey = key();
        HttpResponse<String> paidOff = client.post(LOANS + "/" + loanId + "/payoff",
                "{\"quoteId\":\"" + quoteId + "\"}", customer.token(), payoffKey);
        assertThat(paidOff.statusCode()).as(paidOff.body()).isEqualTo(201);
        assertThat(paidOff.body()).contains("\"quoteId\":\"" + quoteId + "\"", "\"closureReason\":\"PAID_OFF_EARLY\"",
                "\"paid\":\"" + amount + "\"", "\"refunded\":\"0.00\"");
        HttpResponse<String> replayed = client.post(LOANS + "/" + loanId + "/payoff",
                "{\"quoteId\":\"" + quoteId + "\"}", customer.token(), payoffKey);
        assertThat(replayed.body()).as("the receipt, byte for byte").isEqualTo(paidOff.body());
        HttpResponse<String> converged = client.post(LOANS + "/" + loanId + "/payoff",
                "{\"quoteId\":\"" + quoteId + "\"}", customer.token(), key());
        assertThat(converged.statusCode()).as(converged.body()).isEqualTo(201);
        assertThat(field(converged.body(), "executionId")).as("one payoff").isEqualTo(field(paidOff.body(), "executionId"));
        assertThat(payoffs("quoted")).as("counted after the commit, the replay not").isEqualTo(quotedBefore + 1);
        assertThat(payoffs("paid_off")).as("the converged and the replayed not counted").isEqualTo(paidOffBefore + 1);

        assertThat(client.get(LOANS + "/" + loanId, customer.token()).body()).contains("\"status\":\"CLOSED\"");
        assertThat(count("SELECT count(*) FROM (SELECT a.id FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.owner_ref = ?::uuid GROUP BY a.id HAVING sum(CASE l.direction"
                + " WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END) <> 0) unsettled", loanId))
                .as("every balance of the loan zero").isZero();
        assertThat(lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow, customer.party(), EUR))
                .isZero()).as("nothing outstanding for the party").isTrue();
        assertThat(lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom())
                .as("the principal repaid restores the headroom")
                .isEqualTo(headroomBefore.plus(Money.ofMinorUnits(principal, EUR)));
        HttpResponse<String> afterwards = client.post(LOANS + "/" + loanId + "/payoff-quotes", "{}", customer.token(),
                key());
        assertThat(afterwards.statusCode()).as(afterwards.body()).isEqualTo(409);
        assertThat(afterwards.body()).contains("lending.LoanNotRepayable");

        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = client.post(LOANS + "/" + loanId + "/payoff", "{\"quoteId\":\"" + quoteId + "\"}",
                stranger.token(), key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound");
    }

    private double payoffs(String outcome) {
        return meterRegistry.find(PayoffMetrics.PAYOFF).tag("outcome", outcome).counter().count();
    }

    /** Money paid into the party's wallet - the fixture standing in for payments' pay-in, never a lending path. */
    private void topUpWallet(UUID party, Money amount) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p11t26")))) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p11t26-topup:" + UUID.randomUUID(), today, today,
                        "the suite's top-up", List.of(
                                new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR)
                                        .orElseThrow().id(), Direction.DEBIT, amount),
                                new JournalLine(loanWallets.existingWallet(uow, party, EUR).orElseThrow(),
                                        Direction.CREDIT, amount))));
            });
        }
    }

    // ------------------------------------------------------------------ auto-collection (P11-TSK-019)

    @Test
    @DisplayName("a loan whose terms collect automatically is collected on its due date through the wired servicing sweep"
            + " (P11-TSK-019): billed and collected in one step under the system actor - one collection_attempt COLLECTED,"
            + " one AUTO_COLLECTION repayment of exactly the bill under the attempt's key, lending.RepaymentReceived of"
            + " that kind; the schedule read shows the instalment PAID and the repayments read lists it; counted after the"
            + " commit in finapp.lending.collection and finapp.lending.repayment{kind=auto_collection}; a second sweep"
            + " collects nothing")
    void aLoanIsCollectedOnItsDueDateThroughTheWiredSweep() throws Exception {
        activateTerms(LoanApplicationDatabaseTest.terms(LoanProduct.PERSONAL_LOAN).toBuilder().autoCollection(true)
                .build());
        CreditFixtures.Customer customer;
        String offer;
        try {
            customer = customers.consentingCustomer();
            offer = offered(customer, loan("3650.00", 12, 10));
        } finally {
            // The offer pinned the collecting version; the suite's default (no auto-collection) is back in force.
            activateTerms(LoanApplicationDatabaseTest.terms(LoanProduct.PERSONAL_LOAN));
        }
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        toFirstDueDate(loanId);
        double collectedBefore = collections("collected");
        double repaidBefore = meterRegistry.find(RepaymentMetrics.REPAYMENT).tag("kind", "auto_collection")
                .tag("outcome", "repaid").counter().count();
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        long bill = count("SELECT interest_due_minor + principal_due_minor FROM lending.instalment_billing"
                + " WHERE loan_id = ?::uuid", loanId);
        assertThat(count("SELECT count(*) FROM lending.collection_attempt WHERE loan_id = ?::uuid AND outcome ="
                + " 'COLLECTED' AND collected_minor = due_minor", loanId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment r JOIN lending.collection_attempt a"
                + " ON a.repayment_id = r.id WHERE r.loan_id = ?::uuid AND r.kind = 'AUTO_COLLECTION'"
                + " AND r.request_key = a.due_item_id || ':' || a.attempt_date AND r.amount_minor = ?", loanId, bill))
                .as("one collection of exactly the bill, under the attempt's key").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?::uuid AND event_type ="
                + " 'lending.RepaymentReceived' AND convert_from(payload, 'UTF8') LIKE '%\"kind\":\"AUTO_COLLECTION\"%'",
                loanId)).isEqualTo(1);
        assertThat(collections("collected")).as("counted after the commit").isEqualTo(collectedBefore + 1);
        assertThat(meterRegistry.find(RepaymentMetrics.REPAYMENT).tag("kind", "auto_collection").tag("outcome", "repaid")
                .counter().count()).isEqualTo(repaidBefore + 1);
        assertThat(client.get(LOANS + "/" + loanId + "/schedule", customer.token()).body())
                .contains("\"status\":\"PAID\"");
        assertThat(client.get(LOANS + "/" + loanId + "/repayments", customer.token()).body())
                .contains("\"kind\":\"AUTO_COLLECTION\"");
        servicing.sweepOnce();
        assertThat(count("SELECT count(*) FROM lending.collection_attempt WHERE loan_id = ?::uuid", loanId))
                .as("once a day, however often swept").isEqualTo(1);
    }

    private void activateTerms(com.finapp.lending.LoanTerms terms) {
        lendingTransactions.inTransaction(uow -> {
            var proposed = termsAdministration.propose(uow, terms, "the suite's terms", employee(),
                    CorrelationId.generate(ids));
            termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
            return null;
        });
    }

    private double collections(String outcome) {
        return meterRegistry.find(LoanServicingMetrics.COLLECTION).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("a repayment is reversed under four eyes end to end (P11-TSK-020): repaid over the door, then the borrower"
            + " - granted the servicing role - refused their own loan 403 lending.SelfDealingRefused, recorded FAILED"
            + " through identity's real read; an agent proposes (201, the key replaying its receipt), is refused their own"
            + " approval 403, a second agent approves - rule 10 posted, the customer's read shows REVERSED, the platform's"
            + " credit exposure rises by the principal re-opened, the operator's read lists it and is audited; each act"
            + " counted after its commit")
    void aRepaymentIsReversedUnderFourEyesEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3700.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        toFirstDueDate(loanId);
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        long interest = count("SELECT interest_due_minor FROM lending.instalment_billing WHERE loan_id = ?::uuid", loanId);
        long principal = count("SELECT principal_due_minor FROM lending.instalment_billing WHERE loan_id = ?::uuid",
                loanId);
        String amount = Money.ofMinorUnits(interest + principal, EUR).toBigDecimal().toPlainString();
        HttpResponse<String> repaid = repay(customer.token(), loanId, amount, key());
        assertThat(repaid.statusCode()).as(repaid.body()).isEqualTo(201);
        String repaymentId = field(repaid.body(), "repaymentId");
        Money exposureBefore = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                customer.party(), EUR));
        String reversalPath = "/v1/operator/loans/" + loanId + "/repayments/" + repaymentId + "/reversal";
        String reason = "{\"reason\":\"the operator keyed the customer's transfer twice\"}";

        double selfDealingBefore = reversals("self_dealing_refused");
        assign(UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE party_id = ?::uuid",
                customer.party().toString())), RoleName.LOAN_SERVICING_AGENT);
        HttpResponse<String> insider = client.post(reversalPath, reason, customer.token(), key());
        assertThat(insider.statusCode()).as(insider.body()).isEqualTo(403);
        assertThat(insider.body()).contains("lending.SelfDealingRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = ?", loanId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment_reversal WHERE loan_id = ?::uuid", loanId)).isZero();
        assertThat(reversals("self_dealing_refused")).isEqualTo(selfDealingBefore + 1);

        String proposer = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String approver = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        double approvedBefore = reversals("approved");
        String proposalKey = key();
        HttpResponse<String> proposed = client.post(reversalPath, reason, proposer, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"", "\"repaymentId\":\"" + repaymentId + "\"")
                .doesNotContain("keyed the customer");
        assertThat(client.post(reversalPath, reason, proposer, proposalKey).body()).as("the receipt, byte for byte")
                .isEqualTo(proposed.body());
        String decision = "{\"reversalId\":\"" + field(proposed.body(), "reversalId")
                + "\",\"reason\":\"matched the bank statement\"}";
        HttpResponse<String> own = client.post(reversalPath + "/approval", decision, proposer, key());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("lending.SelfApprovalRefused");
        HttpResponse<String> approved = client.post(reversalPath + "/approval", decision, approver, key());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"", "\"journalEntryId\":\"");
        assertThat(reversals("approved")).as("counted after the commit").isEqualTo(approvedBefore + 1);

        assertThat(client.get(LOANS + "/" + loanId + "/repayments", customer.token()).body())
                .contains("\"status\":\"REVERSED\"");
        Money exposureAfter = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                customer.party(), EUR));
        assertThat(exposureAfter).as("-013's read: exposure rises by the principal the reversal re-opened")
                .isEqualTo(exposureBefore.plus(Money.ofMinorUnits(principal, EUR)));
        HttpResponse<String> read = client.get("/v1/operator/loans/" + loanId, proposer);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"status\":\"REVERSED\"", field(proposed.body(), "reversalId"));
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.LoanRead'"
                + " AND target_id = ?", loanId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the explanation door (P11-TSK-029)

    @Test
    @DisplayName("a loan is explained and replayed end to end (P11-TSK-029): disbursed through the sweep, then the borrower -"
            + " granted the investigator's role - refused their own loan's explanation and replay 403"
            + " lending.SelfDealingRefused, each recorded FAILED; a lending officer is served the explanation from rows"
            + " (the agreement and its hash, the schedule, the disbursement's entry named by its row, the six balances"
            + " explained, HOLDS) audited lending.ExplanationRead with no amount in the record, and the replay IDENTICAL"
            + " audited lending.LoanReplayed with the reason")
    void aLoanIsExplainedAndReplayedEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("4200.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        String explanation = "/v1/operator/loans/" + loanId + "/explanation";
        String replay = "/v1/operator/loans/" + loanId + "/replay";
        String reason = "{\"reason\":\"the customer disputes the first schedule\"}";

        assign(UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE party_id = ?::uuid",
                customer.party().toString())), RoleName.LENDING_OFFICER);
        HttpResponse<String> insider = client.get(explanation, customer.token());
        assertThat(insider.statusCode()).as(insider.body()).isEqualTo(403);
        assertThat(insider.body()).contains("lending.SelfDealingRefused").doesNotContain("4200");
        assertThat(client.post(replay, reason, customer.token(), null).statusCode()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = ? AND change_summary LIKE '%act=investigation:%'", loanId))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('lending.ExplanationRead',"
                + " 'lending.LoanReplayed') AND target_id = ?", loanId)).as("nothing served, nothing recorded").isZero();

        String officer = sessionWith(RoleName.LENDING_OFFICER);
        HttpResponse<String> explained = client.get(explanation, officer);
        assertThat(explained.statusCode()).as(explained.body()).isEqualTo(200);
        assertThat(explained.body()).contains("\"loanId\":\"" + loanId + "\"", "\"status\":\"ACTIVE\"",
                "\"version\":1", "\"origin\":\"OFFER\"", "\"termsSha256\":\"" + hash.toLowerCase(java.util.Locale.ROOT)
                        + "\"", "\"explainedBy\":\"DISBURSEMENT\"", "\"subledgerVerdict\":\"HOLDS\"",
                "\"account\":\"LOAN_PRINCIPAL\",\"ledger\":\"4200.00\",\"rows\":\"4200.00\",\"explained\":true")
                .doesNotContain("UNEXPLAINED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.ExplanationRead'"
                + " AND outcome = 'SUCCEEDED' AND target_id = ? AND change_summary LIKE '%subledger=HOLDS%'"
                + " AND change_summary NOT LIKE '%4200%'", loanId)).isEqualTo(1);

        HttpResponse<String> replayed = client.post(replay, reason, officer, null);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(replayed.body()).contains("\"verdict\":\"IDENTICAL\"", "\"divergences\":[]");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.LoanReplayed'"
                + " AND outcome = 'SUCCEEDED' AND target_id = ? AND reason = 'the customer disputes the first schedule'"
                + " AND change_summary LIKE '%verdict=IDENTICAL%'", loanId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the condition (P11-TSK-024)

    @Test
    @DisplayName("a past-due loan's condition is recorded and read end to end (P11-TSK-024): its first instalment due"
            + " yesterday and unpaid, the wired sweep bills it and records B1 at DPD 1 - one row, one"
            + " lending.LoanDelinquencyChanged, the loan still ACTIVE; the customer's read shows PAST_DUE, 1, B1_1_29, not"
            + " defaulted, and so does the audited operator read; finapp.lending.delinquency counts it; repaid over the"
            + " door, the customer's read is CURRENT at once")
    void aPastDueLoansConditionIsRecordedAndReadEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3750.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        toFirstDueDate(loanId, 1);
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?::uuid", loanId)).isEqualTo(1);
        assertThat(scalar("SELECT kind || ':' || days_past_due || ':' || bucket || ':' || previous_bucket || ':' || defaulted"
                + " FROM lending.loan_condition_event WHERE loan_id = ?::uuid", loanId))
                .isEqualTo("DELINQUENCY:1:B1_1_29:CURRENT:false");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?::uuid AND event_type ="
                + " 'lending.LoanDelinquencyChanged'", loanId)).isEqualTo(1);
        String condition = "\"condition\":{\"delinquency\":\"PAST_DUE\",\"daysPastDue\":1,\"bucket\":\"B1_1_29\","
                + "\"defaulted\":false,\"drawsSuspended\":null}";
        String read = client.get(LOANS + "/" + loanId, customer.token()).body();
        assertThat(read).contains("\"status\":\"ACTIVE\"", condition);
        String operator = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        HttpResponse<String> operatorRead = client.get("/v1/operator/loans/" + loanId, operator);
        assertThat(operatorRead.statusCode()).as(operatorRead.body()).isEqualTo(200);
        assertThat(operatorRead.body()).contains(condition);
        assertThat(meterRegistry.find(LoanConditionMetrics.DELINQUENCY).tag("product", "personal_loan")
                .tag("bucket", "b1_1_29").gauge().value()).as("counted from the recorded condition").isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.find(LoanConditionMetrics.DEFAULT).tag("product", "credit_line").gauge()).isNotNull();

        long owed = count("SELECT interest_due_minor + principal_due_minor FROM lending.instalment_billing"
                + " WHERE loan_id = ?::uuid", loanId);
        HttpResponse<String> repaid = repay(customer.token(), loanId,
                Money.ofMinorUnits(owed, EUR).toBigDecimal().toPlainString(), key());
        assertThat(repaid.statusCode()).as(repaid.body()).isEqualTo(201);
        assertThat(client.get(LOANS + "/" + loanId, customer.token()).body()).contains("\"condition\":{\"delinquency\":"
                + "\"CURRENT\",\"daysPastDue\":0,\"bucket\":null,\"defaulted\":false,\"drawsSuspended\":null}");
    }

    // ------------------------------------------------------------------ the late fee and its waiver (P11-TSK-025)

    @Test
    @DisplayName("a late fee is assessed through the wired sweep and waived under four eyes end to end (P11-TSK-025): the"
            + " first instalment due five days ago and unpaid, the wired sweep bills it and assesses the pinned 15.00 fee"
            + " once - one lending.LoanFeeAssessed, finapp.lending.fee counted; the operator's read lists it; an agent"
            + " proposes its waiver over the door (201, the key replaying its receipt), is refused their own approval 403,"
            + " a second agent approves - rule 8 posted as a reversal of the fee's entry, the item owes no fee, the"
            + " customer's schedule shows it, finapp.lending.waiver counted; a further waiver of the fee is 422"
            + " lending.WaiverExceedsDue")
    void aLateFeeIsAssessedAndWaivedEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("3800.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        toFirstDueDate(loanId, 5);
        double feesBefore = meterRegistry.find(LoanServicingMetrics.FEE).tag("kind", "late").tag("outcome", "assessed")
                .counter().count();
        LoanServicingSchedule servicing = new LoanServicingSchedule(loanServicing, lendingTransactions, servicingMetrics,
                Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (servicing.sweepOnce() == 500) {
            // every due account serviced
        }
        assertThat(scalar("SELECT kind || ':' || amount_minor FROM lending.fee_assessment WHERE loan_id = ?::uuid", loanId))
                .isEqualTo("LATE:1500");
        String assessment = scalar("SELECT id::text FROM lending.fee_assessment WHERE loan_id = ?::uuid", loanId);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?::uuid AND event_type ="
                + " 'lending.LoanFeeAssessed'", loanId)).isEqualTo(1);
        assertThat(meterRegistry.find(LoanServicingMetrics.FEE).tag("kind", "late").tag("outcome", "assessed").counter()
                .count()).as("counted after the commit").isEqualTo(feesBefore + 1);

        String proposer = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String approver = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        HttpResponse<String> listed = client.get("/v1/operator/loans/" + loanId, proposer);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.body()).contains("\"assessmentId\":\"" + assessment + "\"", "\"amount\":\"15.00\"");

        String waivers = "/v1/operator/loans/" + loanId + "/waivers";
        String proposal = "{\"kind\":\"FEE\",\"targetId\":\"" + assessment + "\",\"amount\":\"15.00\",\"currency\":\"EUR\","
                + "\"reason\":\"branch outage on the due date, documented\"}";
        double approvedBefore = meterRegistry.find(LoanWaiverMetrics.WAIVER).tag("kind", "fee").tag("outcome", "approved")
                .counter().count();
        String proposalKey = key();
        HttpResponse<String> proposed = client.post(waivers, proposal, proposer, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"", "\"kind\":\"FEE\"").doesNotContain("branch outage");
        assertThat(client.post(waivers, proposal, proposer, proposalKey).body()).as("the receipt, byte for byte")
                .isEqualTo(proposed.body());
        String waiver = field(proposed.body(), "waiverId");
        String decision = "{\"reason\":\"matched the outage log\"}";
        HttpResponse<String> own = client.post(waivers + "/" + waiver + "/approval", decision, proposer, key());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("lending.SelfApprovalRefused");
        HttpResponse<String> approved = client.post(waivers + "/" + waiver + "/approval", decision, approver, key());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"", "\"journalEntryId\":\"");
        assertThat(scalar("SELECT reverses_entry_id::text FROM ledger.journal_entry WHERE id = ?::uuid",
                field(approved.body(), "journalEntryId")))
                .isEqualTo(scalar("SELECT journal_entry_id::text FROM lending.fee_assessment WHERE id = ?::uuid", assessment));
        assertThat(count("SELECT lending.billing_component_billed(due_item_id, 'FEES') FROM lending.fee_assessment"
                + " WHERE id = ?::uuid", assessment)).as("the item owes no fee").isZero();
        assertThat(meterRegistry.find(LoanWaiverMetrics.WAIVER).tag("kind", "fee").tag("outcome", "approved").counter()
                .count()).isEqualTo(approvedBefore + 1);
        assertThat(client.get(LOANS + "/" + loanId + "/schedule", customer.token()).body())
                .contains("\"feesBilled\":\"0.00\"");

        HttpResponse<String> exceeds = client.post(waivers, proposal.replace("15.00", "0.01"), proposer, key());
        assertThat(exceeds.statusCode()).as(exceeds.body()).isEqualTo(422);
        assertThat(exceeds.body()).contains("lending.WaiverExceedsDue");
    }

    @Test
    @DisplayName("a loan is restructured under four eyes and the customer's acceptance end to end (P11-TSK-027): a freshly"
            + " disbursed loan - an agent proposes a lower rate over a longer term over the door (201, the key replaying its"
            + " receipt), is refused their own approval 403, a second agent approves (200, APPROVED with its expiry); the"
            + " customer reads it with its document, an acceptance echoing another hash is 409 lending.TermsChanged and"
            + " one from a session below MULTI_FACTOR 403, the acceptance is 201 - agreement version 2 and schedule"
            + " version 2, the key replaying its receipt; the operator's read lists it ACCEPTED, the customer's schedule"
            + " door answers version 2, finapp.lending.amendment counted once per act")
    void aLoanIsRestructuredEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("4200.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        String rate = new java.math.BigDecimal(scalar("SELECT nominal_annual_rate FROM lending.loan_agreement"
                + " WHERE loan_id = ?::uuid", loanId)).divide(java.math.BigDecimal.valueOf(2), 4,
                java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();

        String proposer = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String approver = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String amendments = "/v1/operator/loans/" + loanId + "/amendments";
        String proposal = "{\"nominalAnnualRate\":\"" + rate + "\",\"remainingInstalments\":18,"
                + "\"rescheduleArrears\":false,\"reason\":\"hardship after a documented job loss\"}";
        double proposedBefore = amended("proposed");
        double acceptedBefore = amended("accepted");
        String proposalKey = key();
        HttpResponse<String> proposed = client.post(amendments, proposal, proposer, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"", "\"amendsVersion\":1")
                .doesNotContain("job loss");
        assertThat(client.post(amendments, proposal, proposer, proposalKey).body()).as("the receipt, byte for byte")
                .isEqualTo(proposed.body());
        assertThat(amended("proposed")).isEqualTo(proposedBefore + 1);
        String amendment = field(proposed.body(), "amendmentId");
        String decision = "{\"reason\":\"affordability reviewed\"}";
        HttpResponse<String> own = client.post(amendments + "/" + amendment + "/approval", decision, proposer, key());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("lending.SelfApprovalRefused");
        HttpResponse<String> approved = client.post(amendments + "/" + amendment + "/approval", decision, approver, key());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"", "\"expiresAt\":\"");

        String mine = LOANS + "/" + loanId + "/amendments/" + amendment;
        HttpResponse<String> offered = client.get(mine, customer.token());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(200);
        assertThat(offered.body()).contains("LOAN AMENDMENT " + amendment).doesNotContain("job loss");
        String document = field(offered.body(), "documentSha256");
        HttpResponse<String> changed = client.post(mine + "/acceptance", "{\"documentSha256\":\"" + "ab".repeat(32)
                + "\"}", customer.token(), key());
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(409);
        assertThat(changed.body()).contains("lending.TermsChanged");
        String acceptanceKey = key();
        String acceptance = "{\"documentSha256\":\"" + document + "\"}";
        HttpResponse<String> accepted = client.post(mine + "/acceptance", acceptance, customer.token(), acceptanceKey);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        assertThat(accepted.body()).contains("\"status\":\"ACCEPTED\"", "\"agreementVersion\":2",
                "\"scheduleVersion\":2");
        assertThat(client.post(mine + "/acceptance", acceptance, customer.token(), acceptanceKey).body())
                .as("the receipt, byte for byte").isEqualTo(accepted.body());
        assertThat(amended("accepted")).isEqualTo(acceptedBefore + 1);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?::uuid", loanId)).isEqualTo(2);
        assertThat(client.get("/v1/operator/loans/" + loanId, proposer).body())
                .contains("\"amendmentId\":\"" + amendment + "\"", "\"status\":\"ACCEPTED\"");
        assertThat(client.get(LOANS + "/" + loanId + "/schedule", customer.token()).body())
                .contains("\"scheduleVersion\":2", "\"agreementVersion\":2");
    }

    @Test
    @DisplayName("a loan is prepaid end to end (P11-TSK-028): a freshly disbursed loan prepaid EUR 1,000.00 over the door"
            + " from a MULTI_FACTOR session - 201 with agreement version 2 and schedule version 2, the level instalment"
            + " unchanged over fewer instalments, the key replaying its receipt byte for byte; the customer's schedule door"
            + " answers version 2; the platform's credit exposure falls and the capital headroom rises by the amount, the"
            + " prepayment completing while another transaction holds the party's profile (L0) and the capital row (L6);"
            + " the whole principal is 422 lending.PrepaymentExceedsPrincipal; another party's loan 404; finapp.lending.repayment{kind=prepayment} counted once per outcome")
    void aLoanIsPrepaidEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("4300.00", 12, 10));
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        String loanId = field(accept(customer.token(), offer, hash, null, key()).body(), "loanId");
        new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics, Duration.ofSeconds(5), 100,
                Duration.ofMinutes(1)).sweepOnce();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        String instalment = Money.ofMinorUnits(count("SELECT instalment_minor FROM lending.repayment_schedule"
                + " WHERE loan_id = ?::uuid AND schedule_version = 1", loanId), EUR).toBigDecimal().toPlainString();
        Money exposureBefore = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                customer.party(), EUR));
        Money headroomBefore = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        double prepaidBefore = prepaid("prepaid");
        double exceedsBefore = prepaid("exceeds_principal");

        String prepayments = LOANS + "/" + loanId + "/prepayments";
        String body = "{\"amount\":\"1000.00\",\"currency\":\"EUR\"}";
        String prepaymentKey = key();
        // No L0 and no L6: the prepayment completes while another transaction holds the party's credit profile and the
        // currency's LENDING_CAPITAL row - exposure only falls and headroom only rises, so it waits on neither.
        HttpResponse<String> prepaid;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement profile = holder.prepareStatement(
                            "SELECT 1 FROM credit.credit_profile WHERE party_id = ? FOR UPDATE");
                    PreparedStatement capital = holder.prepareStatement("SELECT 1 FROM ledger.ledger_account"
                            + " WHERE purpose = 'LENDING_CAPITAL' AND currency = 'EUR' FOR UPDATE")) {
                profile.setObject(1, customer.party());
                try (ResultSet row = profile.executeQuery()) {
                    assertThat(row.next()).as("the decided party has its profile").isTrue();
                }
                try (ResultSet row = capital.executeQuery()) {
                    assertThat(row.next()).as("ledger V026 seeds the capital account").isTrue();
                }
            }
            Future<HttpResponse<String>> prepaying =
                    pool.submit(() -> client.post(prepayments, body, customer.token(), prepaymentKey));
            prepaid = prepaying.get(60, TimeUnit.SECONDS);
            holder.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(prepaid.statusCode()).as(prepaid.body()).isEqualTo(201);
        assertThat(prepaid.body()).contains("\"kind\":\"PREPAYMENT\"", "\"target\":\"NOT_DUE_PRINCIPAL\"",
                "\"agreementVersion\":2", "\"scheduleVersion\":2", "\"instalment\":\"" + instalment + "\"");
        assertThat(Integer.parseInt(prepaid.body().replaceAll(".*\"remainingInstalments\":([0-9]+).*", "$1")))
                .as("the term shortened").isLessThan(12);
        assertThat(client.post(prepayments, body, customer.token(), prepaymentKey).body()).as("the receipt, byte for byte")
                .isEqualTo(prepaid.body());
        assertThat(prepaid("prepaid")).as("counted after the commit, the replay not").isEqualTo(prepaidBefore + 1);
        assertThat(client.get(LOANS + "/" + loanId + "/schedule", customer.token()).body())
                .contains("\"scheduleVersion\":2", "\"agreementVersion\":2");
        Money exposureAfter = lendingTransactions.inTransaction(uow -> creditExposure.outstandingFor(uow,
                customer.party(), EUR));
        assertThat(exposureAfter).as("exposure falls by the amount prepaid").isEqualTo(exposureBefore.minus(Money.ofMinorUnits(1_000_00, EUR)));
        assertThat(lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom())
                .as("the principal repaid restores the headroom")
                .isEqualTo(headroomBefore.plus(Money.ofMinorUnits(1_000_00, EUR)));

        HttpResponse<String> whole = client.post(prepayments, "{\"amount\":\"3300.00\",\"currency\":\"EUR\"}",
                customer.token(), key());
        assertThat(whole.statusCode()).as(whole.body()).isEqualTo(422);
        assertThat(whole.body()).contains("lending.PrepaymentExceedsPrincipal");
        assertThat(prepaid("exceeds_principal")).isEqualTo(exceedsBefore + 1);
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> foreign = client.post(prepayments, body, stranger.token(), key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("lending.NotFound");
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?::uuid AND kind = 'PREPAYMENT'", loanId))
                .isEqualTo(1);
    }

    private double prepaid(String outcome) {
        return meterRegistry.find(RepaymentMetrics.REPAYMENT).tag("kind", "prepayment").tag("outcome", outcome).counter()
                .count();
    }

    private double amended(String outcome) {
        return meterRegistry.find(LoanAmendmentMetrics.AMENDMENT).tag("outcome", outcome).counter().count();
    }

    // ------------------------------------------------------------------ plumbing

    private double reversals(String outcome) {
        return meterRegistry.find(RepaymentReversalMetrics.REVERSAL).tag("outcome", outcome).counter().count();
    }

    /** {@code role} assigned to {@code identity}, by the system - the fixture's grant. */
    private void assign(UUID identity, RoleName role) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
    }

    /** A signed-in operator session holding {@code role}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "la." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "a-perfectly-fine-pw-7";
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Grace"
                + " Hopper\",\"password\":\"" + password + "\"}", null, key()).statusCode()).isEqualTo(201);
        assign(UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login)), role);
        return field(client.post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\""
                + password + "\"}", null, key()).body(), "sessionToken");
    }

    /** The fixture's clock: the loan's dated facts moved back until its first due date is today (never a path). */
    static void toFirstDueDate(String loanId) throws Exception {
        toFirstDueDate(loanId, 0);
    }

    /** As {@link #toFirstDueDate(String)}, the first due date {@code daysAgo} days before today (P11-TSK-024). */
    static void toFirstDueDate(String loanId, int daysAgo) throws Exception {
        long days = daysAgo + count("SELECT i.due_date - s.start_date FROM lending.loan_instalment i"
                + " JOIN lending.accrual_start s ON s.loan_id = i.loan_id WHERE i.loan_id = ?::uuid AND i.sequence = 1",
                loanId);
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE ledger.journal_entry SET posting_date = posting_date - " + days + ", value_date ="
                        + " value_date - " + days + " WHERE id = (SELECT journal_entry_id FROM lending.loan_disbursement"
                        + " WHERE loan_id = '" + loanId + "'); UPDATE lending.loan_disbursement SET value_date = value_date"
                        + " - " + days + " WHERE loan_id = '" + loanId + "'; UPDATE lending.accrual_start SET start_date ="
                        + " start_date - " + days + " WHERE loan_id = '" + loanId + "'; UPDATE lending.repayment_schedule"
                        + " SET start_date = start_date - " + days + " WHERE loan_id = '" + loanId + "';"
                        + " UPDATE lending.loan_instalment SET period_start = period_start - " + days + ", due_date ="
                        + " due_date - " + days + " WHERE loan_id = '" + loanId + "'");
            }
            owner.commit();
        }
    }

    private HttpResponse<String> repay(String token, String loanId, String amount, String key) throws Exception {
        return client.post(LOANS + "/" + loanId + "/repayments", "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}",
                token, key);
    }

    private double repayments(String outcome) {
        return meterRegistry.find(RepaymentMetrics.REPAYMENT).tag("kind", "customer").tag("outcome", outcome).counter()
                .count();
    }

    private double billed() {
        return meterRegistry.find(LoanServicingMetrics.BILLING).tag("kind", "instalment").tag("outcome", "billed")
                .counter().count();
    }

    private HttpResponse<String> draw(String token, String loanId, String amount, String key) throws Exception {
        return client.post(LOANS + "/" + loanId + "/draws", "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}",
                token, key);
    }

    private double drawn(String outcome) {
        return meterRegistry.find(CreditLineDrawMetrics.DRAW).tag("outcome", outcome).counter().count();
    }

    private double accrued(String outcome) {
        return meterRegistry.find(LoanServicingMetrics.ACCRUAL).tag("outcome", outcome).counter().count();
    }

    /** {@code body} applied for by {@code customer}, approved by credit and offered by the sweep; the offer's id. */
    private String offered(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = client.submit(customer.token(), body, key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow, request));
        sweep.sweepOnce();
        return field(client.read(customer.token(), field(submitted.body(), "applicationId")).body(), "offerId");
    }

    private HttpResponse<String> accept(String token, String offer, String hash, String method, String key)
            throws Exception {
        String body = "{\"termsSha256\":\"" + hash + "\"" + (method == null ? "" : ",\"payoutMethodId\":\"" + method + "\"")
                + "}";
        return client.post(OFFERS + "/" + offer + "/acceptance", body, token, key);
    }

    private void plantCapital(Money amount) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p11t12")))) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p11t12-capital:" + UUID.randomUUID(), today, today,
                        "the suite's planted capital", List.of(
                                new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR)
                                        .orElseThrow().id(), Direction.DEBIT, amount),
                                new JournalLine(headroom.capitalAccount(uow, EUR).id(), Direction.CREDIT, amount))));
            });
        }
    }

    private double accepted(LoanProduct product) {
        return meterRegistry.find(LoanAcceptanceMetrics.ACCEPTANCE).tag("product", product.name())
                .tag("outcome", "accepted").counter().count();
    }

    private double posted() {
        return meterRegistry.find(LoanDisbursementMetrics.DISBURSEMENT).tag("payout", "wallet")
                .tag("outcome", "posted").counter().count();
    }

    private static boolean loanLockableNoWait(String loan) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(
                    "SELECT 1 FROM lending.loan WHERE id = ?::uuid FOR UPDATE NOWAIT")) {
                lock.setString(1, loan);
                lock.executeQuery().close();
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        }
    }

    private static boolean offerLockableNoWait(String offer) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(
                    "SELECT 1 FROM lending.loan_offer WHERE id = ?::uuid FOR UPDATE NOWAIT")) {
                lock.setString(1, offer);
                lock.executeQuery().close();
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** Waits until a session is blocked by {@code blocker}, and answers the statement it waits in. */
    private static String awaitAStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid))")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no acceptance ever waited behind the profile lock");
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
