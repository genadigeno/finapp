package com.finapp.app.payments;

import com.finapp.payments.PaymentSweeper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link PaymentSweeper#sweep()} on a fixed delay, on every instance (`P5-TSK-014`) —
 * the {@code OutboxRelaySchedule} shape, on the rule's other justification.
 *
 * <h2>Every instance polls, deliberately — no lease, no leader, by design</h2>
 *
 * <p>{@code nothingSchedulesAmbiently} (ADR-0024) states its bar in two halves: a scheduled
 * financial process is <strong>idempotent per period</strong> or takes an explicit database
 * lease. The relay took the lease half (per-aggregate advisory locks); the sweeper is the
 * first occupant of the other half: its provider queries are read-only and idempotent, and
 * every write is a conditional transition whose losers converge ({@code INV-IDEM-02}) —
 * N sweepers racing each other, the webhook and a client's retry are the same counted race.
 * The register row is {@code DISTRIBUTED_EXECUTION.md} §3, beside the relay's, each naming
 * its own justification; the exemption is proven load-bearing in the rule's own suite.
 *
 * <h2>What this class must never become</h2>
 *
 * <p>A coordinator — the relay javadoc's exact stance: it holds no state the sweeper depends
 * on, and the executor is per-instance mechanics whose loss costs this instance's ticks and
 * nothing else. Any future scheduled work that is neither idempotent-per-period nor
 * lease-protected does not get to ride on this exemption.
 *
 * <h2>A failed tick is logged and the schedule continues</h2>
 *
 * <p>The failure class only — never provider bytes, hosts or amounts ({@code INV-AUD-02});
 * per-row failures inside a successful tick are already the sweeper's own anti-stall
 * machinery. Its acting judgements are counted where they are written, never here: since
 * `P7-TSK-015` every applier reports them to its {@code RailOutcomeObserver}, which counts
 * after the row's own transaction commits.
 */
@Slf4j
public final class PaymentSweeperSchedule implements SmartLifecycle {

    /**
     * The sweep's dispatched bound, as ONE placeholder (the Phase 6 → 7 transition, the payout's
     * {@code MerchantPayoutBeans.DISPATCHED_AGE}): the sweep asks nothing about a younger
     * dispatch, and the stuck-payment gauge counts a dispatched or authorized operation only
     * past it. Both read this constant, so the two can never disagree about what "stuck" means.
     */
    public static final String DISPATCHED_AGE = "${finapp.payments.sweeper.dispatched-age:PT10M}";

    private final PaymentSweeper sweeper;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public PaymentSweeperSchedule(PaymentSweeper sweeper, Duration pollInterval) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
    }

    @Override
    public void start() {
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "payment-sweeper");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(
                this::sweepQuietly,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sweepQuietly() {
        try {
            PaymentSweeper.SweepResult result = sweeper.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweeper's own per-row lines.
                log.info(
                        "Payment sweep: {} candidates, {} applied, {} skipped, {} failed,"
                                + " {} attempt and {} refund judgements acted",
                        result.candidates(),
                        result.applied(),
                        result.skipped(),
                        result.failedRows(),
                        result.actingJudgements().size(),
                        result.refundJudgements().size());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or provider message can name hosts and identifiers.
            log.warn("Payment sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }
}
