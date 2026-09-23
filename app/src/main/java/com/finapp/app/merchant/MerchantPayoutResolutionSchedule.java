package com.finapp.app.merchant;

import com.finapp.app.telemetry.MerchantMeters;
import com.finapp.merchant.MerchantPayoutResolution;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link MerchantPayoutResolution#sweep()} on a fixed delay, on every instance
 * (`P6-TSK-012`) — the {@code PaymentSweeperSchedule} shape, on the same justification.
 *
 * <h2>Every instance polls, deliberately — no lease, no leader</h2>
 *
 * <p>{@code nothingSchedulesAmbiently} (ADR-0024) states its bar in two halves: a scheduled
 * financial process is <strong>idempotent per period</strong> or takes an explicit database
 * lease. This is the idempotent half: the provider query is read-only and idempotent by our
 * reference, and every write is a conditional transition on a locked row whose losers converge
 * ({@code INV-IDEM-02}) — N schedules, the synchronous answer and a takeover's re-send are one
 * counted race. The register row is {@code DISTRIBUTED_EXECUTION.md} §3.
 *
 * <h2>What this class must never become</h2>
 *
 * <p>A coordinator. It holds no state the sweep depends on, and the executor is per-instance
 * mechanics whose loss costs this instance's ticks and nothing else — a missed tick leaves an
 * ambiguous payout's hold standing one poll interval longer, visibly.
 *
 * <h2>A failed tick is logged and the schedule continues</h2>
 *
 * <p>The failure class only — never identifiers or provider bytes ({@code INV-AUD-02}); per-row
 * failures inside a successful tick are the sweep's own anti-stall machinery.
 */
@Slf4j
public final class MerchantPayoutResolutionSchedule implements SmartLifecycle {

    private final MerchantPayoutResolution resolution;
    private final MerchantMeters meters;
    private final Duration pollInterval;
    private ScheduledExecutorService executor;

    public MerchantPayoutResolutionSchedule(
            MerchantPayoutResolution resolution, MerchantMeters meters, Duration pollInterval) {
        this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
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
                            Thread thread = new Thread(runnable, "merchant-payout-resolution");
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
            MerchantPayoutResolution.SweepResult result = resolution.sweep();
            // The tick's OWN acting judgements, counted after their per-row transactions
            // committed (P6-TSK-013, the PaymentSweeperSchedule seam): a resolution racing a
            // sibling sweep or the synchronous answer is counted by whichever won, once.
            result.actingJudgements().forEach(meters::payoutJudged);
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the sweep's own per-row lines.
                log.info(
                        "Merchant payout resolution: {} candidates, {} resolved, {} skipped,"
                                + " {} failed",
                        result.candidates(),
                        result.resolved(),
                        result.skipped(),
                        result.failedRows());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts, identifiers and values.
            log.warn("Merchant payout resolution failed: {}", failure.getClass().getSimpleName());
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
