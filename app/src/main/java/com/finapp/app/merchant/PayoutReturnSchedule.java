package com.finapp.app.merchant;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link PayoutReturnSweep#sweep()} on a fixed delay, on every instance (`P8-TSK-019`,
 * ADR-0073 §4) — the {@code ReturnResolutionSchedule} shape: leaderless by design, the item's
 * share-locked re-read, the payout row lock, {@code UNIQUE (payout_id)} and the posting key
 * arbitrate, and a tick that finds nothing costs one bounded query.
 */
@Slf4j
public final class PayoutReturnSchedule implements SmartLifecycle {

    private final PayoutReturnSweep sweep;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public PayoutReturnSchedule(PayoutReturnSweep sweep, Duration pollInterval) {
        this.sweep = Objects.requireNonNull(sweep, "sweep must not be null");
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
                            Thread thread = new Thread(runnable, "payout-return");
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
            PayoutReturnSweep.SweepResult result = sweep.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live nowhere in these lines.
                log.info(
                        "Payout return sweep: {} candidates, {} applied, {} not applicable,"
                                + " {} skipped, {} failed",
                        result.candidates(),
                        result.applied(),
                        result.notApplicable(),
                        result.skipped(),
                        result.failedRows());
            }
        } catch (RuntimeException failure) {
            log.warn("Payout return sweep failed: {}", failure.getClass().getSimpleName());
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
