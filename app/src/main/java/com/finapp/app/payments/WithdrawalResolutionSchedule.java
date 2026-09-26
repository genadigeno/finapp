package com.finapp.app.payments;

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
 */
@Slf4j
public final class WithdrawalResolutionSchedule implements SmartLifecycle {

    private final WithdrawalResolution resolution;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public WithdrawalResolutionSchedule(WithdrawalResolution resolution, Duration pollInterval) {
        this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
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
                            Thread thread = new Thread(runnable, "withdrawal-resolution");
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
