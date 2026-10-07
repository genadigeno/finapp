package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.app.reconciliation.CorridorRuleSetV1;
import com.finapp.fx.FxPlanVerification;
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
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * The cross-border outbound credit's resolution and completion, against a live database, the simulated FX provider
 * and the simulated corridor provider (`P9-TSK-020`, PHASE_9_PLAN.md sections 12.4(g) and 12.8; scenarios 4, 5, 6 and
 * 9; {@code INV-XB-01}, {@code INV-XB-03}, {@code INV-LIFE-03}, {@code INV-IDEM-04}, {@code INV-PAY-01},
 * {@code INV-SET-02}, {@code INV-FX-04}): the completion entry exact and equal to the offer, the hold and the
 * instruction; its expectation; ten appliers and a racing sweep making one of everything; an answer implying
 * acceptance with a delivery on an UNKNOWN credit; a lost response; a rejected send and NEVER_RECEIVED posting
 * nothing; the callback door's hints - ten deliveries, unsigned, stale and forged-but-signed; policies activated
 * between the quote and the completion changing nothing; the plan replay agreeing.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("outbound credit resolution and completion (P9-TSK-020)")
class OutboundCreditResolutionDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final String WEBHOOKS = "/v1/providers/payments/corridor/webhooks";
    private static final byte[] WEBHOOK_KEY = "a-corridor-webhook-test-key-of-32-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private OutboundCreditResolution resolution;
    @Autowired private com.finapp.payments.OutboundCreditStore outboundCreditStore;
    @Autowired private OutboundCreditOutcomes outboundCreditOutcomes;
    @Autowired private com.finapp.payments.RailOperations railOperations;
    @Autowired private com.finapp.payments.PaymentRails paymentRails;
    @Autowired private com.finapp.payments.ProviderEvidenceStore<Connection> providerEvidenceStore;
    @Autowired private com.finapp.sharedkernel.id.IdGenerator idGenerator;
    @Autowired private com.finapp.payments.TransactionRunner paymentTransactionRunner;
    @Autowired private com.finapp.payments.OutboundCreditReturnStore outboundCreditReturnStore;
    @Autowired private FxPlanVerification planVerification;
    @Autowired private RuleSetAdministration ruleSets;

    private static SimulatedCorridorEngine corridor() {
        try {
            return SimulatedCorridorEngine.start(WEBHOOK_KEY);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(fxEngine, Map.of());
        }
        registry.add("finapp.fx.provider.url", () -> fxEngine.baseUrl().toString());
        registry.add("finapp.corridor.provider.url", () -> CORRIDOR.baseUrl().toString());
        registry.add("finapp.corridor.webhook.key", () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
        // A RECEIVED credit is due an inquiry at once: the racing sweep below finds it (and RECEIVED credits of
        // other suites, whose unrecognised answer can never fail a RECEIVED credit).
        registry.add("finapp.payments.outbound.sweeper.received-age", () -> "PT0.001S");
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
        CORRIDOR.acceptOnReceipt(false);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        activateCorridorRuleSet();
        awaitRoutingVersionInForce();
    }

    // ------------------------------------------------------------------ the completion

    @Test
    @DisplayName("P9-TST-001's find: undelivered credits never starve one awaiting its outcome - every credit holding"
            + " money is swept before any delivery poll, so a page the delivery polls would fill still completes it")
    void undeliveredCreditsNeverStarveOneAwaitingItsOutcome() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        Paid undelivered = paid("Undelivered Person", FxTestClient.key());
        CORRIDOR.acceptOnReceipt(false);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", undelivered.credit())).isEqualTo("COMPLETED");
        Paid waiting = paid("Waiting Person", FxTestClient.key());
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", waiting.credit())).isEqualTo("RECEIVED");
        CORRIDOR.accept(waiting.reference());
        // A page exactly as wide as the credits awaiting their outcome: by the permit alone, the older undelivered
        // credit took a place on it and the newest waiting one - this one - was never asked about.
        long awaiting = count("SELECT count(*) FROM payments.outbound_credit WHERE status = 'RECEIVED' OR (status IN"
                + " ('DISPATCHED', 'UNKNOWN') AND recall_requested_at IS NOT NULL AND recall_outcome IS NULL)");
        Duration day = Duration.ofDays(1);
        Duration due = Duration.ofMillis(1);
        OutboundCreditResolution page = new OutboundCreditResolution(outboundCreditStore, outboundCreditOutcomes,
                railOperations, paymentRails, providerEvidenceStore,
                new OutboundCreditResolution.Config(day, day, due, due, Duration.ofMinutes(5), (int) awaiting),
                idGenerator, Clock.systemUTC(), paymentTransactionRunner, outboundCreditReturnStore);
        page.sweep();
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", waiting.credit()))
                .as("the waiting credit completed: no delivery poll took its place").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("ACCEPTED completes in one transaction: the entry exact (12.4(g)) and equal to the offer, the hold and"
            + " the instruction (INV-XB-03); the claim, the trade, the quote EXECUTED, the payment SENT, the"
            + " CROSSBORDER_PAYOUT expectation - and the plan replay agrees")
    void theCompletionPostsExactlyAndOpensItsExpectation() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key());
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("RECEIVED");
        CORRIDOR.accept(paid.reference());
        assertThat(resolve(paid.reference())).hasValueSatisfying(applied -> assertThat(applied.acting()).isTrue());

        assertCompletionExact(paid);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("COMPLETED");
        String providerReference = scalar("SELECT provider_reference FROM payments.outbound_credit WHERE id = ?", paid.credit());
        assertThat(providerReference).startsWith("XP-");
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN payments.outbound_credit c ON c.hold_id = h.id"
                + " WHERE c.id = ?", paid.credit())).isEqualTo("RELEASED");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(paid.quote()))).isEqualTo("EXECUTED");
        assertThat(count("SELECT count(*) FROM fx.trade t JOIN ledger.journal_entry e ON e.id = t.journal_entry_id"
                        + " WHERE t.quote_id = ? AND t.purpose = 'CROSS_BORDER' AND e.idempotency_scope = ?",
                UUID.fromString(paid.quote()), entryScope(paid))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.scheme_execution_claim WHERE subject_kind = 'OUTBOUND_CREDIT'"
                + " AND subject_id = ? AND scheme_reference = ?", paid.credit(), providerReference)).isEqualTo(1);
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("IN_TRANSIT");
        HttpResponse<String> read = client().get(PAYMENTS + "/" + paid.payment(), paid.customer().token());
        assertThat(field(read.body(), "status")).isEqualTo("SENT");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentInTransit'"
                + " AND aggregate_id = ?", paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event e JOIN fx.trade t ON t.id = e.aggregate_id"
                + " WHERE e.event_type = 'fx.FxTradeExecuted' AND t.quote_id = ?", UUID.fromString(paid.quote()))).isEqualTo(1);

        ClearingLineCopies.Opened opened = ClearingLineCopies.assertOpensItsClearingLinesCopy(ExpectationKind.CROSSBORDER_PAYOUT,
                paid.credit().toString(), OutboundCreditOutcomes.POSTING_KEY_PREFIX + paid.credit(), ExpectationDirection.OUTBOUND);
        ClearingLineCopies.assertKeyed(opened, KeyKind.END_TO_END_REF, paid.reference());
        ClearingLineCopies.assertKeyed(opened, KeyKind.PAYOUT_PROVIDER_REF, providerReference);

        UUID trade = UUID.fromString(scalar("SELECT id::text FROM fx.trade WHERE quote_id = ?", UUID.fromString(paid.quote())));
        try (Connection app = DatabaseRoles.application()) {
            assertThat(planVerification.verify(app).divergences())
                    .as("the golden replay reproduces the cross-border trade's plan and entry")
                    .noneMatch(divergence -> divergence.trade().value().equals(trade));
        }
    }

    @Test
    @DisplayName("a provider that accepts at once completes inside the authorization's second transaction: 202 SENT")
    void anImmediateAcceptanceAnswersSent() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        Paid paid = paid("Clear Person", FxTestClient.key());
        assertThat(paid.status()).isEqualTo("SENT");
        assertCompletionExact(paid);
    }

    // ------------------------------------------------------------------ duplicates and races

    @Test
    @DisplayName("ten appliers and a racing sweep (FeeOnce): one transition, one entry, one fee line, one claim, one"
            + " trade, one expectation")
    void tenAppliersMakeOneOfEverything() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key());
        CORRIDOR.accept(paid.reference());
        ExecutorService pool = Executors.newFixedThreadPool(11);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> acting = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            acting.add(pool.submit(() -> {
                start.await();
                return resolve(paid.reference()).map(OutboundCreditOutcomes.Applied::acting).orElse(false);
            }));
        }
        Future<?> sweep = pool.submit(() -> {
            start.await();
            return resolution.sweep();
        });
        start.countDown();
        int actors = 0;
        for (Future<Boolean> one : acting) {
            actors += one.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        sweep.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(actors).as("at most one applier acted; the sweep may have been the one").isLessThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", entryScope(paid))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE e.idempotency_scope = ?"
                + " AND a.purpose = 'FEE_REVENUE'", entryScope(paid))).as("one fee line").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.scheme_execution_claim WHERE subject_id = ?", paid.credit())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?", UUID.fromString(paid.quote()))).isEqualTo(1);
        assertThat(ClearingLineCopies.expectationsOf(ExpectationKind.CROSSBORDER_PAYOUT, paid.credit().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM crossborder.payment_event WHERE payment_id = ? AND to_status = 'IN_TRANSIT'",
                paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.OutboundCreditOutcomeApplied'"
                + " AND target_id = ? AND reason IS NULL", paid.credit().toString()))
                .as("one acting record for the completion after the RECEIVED one").isEqualTo(2);
    }

    @Test
    @DisplayName("scenario 4: the send's response is lost - UNKNOWN, the hold standing; the provider executes and delivers;"
            + " the inquiry applies the completion then the delivery in one transaction - one execution, one entry, the"
            + " provider's reference stored")
    void anAcceptanceWithDeliveryOnAnUnknownCredit() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key(), CORRIDOR::loseNextResponse);
        assertThat(paid.status()).isEqualTo("PROCESSING");
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("UNKNOWN");
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN payments.outbound_credit c ON c.hold_id = h.id"
                + " WHERE c.id = ?", paid.credit())).isEqualTo("ACTIVE");
        CORRIDOR.accept(paid.reference());
        CORRIDOR.deliver(paid.reference());
        resolve(paid.reference());
        assertThat(scalar("SELECT status || ' ' || (delivered_at IS NOT NULL) FROM payments.outbound_credit WHERE id = ?",
                paid.credit())).isEqualTo("COMPLETED true");
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("DELIVERED");
        assertThat(scalar("SELECT string_agg(to_status, '>' ORDER BY occurred_at, to_status DESC) FROM crossborder.payment_event"
                + " WHERE payment_id = ?", paid.payment())).isEqualTo("SUBMITTED>IN_TRANSIT>DELIVERED");
        assertThat(CORRIDOR.creditsOf(paid.reference())).as("one execution at the provider").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", entryScope(paid))).isEqualTo(1);
        assertThat(scalar("SELECT provider_reference FROM payments.outbound_credit WHERE id = ?", paid.credit())).startsWith("XP-");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentDelivered'"
                + " AND aggregate_id = ?", paid.payment())).isEqualTo(1);
        // A later inquiry learns nothing new: no second delivery, no second entry. Since P9-TSK-023 a completed credit
        // can still be returned, so a hint is still asked of the provider - and acts on nothing.
        assertThat(resolve(paid.reference())).hasValueSatisfying(again -> assertThat(again.acting()).isFalse());
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", entryScope(paid))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ failures

    @Test
    @DisplayName("a rejected send fails DECLINED in the second transaction: nothing posted, the hold released, the quote"
            + " ABANDONED, the payment FAILED")
    void aRejectedSendPostsNothing() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key(), () -> CORRIDOR.rejectNextSend("beneficiary_closed"));
        assertThat(paid.status()).isEqualTo("FAILED");
        assertFailedWithNothingPosted(paid, "DECLINED");
    }

    @Test
    @DisplayName("NEVER_RECEIVED: an UNKNOWN credit the provider does not know fails only past the declared deadline and"
            + " margin since its latest permit - before it, it waits")
    void anUnrecognisedCreditPastItsDeadlineNeverArrived() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key(), CORRIDOR::serverErrorNext);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("UNKNOWN");
        assertThat(resolve(paid.reference())).hasValueSatisfying(applied -> assertThat(applied.acting()).isFalse());
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("UNKNOWN");
        agePermit(paid.credit(), Duration.ofMinutes(20));
        assertThat(resolve(paid.reference())).hasValueSatisfying(applied -> assertThat(applied.acting()).isTrue());
        assertFailedWithNothingPosted(paid, "NEVER_RECEIVED");
    }

    // ------------------------------------------------------------------ the callback door

    @Test
    @DisplayName("scenario 6: ten signed deliveries of one callback and a racing sweep - one effect; unsigned or stale is"
            + " 401 and writes nothing; a forged but signed callback moves nothing the inquiry does not confirm")
    void callbacksAreHints() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key());
        String forged = "{\"eventId\":\"forged-" + UUID.randomUUID() + "\",\"endToEndRef\":\"" + paid.reference()
                + "\",\"status\":\"accepted\",\"providerRef\":\"XP-forged\",\"acceptedAt\":\"2026-10-05T10:00:00Z\"}";
        assertThat(callback(forged, Instant.now()).statusCode()).isEqualTo(202);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit()))
                .as("the inquiry says RECEIVED: the forged acceptance moved nothing").isEqualTo("RECEIVED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", entryScope(paid))).isZero();

        CORRIDOR.accept(paid.reference());
        SimulatedCorridorEngine.SignedCallback accepted = CORRIDOR.callbacks().stream()
                .filter(one -> one.body().contains("\"endToEndRef\":\"" + paid.reference() + "\"")
                        && one.body().contains("\"accepted\""))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(callback(accepted.body(), null).statusCode()).as("unsigned").isEqualTo(401);
        assertThat(callback(accepted.body(), Instant.now().minus(Duration.ofHours(1))).statusCode()).as("stale").isEqualTo(401);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("RECEIVED");

        ExecutorService pool = Executors.newFixedThreadPool(11);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            codes.add(pool.submit(() -> {
                start.await();
                return callback(accepted.body(), Instant.now()).statusCode();
            }));
        }
        Future<?> sweep = pool.submit(() -> {
            start.await();
            try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
                return resolution.sweep();
            }
        });
        start.countDown();
        for (Future<Integer> code : codes) {
            assertThat(code.get(60, TimeUnit.SECONDS)).isIn(202, 409);
        }
        sweep.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", entryScope(paid))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.inbox_message WHERE consumer = 'payments.corridor-webhook'"
                + " AND dedupe_key LIKE ?", "%" + eventIdOf(accepted.body()))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.provider_evidence WHERE outbound_credit_id = ? AND kind = 'WEBHOOK'",
                paid.credit())).as("each authentic delivery retained").isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ frozen terms

    @Test
    @DisplayName("scenario 9: a corridor policy and a pricing policy activated between the quote and the completion change"
            + " nothing - the fee and the plan posted are the offer's")
    void policiesActivatedAfterTheOfferChangeNothing() throws Exception {
        Paid paid = paid("Clear Person", FxTestClient.key());
        activateCorridorPolicy();
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.RACE);
        CORRIDOR.accept(paid.reference());
        resolve(paid.reference());
        assertCompletionExact(paid);
    }

    // ------------------------------------------------------------------ plumbing

    record Paid(FxTestClient.Customer customer, String quote, UUID payment, UUID credit, String reference, String status) {}

    private Paid paid(String name, String key) throws Exception {
        return paid(name, key, () -> { });
    }

    /** Pays a fresh offer, {@code arm} run just before the payment's POST - the engine's next request is its send. */
    private Paid paid(String name, String key, Runnable arm) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1000.00", "EUR"));
        String quote = offer(customer, beneficiary(customer, name));
        arm.run();
        HttpResponse<String> paid = client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), key);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        String reference = scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit);
        return new Paid(customer, quote, payment, credit, reference, field(paid.body(), "status"));
    }

    /**
     * The completion entry, line for line, against the stored offer and plan (PHASE_9_PLAN.md section 12.4(g)): the
     * wallet debited the offer's total debit, the fee credited to FEE_REVENUE, the plan's positions, margin and residual,
     * and the corridor's clearing credited the offer's guaranteed destination - which the credit was instructed.
     */
    private static void assertCompletionExact(Paid paid) throws Exception {
        UUID quote = UUID.fromString(paid.quote());
        long totalDebit = Long.parseLong(scalar("SELECT total_debit_minor::text FROM crossborder.payment_offer WHERE quote_id = ?", quote));
        long fee = Long.parseLong(scalar("SELECT fee_minor::text FROM crossborder.payment_offer WHERE quote_id = ?", quote));
        long source = Long.parseLong(scalar("SELECT source_minor::text FROM crossborder.payment_offer WHERE quote_id = ?", quote));
        long destination = Long.parseLong(scalar("SELECT destination_minor::text FROM crossborder.payment_offer WHERE quote_id = ?", quote));
        long positionSource = Long.parseLong(scalar("SELECT position_source_minor::text FROM fx.quote WHERE id = ?", quote));
        long positionDestination = Long.parseLong(scalar("SELECT position_destination_minor::text FROM fx.quote WHERE id = ?", quote));
        long margin = Long.parseLong(scalar("SELECT margin_minor::text FROM fx.quote WHERE id = ?", quote));
        long residual = Long.parseLong(scalar("SELECT residual_minor::text FROM fx.quote WHERE id = ?", quote));
        assertThat(Long.parseLong(scalar("SELECT customer_source_minor::text FROM fx.quote WHERE id = ?", quote))).isEqualTo(source);
        assertThat(Long.parseLong(scalar("SELECT customer_destination_minor::text FROM fx.quote WHERE id = ?", quote)))
                .isEqualTo(destination);
        assertThat(totalDebit).isEqualTo(source + fee);
        // Offer = hold = instruction.
        assertThat(scalar("SELECT held_minor || ' ' || amount_minor FROM payments.outbound_credit WHERE id = ?", paid.credit()))
                .isEqualTo(totalDebit + " " + destination);
        List<String> expected = new ArrayList<>(List.of(
                "EUR CUSTOMER_WALLET DEBIT " + totalDebit,
                "EUR FEE_REVENUE CREDIT " + fee,
                "EUR FX_POSITION CREDIT " + positionSource,
                "JPY FX_POSITION DEBIT " + positionDestination,
                "JPY CORRIDOR_CLEARING CREDIT " + destination));
        if (margin > 0) {
            expected.add("JPY FX_SPREAD_REVENUE CREDIT " + margin);
        }
        if (residual != 0) {
            expected.add("JPY ROUNDING_RESIDUAL " + (residual > 0 ? "CREDIT " : "DEBIT ") + Math.abs(residual));
        }
        List<String> posted = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT l.currency, a.purpose, l.direction, l.amount_minor"
                        + " FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                        + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE e.idempotency_scope = ?")) {
            read.setString(1, entryScope(paid));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    posted.add(row.getString(1).trim() + " " + row.getString(2) + " " + row.getString(3) + " " + row.getLong(4));
                }
            }
        }
        assertThat(posted).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static void assertFailedWithNothingPosted(Paid paid, String reason) throws Exception {
        assertThat(scalar("SELECT status || ' ' || failure_reason FROM payments.outbound_credit WHERE id = ?", paid.credit()))
                .isEqualTo("FAILED " + reason);
        assertThat(scalar("SELECT status || ' ' || failure_reason FROM crossborder.payment WHERE id = ?", paid.payment()))
                .isEqualTo("FAILED " + reason);
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN payments.outbound_credit c ON c.hold_id = h.id"
                + " WHERE c.id = ?", paid.credit())).isEqualTo("RELEASED");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(paid.quote()))).isEqualTo("ABANDONED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", entryScope(paid))).isZero();
        assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?", UUID.fromString(paid.quote()))).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxQuoteAbandoned' AND aggregate_id = ?",
                UUID.fromString(paid.quote()))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentFailed'"
                + " AND aggregate_id = ?", paid.payment())).isEqualTo(1);
    }

    private static String entryScope(Paid paid) {
        return PostingService.IDEMPOTENCY_SCOPE + ":" + OutboundCreditOutcomes.POSTING_KEY_PREFIX + paid.credit();
    }

    private Optional<OutboundCreditOutcomes.Applied> resolve(String reference) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return resolution.resolve(new EndToEndReference(reference));
        }
    }

    private HttpResponse<String> callback(String body, Instant signedAt) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + WEBHOOKS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (signedAt != null) {
            String timestamp = Long.toString(signedAt.getEpochSecond());
            request.header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                    .header(WebhookSignature.SIGNATURE_HEADER,
                            new WebhookSignature(WEBHOOK_KEY, Duration.ofMinutes(5), Clock.systemUTC()).sign(timestamp, bytes));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String eventIdOf(String body) {
        int start = body.indexOf("\"eventId\":\"") + 11;
        return body.substring(start, body.indexOf('"', start));
    }

    /** Ages a credit's latest permit - the owner's act, the machine's trigger suspended for it. */
    private static void agePermit(UUID credit, Duration by) throws Exception {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (PreparedStatement off = owner.prepareStatement(
                            "ALTER TABLE payments.outbound_credit DISABLE TRIGGER outbound_credit_machine_is_legal");
                    PreparedStatement age = owner.prepareStatement("UPDATE payments.outbound_credit SET last_dispatched_at ="
                            + " last_dispatched_at - ? * interval '1 second' WHERE id = ?");
                    PreparedStatement on = owner.prepareStatement(
                            "ALTER TABLE payments.outbound_credit ENABLE TRIGGER outbound_credit_machine_is_legal")) {
                off.execute();
                age.setLong(1, by.toSeconds());
                age.setObject(2, credit);
                assertThat(age.executeUpdate()).isEqualTo(1);
                on.execute();
            }
            owner.commit();
        }
    }

    private String offer(FxTestClient.Customer customer, String beneficiary) throws Exception {
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"100.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        return field(offered.body(), "id");
    }

    private String beneficiary(FxTestClient.Customer customer, String name) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("JP", "JPY", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"JP\",\"currency\":\"JPY\",\"grant\":\""
                + grant + "\",\"name\":\"" + name + "\",\"nickname\":\"Osaka\",\"entityType\":\"INDIVIDUAL\"}",
                customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    /** The corridor source's rule set v1, activated once by two controllers when no version is active (D26). */
    private void activateCorridorRuleSet() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSets.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSets.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE), "Reviewed against O7",
                    Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    /** The seeded routing version in force by the host's clock (the payment suite's guard). */
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
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the resolution suite"), first,
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
        CounterpartyScreeningProvider clearCounterpartyScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
