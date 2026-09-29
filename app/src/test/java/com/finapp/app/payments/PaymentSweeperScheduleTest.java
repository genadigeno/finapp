package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.finapp.payments.PaymentSweeper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schedule's own mechanics (`P5-TSK-014`): ticks invoke the sweeper, a throwing tick is
 * logged and the schedule continues (the relay's stance), stop stops. The sweeper's
 * correctness is the database suite's; what only this test can see is the lifecycle.
 */
@DisplayName("the payment sweeper schedule (P5-TSK-014)")
class PaymentSweeperScheduleTest {

    @Test
    @DisplayName("ticks invoke the sweeper on the configured delay, and stop stops")
    void ticksInvokeTheSweeper() {
        AtomicInteger ticks = new AtomicInteger();
        PaymentSweeperSchedule schedule =
                new PaymentSweeperSchedule(ticking(ticks, false), Duration.ofMillis(50));
        schedule.start();
        try {
            assertThat(schedule.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 2);
        } finally {
            schedule.stop();
        }
        assertThat(schedule.isRunning()).isFalse();
        int after = ticks.get();
        await().during(Duration.ofMillis(200)).until(() -> ticks.get() == after);
    }

    @Test
    @DisplayName("a throwing tick is logged and the schedule continues - the relay's stance")
    void aThrowingTickDoesNotKillTheSchedule() {
        AtomicInteger ticks = new AtomicInteger();
        PaymentSweeperSchedule schedule =
                new PaymentSweeperSchedule(ticking(ticks, true), Duration.ofMillis(50));
        schedule.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 3);
        } finally {
            schedule.stop();
        }
    }

    @Test
    @DisplayName("a non-positive interval is refused at construction")
    void aNonPositiveIntervalIsRefused() {
        AtomicInteger ticks = new AtomicInteger();
        assertThatThrownBy(
                        () -> new PaymentSweeperSchedule(ticking(ticks, false), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PaymentSweeperSchedule(
                                        ticking(ticks, false), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Both of the sweep's bounds license {@code FAILED(NEVER_RECEIVED)}, so neither may be zero:
     * at zero the sweep can ask about a request still in flight, hear "never saw it" and fail a
     * payment the provider then performs. Found by the Phase 6 → 7 transition in this guard, a
     * day after the Phase 6 review had closed the same defect in the payout's sweep.
     */
    @Test
    @DisplayName("a zero or negative bound is refused at construction - either bound can license"
            + " NEVER_RECEIVED")
    void aZeroOrNegativeBoundIsRefused() {
        com.finapp.payments.TransactionRunner never =
                new com.finapp.payments.TransactionRunner() {
                    @Override
                    public <R> R inTransaction(
                            java.util.function.Function<java.sql.Connection, R> work) {
                        throw new AssertionError("construction must not touch the database");
                    }
                };
        Duration minute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> sweeper(never, Duration.ZERO, minute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatchedAge must be positive");
        assertThatThrownBy(() -> sweeper(never, minute, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknownAge must be positive");
        assertThatThrownBy(() -> sweeper(never, Duration.ofMillis(-1), minute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatchedAge must be positive");
        assertThatThrownBy(() -> sweeper(never, minute, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknownAge must be positive");
        // The positive control: the least bound the database suites use constructs.
        Duration dueNow = Duration.ofNanos(1_000);
        assertThat(sweeper(never, dueNow, dueNow)).isNotNull();
    }

    /**
     * A real {@link PaymentSweeper} whose {@code sweep()} counts and optionally throws:
     * {@code PaymentSweeper} is final, so the counting rides in a {@code TransactionRunner}
     * that intercepts the candidate read — the first thing every sweep does — which keeps the
     * schedule exercising the REAL type's entry point rather than a test double's.
     */
    private static PaymentSweeper ticking(AtomicInteger ticks, boolean throwing) {
        com.finapp.payments.TransactionRunner runner =
                new com.finapp.payments.TransactionRunner() {
                    @Override
                    public <R> R inTransaction(
                            java.util.function.Function<java.sql.Connection, R> work) {
                        ticks.incrementAndGet();
                        if (throwing) {
                            throw new IllegalStateException("tick made to fail");
                        }
                        @SuppressWarnings("unchecked")
                        R empty = (R) java.util.List.of();
                        return empty;
                    }
                };
        return sweeper(runner, Duration.ofMinutes(10), Duration.ofMinutes(1));
    }

    /** The production construction, with the bounds this test chooses. */
    private static PaymentSweeper sweeper(
            com.finapp.payments.TransactionRunner runner,
            Duration dispatchedAge,
            Duration unknownAge) {
        com.finapp.payments.JdbcPaymentAttemptStore attempts =
                new com.finapp.payments.JdbcPaymentAttemptStore();
        com.finapp.payments.JdbcPaymentIntentStore intents =
                new com.finapp.payments.JdbcPaymentIntentStore();
        com.finapp.payments.JdbcProviderEvidenceStore evidence =
                new com.finapp.payments.JdbcProviderEvidenceStore(
                        new com.finapp.payments.EvidenceCipher(
                                "0123456789abcdef0123456789abcdef"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                1,
                                new java.security.SecureRandom()),
                        ids());
        NoProvider provider = new NoProvider();
        com.finapp.payments.PaymentOutcomes outcomes =
                new com.finapp.payments.PaymentOutcomes(
                        new com.finapp.payments.JdbcPaymentIntentStore(),
                        new com.finapp.payments.JdbcPaymentAttemptStore(),
                        new com.finapp.payments.JdbcRefundStore(),
                        new com.finapp.ledger.HoldService(
                                new com.finapp.ledger.JdbcLedgerAccountStore(),
                                new com.finapp.ledger.JdbcBalanceDerivation(),
                                new com.finapp.ledger.JdbcHoldStore(),
                                new com.finapp.ledger.JdbcBalanceProjection(),
                                (uow, record) -> {},
                                (uow, envelope, payload, mediaType) -> {},
                                ids(),
                                java.time.Clock.systemUTC()),
                        new com.finapp.ledger.PostingService(
                                new com.finapp.platform.idempotency.IdempotentExecutor(
                                        new com.finapp.platform.idempotency
                                                .JdbcIdempotencyRecordStore(),
                                        java.time.Clock.systemUTC(),
                                        Duration.ofDays(1),
                                        Duration.ofMinutes(5)),
                                new com.finapp.ledger.JdbcJournalEntryStore(ids()),
                                (uow, record) -> {},
                                new com.finapp.platform.outbox.JdbcOutboxWriter(),
                                new com.finapp.ledger.JdbcBalanceProjection(),
                                ids(),
                                java.time.Clock.systemUTC(),
                                com.finapp.ledger.PostingObserver.NONE),
                        new com.finapp.ledger.ChartOfAccounts<>(
                                new com.finapp.ledger.JdbcLedgerAccountStore()),
                        // THE PRODUCTION SEAM (P6-TSK-005): the composition production posts through,
                        // not the wallet one directly - so "no fee pin, two lines" is proven where it
                        // matters. Every payment in this suite is a top-up and falls back.
                        new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                                new com.finapp.merchant.MerchantSettlement(
                                        new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                        new com.finapp.merchant.JdbcFeeScheduleStore(),
                                        new com.finapp.ledger.JdbcLedgerAccountStore(),
                                        new com.finapp.ledger.ChartOfAccounts<>(new com.finapp.ledger.JdbcLedgerAccountStore()),
                                        new com.finapp.platform.outbox.JdbcOutboxWriter(),
                                        ids()),
                                new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {},
                        new com.finapp.app.telemetry.MerchantMeters(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())),
                        // THE REFUND'S MIRROR SEAM (P6-TSK-014): this schedule's sweeper
                        // never posts a refund, and the seam is wired anyway so the
                        // construction stays the one MerchantBeans performs.
                        new com.finapp.app.merchant.MerchantBoundRefundComposition(
                                new com.finapp.merchant.MerchantSettlement(
                                        new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                        new com.finapp.merchant.JdbcFeeScheduleStore(),
                                        new com.finapp.ledger.JdbcLedgerAccountStore(),
                                        new com.finapp.ledger.ChartOfAccounts<>(
                                                new com.finapp.ledger.JdbcLedgerAccountStore()),
                                        new com.finapp.platform.outbox.JdbcOutboxWriter(),
                                        ids()),
                                new com.finapp.payments.WalletRefundComposition()),
                        (uow, record) -> {},
                        (uow, envelope, payload, mediaType) -> {},
                        ids(),
                        java.time.Clock.systemUTC(),
                        com.finapp.payments.PaymentRails.of(java.util.List.of(com.finapp.payments.SimulatedCardPspAdapter.RAIL)),
                new com.finapp.payments.JdbcUnmatchedConfirmationStore(),
                new com.finapp.ledger.JdbcLedgerAccountStore(),
                // The dispute money (P7-TSK-013), production-shaped: a failed refund here
                // locks its card attempt first and finds no chargeback.
                com.finapp.app.payments.ChargebackAccountingFixture.over(
                        new com.finapp.ledger.PostingService(
                                new com.finapp.platform.idempotency.IdempotentExecutor(
                                        new com.finapp.platform.idempotency
                                                .JdbcIdempotencyRecordStore(),
                                        java.time.Clock.systemUTC(),
                                        Duration.ofDays(1),
                                        Duration.ofMinutes(5)),
                                new com.finapp.ledger.JdbcJournalEntryStore(ids()),
                                (uow, record) -> {},
                                new com.finapp.platform.outbox.JdbcOutboxWriter(),
                                new com.finapp.ledger.JdbcBalanceProjection(),
                                ids(),
                                java.time.Clock.systemUTC(),
                                com.finapp.ledger.PostingObserver.NONE),
                        com.finapp.payments.PaymentRails.of(java.util.List.of(com.finapp.payments.SimulatedCardPspAdapter.RAIL)),
                        ids(),
                        java.time.Clock.systemUTC()),
                        com.finapp.payments.RailOutcomeObserver.NONE,
                        new com.finapp.payments.JdbcSchemeExecutionClaimStore(),
                        // The expectation seam (P8-TSK-004): a quiet double - the register's
                        // coupling is SettlementExpectationDatabaseTest's to prove.
                        new com.finapp.payments.SettlementExpectations() {
                            @Override
                            public void open(java.sql.Connection uow, Opening opening) {}

                            @Override
                            public void alias(
                                    java.sql.Connection uow, AliasRegistration registration) {}
                        });
        com.finapp.payments.PaymentVoid voids =
                new com.finapp.payments.PaymentVoid(
                        runner, intents, attempts, evidence, provider, outcomes,
                        com.finapp.payments.PaymentRails.of(java.util.List.of(
                                com.finapp.payments.SimulatedCardPspAdapter.RAIL)),
                        (uow, record) -> {}, ids(), java.time.Clock.systemUTC());
        return new PaymentSweeper(
                runner,
                attempts,
                intents,
                new com.finapp.payments.JdbcRefundStore(),
                evidence,
                provider,
                outcomes,
                // The stranded-authorization leg's command (the Phase 6 -> 7 transition): never
                // reached here either, because the runner intercepts every read first.
                new com.finapp.payments.PaymentCapture(
                        runner,
                        intents,
                        attempts,
                        evidence,
                        provider,
                        outcomes,
                        voids,
                        (uow, record) -> {},
                        ids(),
                        java.time.Clock.systemUTC()),
                voids,
                ids(),
                java.time.Clock.systemUTC(),
                dispatchedAge,
                unknownAge,
                50);
    }

    private static com.finapp.sharedkernel.id.IdGenerator ids() {
        return new com.finapp.sharedkernel.id.IdGenerator(
                java.time.Clock.systemUTC(), new java.security.SecureRandom());
    }

    /** Never reached: the runner intercepts before any provider question can be asked. */
    private static final class NoProvider implements com.finapp.payments.PaymentProvider {
        @Override
        public String providerName() {
            return "none";
        }

        @Override
        public com.finapp.payments.ProviderAnswer authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.finapp.payments.ProviderAnswer capture(CaptureRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.finapp.payments.ProviderAnswer refund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.finapp.payments.ProviderAnswer voidAuthorization(VoidRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.finapp.payments.QueryAnswer query(
                com.finapp.payments.ProviderIdempotencyReference ourReference) {
            throw new UnsupportedOperationException();
        }
    }
}
