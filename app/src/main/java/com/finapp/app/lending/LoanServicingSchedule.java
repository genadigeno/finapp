package com.finapp.app.lending;

import com.finapp.lending.LoanId;
import com.finapp.lending.LoanServicing;
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
 * The servicing sweep (`P11-TSK-016`; PHASE_11_PLAN.md sections 7.1 T-svc and 7.5, ADR-0094): every account in servicing
 * is serviced once each business date - its elapsed dates accrued (and, as later tasks land, its instalments billed, its
 * statement produced, its condition evaluated). Every instance runs it with no lease: each tick claims the accounts whose
 * permit has come in ONE statement on the database clock - the permit re-stamped to the retry cadence, rows another
 * sweeper or a servicing act holds skipped - and takes each one's step in a transaction of its own under L3, every date
 * born once by {@code interest_accrual UNIQUE (loan_id, accrual_date)} and posted under the ledger key
 * {@code lending.accrual:<loan>:<date>}; so the same account on two instances, or ten, accrues each date once
 * ({@code InterestAccrualDatabaseTest}, scenario 4). A completed step moves its permit to the start of the next business
 * date in the pinned zone. A step that fails rolls back and keeps the claim's cadence; it never holds the page - the next
 * account is serviced regardless (the starvation lesson). Off in test contexts
 * ({@code finapp.lending.servicing.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class LoanServicingSchedule implements SmartLifecycle {

    private final LoanServicing servicing;
    private final LendingTransactions transactions;
    private final LoanServicingMetrics metrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration retryCadence;
    private ScheduledExecutorService executor;

    public LoanServicingSchedule(
            LoanServicing servicing,
            LendingTransactions transactions,
            LoanServicingMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration retryCadence) {
        this.servicing = Objects.requireNonNull(servicing, "servicing");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.retryCadence = Objects.requireNonNull(retryCadence, "retryCadence");
        if (pollInterval.isNegative() || pollInterval.isZero() || retryCadence.isNegative() || retryCadence.isZero()) {
            throw new IllegalArgumentException("the poll interval and the retry cadence must be positive");
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
    }

    /**
     * One sweep under the system actor: the due accounts claimed and each one's step taken. Each step's meter is recorded
     * after its transaction committed.
     *
     * @return how many accounts this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<LoanId> due = transactions.inTransaction(uow -> servicing.claim(uow, batch, retryCadence));
            for (LoanId loan : due) {
                try {
                    LoanServicing.Outcome step = transactions.inTransaction(uow -> servicing.service(uow, loan));
                    metrics.record(step);
                } catch (RuntimeException failure) {
                    // The claim's cadence stands and a later tick steps again; the class name only - never a party.
                    metrics.failed();
                    log.warn("Loan servicing step failed: {}", failure.getClass().getSimpleName());
                }
            }
            return due.size();
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lending-servicing");
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
            log.warn("Loan servicing sweep failed: {}", failure.getClass().getSimpleName());
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
