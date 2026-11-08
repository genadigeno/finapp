package com.finapp.app.payments;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.payments.CallbackHintResolution;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link CallbackHintResolution#sweep()} on a fixed delay, on every instance (`P15-TSK-036`) — the
 * {@code PayInResolutionSchedule} shape: leaderless by design, the database's conditional permit and the appliers'
 * conditional transitions arbitrate, and a tick that finds nothing costs one bounded query.
 */
@Slf4j
public final class CallbackHintResolutionSchedule implements SmartLifecycle {

    private final CallbackHintResolution resolution;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("callback-hint-resolution");

    public CallbackHintResolutionSchedule(CallbackHintResolution resolution, Duration pollInterval) {
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
            CallbackHintResolution.SweepResult result = resolution.sweep();
            if (result.candidates() > 0 || result.expired() > 0) {
                // Counts only - identifiers live in the sweep's own per-row lines.
                log.info(
                        "Callback hint sweep: {} candidates, {} asked, {} concluded, {} expired",
                        result.candidates(),
                        result.asked(),
                        result.concluded(),
                        result.expired());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or provider message can name hosts and identifiers.
            log.warn("Callback hint sweep failed: {}", failure.getClass().getSimpleName());
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

    /** After the HTTP drain, before the consumers ({@link com.finapp.app.lifecycle.ShutdownOrder}). */
    @Override
    public int getPhase() {
        return com.finapp.app.lifecycle.ShutdownOrder.SCHEDULES;
    }

    /** The longest {@link #stop()} takes, below {@code spring.lifecycle.timeout-per-shutdown-phase} ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return drain.stopBound();
    }
}
