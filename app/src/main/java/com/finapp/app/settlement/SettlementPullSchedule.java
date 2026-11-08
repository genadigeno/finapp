package com.finapp.app.settlement;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link SettlementPullSweep#sweep()} on a fixed delay, on every instance (`P8-TSK-021`,
 * ADR-0066 §1) — the {@code SettlementIntakeSchedule} shape: leaderless by design, the windowed
 * {@code pull_permit} pacing the herd and the content unique arbitrating what lands, a tick that
 * owes nothing costing a bounded read per source. Registered in {@code DISTRIBUTED_EXECUTION.md}
 * §3 and {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}. Off in test
 * contexts ({@code finapp.settlement.pull.sweeper.enabled=false}); the database suites drive the
 * sweep and the pull directly.
 */
@Slf4j
public final class SettlementPullSchedule implements SmartLifecycle {

    private final SettlementPullSweep sweep;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("settlement-pull");

    public SettlementPullSchedule(SettlementPullSweep sweep, Duration pollInterval) {
        this.sweep = Objects.requireNonNull(sweep, "sweep must not be null");
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

    private void sweepQuietly() {
        try {
            SettlementPullSweep.SweepResult result = sweep.sweep();
            if (result.owed() > 0) {
                // Counts only - never a source reference, a path or a key.
                log.info(
                        "Settlement pull sweep: {} owed, {} pulled, {} paced, {} failed",
                        result.owed(),
                        result.pulled(),
                        result.paced(),
                        result.failedRows());
            }
        } catch (RuntimeException failure) {
            log.warn("Settlement pull sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** This schedule's drain: its name is what a stopping instance's log calls it. */
    public ScheduleDrain drain() {
        return drain;
    }

    /** Stops claiming, then lets the tick in flight finish within its bound ({@link ScheduleDrain}). */
    @Override
    public void stop() {
        drain.stop();
    }

    /** Spring's form: stops claiming at once and calls back once the tick has drained, so a phase drains in parallel. */
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
