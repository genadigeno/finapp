package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.OutboundReturnWorker;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.app.reconciliation.CorridorRuleSetV1;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.PostingService;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditOutcomes;
import com.finapp.payments.OutboundCreditResolution;
import com.finapp.payments.OutboundCreditReturns;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.reconciliation.WaitingPayoutReturns;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cross-border returns (`P9-TSK-023`, PHASE_9_PLAN.md sections 12.4(i) and 12.9.3; scenario 7; {@code INV-XB-04},
 * {@code INV-REC-09}, {@code INV-IDEM-04}, {@code INV-AUD-04}): one return, one credit, one fee refund - on the inquiry
 * channel and on the report's {@code PAYOUT_RETURNED} line, under ten concurrent appliers, with the return reported
 * before the completion is known; a return that differs from the instructed credit, or whose customer is not active,
 * posts nothing and parks for a person, whose four-eyes transfer returns it - the fee refunded, the payment
 * {@code RETURNED} - and loses to an inquiry that applied it first.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("cross-border returns (P9-TSK-023)")
class CrossBorderReturnDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final String DESK = "/v1/operator/reconciliation";
    private static final String CORRIDOR_SOURCE = "corridor-sim-a.settlement";
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private OutboundCreditResolution resolution;
    @Autowired private OutboundReturnWorker worker;
    @Autowired private WaitingPayoutReturns waitingPayoutReturns;
    @Autowired private WaitingPayoutReturns waitingCorridorReturns;
    @Autowired private MeterRegistry meters;

    private static SimulatedCorridorEngine corridor() {
        try {
            return SimulatedCorridorEngine.start("a-corridor-callback-test-key-of-32-byte".getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(new byte[32]);
            // Section 12.4's provider rate for EUR -> USD.
            FxTestClient.rates(fxEngine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> fxEngine.baseUrl().toString());
        registry.add("finapp.corridor.provider.url", () -> CORRIDOR.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        fxEngine.close();
        CORRIDOR.close();
    }

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    @BeforeEach
    void policiesAndReferences() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        activateCorridorRuleSet();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        awaitRoutingVersionInForce();
    }

    // ------------------------------------------------------------------ the inquiry channel, exact

    @Test
    @DisplayName("12.4(i) exact on the inquiry channel: the 1,079.60 USD credited to the USD wallet opened for it, the"
            + " 2.50 EUR fee refunded, CROSSBORDER_RETURN opened INBOUND and later SETTLED by the report's line, the"
            + " payment RETURNED (basis APPLIED); a second inquiry and the worker write nothing")
    void anExactReturnAppliesOnTheInquiryChannel() throws Exception {
        Paid paid = paid("Clear Person");
        assertThat(walletAccount(paid, "USD")).as("no USD wallet before the return").isEmpty();
        long walletEvents = walletEvents(paid);
        double applied = counted("applied");

        CORRIDOR.returnCredit(paid.reference());
        assertThat(resolve(paid.reference())).isPresent();

        assertThat(linesOf(returnEntry(paid))).containsExactlyInAnyOrder(
                "USD CORRIDOR_CLEARING DEBIT 107960", "USD CUSTOMER_WALLET CREDIT 107960",
                "EUR FEE_REVENUE DEBIT 250", "EUR CUSTOMER_WALLET CREDIT 250");
        assertThat(walletAccount(paid, "USD")).as("the USD wallet opened in the applier's transaction").isPresent();
        assertThat(walletEvents(paid) - walletEvents).as("accounts.WalletCurrencyAdded for the opened wallet").isEqualTo(1);
        assertThat(walletBalance(walletAccount(paid, "USD").orElseThrow())).isEqualTo(107_960);
        assertThat(scalar("SELECT applied_by || ' ' || amount_minor || ' ' || amount_currency FROM payments.outbound_credit_return"
                + " WHERE outbound_credit_id = ?", paid.credit())).isEqualTo("APPLIER 107960 USD");
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("RETURNED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentReturned'"
                + " AND aggregate_id = ? AND convert_from(payload, 'UTF8') LIKE '%APPLIED%'", paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.OutboundCreditReturnApplied'"
                + " AND target_id = ?", paid.credit().toString())).isEqualTo(1);
        assertThat(scalar("SELECT status || ' ' || direction || ' ' || amount_minor FROM reconciliation.expectation"
                + " WHERE kind = 'CROSSBORDER_RETURN' AND operation_ref = ?", paid.credit().toString()))
                .isEqualTo("OPEN INBOUND 107960");
        ClearingLineCopies.assertOpensItsClearingLinesCopy(ExpectationKind.CROSSBORDER_RETURN, paid.credit().toString(),
                OutboundCreditReturns.POSTING_KEY_PREFIX + paid.credit(), ExpectationDirection.INBOUND);
        // Counted on every channel since P9-TSK-027 (the composition counts the applied return, after commit).
        assertThat(counted("applied") - applied).as("the inquiry channel's return is counted applied, once").isEqualTo(1);

        // Duplicates: a second inquiry (still following the delivery) acts on nothing and writes no second return.
        assertThat(resolve(paid.reference())).hasValueSatisfying(again -> assertThat(again.acting()).isFalse());
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .isEqualTo(1);

        // The report's line arrives: the worker finds the fact, the anchored rule allocates the line.
        UUID report = acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, "1079.60", paid)), "1079.60"));
        OutboundReturnWorker.SweepResult swept = worker.sweep();
        assertThat(swept.applied()).isZero();
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_RETURN' AND operation_ref = ?",
                paid.credit().toString())).isEqualTo("SETTLED");
        assertThat(itemStatus(returnedItem(report))).isEqualTo("MATCHED");
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .isEqualTo(1);
        assertThat(walletBalance(walletAccount(paid, "USD").orElseThrow())).as("one credit").isEqualTo(107_960);
    }

    // ------------------------------------------------------------------ the report channel

    @Test
    @DisplayName("the report channel: a BOUNCED line waits UNMATCHED, the corridor worker applies it (counted applied),"
            + " the anchored rule then allocates it to its CROSSBORDER_RETURN; the merchant reader never sees it")
    void anExactReturnAppliesFromTheReport() throws Exception {
        Paid paid = paid("Clear Person");
        double applied = counted("applied");
        UUID report = acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, "1079.60", paid)), "1079.60"));
        matchUntilQuiet();
        UUID item = returnedItem(report);
        assertThat(itemStatus(item)).as("no CROSSBORDER_RETURN stands yet").isEqualTo("UNMATCHED");
        assertThat(waiting(waitingCorridorReturns)).contains(item);
        assertThat(waiting(waitingPayoutReturns)).as("the merchant worker's reader is scoped to its own sources")
                .doesNotContain(item);

        assertThat(worker.sweep().applied()).isGreaterThanOrEqualTo(1);
        assertThat(counted("applied") - applied).isGreaterThanOrEqualTo(1);
        assertThat(linesOf(returnEntry(paid))).contains("USD CORRIDOR_CLEARING DEBIT 107960", "EUR FEE_REVENUE DEBIT 250");
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("RETURNED");
        matchUntilQuiet();
        assertThat(itemStatus(item)).isEqualTo("MATCHED");
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_RETURN' AND operation_ref = ?",
                paid.credit().toString())).isEqualTo("SETTLED");

        // The inquiry channel afterwards: the engine reports the same return; the fact stands, nothing more.
        CORRIDOR.returnCredit(paid.reference());
        resolve(paid.reference());
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .isEqualTo(1);
        assertThat(walletBalance(walletAccount(paid, "USD").orElseThrow())).isEqualTo(107_960);
    }

    // ------------------------------------------------------------------ before the completion is known

    @Test
    @DisplayName("a return reported before the completion is known: the worker defers, writing nothing; the inquiry"
            + " applies the completion and then the return in one transaction; the worker then finds the fact")
    void aReturnBeforeTheCompletionWaits() throws Exception {
        CORRIDOR.acceptOnReceipt(false);
        Paid paid = paid("Clear Person");
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("RECEIVED");
        CORRIDOR.accept(paid.reference());
        CORRIDOR.returnCredit(paid.reference());
        String providerReference = scalar("SELECT provider_reference FROM payments.outbound_credit WHERE id = ?", paid.credit());
        UUID report = acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, "1079.60",
                providerReference, paid.reference())), "1079.60"));
        matchUntilQuiet();
        double deferred = counted("deferred");

        assertThat(worker.sweep().deferred()).isGreaterThanOrEqualTo(1);
        assertThat(counted("deferred") - deferred).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .as("deferred: nothing written").isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE ?",
                "%:" + paid.credit())).as("no completion, no return").isZero();

        assertThat(resolve(paid.reference())).hasValueSatisfying(done -> assertThat(done.acting()).isTrue());
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("COMPLETED");
        // One transaction: both rows carry the same creating transaction id.
        assertThat(scalar("SELECT (xmin::text = (SELECT xmin::text FROM ledger.journal_entry WHERE idempotency_scope = ?))::text"
                        + " FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + paid.credit(),
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + paid.credit()))
                .as("the completion and the return in one transaction").isEqualTo("true");
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("RETURNED");

        assertThat(worker.sweep().applied()).isZero();
        matchUntilQuiet();
        assertThat(itemStatus(returnedItem(report))).isEqualTo("MATCHED");
    }

    // ------------------------------------------------------------------ ten appliers

    @Test
    @DisplayName("ten concurrent appliers - five report workers, five hinted inquiries - make one return: one fact, one"
            + " entry, one wallet credit, one fee refund, one event")
    void tenAppliersMakeOneReturn() throws Exception {
        Paid paid = paid("Clear Person");
        CORRIDOR.returnCredit(paid.reference());
        acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, "1079.60", paid)), "1079.60"));
        matchUntilQuiet();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> racers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                boolean inquirer = i % 2 == 0;
                racers.add(pool.submit(() -> {
                    start.await();
                    return inquirer ? resolve(paid.reference()) : worker.sweep();
                }));
            }
            start.countDown();
            for (Future<?> racer : racers) {
                racer.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + paid.credit())).isEqualTo(1);
        assertThat(walletBalance(walletAccount(paid, "USD").orElseThrow())).isEqualTo(107_960);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentReturned'"
                + " AND aggregate_id = ?", paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_RETURN' AND operation_ref = ?",
                paid.credit().toString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ never posted without a person

    @Test
    @DisplayName("a partial return, a return in another declared currency and an exact return to a suspended customer"
            + " each post nothing (counted not_applicable) and park at grace REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)")
    void aReturnThatDiffersParks() throws Exception {
        Paid partial = paid("Clear Person");
        Paid jpy = paid("Clear Person");
        Paid suspended = paid("Clear Person");
        setStatus(suspended, "SUSPENDED");
        double notApplicable = counted("not_applicable");
        UUID usdReport = acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, "1000.00", partial),
                bouncedLine(2, "1079.60", suspended)), "2079.60"));
        UUID jpyReport = acceptedBatch(corridorReport("JPY", List.of(bouncedLine(1, "16221", jpy)), "16221"));
        matchUntilQuiet();

        assertThat(worker.sweep().notApplicable()).isGreaterThanOrEqualTo(3);
        assertThat(counted("not_applicable") - notApplicable).isGreaterThanOrEqualTo(3);
        for (Paid paid : List.of(partial, jpy, suspended)) {
            assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                    .isZero();
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + paid.credit())).isZero();
            assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isNotEqualTo("RETURNED");
        }
        assertThat(walletAccount(suspended, "USD")).as("a suspended customer's wallet is never opened").isEmpty();

        List<UUID> items = new ArrayList<>(returnedItems(usdReport));
        items.addAll(returnedItems(jpyReport));
        for (UUID item : items) {
            expireGrace(item);
        }
        matchUntilQuiet();
        for (UUID item : items) {
            assertThat(itemStatus(item)).isEqualTo("PARKED");
            assertThat(scalar("SELECT type || ':' || cause FROM reconciliation.break WHERE external_item_id = ?", item))
                    .isEqualTo("REVERSAL_MISMATCH:RETURN_NOT_APPLICABLE");
        }
    }

    // ------------------------------------------------------------------ the person's way out

    @Test
    @DisplayName("a parked partial return resolved by a four-eyes transfer to the customer's USD wallet: the transfer the"
            + " only USD credit, the fee refunded once (crossborder-return-fee), the fact RESOLUTION, the payment"
            + " RETURNED (basis RESOLVED); a target other than the customer's own wallet is refused")
    void aParkedReturnIsResolvedByAPerson() throws Exception {
        Paid paid = paid("Clear Person");
        client().addCurrency(paid.customer(), paid.product(), "USD");
        UUID usdWallet = walletAccount(paid, "USD").orElseThrow();
        UUID breakId = parked(paid, "1000.00");

        Paid stranger = paid("Clear Person");
        client().addCurrency(stranger.customer(), stranger.product(), "USD");
        HttpResponse<String> refused = propose(breakId, walletAccount(stranger, "USD").orElseThrow());
        assertThat(refused.body()).contains("reconciliation.ResolutionTargetRefused");

        double resolved = counted("resolved");
        UUID transfer = fourEyesTransfer(breakId, usdWallet);
        assertThat(linesOf(transfer.toString())).containsExactlyInAnyOrder(
                "USD SUSPENSE_UNMATCHED DEBIT 100000", "USD CUSTOMER_WALLET CREDIT 100000");
        assertThat(linesOf(scalar("SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return-fee:" + paid.credit())))
                .containsExactlyInAnyOrder("EUR FEE_REVENUE DEBIT 250", "EUR CUSTOMER_WALLET CREDIT 250");
        assertThat(walletBalance(usdWallet)).as("the transfer the only USD credit").isEqualTo(100_000);
        assertThat(scalar("SELECT applied_by || ' ' || (resolution_id IS NOT NULL) FROM payments.outbound_credit_return"
                + " WHERE outbound_credit_id = ?", paid.credit())).isEqualTo("RESOLUTION true");
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("RETURNED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentReturned'"
                + " AND aggregate_id = ? AND convert_from(payload, 'UTF8') LIKE '%RESOLVED%'", paid.payment())).isEqualTo(1);
        assertThat(counted("resolved") - resolved).isEqualTo(1);
        // The park already moved the value off CORRIDOR_CLEARING: the fee refund opens nothing.
        ClearingLineCopies.assertOpensNothing("crossborder-return-fee:" + paid.credit());
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + paid.credit()))
                .as("no automated return besides").isZero();
    }

    @Test
    @DisplayName("P9-DOC-001: the approval posts two entries - the fee refund, then the transfer - so it locks the union of"
            + " their projection rows in order before the first: held at the source wallet, it already holds the USD"
            + " suspense (seeded, so first), which posting the fee alone never touches")
    void theApprovalPreLocksBothEntriesInOrder() throws Exception {
        Paid paid = paid("Clear Person");
        client().addCurrency(paid.customer(), paid.product(), "USD");
        UUID usdWallet = walletAccount(paid, "USD").orElseThrow();
        UUID eurWallet = walletAccount(paid, "EUR").orElseThrow();
        UUID breakId = parked(paid, "1000.00");
        HttpResponse<String> proposed = propose(breakId, usdWallet);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        UUID suspense = UUID.fromString(scalar("SELECT id::text FROM ledger.ledger_account WHERE purpose ="
                + " 'SUSPENSE_UNMATCHED' AND currency = 'USD'"));
        assertThat(suspense).as("seeded ids sort first (D18)").isLessThan(eurWallet);

        ExecutorService approver = Executors.newSingleThreadExecutor();
        try (Connection blocker = DatabaseRoles.migrator()) {
            blocker.setAutoCommit(false);
            lockBalance(blocker, eurWallet);
            Future<HttpResponse<String>> approval = approver.submit(() -> client().post(DESK + "/resolutions/"
                    + field(proposed.body(), "resolutionId") + "/approval", "{}",
                    sessionWith(RoleName.RECONCILIATION_OPERATOR), null));
            awaitBlockedOnTheBalanceRow();
            try (Connection probe = DatabaseRoles.migrator()) {
                probe.setAutoCommit(false);
                assertThat(tryLockBalance(probe, suspense))
                        .as("waiting at the source wallet, the approval already holds the USD suspense - the union"
                                + " pre-locked, never the fee entry's pair first and the transfer's after")
                        .isFalse();
                probe.rollback();
            }
            blocker.rollback();
            HttpResponse<String> approved = approval.get(1, TimeUnit.MINUTES);
            assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        } finally {
            approver.shutdownNow();
        }
        assertThat(walletBalance(usdWallet)).isEqualTo(100_000);
    }

    private static void lockBalance(Connection unitOfWork, UUID account) throws Exception {
        try (PreparedStatement lock = unitOfWork.prepareStatement(
                "SELECT 1 FROM ledger.account_balance WHERE ledger_account_id = ? FOR UPDATE")) {
            lock.setObject(1, account);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).as("a balance row for " + account).isTrue();
            }
        }
    }

    /** Whether the balance row could be locked at once - false when another transaction holds it. */
    private static boolean tryLockBalance(Connection unitOfWork, UUID account) throws Exception {
        try (PreparedStatement lock = unitOfWork.prepareStatement(
                "SELECT 1 FROM ledger.account_balance WHERE ledger_account_id = ? FOR UPDATE NOWAIT")) {
            lock.setObject(1, account);
            lock.executeQuery().close();
            return true;
        } catch (java.sql.SQLException held) {
            assertThat(held.getSQLState()).as(held.getMessage()).isEqualTo("55P03");
            return false;
        }
    }

    /** Until a backend waits on a row lock over the balance projection (bounded). */
    private static void awaitBlockedOnTheBalanceRow() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND query LIKE '%account_balance%' AND query NOT LIKE '%pg_stat_activity%'") == 0) {
            assertThat(System.nanoTime()).as("the approval reached the held balance row").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("the resolution racing an inquiry-applied return: proposed while the customer was suspended and then"
            + " reactivated, the inquiry applies first; the approval is 409 ResolutionStale with nothing moved, and a"
            + " fresh proposal is refused ReturnAlreadyAttributed - one credit")
    void anInquiryAppliedReturnBeatsTheResolution() throws Exception {
        Paid paid = paid("Clear Person");
        client().addCurrency(paid.customer(), paid.product(), "USD");
        UUID usdWallet = walletAccount(paid, "USD").orElseThrow();
        setStatus(paid, "SUSPENDED");
        UUID breakId = parked(paid, "1079.60");
        setStatus(paid, "ACTIVE");

        HttpResponse<String> proposed = propose(breakId, usdWallet);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        CORRIDOR.returnCredit(paid.reference());
        assertThat(resolve(paid.reference())).isPresent();
        assertThat(walletBalance(usdWallet)).isEqualTo(107_960);

        HttpResponse<String> approved = client().post(DESK + "/resolutions/" + field(proposed.body(), "resolutionId")
                + "/approval", "{}", sessionWith(RoleName.RECONCILIATION_OPERATOR), null);
        assertThat(approved.body()).contains("reconciliation.ResolutionStale");
        assertThat(walletBalance(usdWallet)).as("nothing moved: one credit").isEqualTo(107_960);
        assertThat(scalar("SELECT applied_by FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .isEqualTo("APPLIER");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return-fee:" + paid.credit())).isZero();

        // The stale proposal stays live until a person closes it; rejected, a fresh proposal is refused at once.
        assertThat(client().post(DESK + "/resolutions/" + field(proposed.body(), "resolutionId") + "/rejection",
                        "{\"reason\":\"the credit's return was applied from its evidence\"}",
                        sessionWith(RoleName.RECONCILIATION_OPERATOR), null).statusCode())
                .isEqualTo(200);
        assertThat(propose(breakId, usdWallet).body()).contains("reconciliation.ReturnAlreadyAttributed");
    }

    // ------------------------------------------------------------------ atomicity

    @Test
    @DisplayName("atomicity: the payment's RETURNED edge refused by an injected fault - the worker's transaction (T-f) and"
            + " the approval's (T-g) each leave nothing, no entry, no fact, no fee refund; the fault lifted, each completes"
            + " once")
    void aFailedReturnLeavesNothing() throws Exception {
        Paid applied = paid("Clear Person");
        acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, "1079.60", applied)), "1079.60"));
        matchUntilQuiet();
        try (AutoCloseable fault = refuseReturned(applied)) {
            OutboundReturnWorker.SweepResult failed = worker.sweep();
            assertThat(failed.failedRows()).isGreaterThanOrEqualTo(1);
            assertNothingReturned(applied);
        }
        worker.sweep();
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", applied.credit()))
                .isEqualTo(1);
        assertThat(walletBalance(walletAccount(applied, "USD").orElseThrow())).isEqualTo(107_960);

        Paid resolved = paid("Clear Person");
        client().addCurrency(resolved.customer(), resolved.product(), "USD");
        UUID usdWallet = walletAccount(resolved, "USD").orElseThrow();
        UUID breakId = parked(resolved, "1000.00");
        HttpResponse<String> proposed = propose(breakId, usdWallet);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String approval = DESK + "/resolutions/" + field(proposed.body(), "resolutionId") + "/approval";
        String approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        try (AutoCloseable fault = refuseReturned(resolved)) {
            HttpResponse<String> refused = client().post(approval, "{}", approver, null);
            assertThat(refused.statusCode()).as(refused.body()).isNotEqualTo(200);
            assertNothingReturned(resolved);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return-fee:" + resolved.credit())).isZero();
            assertThat(walletBalance(usdWallet)).as("the transfer rolled back with it").isZero();
        }
        HttpResponse<String> approved = client().post(approval, "{}", approver, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(walletBalance(usdWallet)).isEqualTo(100_000);
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", resolved.payment())).isEqualTo("RETURNED");
    }

    // ------------------------------------------------------------------ the needle

    @Test
    @DisplayName("the needle (return leg): the beneficiary's name is in no table, event, audit record or response the"
            + " return touched")
    void theNameReachesNoReturn() throws Exception {
        String name = "Isolde Needlehart " + UUID.randomUUID().toString().substring(0, 6);
        Paid paid = paid(name);
        CORRIDOR.returnCredit(paid.reference());
        assertThat(resolve(paid.reference())).isPresent();
        for (String table : List.of("payments.outbound_credit_return", "payments.outbound_credit", "crossborder.payment_event",
                "reconciliation.expectation", "ledger.journal_entry")) {
            assertThat(count("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?", "%" + name + "%")).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE convert_from(payload, 'UTF8') LIKE ?",
                "%" + name + "%")).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record t WHERE t::text LIKE ?", "%" + name + "%")).isZero();
    }

    // ------------------------------------------------------------------ the machines and arbiters (P9-DOC-001)

    @Test
    @DisplayName("P9-DOC-001: the payment and outbound credit machines refuse every illegal edge from every writer, and"
            + " a second payment, scheme-execution claim or return is refused by its UNIQUE with every trigger off")
    void theMachinesAndArbitersHoldForEveryWriter() throws Exception {
        // A payment held SUBMITTED, its credit RECEIVED: no edge but to IN_TRANSIT or FAILED, none backward.
        CORRIDOR.acceptOnReceipt(false);
        Paid waiting = paid("Clear Person");
        CORRIDOR.acceptOnReceipt(true);
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", waiting.payment())).isEqualTo("SUBMITTED");
        for (String to : List.of("DELIVERED", "RETURNED")) {
            assertMachineRefuses("UPDATE crossborder.payment SET status = '" + to + "' WHERE id = ?", waiting.payment());
        }
        for (String to : List.of("DISPATCHED", "UNKNOWN")) {
            assertMachineRefuses("UPDATE payments.outbound_credit SET status = '" + to + "' WHERE id = ?", waiting.credit());
        }

        // A returned payment: RETURNED and its COMPLETED credit are terminal.
        Paid returned = paid("Clear Person");
        CORRIDOR.returnCredit(returned.reference());
        resolve(returned.reference());
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", returned.payment())).isEqualTo("RETURNED");
        for (String to : List.of("SUBMITTED", "IN_TRANSIT", "DELIVERED")) {
            assertMachineRefuses("UPDATE crossborder.payment SET status = '" + to + "' WHERE id = ?", returned.payment());
        }
        assertMachineRefuses("UPDATE crossborder.payment SET status = 'FAILED', failure_reason = 'DECLINED' WHERE id = ?",
                returned.payment());
        for (String to : List.of("DISPATCHED", "UNKNOWN", "RECEIVED")) {
            assertMachineRefuses("UPDATE payments.outbound_credit SET status = '" + to + "' WHERE id = ?", returned.credit());
        }
        assertMachineRefuses("UPDATE payments.outbound_credit SET status = 'FAILED', failure_reason = 'DECLINED' WHERE id = ?",
                returned.credit());
        assertMachineRefuses("DELETE FROM crossborder.payment WHERE id = ?", returned.payment());

        // The born-once arbiters, with every user trigger off (the lock-bypass probe): each UNIQUE refuses alone.
        assertArbiterRefuses("crossborder.payment", "payment_one_per_quote",
                "INSERT INTO crossborder.payment SELECT (jsonb_populate_record(p, jsonb_build_object('id', gen_random_uuid(),"
                        + " 'offer_id', gen_random_uuid(), 'outbound_credit_id', gen_random_uuid(), 'dispatch_key', 'probe-'"
                        + " || gen_random_uuid()))).* FROM crossborder.payment p WHERE p.id = ?", returned.payment());
        assertArbiterRefuses("payments.scheme_execution_claim", "scheme_execution_claim_one_per_execution",
                "INSERT INTO payments.scheme_execution_claim SELECT (jsonb_populate_record(c, jsonb_build_object('subject_id',"
                        + " gen_random_uuid()))).* FROM payments.scheme_execution_claim c WHERE c.subject_kind = 'OUTBOUND_CREDIT'"
                        + " AND c.subject_id = ?", returned.credit());
        assertArbiterRefuses("payments.outbound_credit_return", "outbound_credit_return_once",
                "INSERT INTO payments.outbound_credit_return SELECT (jsonb_populate_record(r, jsonb_build_object('id',"
                        + " gen_random_uuid(), 'journal_entry_id', gen_random_uuid()))).* FROM payments.outbound_credit_return r"
                        + " WHERE r.outbound_credit_id = ?", returned.credit());
    }

    private static void assertMachineRefuses(String sql, UUID id) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement update = app.prepareStatement(sql)) {
            update.setObject(1, id);
            org.assertj.core.api.Assertions.assertThatThrownBy(update::executeUpdate)
                    .as("refused for every writer: %s", sql)
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    /** As the table's owner with every user trigger off: only the constraint stands between the row and a second copy. */
    private static void assertArbiterRefuses(String table, String constraint, String sql, UUID id) throws Exception {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (java.sql.Statement ddl = owner.createStatement(); PreparedStatement insert = owner.prepareStatement(sql)) {
                ddl.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
                insert.setObject(1, id);
                org.assertj.core.api.Assertions.assertThatThrownBy(insert::executeUpdate)
                        .as("%s alone refuses a second row", constraint)
                        .isInstanceOf(java.sql.SQLException.class)
                        .satisfies(failure -> assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("23505"))
                        .hasMessageContaining(constraint);
            } finally {
                owner.rollback();
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    record Paid(FxTestClient.Customer customer, UUID product, UUID quote, UUID payment, UUID credit, String reference,
            String providerReference) {}

    /** Pays section 12.4(g)'s offer (1,000.00 EUR fixed source to a US beneficiary) from a funded EUR wallet. */
    private Paid paid(String beneficiaryName) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1100.00", "EUR"));
        String beneficiary = beneficiary(customer, beneficiaryName);
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"1000.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        assertThat(offered.body()).doesNotContain(beneficiaryName);
        String quote = field(offered.body(), "id");
        HttpResponse<String> paid = client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        assertThat(paid.body()).doesNotContain(beneficiaryName);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        assertThat(scalar("SELECT destination_minor::text FROM crossborder.payment_offer WHERE quote_id = ?::uuid", quote))
                .isEqualTo("107960");
        return new Paid(customer, product, UUID.fromString(quote), payment, credit,
                scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit),
                scalar("SELECT provider_reference FROM payments.outbound_credit WHERE id = ?", credit));
    }

    /** The credit's return line parked at grace (the customer's state as the caller set it); its break. */
    private UUID parked(Paid paid, String amount) throws Exception {
        UUID report = acceptedBatch(corridorReport("USD", List.of(bouncedLine(1, amount, paid)), amount));
        matchUntilQuiet();
        worker.sweep();
        UUID item = returnedItem(report);
        assertThat(itemStatus(item)).isEqualTo("UNMATCHED");
        expireGrace(item);
        matchUntilQuiet();
        assertThat(itemStatus(item)).isEqualTo("PARKED");
        return UUID.fromString(scalar("SELECT id::text FROM reconciliation.break WHERE external_item_id = ?"
                + " AND cause = 'RETURN_NOT_APPLICABLE'", item));
    }

    private HttpResponse<String> propose(UUID breakId, UUID target) throws Exception {
        return client().post(DESK + "/breaks/" + breakId + "/resolutions", "{\"kind\":\"TRANSFER_TO_ACCOUNT\","
                + "\"reasonCode\":\"FUNDS_ATTRIBUTED\",\"narrative\":\"the returned credit goes back to its customer\","
                + "\"targetAccountId\":\"" + target + "\"}", sessionWith(RoleName.RECONCILIATION_OPERATOR), FxTestClient.key());
    }

    /** A four-eyes TRANSFER_TO_ACCOUNT of the break's parked value; the approved entry. */
    private UUID fourEyesTransfer(UUID breakId, UUID target) throws Exception {
        HttpResponse<String> proposed = propose(breakId, target);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = client().post(DESK + "/resolutions/" + field(proposed.body(), "resolutionId")
                + "/approval", "{}", sessionWith(RoleName.RECONCILIATION_OPERATOR), null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        return UUID.fromString(field(approved.body(), "journalEntryId"));
    }

    private Optional<OutboundCreditOutcomes.Applied> resolve(String reference) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return resolution.resolve(new EndToEndReference(reference));
        }
    }

    /** A fault injected beneath the payment's RETURNED edge, for this payment only; closing it lifts the fault. */
    private static AutoCloseable refuseReturned(Paid paid) throws Exception {
        String name = "probe_refuse_returned_" + paid.payment().toString().replace("-", "").substring(0, 12);
        try (Connection owner = DatabaseRoles.migrator(); java.sql.Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION crossborder." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.payment_id = '" + paid.payment() + "'::uuid AND NEW.to_status = 'RETURNED' THEN"
                    + " RAISE EXCEPTION 'injected fault'; END IF; RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON crossborder.payment_event FOR EACH ROW EXECUTE FUNCTION"
                    + " crossborder." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.migrator(); java.sql.Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON crossborder.payment_event");
                ddl.execute("DROP FUNCTION crossborder." + name + "()");
            }
        };
    }

    private static void assertNothingReturned(Paid paid) throws Exception {
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?", paid.credit()))
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + paid.credit())).isZero();
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isNotEqualTo("RETURNED");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_RETURN' AND operation_ref = ?",
                paid.credit().toString())).isZero();
    }

    private static long walletEvents(Paid paid) throws Exception {
        return count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'accounts.WalletCurrencyAdded'"
                + " AND aggregate_id = ?", paid.product());
    }

    private double counted(String outcome) {
        // Summed across corridors (the corridor tag since P9-TSK-027).
        return meters.find("finapp.crossborder.return").tag("outcome", outcome).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static String returnEntry(Paid paid) throws Exception {
        return scalar("SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + paid.credit());
    }

    private static Optional<UUID> walletAccount(Paid paid, String currency) throws Exception {
        return count("SELECT count(*) FROM ledger.ledger_account WHERE owner_ref::text = ? AND purpose = 'CUSTOMER_WALLET'"
                        + " AND currency = ?", paid.product().toString(), currency) == 0
                ? Optional.empty()
                : Optional.of(UUID.fromString(scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref::text = ?"
                        + " AND purpose = 'CUSTOMER_WALLET' AND currency = ?", paid.product().toString(), currency)));
    }

    private static long walletBalance(UUID account) throws Exception {
        return count("SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)"
                + " FROM ledger.journal_line WHERE ledger_account_id = ?", account);
    }

    private static void setStatus(Paid paid, String status) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement("UPDATE party.customer SET status = ?,"
                        + " status_changed_at = GREATEST(now(), status_changed_at) WHERE party_id = ?")) {
            update.setString(1, status);
            update.setObject(2, paid.customer().party());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    /** The stored window moved, never the clock: expiry stays a database-clock fact. */
    private static void expireGrace(UUID item) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement(
                        "UPDATE reconciliation.external_item SET grace_until = now() - interval '1 hour' WHERE id = ?")) {
            update.setObject(1, item);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static UUID returnedItem(UUID batch) throws Exception {
        List<UUID> items = returnedItems(batch);
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private static List<UUID> returnedItems(UUID batch) throws Exception {
        List<UUID> items = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT i.id FROM reconciliation.external_item i"
                        + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?"
                        + " AND i.line_type = 'PAYOUT_RETURNED' ORDER BY i.line_no")) {
            read.setObject(1, batch);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    items.add(row.getObject(1, UUID.class));
                }
            }
        }
        return items;
    }

    private static String itemStatus(UUID item) throws Exception {
        return scalar("SELECT status FROM reconciliation.external_item WHERE id = ?", item);
    }

    private static List<UUID> waiting(WaitingPayoutReturns reader) throws Exception {
        List<UUID> seen = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            Optional<UUID> after = Optional.empty();
            while (true) {
                List<WaitingPayoutReturns.WaitingReturn> page = reader.page(app, after, 50);
                page.forEach(item -> seen.add(item.itemId()));
                if (page.size() < 50) {
                    return seen;
                }
                after = Optional.of(page.get(page.size() - 1).itemId());
            }
        }
    }

    private static String bouncedLine(int sequence, String amount, Paid paid) {
        return bouncedLine(sequence, amount, paid.providerReference(), paid.reference());
    }

    private static String bouncedLine(int sequence, String amount, String providerReference, String reference) {
        return "D," + sequence + ",BOUNCED," + amount + ",," + providerReference + "," + reference;
    }

    private static String corridorReport(String currency, List<String> lines, String net) {
        return "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + "," + currency + "," + today() + "\n"
                + String.join("\n", lines) + "\n"
                + "T," + lines.size() + "," + net + ",XBA-" + (10_000 + RANDOMNESS.nextInt(80_000)) + "\n";
    }

    private static List<String> linesOf(String entry) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT l.currency, a.purpose, l.direction, l.amount_minor"
                        + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE l.entry_id = ?")) {
            read.setObject(1, UUID.fromString(entry));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1).trim() + " " + row.getString(2) + " " + row.getString(3) + " " + row.getLong(4));
                }
            }
        }
        return lines;
    }

    private UUID acceptedBatch(String content) throws Exception {
        FileReception.Result result;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(CORRIDOR_SOURCE, com.finapp.settlement.DeliveryChannel.PULL,
                            content.getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM,
                            com.finapp.settlement.SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
        }
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        UUID fileId = ((FileReception.Result.New) result).fileId();
        parsing.sweep();
        acceptance.sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .as(content).isEqualTo("ACCEPTED:-");
        return UUID.fromString(scalar("SELECT id::text FROM settlement.batch WHERE file_id = ?", fileId));
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).replaceAll("[0-9]", "q");
    }

    private String beneficiary(FxTestClient.Customer customer, String name) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("US", "USD", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"US\",\"currency\":\"USD\",\"grant\":\""
                + grant + "\",\"name\":\"" + name + "\",\"nickname\":\"Payee\",\"entityType\":\"INDIVIDUAL\"}",
                customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private void activateCorridorRuleSet() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).as("routing version 5 is not yet in force by the host's clock (database now %s)",
                    scalar("SELECT now()::text")).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private void activateCorridorPolicy() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                client().post(POLICIES + "/" + row.getObject(1, UUID.class) + "/rejection", "{\"reason\":\"cleared\"}", first, null);
            }
        }
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the corridor return suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key());
        return field(session.body(), "sessionToken");
    }

    @TestConfiguration
    static class Doubles {

        /** The screening provider: every name clear. */
        @Bean
        @Primary
        CounterpartyScreeningProvider clearCorridorReturnScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
