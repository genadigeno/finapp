package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplSpans;
import com.finapp.bnpl.ServicingOutcome;
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
 * The BNPL servicing sweep (`P12-TSK-015`; {@code PHASE_12_PLAN.md} sections 7.1 T-svc and 7.5; lending's
 * {@code LoanServicingSchedule}): every {@code ACTIVE} obligation is serviced once each business date - its instalments
 * due billed and money already held applied to them, and, as later tasks land, its accrual run first (T-acc, `-017`), its
 * collection, closure, condition and late fee. Every instance runs it with no lease: each tick claims the obligations whose
 * permit has come in ONE statement on the database clock - {@code FOR UPDATE SKIP LOCKED}, the permit re-stamped to the
 * retry cadence - and takes each one's step in a transaction of its own under B4, every instalment billed once by
 * {@code bnpl_billing UNIQUE (instalment_id)} and the ledger key {@code bnpl.billing:<instalment>}; so the same obligation
 * on two instances, or ten, bills each instalment once ({@code BnplBillingDatabaseTest#tenSweepersBillOnce}). A completed
 * step moves its permit to the start of the next business date in the pinned zone. A step that fails rolls back and keeps
 * the claim's cadence; it never holds the page - the next obligation is serviced regardless. Off in test contexts
 * ({@code finapp.bnpl.servicing.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 *
 * <p><strong>T-acc first</strong> (`P12-TSK-017`): before each obligation's step the sweep runs its accrual in a transaction
 * of its own - {@link BnplAccruals#accrue}, B4 and the database clock, every elapsed date accrued oldest first, at most
 * {@link BnplAccruals#MAX_ACCRUALS_PER_STEP} dates a call and at most {@link #MAX_ACCRUAL_CALLS} calls - so T-svc, which
 * never accrues inside its own transaction, finds the record caught up and no transaction holding the currency's
 * {@code BNPL_INTEREST_INCOME} row ever waits on a wallet. A step that still answers {@code BEHIND_ACCRUAL} (a midnight passed
 * between the two transactions) has T-acc run once more and is asked once more - never a loop. A T-acc call that fails rolls
 * back alone: its obligation's step is not taken this tick, the claim's cadence retries it, and the page continues.
 */
@Slf4j
public final class BnplServicingSchedule implements SmartLifecycle {

    /** The most T-acc calls before one step: 8 x 92 dates - more than any pay-in-N accrual span. */
    static final int MAX_ACCRUAL_CALLS = 8;

    private final BnplServicing servicing;
    private final BnplAccruals accruals;
    private final BnplTransactions transactions;
    private final BnplServicingMetrics metrics;
    private final BnplAccrualMetrics accrualMetrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration retryCadence;
    private final BnplFlowScope flow;
    private ScheduledExecutorService executor;

    public BnplServicingSchedule(
            BnplServicing servicing,
            BnplAccruals accruals,
            BnplTransactions transactions,
            BnplServicingMetrics metrics,
            BnplAccrualMetrics accrualMetrics,
            Duration pollInterval,
            int batch,
            Duration retryCadence) {
        this(servicing, accruals, transactions, metrics, accrualMetrics, pollInterval, batch, retryCadence,
                BnplFlowScope.NONE);
    }

    /** With the step's span, each leg in its obligation's stored correlation (`P12-TSK-026`). */
    public BnplServicingSchedule(
            BnplServicing servicing,
            BnplAccruals accruals,
            BnplTransactions transactions,
            BnplServicingMetrics metrics,
            BnplAccrualMetrics accrualMetrics,
            Duration pollInterval,
            int batch,
            Duration retryCadence,
            BnplFlowScope flow) {
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
        this.servicing = Objects.requireNonNull(servicing, "servicing");
        this.accruals = Objects.requireNonNull(accruals, "accruals");
        this.accrualMetrics = Objects.requireNonNull(accrualMetrics, "accrualMetrics");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * One sweep under the system actor: the due obligations claimed and each one's step taken. Each step's meter is
     * recorded after its transaction committed.
     *
     * @return how many obligations this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<BnplObligationId> due = transactions.inTransaction(uow -> servicing.claim(uow, batch, retryCadence));
            for (BnplObligationId obligation : due) {
                flow.forObligation(BnplSpans.SERVICE, obligation, () -> {
                    step(obligation);
                    return null;
                });
            }
            return due.size();
        }
    }

    /** One obligation's T-acc and T-svc, its meters after each commit; a failure counted and logged, never thrown. */
    private void step(BnplObligationId obligation) {
        try {
            // T-acc first, in transactions of its own; a failed accrual leaves the step to the claim's cadence.
            if (!accruedFirst(obligation)) {
                return;
            }
            ServicingOutcome step = transactions.inTransaction(uow -> servicing.service(uow, obligation));
            if (step.kind() == ServicingOutcome.Kind.BEHIND_ACCRUAL && accruedFirst(obligation)) {
                // A midnight passed between T-acc and the step: T-acc once more, the step once more - never a loop.
                step = transactions.inTransaction(uow -> servicing.service(uow, obligation));
            }
            metrics.record(step);
        } catch (RuntimeException failure) {
            // The claim's cadence stands and a later tick steps again; the class name only - never a party.
            metrics.failed(failure);
            log.warn("BNPL servicing step failed: {}", failure.getClass().getSimpleName());
        }
    }

    /**
     * T-acc for {@code obligation} until its record is caught up - each call its own transaction, its dates counted after
     * it committed - at most {@link #MAX_ACCRUAL_CALLS} calls; whether no call failed.
     */
    private boolean accruedFirst(BnplObligationId obligation) {
        for (int call = 0; call < MAX_ACCRUAL_CALLS; call++) {
            BnplAccruals.Outcome accrued;
            try {
                accrued = transactions.inTransaction(uow -> accruals.accrue(uow, obligation));
            } catch (RuntimeException failure) {
                // Rolled back alone; the claim's cadence retries. The class name only - never a party.
                accrualMetrics.failed();
                log.warn("BNPL accrual failed: {}", failure.getClass().getSimpleName());
                return false;
            }
            accrualMetrics.record(accrued);
            if (accrued.caughtUp()) {
                return true;
            }
        }
        // Still behind after the bound: the step answers BEHIND_ACCRUAL and the claim's cadence continues the catch-up.
        return true;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bnpl-servicing");
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
            log.warn("BNPL servicing sweep failed: {}", failure.getClass().getSimpleName());
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
