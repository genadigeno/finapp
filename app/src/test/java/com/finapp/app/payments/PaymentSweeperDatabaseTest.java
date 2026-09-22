package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.PaymentSweeper;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation-by-query sweeper against a real PostgreSQL and a real HTTP provider
 * (`P5-TSK-014`, ADR-0046 §4): the stranded and the aged resolve, concurrent sweepers race to
 * one winner counted, and a sweeper racing the webhook produces one effect — all through the
 * one shared outcome component, so the money clauses are `P5-TSK-010`'s proofs arriving at a
 * new caller.
 *
 * <p><strong>The bounds are injected, so the tests inject zero</strong> rather than aging
 * rows: what the bound does is its own test (a fresh dispatch inside a real bound stays
 * untouched), and everything else drives resolution directly.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the reconciliation-by-query sweeper (P5-TSK-014)")
class PaymentSweeperDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(12_00, EUR);
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WEBHOOK_KEY =
            "0011223344556677889900112233445566".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider psp;

    private final CountingRunner runner = new CountingRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(
                    new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom()), IDS);
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcPaymentParticipants participants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new JdbcCustomerAccountStore(),
                    ledgerAccounts,
                    new JdbcPaymentMethodStore());

    @BeforeAll
    static void startProvider() {
        psp = SimulatedProvider.start();
    }

    @AfterAll
    static void stopProvider() {
        psp.close();
    }

    private CorrelationContext.Scope testFlow;

    @BeforeEach
    void reset() {
        psp.reset();
        testFlow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("swp-" + UUID.randomUUID())));
    }

    @AfterEach
    void leaveScope() {
        testFlow.close();
    }

    // -----------------------------------------------------------------
    // The accepts
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a stranded AUTH_DISPATCHED (crash mid-call) resolves to AUTHORIZED on an"
            + " approved query - the sweeper healing what nobody retried")
    void aStrandedDispatchResolvesToAuthorized() throws Exception {
        Holder holder = holder();
        PaymentAttempt stranded = strandedDispatch(holder);
        queryAnswers(stranded.authorizationReference(), "approved", "psp_q-auth");

        sweeper(Duration.ZERO, Duration.ZERO).sweep();

        // Row-scoped, deliberately: the shared database carries other suites' stranded rows,
        // and the tally is telemetry - the count of record is THIS row and ITS tables.
        assertThat(attemptStatus(stranded.id())).isEqualTo("AUTHORIZED");
        assertThat(evidenceCount(stranded.id()))
                .as("the query's answer retained as QUERY_RESULT, attributed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an aged CAPTURE_UNKNOWN resolves to CAPTURED with exactly one posting and the"
            + " intent SUCCEEDED - and a second sweep finds nothing to do")
    void anAgedCaptureUnknownResolvesToCapturedOnce() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureUnknownAttempt(holder);
        String captureReference = captureReference(attemptId);
        queryAnswers(new ProviderIdempotencyReference(captureReference), "approved", "psp_q-cap");

        sweeper(Duration.ZERO, Duration.ZERO).sweep();

        assertThat(attemptStatus(attemptId)).isEqualTo("CAPTURED");
        assertThat(intentStatus(holder.intent())).isEqualTo("SUCCEEDED");
        assertThat(entriesByReference(attemptId)).isEqualTo(1);

        // The second sweep: a terminal is never a candidate, so nothing touches the row and
        // the entry count holds - asserted on the row's own tables (the tally is fleet-wide
        // over a shared test database, so it is telemetry here, not the count of record).
        sweeper(Duration.ZERO, Duration.ZERO).sweep();
        assertThat(entriesByReference(attemptId)).isEqualTo(1);
        assertThat(transitionCount(attemptId, "CAPTURED")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten concurrent sweepers race to ONE winner counted: one entry, one CAPTURED"
            + " transition, the losers converged")
    void concurrentSweepersRaceToOneWinner() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureUnknownAttempt(holder);
        queryAnswers(
                new ProviderIdempotencyReference(captureReference(attemptId)),
                "approved",
                "psp_q-race");

        int sweepers = 10;
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(sweepers);
        try {
            List<Future<PaymentSweeper.SweepResult>> results =
                    java.util.stream.IntStream.range(0, sweepers)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        open.await();
                                                        try (CorrelationContext.Scope flow =
                                                                CorrelationContext.enter(
                                                                        Correlation.startingWith(
                                                                                CorrelationId
                                                                                        .generate(
                                                                                                IDS)))) {
                                                            return sweeper(
                                                                            Duration.ZERO,
                                                                            Duration.ZERO)
                                                                    .sweep();
                                                        }
                                                    }))
                            .toList();
            open.countDown();
            for (Future<PaymentSweeper.SweepResult> result : results) {
                assertThat(result.get().failedRows()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }

        // ONE winner, counted where winners are counted - the tables: ten sweepers may all
        // SUBMIT an application (the tally's 'applied' says so honestly), and the conditional
        // plus the posting's idempotency claim admit exactly one change.
        assertThat(entriesByReference(attemptId)).as("one posting, whoever won").isEqualTo(1);
        assertThat(transitionCount(attemptId, "CAPTURED")).isEqualTo(1);
        assertThat(attemptStatus(attemptId)).isEqualTo("CAPTURED");
    }

    @Test
    @DisplayName("a sweeper racing the webhook produces ONE effect - the same conditional edge,"
            + " whichever resolver wins")
    void aSweeperRacingTheWebhookProducesOneEffect() throws Exception {
        Holder holder = holder();
        PaymentAttemptId attemptId = captureUnknownAttempt(holder);
        String captureReference = captureReference(attemptId);
        queryAnswers(
                new ProviderIdempotencyReference(captureReference), "approved", "psp_q-vs-wh");
        PaymentWebhookService webhooks = webhookService();

        int racers = 5;
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers * 2);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    open.await();
                                    try (CorrelationContext.Scope flow =
                                            CorrelationContext.enter(
                                                    Correlation.startingWith(
                                                            CorrelationId.generate(IDS)))) {
                                        return sweeper(Duration.ZERO, Duration.ZERO).sweep();
                                    }
                                }));
                final int n = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    open.await();
                                    try (CorrelationContext.Scope flow =
                                            CorrelationContext.enter(
                                                    Correlation.startingWith(
                                                            CorrelationId.generate(IDS)))) {
                                        deliverWebhook(
                                                webhooks,
                                                "{\"eventId\":\"evt_race-" + n + "-"
                                                        + UUID.randomUUID()
                                                        + "\",\"operation\":\""
                                                        + captureReference
                                                        + "\",\"status\":\"approved\","
                                                        + "\"reference\":\"psp_wh-race-" + n
                                                        + "\"}");
                                        return null;
                                    }
                                }));
            }
            open.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(entriesByReference(attemptId))
                .as("ONE financial effect across both resolvers (the accept)")
                .isEqualTo(1);
        assertThat(transitionCount(attemptId, "CAPTURED")).isEqualTo(1);
        assertThat(intentStatus(holder.intent())).isEqualTo("SUCCEEDED");
    }

    // -----------------------------------------------------------------
    // The licence and its limit
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an explicit UNRECOGNISED resolves the stranded dispatch to"
            + " FAILED(NEVER_RECEIVED) - both rows, nothing posted (the sweeper's licence)")
    void unrecognisedResolvesToFailedNeverReceived() throws Exception {
        Holder holder = holder();
        PaymentAttempt stranded = strandedDispatch(holder);
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH
                        + stranded.authorizationReference().value(),
                200,
                "{\"status\":\"unrecognised\"}");

        sweeper(Duration.ZERO, Duration.ZERO).sweep();

        assertThat(attemptStatus(stranded.id())).isEqualTo("FAILED");
        assertThat(failureReason(stranded.id()))
                .as("the new reason, surviving V007's CHECK by this very write")
                .isEqualTo("NEVER_RECEIVED");
        assertThat(intentStatus(holder.intent())).isEqualTo("FAILED");
        assertThat(entriesByReference(stranded.id())).isZero();
    }

    @Test
    @DisplayName("INDETERMINATE - a 500 and a 404 alike - marks the honest *_UNKNOWN once and"
            + " NEVER fails: ambiguity is not the licence (the expensive direction closed)")
    void indeterminateMarksUnknownOnceAndNeverFails() throws Exception {
        // A 500: the provider is broken, not answering.
        Holder broken = holder();
        PaymentAttempt strandedBroken = strandedDispatch(broken);
        psp.failsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH
                        + strandedBroken.authorizationReference().value(),
                500);
        sweeper(Duration.ZERO, Duration.ZERO).sweep();
        assertThat(attemptStatus(strandedBroken.id())).isEqualTo("AUTH_UNKNOWN");

        // The second sweep: still indeterminate, still AUTH_UNKNOWN, never FAILED - the next
        // tick simply asks again (INV-LIFE-03), and exactly one UNKNOWN transition ever lands.
        sweeper(Duration.ZERO, Duration.ZERO).sweep();
        assertThat(attemptStatus(strandedBroken.id())).isEqualTo("AUTH_UNKNOWN");
        assertThat(transitionCount(strandedBroken.id(), "AUTH_UNKNOWN")).isEqualTo(1);

        // A 404 WITH A BODY: a status code is not an answer - a misrouted load balancer
        // must never resolve a live operation to FAILED (QueryAnswer's fold, proven
        // load-bearing here). The body matters: an empty 404 is already refused by the
        // evidence bound, so only a bodied one reaches the status-code fold - the gap the
        // P5-TST-001 battery's surviving mutation exposed, closed by this shape.
        Holder misrouted = holder();
        PaymentAttempt strandedMisrouted = strandedDispatch(misrouted);
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH
                        + strandedMisrouted.authorizationReference().value(),
                404,
                "<html><body>404 Not Found - gateway</body></html>");
        sweeper(Duration.ZERO, Duration.ZERO).sweep();
        assertThat(attemptStatus(strandedMisrouted.id())).isEqualTo("AUTH_UNKNOWN");
        assertThat(failureReason(strandedMisrouted.id())).isNull();
    }

    // -----------------------------------------------------------------
    // The bound, and the anti-stall posture
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a fresh dispatch inside its bound is not swept - probably mid-call, left"
            + " alone (the bound is load-bearing)")
    void aFreshDispatchIsNotSwept() throws Exception {
        Holder holder = holder();
        PaymentAttempt fresh = strandedDispatch(holder);

        sweeper(Duration.ofMinutes(10), Duration.ofMinutes(1)).sweep();

        // Row-scoped: the fleet-wide candidate list may carry other suites' aged rows, but
        // THIS fresh dispatch stays untouched - not transitioned, not even queried.
        assertThat(attemptStatus(fresh.id())).isEqualTo("AUTH_DISPATCHED");
        assertThat(evidenceCount(fresh.id())).as("not even queried").isZero();
    }

    @Test
    @DisplayName("one failing row does not stall the sweep - the rows behind it are other"
            + " customers' money")
    void oneFailingRowDoesNotStallTheSweep() throws Exception {
        Holder first = holder();
        PaymentAttempt poisoned = strandedDispatch(first);
        Holder second = holder();
        PaymentAttempt healthy = strandedDispatch(second);
        queryAnswers(healthy.authorizationReference(), "approved", "psp_q-behind");

        PaymentProvider throwingForFirst =
                new PaymentProvider() {
                    private final PaymentProvider delegate = adapter();

                    @Override
                    public String providerName() {
                        return delegate.providerName();
                    }

                    @Override
                    public ProviderAnswer authorize(AuthorizationRequest request) {
                        return delegate.authorize(request);
                    }

                    @Override
                    public ProviderAnswer capture(CaptureRequest request) {
                        return delegate.capture(request);
                    }

                    @Override
                    public ProviderAnswer refund(RefundRequest request) {
                        return delegate.refund(request);
                    }

                    @Override
                    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
                        if (ourReference.equals(poisoned.authorizationReference())) {
                            throw new IllegalStateException("poisoned row");
                        }
                        return delegate.query(ourReference);
                    }
                };
        PaymentSweeper sweeper =
                new PaymentSweeper(
                        runner, attempts, intents, evidence, throwingForFirst, outcomes(),
                        IDS, CLOCK, Duration.ZERO, Duration.ZERO, 50);

        PaymentSweeper.SweepResult result = sweeper.sweep();

        assertThat(result.failedRows()).isGreaterThanOrEqualTo(1);
        assertThat(attemptStatus(healthy.id()))
                .as("the row behind the poisoned one still resolved")
                .isEqualTo("AUTHORIZED");
        assertThat(attemptStatus(poisoned.id()))
                .as("the poisoned row is untouched for the next tick")
                .isEqualTo("AUTH_DISPATCHED");
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private record Holder(
            UUID party, Actor person, PaymentIntentId intent, LedgerAccountId wallet) {}

    /** The REAL chain: party → ACTIVE customer → wallet product → ledger wallet → instrument. */
    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        Actor person = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Sweep Holder', now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
            execute(app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, token_reference,"
                            + " brand, display_suffix, expiry_month, expiry_year, status,"
                            + " created_at) VALUES (?, ?, ?, 'Visa', '4242', 12, 2030,"
                            + " 'ACTIVE', now())",
                    method, party, "tok-sweep-" + UUID.randomUUID());
        }

        SecurityContext.Scope actor = SecurityContext.enter(person);
        CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("fix-" + UUID.randomUUID())));
        try {
            runner.inTransaction(
                    uow ->
                            new AccountOpening(
                                            new JdbcCustomerAccountStore(),
                                            ledgerAccounts,
                                            new VerifiedAccountHolder(new JdbcPartyStore()),
                                            new JdbcAuditWriter(),
                                            new JdbcOutboxWriter(),
                                            IDS,
                                            CLOCK)
                                    .open(uow, party, ProductType.WALLET, EUR));
            PaymentCreation.CreationResult created =
                    runner.inTransaction(
                            uow ->
                                    new PaymentCreation(
                                                    executor(),
                                                    participants,
                                                    intents,
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    IDS,
                                                    CLOCK)
                                            .create(
                                                    uow,
                                                    new PaymentCreation.CreatePaymentCommand(
                                                            party,
                                                            method,
                                                            AMOUNT,
                                                            "swp-" + UUID.randomUUID())));
            LedgerAccountId wallet =
                    runner.inTransaction(
                            uow ->
                                    intents.findById(uow, created.intent())
                                            .orElseThrow()
                                            .walletAccount());
            return new Holder(party, person, created.intent(), wallet);
        } finally {
            flow.close();
            actor.close();
        }
    }

    /** The crash mid-call, reproduced: intent PROCESSING, attempt born AUTH_DISPATCHED. */
    private PaymentAttempt strandedDispatch(Holder holder) {
        return runner.inTransaction(
                uow -> {
                    intents.transition(
                            uow,
                            holder.intent(),
                            PaymentIntentStatus.REQUIRES_CONFIRMATION,
                            PaymentIntentStatus.PROCESSING);
                    PaymentAttempt attempt =
                            PaymentAttempt.create(
                                    IDS,
                                    CLOCK,
                                    holder.intent(),
                                    new ProviderIdempotencyReference("swp-" + IDS.next()));
                    attempts.insert(uow, attempt);
                    return attempt;
                });
    }

    /** The real flow to CAPTURE_UNKNOWN: confirm approved, capture receives-then-loses. */
    private PaymentAttemptId captureUnknownAttempt(Holder holder) throws Exception {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_a-" + UUID.randomUUID() + "\"}");
        psp.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);

        SecurityContext.Scope actor = SecurityContext.enter(holder.person());
        CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("cu-" + UUID.randomUUID())));
        try {
            new PaymentConfirmation(
                            runner, intents, attempts, evidence, participants, adapter(),
                            outcomes(), new JdbcAuditWriter(), IDS, CLOCK)
                    .confirm(holder.party(), holder.intent());
            PaymentAttemptId attemptId =
                    runner.inTransaction(
                            uow ->
                                    attempts.findForIntent(uow, holder.intent())
                                            .orElseThrow()
                                            .id());
            new PaymentCapture(
                            runner, intents, attempts, evidence, adapter(), outcomes(),
                            new JdbcAuditWriter(), IDS, CLOCK)
                    .capture(attemptId);
            assertThat(attemptStatus(attemptId)).isEqualTo("CAPTURE_UNKNOWN");
            psp.reset();
            return attemptId;
        } finally {
            flow.close();
            actor.close();
        }
    }

    private PaymentSweeper sweeper(Duration dispatchedAge, Duration unknownAge) {
        return new PaymentSweeper(
                runner, attempts, intents, evidence, adapter(), outcomes(), IDS, CLOCK,
                dispatchedAge, unknownAge, 50);
    }

    /** A registry of this suite's own: the meters' wiring is the telemetry suites'. */
    private static com.finapp.app.telemetry.PaymentMeters meters() {
        return new com.finapp.app.telemetry.PaymentMeters(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                SimulatedCardPspAdapter.NAME);
    }

    private PaymentOutcomes outcomes() {
        return new PaymentOutcomes(
                intents,
                attempts,
                new com.finapp.payments.JdbcRefundStore(),
                new com.finapp.ledger.HoldService(
                        ledgerAccounts,
                        new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore(),
                        new com.finapp.ledger.JdbcBalanceProjection(),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        IDS,
                        CLOCK),
                new PostingService(
                        executor(),
                        new JdbcJournalEntryStore(IDS),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        new JdbcBalanceProjection(),
                        IDS,
                        CLOCK,
                        PostingObserver.NONE),
                new ChartOfAccounts<>(ledgerAccounts),
                // THE PRODUCTION SEAM (P6-TSK-005): the composition production posts
                // through, not the wallet one directly - so "no fee pin, two lines" is
                // proven where it matters. A payment with no pin falls back, which is
                // this suite's every payment.
                new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {}),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private SimulatedCardPspAdapter adapter() {
        return new SimulatedCardPspAdapter(
                URI.create(psp.baseUrl()), Duration.ofSeconds(2), PSP_KEY);
    }

    /** The REAL webhook resolver, composed as the beans compose it — no HTTP needed here. */
    private PaymentWebhookService webhookService() {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        DatabaseRoles.required("finapp.db.url"),
                        DatabaseRoles.required("finapp.db.app.user"),
                        DatabaseRoles.required("finapp.db.app.password"));
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new PaymentWebhookService(
                new WebhookSignature(WEBHOOK_KEY, Duration.ofMinutes(5), CLOCK),
                evidence,
                attempts,
                intents,
                new com.finapp.payments.JdbcRefundStore(),
                meters(),
                outcomes(),
                new InboxConsumer<>(new JdbcInboxRecordStore(), CLOCK, Duration.ofDays(14)),
                new tools.jackson.databind.ObjectMapper(),
                CLOCK,
                template,
                dataSource);
    }

    private void deliverWebhook(PaymentWebhookService webhooks, String body) {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        webhooks.deliver(bytes, timestamp, hmacHex(timestamp + "." + body));
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    private void queryAnswers(
            ProviderIdempotencyReference reference, String status, String pspReference) {
        psp.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + reference.value(),
                200,
                "{\"status\":\"" + status + "\",\"reference\":\"" + pspReference + "\"}");
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    // -----------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------

    private String captureReference(PaymentAttemptId attempt) throws SQLException {
        return oneString(
                "SELECT capture_reference FROM payments.payment_attempt WHERE id = ?",
                attempt.value());
    }

    private String attemptStatus(PaymentAttemptId attempt) throws RuntimeException {
        try {
            return oneString(
                    "SELECT status FROM payments.payment_attempt WHERE id = ?", attempt.value());
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String intentStatus(PaymentIntentId intent) throws SQLException {
        return oneString(
                "SELECT status FROM payments.payment_intent WHERE id = ?", intent.value());
    }

    private String failureReason(PaymentAttemptId attempt) throws SQLException {
        return oneString(
                "SELECT failure_reason FROM payments.payment_attempt WHERE id = ?",
                attempt.value());
    }

    private static long entriesByReference(PaymentAttemptId attempt) throws SQLException {
        return count(
                "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                attempt.value().toString());
    }

    private static long transitionCount(PaymentAttemptId attempt, String to) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.payment_attempt_event"
                        + " WHERE attempt_id = ? AND to_status = ?",
                attempt.value(),
                to);
    }

    private static long evidenceCount(PaymentAttemptId attempt) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.provider_evidence WHERE attempt_id = ?",
                attempt.value());
    }

    private static String oneString(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static final class CountingRunner implements TransactionRunner {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection unitOfWork = DatabaseRoles.application()) {
                try {
                    unitOfWork.setAutoCommit(false);
                    R result = work.apply(unitOfWork);
                    unitOfWork.commit();
                    return result;
                } catch (RuntimeException failure) {
                    unitOfWork.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("transaction plumbing failed", failure);
            }
        }
    }
}
