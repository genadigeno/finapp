package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.lending.LoanPayoutStore;
import com.finapp.lending.LoanPayoutSteps;
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
 * The payout sweep, path X (`P11-TSK-015`; PHASE_11_PLAN.md sections 7.1 T-pyo / T-pyr and 7.5, LENDING_LIFECYCLES.md
 * section 4.7, ADR-0097): every disbursed loan's external payout is dispatched once through payments and concluded from
 * payments' word. Every instance runs it with no lease: each tick claims the due payouts in ONE statement on the database
 * clock - {@code PENDING} ones moved to {@code DISPATCHING}, lapsed {@code DISPATCHING} ones re-driven, {@code DISPATCHED}
 * ones due an observation - each permit re-stamped and rows another sweeper holds skipped; the claim commits BEFORE
 * payments is called, and each payout's step acts only on the permit its claim stamped. So ten sweepers make one
 * withdrawal ({@code LoanPayoutDatabaseTest}, scenario 1's payout half), a crash anywhere re-drives under the same
 * instruction, and a withdrawal payments holds {@code UNKNOWN} is observed, never re-sent (scenario 5). A failed step lets
 * its permit lapse; it never holds the page. Off in test contexts ({@code finapp.lending.payout.sweeper.enabled}); the
 * suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class LoanPayoutSchedule implements SmartLifecycle {

    private final LoanPayoutSteps steps;
    private final LendingTransactions transactions;
    private final LoanPayoutMetrics metrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration permit;
    private final LendingFlowScope flow;
    private ScheduledExecutorService executor;

    public LoanPayoutSchedule(
            LoanPayoutSteps steps,
            LendingTransactions transactions,
            LoanPayoutMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit) {
        this(steps, transactions, metrics, pollInterval, batch, permit, LendingFlowScope.NONE);
    }

    /** As above, each step's span recorded in its flow's stored correlation (P11-TSK-030). */
    public LoanPayoutSchedule(
            LoanPayoutSteps steps,
            LendingTransactions transactions,
            LoanPayoutMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit,
            LendingFlowScope flow) {
        this.flow = java.util.Objects.requireNonNull(flow, "flow");
        this.steps = Objects.requireNonNull(steps, "steps");
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
     * One sweep under the system actor: the due payouts claimed (committed) and each one's step taken, every effect
     * metered after its transaction committed.
     *
     * @return how many payouts this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<LoanPayoutStore.Claimed> due = transactions.inTransaction(uow -> steps.claim(uow, batch, permit));
            for (LoanPayoutStore.Claimed claimed : due) {
                try {
                    flow.forLoan(LendingSpans.PAYOUT, claimed.loan(),
                            () -> steps.step(transactions, claimed, SecurityContext.require())).forEach(metrics::record);
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick re-drives under the same instruction; the class name only.
                    log.warn("Loan payout step failed: {}", failure.getClass().getSimpleName());
                }
            }
            return due.size();
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lending-payout");
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
            log.warn("Loan payout sweep failed: {}", failure.getClass().getSimpleName());
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
