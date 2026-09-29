package com.finapp.app.settlement;

import com.finapp.settlement.FileParsing;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link FileParsing#sweep()} on a fixed delay, on every instance (`P8-TSK-008`) — the
 * {@code ReturnResolutionSchedule} shape: leaderless by design, the {@code FOR UPDATE SKIP
 * LOCKED} claim, the conditional {@code RECEIVED → PARSED} and the batch uniques arbitrate in
 * PostgreSQL, and a tick that finds nothing costs one bounded query. Registered in
 * {@code DISTRIBUTED_EXECUTION.md} §3 and
 * {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}, on the
 * conditional-transition half of the bar. Off in test contexts
 * ({@code finapp.settlement.intake.sweeper.enabled=false}); the database suites drive the
 * sweep directly.
 */
@Slf4j
public final class SettlementIntakeSchedule implements SmartLifecycle {

    private final FileParsing parsing;
    private final Duration pollInterval;

    private ScheduledExecutorService executor;

    public SettlementIntakeSchedule(FileParsing parsing, Duration pollInterval) {
        this.parsing = Objects.requireNonNull(parsing, "parsing must not be null");
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
                            Thread thread = new Thread(runnable, "settlement-intake");
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
            FileParsing.SweepResult result = parsing.sweep();
            if (result.candidates() > 0) {
                // Counts only - identifiers live in the leg's own per-file records.
                log.info(
                        "Settlement intake sweep: {} candidates, {} parsed, {} rejected,"
                                + " {} failed",
                        result.candidates(),
                        result.parsed(),
                        result.rejected(),
                        result.failed());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts and identifiers.
            log.warn("Settlement intake sweep failed: {}", failure.getClass().getSimpleName());
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
