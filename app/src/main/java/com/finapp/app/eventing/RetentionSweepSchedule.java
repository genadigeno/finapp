package com.finapp.app.eventing;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.platform.retention.RetentionSweep;
import com.finapp.platform.retention.RetentionSweepException;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs one {@link RetentionSweep} on a fixed delay, on every instance (`P15-TSK-029`, C12). One class, two beans: the outbox's
 * published rows and the inbox's expired records.
 *
 * <p><strong>No lease, no leader: idempotent per row.</strong> {@code nothingSchedulesAmbiently}'s bar is idempotent per period
 * or a lease, and this stands on the first half. Each batch is one transaction deleting rows it holds under
 * {@code FOR UPDATE SKIP LOCKED}, so ten instances delete disjoint batches, a row is deleted once, and a run that finds nothing
 * deletes nothing. Its register row is in {@code DISTRIBUTED_EXECUTION.md} section 3.
 *
 * <p><strong>A tick is bounded, and each batch stands alone.</strong> Up to {@link #MAX_BATCHES} batches, stopping at the first
 * short one; a batch that fails ends the tick, is counted, and the next tick resumes, so an interrupted sweep loses nothing.
 * One batch borrows one connection from the instance's pool and returns it before the next: the fleet's connection budget
 * ({@code instances x pool}) does not move.
 */
@Slf4j
public final class RetentionSweepSchedule implements SmartLifecycle {

    /** The batches one tick may delete before yielding to the next tick. */
    static final int MAX_BATCHES = 20;

    private final RetentionSweep sweep;
    private final Duration interval;
    private final Counter swept;
    private final Counter failed;
    private final ScheduleDrain drain;

    /**
     * @param name the schedule's thread name, and what a stopping instance's log calls it
     * @param sweep the batch this schedule repeats
     * @param interval the delay between one tick's end and the next's start
     */
    public RetentionSweepSchedule(String name, RetentionSweep sweep, Duration interval, RetentionSweepMeters meters) {
        this.sweep = Objects.requireNonNull(sweep, "sweep must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        Objects.requireNonNull(meters, "meters must not be null");
        this.swept = meters.swept(sweep.table());
        this.failed = meters.failed(sweep.table());
        this.drain = new ScheduleDrain(name);
    }

    /**
     * Deletes every batch this tick can reach, each its own transaction.
     *
     * @return how many rows this call deleted
     * @throws RetentionSweepException if a batch failed; the batches before it stand
     */
    public int sweepOnce() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = sweep.sweepBatch();
            swept.increment(deleted);
            total += deleted;
            if (deleted < sweep.batchSize()) {
                break;
            }
        }
        return total;
    }

    /** The sweep this schedule repeats, its bound and table included. */
    public RetentionSweep sweep() {
        return sweep;
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RetentionSweepException failure) {
            // The SQLState alone: 55000 is an inbox column the sweep was not reviewed against (OPERATIONS_RUNBOOK.md 11.15).
            failed.increment();
            log.warn("Retention sweep of {} failed: SQLState {}", sweep.table(), failure.sqlState());
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can carry values.
            failed.increment();
            log.warn("Retention sweep of {} failed: {}", sweep.table(), failure.getClass().getSimpleName());
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
