package com.finapp.app.reconciliation;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.reconciliation.Matching;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the matcher's sweep on a fixed delay, on every instance (`P8-TSK-011`,
 * {@link Matching#sweep()}) — the {@code SettlementIntakeSchedule} shape: leaderless by
 * design, the per-source {@code pg_try_advisory_xact_lock} ORDERS while the items'
 * conditional edges, `V005`'s uniques and the deferred Σ triggers ARBITRATE in PostgreSQL,
 * and a tick that finds nothing costs one bounded query. Registered in
 * {@code DISTRIBUTED_EXECUTION.md} §3 and
 * {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}, on the
 * conditional-transition half of the bar. Off in test contexts
 * ({@code finapp.reconciliation.matching.sweeper.enabled=false}); the database suites drive
 * the sweep directly.
 */
@Slf4j
public final class ReconciliationSchedule implements SmartLifecycle {

    private final Matching matching;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("reconciliation-matching");

    public ReconciliationSchedule(Matching matching, Duration pollInterval) {
        this.matching = Objects.requireNonNull(matching, "matching must not be null");
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
            Matching.SweepResult swept = matching.sweep();
            if (swept.chunks() > 0 || swept.blockedRuns() > 0 || swept.graced() > 0
                    || swept.rematched() > 0 || swept.reprocessed() > 0) {
                // Counts only - identifiers live in the legs' own per-item records.
                log.info(
                        "Matching sweep: {} sources, {} chunks, {} decided, {} completed,"
                                + " {} blocked, {} graced, {} rematched, {} reprocessed",
                        swept.sources(),
                        swept.chunks(),
                        swept.decided(),
                        swept.completedRuns(),
                        swept.blockedRuns(),
                        swept.graced(),
                        swept.rematched(),
                        swept.reprocessed());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts and identifiers.
            log.warn("Matching sweep failed: {}", failure.getClass().getSimpleName());
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
