package com.finapp.app.payments;

import com.finapp.app.lifecycle.DrainedSchedule;
import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.payments.ReturnResolution;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs {@link ReturnResolution#sweep()} on a fixed delay, on every instance (`P7-TSK-010`)
 * — the {@code PayInResolutionSchedule} shape: leaderless by design, the database's
 * conditional permit renewal and transitions arbitrate, and a tick that finds nothing
 * costs one bounded query.
 */
@Slf4j
public final class ReturnResolutionSchedule implements DrainedSchedule {

    private final ReturnResolution resolution;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("return-resolution");

    public ReturnResolutionSchedule(ReturnResolution resolution, Duration pollInterval) {
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

    private void sweepQuietly() {
        try {
            ReturnResolution.SweepResult result = resolution.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweep's own per-row lines.
                log.info(
                        "Return sweep: {} candidates, {} applied, {} skipped",
                        result.candidates(),
                        result.applied(),
                        result.skipped());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or scheme message can name hosts and identifiers.
            log.warn("Return sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public ScheduleDrain drain() {
        return drain;
    }
}
