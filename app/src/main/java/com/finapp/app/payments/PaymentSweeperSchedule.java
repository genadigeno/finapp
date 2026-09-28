package com.finapp.app.payments;

import com.finapp.app.telemetry.PaymentMeters;
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
 * machinery. (Sweep meters are plan §15's, arriving with `P5-TSK-017`.)
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
    private final PaymentMeters meters;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public PaymentSweeperSchedule(
            PaymentSweeper sweeper, PaymentMeters meters, Duration pollInterval) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
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
            // The tick's acting judgements, counted after their per-row transactions
            // committed (`P5-TSK-017`): the sweeper's own tally is telemetry and may
            // overcount convergence, which is exactly why the METER reads the acting list
            // instead - a swept resolution racing a webhook is counted by whichever won,
            // once.
            result.actingJudgements().forEach(this::count);
            // The refund leg's own acting judgements (the Phase 6 -> 7 transition), counted on
            // the series the refund door and the webhook already feed.
            result.refundJudgements().forEach(this::countRefund);
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweeper's own per-row lines.
                log.info(
                        "Payment sweep: {} candidates, {} applied, {} skipped, {} failed",
                        result.candidates(),
                        result.applied(),
                        result.skipped(),
                        result.failedRows());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or provider message can name hosts and identifiers.
            log.warn("Payment sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** The attempt machine's vocabulary; a dispatched state is no judgement (see the door). */
    private void count(com.finapp.payments.PaymentAttemptStatus status) {
        switch (status) {
            case AUTHORIZED -> meters.attempt(PaymentMeters.Judgement.AUTHORIZED);
            case CAPTURED -> meters.attempt(PaymentMeters.Judgement.CAPTURED);
            case FAILED -> meters.attempt(PaymentMeters.Judgement.FAILED);
            case AUTH_UNKNOWN, CAPTURE_UNKNOWN -> meters.attempt(PaymentMeters.Judgement.UNKNOWN);
            case AUTH_DISPATCHED, CAPTURE_DISPATCHED -> {
                // Mid-question: the sweeper never commits one, and counting it would be
                // throughput for a decision nobody made.
            }
        }
    }

    /** The refund machine's vocabulary, the refund door's own mapping (`PaymentService`). */
    private void countRefund(com.finapp.payments.RefundStatus status) {
        switch (status) {
            case COMPLETED -> meters.refund(PaymentMeters.RefundOutcome.COMPLETED);
            case FAILED -> meters.refund(PaymentMeters.RefundOutcome.FAILED);
            case UNKNOWN -> meters.refund(PaymentMeters.RefundOutcome.UNKNOWN);
            case DISPATCHED -> {
                // A dispatch is no judgement, and the sweep never commits one.
            }
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
