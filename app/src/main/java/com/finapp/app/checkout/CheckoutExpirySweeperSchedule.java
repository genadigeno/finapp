package com.finapp.app.checkout;

import com.finapp.app.telemetry.CheckoutMeters;
import com.finapp.checkout.CheckoutExpirySweeper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link CheckoutExpirySweeper#sweep()} on a fixed delay, on every instance
 * (`P6-TSK-008`) — the {@code PaymentSweeperSchedule} shape, on the same justification.
 *
 * <h2>Every instance polls, deliberately — no lease, no leader</h2>
 *
 * <p>{@code nothingSchedulesAmbiently} (ADR-0024) states its bar in two halves: a scheduled
 * financial process is <strong>idempotent per period</strong> or takes an explicit database
 * lease. The relay took the lease half; the payment sweeper was the first occupant of the
 * other, and this is the second — and the cleanest, because there is no external call at all.
 * Every write is a conditional transition whose losers converge ({@code INV-IDEM-02}), so N
 * schedules racing each other, a customer's confirmation and a late capture are the same
 * counted race. The register row is {@code DISTRIBUTED_EXECUTION.md} §3, beside the other two,
 * each naming its own justification.
 *
 * <h2>What this class must never become</h2>
 *
 * <p>A coordinator. It holds no state the sweeper depends on, and the executor is per-instance
 * mechanics whose loss costs this instance's ticks and nothing else. Any future scheduled work
 * that is neither idempotent-per-period nor lease-protected does not get to ride on this
 * exemption.
 *
 * <h2>The meter is read from the tick, after the rows committed</h2>
 *
 * <p>{@code SweepResult.expired()} counts this tick's own conditional transitions — the losers
 * of a race contributed to {@code skipped} and are not throughput ({@code P5-TSK-017}'s seam,
 * inherited). Counting here rather than inside the sweeper keeps {@code checkout} free of a
 * telemetry dependency and puts the increment strictly after the per-row transaction returned.
 *
 * <h2>A failed tick is logged and the schedule continues</h2>
 *
 * <p>The failure class only — never identifiers, amounts or what anybody was buying
 * ({@code INV-AUD-02}); per-row failures inside a successful tick are already the sweeper's own
 * anti-stall machinery.
 */
public final class CheckoutExpirySweeperSchedule implements SmartLifecycle {

    private static final Logger log =
            LoggerFactory.getLogger(CheckoutExpirySweeperSchedule.class);

    private final CheckoutExpirySweeper sweeper;
    private final CheckoutMeters meters;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public CheckoutExpirySweeperSchedule(
            CheckoutExpirySweeper sweeper, CheckoutMeters meters, Duration pollInterval) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
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
                            Thread thread = new Thread(runnable, "checkout-expiry-sweeper");
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
            CheckoutExpirySweeper.SweepResult result = sweeper.sweep();
            for (int i = 0; i < result.expired(); i++) {
                meters.session(CheckoutMeters.Outcome.EXPIRED);
            }
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweeper's own per-row lines.
                log.info(
                        "Checkout expiry sweep: {} candidates, {} expired, {} skipped, {} failed",
                        result.candidates(),
                        result.expired(),
                        result.skipped(),
                        result.failedRows());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts, identifiers and values.
            log.warn("Checkout expiry sweep failed: {}", failure.getClass().getSimpleName());
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
