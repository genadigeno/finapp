package com.finapp.app.bnpl;

import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplDisputeId;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The BNPL dispute deadline sweep (`P12-TSK-023`; ADR-0106 decisions 4 and 9, OQ-10 - the merchant's silence is
 * acceptance; {@code PHASE_12_PLAN.md} section 7.5): every instance runs it with no lease. Each tick claims, in ONE
 * statement on the database clock in a transaction of its own that commits at once, the {@code OPEN} disputes whose response
 * deadline and permit have come ({@code FOR UPDATE SKIP LOCKED}, the permit re-stamped to the retry cadence) - a
 * database-stamped permit that holds no row - then takes each one's step in a transaction of its own: B5 the purchase, B4
 * the obligation, B6 the dispute, {@code OPEN} and the deadline re-judged on the database clock after the lock, and the
 * refund outcome through T-ref's body ({@link BnplDisputes#lapse}). A merchant's answer or a withdrawal committed first
 * leaves the step nothing; ten sweepers resolve a lapsed dispute once ({@code bnpl_refund UNIQUE (dispute_id)}, the ledger
 * key {@code bnpl.refund:<refund>}). A pay-in-N refund behind its accrual writes nothing - T-acc runs in its own transaction
 * and the step once more, never a loop; a failing step rolls back, keeps the claim's cadence and never holds the page. Off in
 * test contexts ({@code finapp.bnpl.dispute.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class BnplDisputeSchedule implements SmartLifecycle {

    /** The most T-acc calls before one step. */
    static final int MAX_ACCRUAL_CALLS = 8;

    private final BnplDisputes disputes;
    private final BnplAccruals accruals;
    private final BnplTransactions transactions;
    private final BnplDisputeMetrics metrics;
    private final BnplRefundMetrics refundMetrics;
    private final IdGenerator ids;
    private final Duration pollInterval;
    private final int batch;
    private final Duration retryCadence;
    private ScheduledExecutorService executor;

    public BnplDisputeSchedule(
            BnplDisputes disputes,
            BnplAccruals accruals,
            BnplTransactions transactions,
            BnplDisputeMetrics metrics,
            BnplRefundMetrics refundMetrics,
            IdGenerator ids,
            Duration pollInterval,
            int batch,
            Duration retryCadence) {
        // The configuration judged first - a misconfigured instance refuses to start whatever it was handed.
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.retryCadence = Objects.requireNonNull(retryCadence, "retryCadence");
        if (pollInterval.isNegative() || pollInterval.isZero() || retryCadence.isNegative() || retryCadence.isZero()) {
            throw new IllegalArgumentException("the poll interval and the retry cadence must be positive");
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
        this.disputes = Objects.requireNonNull(disputes, "disputes");
        this.accruals = Objects.requireNonNull(accruals, "accruals");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.refundMetrics = Objects.requireNonNull(refundMetrics, "refundMetrics");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * One sweep under the system actor: the lapsed disputes claimed and each one's step taken, its meter counted after its
     * transaction committed.
     *
     * @return how many disputes this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem()) {
            Actor system = SecurityContext.require();
            List<BnplDisputeId> due = transactions.inTransaction(uow -> disputes.claim(uow, batch, retryCadence));
            for (BnplDisputeId dispute : due) {
                CorrelationId correlation = CorrelationId.generate(ids);
                try {
                    Optional<BnplDisputes.Acted> lapsed = lapsed(dispute, system, correlation);
                    lapsed.ifPresent(acted -> {
                        metrics.record(BnplDisputeMetrics.Outcome.of(acted.outcome()));
                        acted.refund().ifPresent(refunded -> refundMetrics.record(refunded.kind(),
                                refunded.closure().isPresent() ? BnplRefundMetrics.Outcome.CLOSED
                                        : BnplRefundMetrics.Outcome.APPLIED));
                    });
                } catch (RuntimeException failure) {
                    // The claim's cadence stands and a later tick steps again; the class name only - never a party.
                    if (failure instanceof AllocationAnomaly) {
                        refundMetrics.anomaly();
                    }
                    metrics.record(failure instanceof BnplRefunds.RefundNotPostable
                            ? BnplDisputeMetrics.Outcome.NOT_POSTABLE : BnplDisputeMetrics.Outcome.SWEEP_FAILED);
                    log.warn("BNPL dispute deadline step failed: {}", failure.getClass().getSimpleName());
                }
            }
            return due.size();
        }
    }

    /** The step, T-acc first in its own transaction whenever the refund outcome finds the record behind. */
    private Optional<BnplDisputes.Acted> lapsed(BnplDisputeId dispute, Actor system, CorrelationId correlation) {
        for (int call = 0; ; call++) {
            try {
                return transactions.inTransaction(uow -> disputes.lapse(uow, dispute, system, correlation));
            } catch (BnplRefunds.BehindAccrual behind) {
                if (call >= MAX_ACCRUAL_CALLS) {
                    throw behind;
                }
                transactions.inTransaction(uow -> accruals.accrue(uow, behind.obligation()));
            }
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bnpl-dispute-deadline");
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
            log.warn("BNPL dispute deadline sweep failed: {}", failure.getClass().getSimpleName());
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
