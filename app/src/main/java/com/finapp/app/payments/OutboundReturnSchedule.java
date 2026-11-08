package com.finapp.app.payments;

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
 * Runs {@link OutboundReturnWorker#sweep()} on a fixed delay, on every instance (`P9-TSK-023`) - the
 * {@code PayoutReturnSchedule} shape: leaderless by design, the item's share lock, the credit's row lock, the return's
 * unique and the posting key arbitrate, and a tick that finds nothing costs one bounded query.
 */
@Slf4j
public final class OutboundReturnSchedule implements SmartLifecycle {

    private final OutboundReturnWorker worker;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("outbound-return");

    public OutboundReturnSchedule(OutboundReturnWorker worker, Duration pollInterval) {
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void sweepQuietly() {
        try {
            OutboundReturnWorker.SweepResult result = worker.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the worker's own per-row lines.
                log.info("Corridor return sweep: {} candidates, {} applied, {} deferred, {} not applicable, {} failed",
                        result.candidates(), result.applied(), result.deferred(), result.notApplicable(), result.failedRows());
            }
        } catch (RuntimeException failure) {
            log.warn("Corridor return sweep failed: {}", failure.getClass().getSimpleName());
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
