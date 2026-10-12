package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
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
 * servicing sweep and its meter. The races and the database's ranks are
 * {@code lending}'s {@code LoanAcceptanceDatabaseTest}'s and {@code LoanDisbursementDatabaseTest}'s.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}, X-TSK-016): the suite brings credit's seeded policies
 * into force, activates both products' lending terms and plants EUR lending capital by a posting from
 * {@code CASH_AT_BANK} - the fixture's, never a path the platform has (bank recognition is the one poster, proven by
 * {@link LendingCapitalDatabaseTest}) - so the capital proof would not hold here, and nothing here asks it to.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the acceptance, the disbursement and the accrual end to end (P11-TSK-012, P11-TSK-014, P11-TSK-016)")
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

    // ------------------------------------------------------------------ plumbing

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
