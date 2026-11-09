package com.finapp.app.accounting;

import com.finapp.accounting.SourceCensus;
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
 * Runs the {@link SourceCensus} on a fixed delay, on every instance (`P15-TSK-035`; {@code THREAT_MODEL.md} T15). No leader: one walker
 * at a time, arbitrated by the head row's {@code FOR UPDATE SKIP LOCKED}, so a replica that finds the row held does nothing this tick.
 * Registered in {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS} and {@code DISTRIBUTED_EXECUTION.md} section 3.
 *
 * <p><strong>A tick is bounded.</strong> Up to {@link #MAX_BATCHES} batches, stopping when a batch comes back idle, another walker has
 * the head or this instance is stopping; each batch borrows one connection from the instance's pool and returns it, so the fleet's
 * connection budget does not move. A batch that fails is logged by class, its head unchanged and its failure stamped: the next tick
 * tries again, and the gauge reads no value in between.
 */
@Slf4j
public final class SourceCensusSchedule implements SmartLifecycle {

    /** The batches one tick may judge before yielding to the next tick. */
    static final int MAX_BATCHES = 20;

    private final SourceCensus census;
    private final Duration interval;
    private final ScheduleDrain drain = new ScheduleDrain("source-census");

    /** @param interval the delay between one tick's end and the next's start */
    public SourceCensusSchedule(SourceCensus census, Duration interval) {
        this.census = Objects.requireNonNull(census, "census");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
    }

    /** One tick: batches until the walk is idle, the head is taken, or the bound is reached. */
    public void sweepOnce() {
        for (int batch = 0; batch < MAX_BATCHES && !drain.stopping(); batch++) {
            SourceCensus.Batch done = census.sweepBatch();
            if (!done.claimed() || done.judged() == 0) {
                return;
            }
        }
    }

    void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            // The class only: a storage message names an operation and an SQLState, never a value, but a driver message might.
            log.warn("The source-join census failed a batch: {}", failure.getClass().getSimpleName());
        }
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
