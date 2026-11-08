package com.finapp.app.reconciliation;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.reconciliation.ReconciliationSweep;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs time's observers on a fixed delay, on every instance (`P8-TSK-013`,
 * {@link ReconciliationSweep#sweep()}) — the {@code ReconciliationSchedule} shape:
 * leaderless by design, every write a conditional whose racers converge (the one-way
 * {@code overdue_since}, the one-open uniques, the expected-value severity step, the
 * run's conditional edge), and a tick that finds nothing costs four bounded queries.
 * Registered in {@code DISTRIBUTED_EXECUTION.md} §3 and
 * {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}, on the
 * conditional-transition half of the bar. Off in test contexts
 * ({@code finapp.reconciliation.sweep.enabled}); the database suites drive the sweep
 * directly.
 */
@Slf4j
public final class ReconciliationSweepSchedule implements SmartLifecycle {

    private final ReconciliationSweep sweep;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("reconciliation-sweep");

    public ReconciliationSweepSchedule(ReconciliationSweep sweep, Duration pollInterval) {
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
            ReconciliationSweep.SweepResult swept = sweep.sweep();
            if (swept.aged() > 0 || swept.escalated() > 0 || swept.blocked() > 0
                    || swept.collisions() > 0) {
                // Counts only - identifiers live in the legs' own per-row records.
                log.info(
                        "Reconciliation sweep: {} aged, {} escalated, {} blocked,"
                                + " {} collisions",
                        swept.aged(),
                        swept.escalated(),
                        swept.blocked(),
                        swept.collisions());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts and identifiers.
            log.warn("Reconciliation sweep failed: {}", failure.getClass().getSimpleName());
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
