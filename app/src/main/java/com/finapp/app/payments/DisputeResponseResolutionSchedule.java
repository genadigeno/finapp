package com.finapp.app.payments;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.TickPhase;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.app.telemetry.SweepMeters;
import com.finapp.payments.DisputeResponseResolution;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link DisputeResponseResolution#sweep()} on a fixed delay, on every instance
 * (`P7-TSK-014`) — the {@code ReturnResolutionSchedule} shape: leaderless by design, the
 * database's conditional permit renewal and transitions arbitrate, and a tick that finds nothing
 * costs one bounded query.
 */
@Slf4j
public final class DisputeResponseResolutionSchedule implements SmartLifecycle {

    /**
     * The sweep's dispatched bound, as ONE placeholder (`P7-TSK-015`): the resolution sweep and
     * the stuck-dispute-response gauge both read it, so they can never disagree about when an
     * answer was due.
     */
    public static final String DISPATCHED_AGE =
            "${finapp.payments.dispute-response.sweeper.dispatched-age:PT2M}";

    private final DisputeResponseResolution resolution;
    private final Duration pollInterval;

    /** The dispute answer sweep's failure counters (`P16-TSK-017`): a tick failing whole, and the tally's unreadable and failed rows. */
    private final SweepMeters.Sweep meters;

    private final ScheduleDrain drain = new ScheduleDrain("dispute-response-resolution");

    public DisputeResponseResolutionSchedule(
            DisputeResponseResolution resolution, Duration pollInterval, SweepMeters.Sweep meters) {
        this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
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
                TickPhase.firstDelayMillis(pollInterval),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void sweepQuietly() {
        try {
            DisputeResponseResolution.SweepResult result = resolution.sweep();
            meters.rows(result.unreadable(), result.failedRows());
            if (result.candidates() + result.unreadable() > 0) {
                // Counts only - identifiers live in the sweep's own per-row lines.
                log.info(
                        "Dispute response sweep: {} candidates, {} applied, {} skipped",
                        result.candidates(),
                        result.applied(),
                        result.skipped());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or PSP message can name hosts and identifiers.
            meters.tickFailed();
            log.warn("Dispute response sweep failed: {}", failure.getClass().getSimpleName());
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
