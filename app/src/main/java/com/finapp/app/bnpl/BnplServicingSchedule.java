package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplServicing;
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
 */
@Slf4j
public final class BnplServicingSchedule implements SmartLifecycle {

    private final BnplServicing servicing;
    private final BnplTransactions transactions;
    private final BnplServicingMetrics metrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration retryCadence;
    private ScheduledExecutorService executor;

    public BnplServicingSchedule(
            BnplServicing servicing,
            BnplTransactions transactions,
            BnplServicingMetrics metrics,
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
        this.servicing = Objects.requireNonNull(servicing, "servicing");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
                try {
                    ServicingOutcome step = transactions.inTransaction(uow -> servicing.service(uow, obligation));
                    metrics.record(step);
                } catch (RuntimeException failure) {
                    // The claim's cadence stands and a later tick steps again; the class name only - never a party.
                    metrics.failed();
                    log.warn("BNPL servicing step failed: {}", failure.getClass().getSimpleName());
                }
            }
            return due.size();
        }
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
