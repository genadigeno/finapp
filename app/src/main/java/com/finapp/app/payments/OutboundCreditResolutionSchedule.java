package com.finapp.app.payments;

import com.finapp.payments.OutboundCreditResolution;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link OutboundCreditResolution#sweep()} on a fixed delay, on every instance (`P9-TSK-020`) - the
 * {@link WithdrawalResolutionSchedule} shape: leaderless by design, the locked row's acting conditional, the
 * claim's primary key and the posting key arbitrate, and a tick that finds nothing costs one bounded query.
 */
@Slf4j
public final class OutboundCreditResolutionSchedule implements SmartLifecycle {

    /**
     * The sweep's dispatched bound, as ONE placeholder: the sweep asks nothing about a younger dispatch - a live
     * flight's own send - and the stuck-credit gauge counts a dispatched credit only past it.
     */
    public static final String DISPATCHED_AGE = "${finapp.payments.outbound.sweeper.dispatched-age:PT2M}";

    private final OutboundCreditResolution resolution;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public OutboundCreditResolutionSchedule(OutboundCreditResolution resolution, Duration pollInterval) {
        this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbound-credit-resolution");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sweepQuietly() {
        try {
            OutboundCreditResolution.SweepResult result = resolution.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweep's own per-row lines.
                log.info("Outbound credit sweep: {} candidates, {} resolved ({})", result.candidates(), result.resolved(),
                        result.actingJudgements());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC or provider message can name hosts and identifiers.
            log.warn("Outbound credit sweep failed: {}", failure.getClass().getSimpleName());
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
