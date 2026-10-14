package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanId;
import com.finapp.platform.security.SecurityContext;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The disbursement sweep (`P11-TSK-014`; PHASE_11_PLAN.md sections 7.1 T-dis and 7.5, LENDING_LIFECYCLES.md sections 4.5
 * and 4.6, ADR-0097): every instalment loan accepted for a wallet payout is disbursed once - or cancelled, its standing
 * lost or its wallet not postable by the deadline. Every instance runs it with no lease: each tick claims the due wallet
 * disbursements in ONE statement on the database clock - the permit re-stamped, rows another sweeper holds skipped - and
 * takes each one's step in a transaction of its own under L0, L3 and L5, every edge a conditional {@code lending V008}'s
 * triggers re-judge, the money under the ledger key {@code lending.disbursement:<loan>}; so the same disbursement on two
 * instances, or ten, posts once ({@code LoanDisbursementDatabaseTest}, scenario 1). A step that cannot post - the wallet
 * not to be had or not postable - writes nothing and lets its permit lapse: the retry cadence is the permit, until the
 * deadline. A step that failed is retried the same way; it never holds the page. Off in test contexts
 * ({@code finapp.lending.disbursement.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class LoanDisbursementSchedule implements SmartLifecycle {

    private final LoanDisbursements disbursements;
    private final LendingTransactions transactions;
    private final LoanDisbursementMetrics metrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration permit;
    private final LendingFlowScope flow;
    private ScheduledExecutorService executor;

    public LoanDisbursementSchedule(
            LoanDisbursements disbursements,
            LendingTransactions transactions,
            LoanDisbursementMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit) {
        this(disbursements, transactions, metrics, pollInterval, batch, permit, LendingFlowScope.NONE);
    }

    /** As above, each step's span recorded in its flow's stored correlation (P11-TSK-030). */
    public LoanDisbursementSchedule(
            LoanDisbursements disbursements,
            LendingTransactions transactions,
            LoanDisbursementMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit,
            LendingFlowScope flow) {
        this.flow = java.util.Objects.requireNonNull(flow, "flow");
        this.disbursements = Objects.requireNonNull(disbursements, "disbursements");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.permit = Objects.requireNonNull(permit, "permit");
        if (pollInterval.isNegative() || pollInterval.isZero() || permit.isNegative() || permit.isZero()) {
            throw new IllegalArgumentException("the poll interval and the permit must be positive");
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
    }

    /**
     * One sweep under the system actor: the due wallet disbursements claimed and each one's step taken. Each step's meter
     * is recorded after its transaction committed.
     *
     * @return how many disbursements this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<LoanId> due = transactions.inTransaction(uow -> disbursements.claim(uow, batch, permit));
            for (LoanId loan : due) {
                try {
                    LoanDisbursements.Outcome step = flow.forLoan(LendingSpans.DISBURSE, loan, () -> transactions.inTransaction(
                            uow -> disbursements.disburse(uow, loan, SecurityContext.require())));
                    metrics.record(step);
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick steps again; the class name only - never a party.
                    log.warn("Loan disbursement step failed: {}", failure.getClass().getSimpleName());
                }
            }
            return due.size();
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lending-disbursement");
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
            log.warn("Loan disbursement sweep failed: {}", failure.getClass().getSimpleName());
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
