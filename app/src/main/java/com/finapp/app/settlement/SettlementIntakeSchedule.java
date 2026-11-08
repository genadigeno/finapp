package com.finapp.app.settlement;

import com.finapp.app.lifecycle.DrainedSchedule;
import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the intake's two legs on a fixed delay, on every instance — parse (`P8-TSK-008`,
 * {@link FileParsing#sweep()}) then accept (`P8-TSK-009`, {@link BatchAcceptance#sweep()}) —
 * the {@code ReturnResolutionSchedule} shape: leaderless by design, each leg's
 * {@code FOR UPDATE SKIP LOCKED} claim, conditional edge and uniques arbitrate in
 * PostgreSQL, and a tick that finds nothing costs two bounded queries. Registered in
 * {@code DISTRIBUTED_EXECUTION.md} §3 and
 * {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}, on the
 * conditional-transition half of the bar. Off in test contexts
 * ({@code finapp.settlement.intake.sweeper.enabled=false}); the database suites drive the
 * sweeps directly.
 */
@Slf4j
public final class SettlementIntakeSchedule implements DrainedSchedule {

    private final FileParsing parsing;
    private final BatchAcceptance acceptance;
    private final Duration pollInterval;

    private final ScheduleDrain drain = new ScheduleDrain("settlement-intake");

    public SettlementIntakeSchedule(
            FileParsing parsing, BatchAcceptance acceptance, Duration pollInterval) {
        this.parsing = Objects.requireNonNull(parsing, "parsing must not be null");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance must not be null");
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
            FileParsing.SweepResult parsed = parsing.sweep();
            if (parsed.candidates() > 0) {
                // Counts only - identifiers live in the leg's own per-file records.
                log.info(
                        "Settlement parse sweep: {} candidates, {} parsed, {} rejected,"
                                + " {} failed",
                        parsed.candidates(),
                        parsed.parsed(),
                        parsed.rejected(),
                        parsed.failed());
            }
        } catch (RuntimeException failure) {
            // The class only: a JDBC message can name hosts and identifiers.
            log.warn("Settlement parse sweep failed: {}", failure.getClass().getSimpleName());
        }
        try {
            BatchAcceptance.SweepResult accepted = acceptance.sweep();
            if (accepted.candidates() > 0) {
                log.info(
                        "Settlement accept sweep: {} candidates, {} accepted, {} rejected,"
                                + " {} failed",
                        accepted.candidates(),
                        accepted.accepted(),
                        accepted.rejected(),
                        accepted.failed());
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "Settlement accept sweep failed: {}",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public ScheduleDrain drain() {
        return drain;
    }
}
