package com.finapp.app.risk;

import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.MonitoringIntake;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.RiskSpans;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The risk monitoring sweep (`P13-TSK-019`; {@code PHASE_13_PLAN.md} sections 7.1 T-mon, 7.5, 12.7;
 * {@code RISK_LIFECYCLES.md} section 4.11; bnpl's {@code BnplOriginationSchedule}) - the intake leg, created here;
 * `-007` adds the profile leg and `-022` the rescreening leg. Every instance runs it with no lease and no leader: each
 * tick claims the ledger journal's watermark row {@code FOR UPDATE SKIP LOCKED} when its permit has come (the Phase 13
 * lock order's R7), reads the entries after it through the port within the settle horizon, inserts their monitored
 * transactions born once and advances the watermark - one batch, one transaction, holding no money row - and goes on
 * while a batch comes back full, up to a bounded number of batches per tick; so ten sweepers on one watermark ingest
 * every entry once ({@code MonitoringIntakeDatabaseTest#tenSweepersIngestOnce}). A batch that fails rolls back whole,
 * is counted ({@code finapp.risk.sweeper.claim.failed{kind=monitoring}}) and leaves the permit for a later tick.
 * Completion events only pull the permit forward (`-005`'s hint). Off in test contexts
 * ({@code finapp.risk.monitoring.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class RiskMonitoringSchedule implements SmartLifecycle {

    private final MonitoringIntake intake;
    private final RiskTransactions transactions;
    private final RiskMonitoringMetrics metrics;
    private final RiskFlowScope flow;
    private final Duration pollInterval;
    private final int batchesPerTick;
    private ScheduledExecutorService executor;

    /**
     * @param pollInterval the tick
     * @param batchesPerTick the most batches one tick takes while they come back full - the drain's bound, so a tick
     *     behind a long outage never holds the watermark for the whole backlog
     */
    public RiskMonitoringSchedule(MonitoringIntake intake, RiskTransactions transactions, RiskMonitoringMetrics metrics,
            RiskFlowScope flow, Duration pollInterval, int batchesPerTick) {
        // The configuration judged first - a misconfigured instance refuses to start whatever it was handed.
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("the poll interval must be positive");
        }
        if (batchesPerTick < 1) {
            throw new IllegalArgumentException("batchesPerTick must be positive: " + batchesPerTick);
        }
        this.batchesPerTick = batchesPerTick;
        this.intake = Objects.requireNonNull(intake, "intake");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * One sweep under the system actor: batches of the intake leg while they come back full, each in a transaction of
     * its own; a failed batch counted and the sweep ended for this tick.
     *
     * @return how many monitored transactions this call bore
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            int born = 0;
            for (int taken = 0; taken < batchesPerTick; taken++) {
                MonitoringIntake.Batch batch;
                try {
                    batch = flow.sweep(RiskSpans.MONITORING_INTAKE, () -> transactions.inTransaction(
                            uow -> intake.sweepBatch(uow, MonitoringWatermark.LEDGER_JOURNAL)));
                } catch (RuntimeException failure) {
                    // The batch rolled back whole; the permit is untouched and a later tick claims again. The class
                    // name only - never a position, an entry or a subject.
                    metrics.claimFailed();
                    log.warn("Risk monitoring intake batch failed: {}", failure.getClass().getSimpleName());
                    break;
                }
                if (!batch.claimed()) {
                    break;
                }
                born += batch.born();
                if (!batch.full()) {
                    break;
                }
            }
            return born;
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "risk-monitoring");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("Risk monitoring sweep failed: {}", failure.getClass().getSimpleName());
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
