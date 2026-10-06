package com.finapp.app.payments;

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

    private ScheduledExecutorService executor;

    public OutboundReturnSchedule(OutboundReturnWorker worker, Duration pollInterval) {
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbound-return");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
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
