package com.finapp.app.fx;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.fx.ReferenceRateFetch;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The reference-rate fetch's schedule (`P9-TSK-005`, ADR-0075 §1) - <strong>leaderless on every
 * instance</strong>, no lease and no leader: {@code fx.rate_fetch_permit} paces the herd to one
 * attempt per window (the database stamps it), and the snapshot's unique and newer-than-latest
 * trigger decide what is stored, so N schedules fetching at once store each observation once.
 * Registered in {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS} with its
 * argument, and in {@code DISTRIBUTED_EXECUTION.md} section 3.
 */
@Slf4j
public final class FxRateFetchSchedule implements SmartLifecycle {

    private final ReferenceRateFetch fetch;
    private final FxRateMetrics metrics;
    private final Duration pollInterval;
    private final ScheduleDrain drain = new ScheduleDrain("fx-rate-fetch");

    public FxRateFetchSchedule(
            ReferenceRateFetch fetch, FxRateMetrics metrics, Duration pollInterval) {
        this.fetch = Objects.requireNonNull(fetch, "fetch must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
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
                this::fetchQuietly,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void fetchQuietly() {
        try {
            ReferenceRateFetch.Result result = fetch.fetchOnce();
            metrics.recorded(result);
            result.failure()
                    .ifPresent(
                            failure ->
                                    // An outcome name only - never a URL, a key or a rate.
                                    log.warn("Reference rate fetch failed: {}", failure));
        } catch (RuntimeException failure) {
            metrics.crashed();
            log.warn("Reference rate fetch crashed: {}", failure.getClass().getSimpleName());
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
