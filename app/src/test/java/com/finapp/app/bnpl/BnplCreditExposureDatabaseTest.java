package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.BnplCreditExposure;
import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.ExposureCensus;
import com.finapp.app.credit.ExposureCensusMetrics;
import com.finapp.app.database.DateShifted;
import com.finapp.app.lending.LendingTransactions;
import com.finapp.app.lending.LoanApplicationDatabaseTest;
import com.finapp.app.lending.LoanOfferMetrics;
import com.finapp.app.lending.LoanOriginationSchedule;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CanonicalSnapshot;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionMaking;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionRequestId;
import com.finapp.identity.Authorization;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Platform credit exposure version 3 against the real purchase (`P12-TSK-012`; PHASE_12_PLAN.md section 12.10, ADR-0104;
 * {@code INV-BNPL-10}, {@code INV-CRD-09}, {@code INV-CRD-01}, {@code INV-BAL-05}, {@code INV-LND-06}): the wired
 * {@link BnplCreditExposure} - the figure credit's own deciding transaction reads under L0 - over loans, lines and BNPL
 * obligations born by the real acceptance (`P12-TSK-013`): an acceptance and a decision for the party serialise on the
 * profile, both orders, the decision reading the reservation or the obligation's principal exactly once; a purchase
 * committing during reads is never torn; a loan, a line and a purchase each counted once; repaid and refund-cancelled
 * principal stop counting; a closed obligation counts its principal (zero, and a planted defect counted); interest and fees
 * are not exposure; a negative principal counts zero; an uninterpretable row fails the read; a version 1 decision replays
 * IDENTICAL; the census finds an over-limit party holding BNPL.
 *
 * <p>The refund arm is an owner-role journal fixture until `P12-TSK-021` posts the real refund; the closed obligation's
 * status the bootstrap superuser's, its triggers suspended for that one statement (no closure task exists yet).
 * <strong>A database of its own</strong> ({@code own-container}). Written by `P12-TSK-012` and <strong>owed a first
 * run</strong> (the owner's no-{@code databaseTest} instruction for the pair).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("platform credit exposure v3 against the real purchase (P12-TSK-012)")
class BnplCreditExposureDatabaseTest {

    private static final long GROSS = 600_00L;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration bnplTerms;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination bnplOrigination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;
    @Autowired private BnplCreditExposure exposure;
    @Autowired private ExposureCensusMetrics census;
    @Autowired private DecisionMaking deciding;
    @Autowired private DecisionReplayer replayer;
    @Autowired private LoanTermsAdministration loanTerms;
    @Autowired private LoanTermsStore loanTermsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination loanOrigination;
    @Autowired private LoanOfferMetrics loanOfferMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdGenerator ids;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, bnplTerms, transactions,
                bnplOrigination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(1_000_000_00L));
        for (LoanProduct product : LoanProduct.values()) {
            lendingTransactions.inTransaction(uow -> {
                if (loanTermsStore.active(uow, product).isEmpty()) {
                    var proposed = loanTerms.propose(uow, LoanApplicationDatabaseTest.terms(product),
                            "the exposure suite's terms", employee(), CorrelationId.generate(ids));
                    loanTerms.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ L0, both orders

    @Test
    @DisplayName("an acceptance and a decision for the party serialise on its profile, both orders, through the real"
            + " purchase: the decision that held L0 read the approval reserved; the decision that waited behind the"
            + " acceptance read the obligation's principal under version 3 - exactly once, never neither")
    void acceptanceAndADecisionForThePartySerialise() throws Exception {
        for (boolean decisionFirst : List.of(true, false)) {
            CreditFixtures.Customer customer = world.customers.consentingCustomer();
            BnplPurchaseWorld.Offer offer = world.offered(customer, world.merchant(), GROSS,
                    token -> BnplApplicationClient.payInN(token, 3));
            UUID second = CreditFixtures.requestFor(customer.party(), CreditProduct.CREDIT_LINE, eur(1_000_00));
            assertThat(CreditFixtures.stepUntil(second, "EVALUATED")).isEqualTo("EVALUATED");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                Future<HttpResponse<String>> accepting;
                Future<?> decided;
                if (decisionFirst) {
                    lock(holder, "SELECT 1 FROM credit.decision_request WHERE id = '" + second + "' FOR UPDATE");
                    decided = pool.submit(() -> decide(second));
                    int decider = awaitPidBlockedBy(pid(holder));
                    accepting = pool.submit(() -> world.accept(customer.token(), offer, BnplApplicationClient.key()));
                    assertThat(awaitStatementBlockedBy(decider)).as("the acceptance waits on credit_profile")
                            .contains("credit_profile");
                } else {
                    lock(holder, "SELECT 1 FROM bnpl.bnpl_offer WHERE id = '" + offer.id() + "' FOR UPDATE");
                    accepting = pool.submit(() -> world.accept(customer.token(), offer, BnplApplicationClient.key()));
                    int acceptance = awaitPidBlockedBy(pid(holder));
                    decided = pool.submit(() -> decide(second));
                    assertThat(awaitStatementBlockedBy(acceptance)).as("the decision waits on credit_profile")
                            .contains("credit_profile");
                }
                holder.rollback();
                assertThat(accepting.get(2, TimeUnit.MINUTES).statusCode()).isEqualTo(201);
                decided.get(2, TimeUnit.MINUTES);
            } finally {
                pool.shutdownNow();
            }
            Money reserved = moneyOf(latest(second, CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE));
            CreditAttribute platform = latest(second, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT);
            if (decisionFirst) {
                assertThat(reserved).as("still reserved when the decision held L0").isEqualTo(eur(GROSS));
                assertThat(moneyOf(platform)).isEqualTo(eur(0));
            } else {
                assertThat(reserved).as("consumed before the decision read it").isEqualTo(eur(0));
                assertThat(moneyOf(platform)).as("the obligation's principal, read by version 3").isEqualTo(eur(GROSS));
                assertThat(platform.provenance())
                        .isEqualTo(new AttributeProvenance.Port("platform-exposure", BnplCreditExposure.VERSION));
            }
            assertThat(reserved.plus(moneyOf(platform))).as("exactly once").isEqualTo(eur(GROSS));
            assertThat(v3(customer.party())).isEqualTo(eur(GROSS));
        }
    }

    @Test
    @DisplayName("a purchase committing during the reads is never missed nor counted twice: every reading of the party's"
            + " figure is 0 before the commit or the principal after it")
    void aPurchaseDuringTheReadIsNeverMissed() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        BnplPurchaseWorld.Offer offer = world.offered(customer, world.merchant(), GROSS,
                token -> BnplApplicationClient.payInN(token, 3));
        AtomicBoolean done = new AtomicBoolean();
        List<Money> readings = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> reading = pool.submit(() -> {
                while (!done.get()) {
                    readings.add(v3(customer.party()));
                }
                return null;
            });
            assertThat(world.accept(customer.token(), offer, BnplApplicationClient.key()).statusCode()).isEqualTo(201);
            done.set(true);
            reading.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        readings.add(v3(customer.party()));
        assertThat(readings).isNotEmpty().allSatisfy(figure -> assertThat(figure).isIn(eur(0), eur(GROSS)));
        assertThat(readings.get(readings.size() - 1)).isEqualTo(eur(GROSS));
    }

    // ------------------------------------------------------------------ the figure

    @Test
    @DisplayName("a party holding a loan, a line and a BNPL purchase is counted once each: the loan's committed principal,"
            + " the line's limit, the obligation's principal")
    void aLoanALineAndAPurchaseAreEachCountedOnce() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        acceptedLending(customer, "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"8000.00\",\"currency\":\"EUR\","
                + "\"termMonths\":24,\"repaymentDay\":10,\"repaymentMethod\":\"WALLET\",\"payoutPreference\":\"WALLET\","
                + "\"declaredMonthlyIncome\":\"3200.00\",\"declaredMonthlyExpenditure\":\"1400.00\"}");
        acceptedLending(customer, "{\"product\":\"CREDIT_LINE\",\"amount\":\"2500.00\",\"currency\":\"EUR\","
                + "\"repaymentDay\":5,\"repaymentMethod\":\"WALLET\"}");
        accepted(customer, token -> BnplApplicationClient.payInN(token, 6));
        assertThat(v3(customer.party())).isEqualTo(eur(8_000_00 + 2_500_00 + GROSS));
    }

    @Test
    @DisplayName("principal repaid stops counting: pay-in-4's instalment 1, collected at purchase, is no longer exposure")
    void principalRepaidStopsCounting() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.topUpWallet(customer.party(), eur(1_000_00));
        accepted(customer, BnplApplicationClient::payIn4);
        assertThat(v3(customer.party())).isEqualTo(eur(450_00));
    }

    @Test
    @DisplayName("principal cancelled by a refund stops counting - an owner-role journal fixture until P12-TSK-021")
    void principalCancelledByARefundStopsCounting() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        String obligation = accepted(customer, token -> BnplApplicationClient.payInN(token, 3));
        post(List.of(line(cash(), Direction.DEBIT, 200_00),
                line(account(obligation, AccountPurpose.BNPL_PRINCIPAL), Direction.CREDIT, 200_00)));
        assertThat(v3(customer.party())).isEqualTo(eur(400_00));
    }

    @Test
    @DisplayName("a closed obligation counts its principal: zero by the closure rule - and a planted defect left on a"
            + " closed obligation is still exposure")
    void aClosedObligationCountsItsPrincipal() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        String obligation = accepted(customer, token -> BnplApplicationClient.payInN(token, 3));
        closed(obligation);
        assertThat(v3(customer.party())).as("a defect: principal on a CLOSED obligation").isEqualTo(eur(GROSS));
        post(List.of(line(cash(), Direction.DEBIT, GROSS),
                line(account(obligation, AccountPurpose.BNPL_PRINCIPAL), Direction.CREDIT, GROSS)));
        assertThat(v3(customer.party())).as("closed at rest: zero").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("interest and fees are not exposure - principal only (A3)")
    void interestAndFeesAreNotExposure() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        String obligation = accepted(customer, token -> BnplApplicationClient.payInN(token, 3));
        post(List.of(line(account(obligation, AccountPurpose.BNPL_INTEREST_ACCRUED), Direction.DEBIT, 5_00),
                line(account(obligation, AccountPurpose.BNPL_INTEREST_DUE), Direction.DEBIT, 3_00),
                line(account(obligation, AccountPurpose.BNPL_FEES_DUE), Direction.DEBIT, 2_00),
                line(cash(), Direction.CREDIT, 10_00)));
        post(List.of(line(cash(), Direction.DEBIT, 1_00),
                line(account(obligation, AccountPurpose.BNPL_CREDIT_BALANCE), Direction.CREDIT, 1_00)));
        assertThat(v3(customer.party())).isEqualTo(eur(GROSS));
    }

    @Test
    @DisplayName("a principal below zero counts zero for its obligation, never offsetting another's")
    void aNegativePrincipalCountsZero() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        String over = accepted(customer, token -> BnplApplicationClient.payInN(token, 3));
        world.topUpWallet(customer.party(), eur(1_000_00));
        accepted(customer, BnplApplicationClient::payIn4);
        post(List.of(line(cash(), Direction.DEBIT, GROSS + 100_00),
                line(account(over, AccountPurpose.BNPL_PRINCIPAL), Direction.CREDIT, GROSS + 100_00)));
        assertThat(v3(customer.party())).as("the pay-in-4's 450.00 alone").isEqualTo(eur(450_00));
    }

    @Test
    @DisplayName("an uninterpretable row fails the read - an obligation with no principal line - never zero")
    void anUninterpretableRowFailsTheRead() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        String obligation = accepted(customer, token -> BnplApplicationClient.payInN(token, 3));
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            // A second obligation of the party, under a purchase of its own, with no journal line at all: a broken
            // record the purchase's one transaction (INV-BNPL-02) never leaves. The row triggers - the deferred
            // INV-BNPL-02 check and the foreign keys' with them - suspended for this one session.
            statement.execute("SET session_replication_role = replica");
            UUID purchase = UUID.randomUUID();
            statement.execute("INSERT INTO bnpl.purchase SELECT '" + purchase + "', gen_random_uuid(), gen_random_uuid(),"
                    + " party_id, merchant_id, product, plan, currency, status, servicing_zone, business_date,"
                    + " authorised_at, financed_at, order_id, correlation_id FROM bnpl.purchase WHERE id = (SELECT"
                    + " purchase_id FROM bnpl.bnpl_obligation WHERE id = '" + obligation + "')");
            statement.execute("INSERT INTO bnpl.bnpl_obligation SELECT gen_random_uuid(), '" + purchase + "',"
                    + " gen_random_uuid(), party_id, gen_random_uuid(), product, plan, currency, status, servicing_zone,"
                    + " purchase_date, opened_at, next_servicing_at, closed_at, closure_reason, correlation_id FROM"
                    + " bnpl.bnpl_obligation WHERE id = '" + obligation + "'");
        }
        assertThatThrownBy(() -> v3(customer.party())).as("refused, never read as zero")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a decision frozen under version 1 replays IDENTICAL after its own purchase: its snapshot keeps its"
            + " figure while version 3 now reads the obligation's principal (INV-CRD-01)")
    void replayOfV1AndV2DecisionsStaysIdentical() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        BnplPurchaseWorld.Offer offer = world.offered(customer, world.merchant(), GROSS,
                token -> BnplApplicationClient.payInN(token, 3));
        UUID decision = UUID.fromString(BnplApplicationClient.scalar("SELECT decision_id::text FROM bnpl.bnpl_offer"
                + " WHERE id = ?::uuid", offer.id()));
        CreditAttribute frozen = attributeOfDecision(decision, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT);
        assertThat(((AttributeProvenance.Port) frozen.provenance()).version()).as("an earlier version's snapshot")
                .isLessThan(BnplCreditExposure.VERSION);
        assertThat(world.accept(customer.token(), offer, BnplApplicationClient.key()).statusCode()).isEqualTo(201);
        assertThat(v3(customer.party())).isEqualTo(eur(GROSS));
        DecisionReplayer.Replay replay = lendingTransactions.inTransaction(uow ->
                replayer.replay(uow, CreditDecisionId.of(decision)).orElseThrow());
        assertThat(replay.verdict()).as(replay.divergences().toString()).isEqualTo(DecisionReplayer.Verdict.IDENTICAL);
        assertThat(attributeOfDecision(decision, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT)).isEqualTo(frozen);
    }

    @Test
    @DisplayName("the census finds a party whose BNPL principal exceeds its decision's policy limit, and holds a party"
            + " within it - FAILS counted, verdict the only tag")
    void theCensusFindsAnOverLimitPartyHoldingBnpl() throws Exception {
        CreditFixtures.Customer within = world.customers.consentingCustomer();
        accepted(within, token -> BnplApplicationClient.payInN(token, 3));
        CreditFixtures.Customer beyond = world.customers.consentingCustomer();
        String obligation = accepted(beyond, token -> BnplApplicationClient.payInN(token, 3));
        assertThat(census.readNow().failingParties()).doesNotContain(within.party(), beyond.party());
        long limit = BnplApplicationClient.count("SELECT p.maximum_exposure_minor FROM bnpl.bnpl_obligation o"
                + " JOIN credit.credit_decision d ON d.id = o.decision_id"
                + " JOIN credit.credit_policy_version p ON p.id = d.policy_version_id WHERE o.id = ?::uuid", obligation);
        post(List.of(line(account(obligation, AccountPurpose.BNPL_PRINCIPAL), Direction.DEBIT, limit - GROSS + 1),
                line(cash(), Direction.CREDIT, limit - GROSS + 1)));
        ExposureCensus.Report after = census.readNow();
        assertThat(after.failingParties()).contains(beyond.party()).doesNotContain(within.party());
        assertThat(meterRegistry.find(ExposureCensusMetrics.CENSUS).tag("verdict", "FAILS").gauge().value())
                .isEqualTo(after.failing());
        assertThat(meterRegistry.find(ExposureCensusMetrics.CENSUS).gauges()).allSatisfy(gauge ->
                assertThat(gauge.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("verdict")));
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code body} offered and accepted end to end; the obligation's id. */
    private String accepted(CreditFixtures.Customer customer, java.util.function.Function<String, String> body)
            throws Exception {
        BnplPurchaseWorld.Offer offer = world.offered(customer, world.merchant(), GROSS, body);
        HttpResponse<String> accepted = world.accept(customer.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id = ?::uuid",
                BnplApplicationClient.field(accepted.body(), "agreementId"));
    }

    /** A lending product applied for, approved, offered and accepted through lending's own doors. */
    private void acceptedLending(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = world.client.post("/v1/me/loan-applications", body, customer.token(),
                BnplApplicationClient.key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(BnplApplicationClient.field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> loanOrigination.hintRequest(uow, request));
        new LoanOriginationSchedule(loanOrigination, lendingTransactions, loanOfferMetrics, Duration.ofSeconds(5), 50,
                Duration.ofHours(1)).sweepOnce();
        String offer = BnplApplicationClient.field(world.client.get("/v1/me/loan-applications/"
                + BnplApplicationClient.field(submitted.body(), "applicationId"), customer.token()).body(), "offerId");
        String hash = BnplApplicationClient.field(world.client.get("/v1/me/loan-offers/" + offer, customer.token()).body(),
                "termsSha256");
        HttpResponse<String> accepted = world.client.post("/v1/me/loan-offers/" + offer + "/acceptance",
                "{\"termsSha256\":\"" + hash + "\"}", customer.token(), BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
    }

    /** The obligation CLOSED - the bootstrap superuser, its triggers suspended: no closure task exists before -015. */
    private static void closed(String obligation) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute("UPDATE bnpl.bnpl_obligation SET status = 'CLOSED', closure_reason = 'REPAID', closed_at ="
                    + " clock_timestamp() WHERE id = '" + obligation + "'");
        }
    }

    private Money v3(UUID party) {
        return lendingTransactions.inTransaction(uow -> exposure.outstandingFor(uow, party, BnplPurchaseWorld.EUR));
    }

    private void decide(UUID request) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            deciding.decide(DecisionRequestId.of(request), CorrelationId.generate(ids));
        }
    }

    private void post(List<JournalLine> lines) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand("p12t12:" + UUID.randomUUID(), today, today,
                        "the exposure suite's fixture", lines));
            });
        }
    }

    private LedgerAccountId cash() {
        return lendingTransactions.inTransaction(uow -> ledgerAccountStore.findOperational(uow,
                AccountPurpose.CASH_AT_BANK, BnplPurchaseWorld.EUR).orElseThrow().id());
    }

    private LedgerAccountId account(String obligation, AccountPurpose purpose) {
        return lendingTransactions.inTransaction(uow -> ledgerAccountStore.findOwned(uow, UUID.fromString(obligation),
                purpose, BnplPurchaseWorld.EUR).orElseThrow().id());
    }

    private static JournalLine line(LedgerAccountId account, Direction direction, long minor) {
        return new JournalLine(account, direction, eur(minor));
    }

    private static CreditAttribute latest(UUID request, CreditAttributeCode code) throws Exception {
        return CanonicalSnapshot.parse(BnplApplicationClient.scalar("SELECT canonical FROM credit.decision_snapshot"
                + " WHERE decision_request_id = ?::uuid ORDER BY sequence DESC LIMIT 1", request.toString()))
                .attribute(code);
    }

    private static CreditAttribute attributeOfDecision(UUID decision, CreditAttributeCode code) throws Exception {
        return CanonicalSnapshot.parse(BnplApplicationClient.scalar("SELECT s.canonical FROM credit.credit_decision d"
                + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id WHERE d.id = ?::uuid", decision.toString()))
                .attribute(code);
    }

    private static Money moneyOf(CreditAttribute attribute) {
        assertThat(attribute.value()).isInstanceOf(AttributeValue.MoneyValue.class);
        return ((AttributeValue.MoneyValue) attribute.value()).value();
    }

    private static void lock(Connection holder, String sql) throws SQLException {
        try (Statement statement = holder.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

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

    private static int awaitPidBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT pid FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getInt(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind session " + blocker);
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
