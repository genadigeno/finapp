package com.finapp.app.payments;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.payments.WithdrawalResolution;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link WithdrawalResolution#sweep()} on a fixed delay, on every instance
 * (`P7-TSK-008`) — the {@code PaymentSweeperSchedule} shape: leaderless by design, the
 * database's conditional transitions arbitrate, and a tick that finds nothing costs one
 * bounded query.
 *
 * <p><strong>Two legs a tick</strong> (`P13-TSK-009`): the inquiry sweep, then the held withdrawal's expiry
 * ({@link WithdrawalResolution#expireHolds()}) - every {@code HELD} withdrawal past its {@code held_until} on the
 * database clock failed {@code RISK_REFUSED}/{@code HOLD_EXPIRED} and its hold released, one row per transaction claimed
 * {@code FOR UPDATE SKIP LOCKED}. The same class, flag and cadence - a leg, not a new scheduler; each leg contained, so
 * one failing never starves the other.
 */
@Slf4j
public final class WithdrawalResolutionSchedule implements SmartLifecycle {

    /**
     * The sweep's dispatched bound, as ONE placeholder (`P7-TSK-015`, the
     * {@code PaymentSweeperSchedule.DISPATCHED_AGE} shape): the sweep asks nothing about a
     * younger dispatch, and the stuck-withdrawal gauge counts a dispatched withdrawal only past
     * it. Both read this constant, so the two can never disagree about what "stuck" means.
     */
    public static final String DISPATCHED_AGE =
            "${finapp.payments.withdrawal.sweeper.dispatched-age:PT10M}";

    private final WithdrawalResolution resolution;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("withdrawal-resolution");

    public WithdrawalResolutionSchedule(WithdrawalResolution resolution, Duration pollInterval) {
        this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, drain.name());
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(
                this::sweepQuietly,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    /** One tick: both legs, each contained. Package-visible for the schedule's own suite. */
    void sweepQuietly() {
        sweepInquiries();
        expireHolds();
    }

    private void expireHolds() {
        try {
            WithdrawalResolution.ExpiryResult result = resolution.expireHolds();
            if (result.expired() > 0 || result.failed() > 0) {
                // Counts only - identifiers live in the leg's own per-row lines.
                log.info(
                        "Withdrawal hold expiry: {} expired, {} failed",
                        result.expired(),
                        result.failed());
            }
        } catch (RuntimeException failure) {
            log.warn("Withdrawal hold expiry failed: {}", failure.getClass().getSimpleName());
        }
    }

    private void sweepInquiries() {
        try {
            WithdrawalResolution.SweepResult result = resolution.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweep's own per-row lines.
                log.info(
                        "Withdrawal sweep: {} candidates, {} resolved ({})",
                        result.candidates(),
                        result.resolved(),
                        result.actingJudgements());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or scheme message can name hosts and identifiers.
            log.warn("Withdrawal sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** This schedule's drain: its name is what a stopping instance's log calls it. */
    public ScheduleDrain drain() {
        return drain;
    }

    /** No new tick starts, and the tick in flight finishes within its bound ({@link ScheduleDrain}). */
    @Override
    public void stop() {
        drain.stop();
    }

    /** Spring's form: no new tick starts, and it calls back once the tick in flight has drained, so a phase drains in parallel. */
    @Override
    public void stop(Runnable callback) {
        drain.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return drain.isRunning();
    }

    /** After the HTTP drain, before the consumers ({@link ShutdownOrder}). */
    @Override
    public int getPhase() {
        return ShutdownOrder.SCHEDULES;
    }

    /** The longest {@link #stop()} takes, below {@code spring.lifecycle.timeout-per-shutdown-phase} ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return drain.stopBound();
    }
}
