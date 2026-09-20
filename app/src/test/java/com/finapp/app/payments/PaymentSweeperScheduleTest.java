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
        return new PaymentSweeper(
                runner,
                new com.finapp.payments.JdbcPaymentAttemptStore(),
                new com.finapp.payments.JdbcPaymentIntentStore(),
                new com.finapp.payments.JdbcProviderEvidenceStore(
                        new com.finapp.payments.EvidenceCipher(
                                "0123456789abcdef0123456789abcdef"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                1,
                                new java.security.SecureRandom()),
                        ids()),
                new NoProvider(),
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
                        (uow, record) -> {},
                        (uow, envelope, payload, mediaType) -> {},
                        ids(),
                        java.time.Clock.systemUTC()),
                ids(),
                java.time.Clock.systemUTC(),
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
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
        public com.finapp.payments.QueryAnswer query(
                com.finapp.payments.ProviderIdempotencyReference ourReference) {
            throw new UnsupportedOperationException();
        }
    }
}
