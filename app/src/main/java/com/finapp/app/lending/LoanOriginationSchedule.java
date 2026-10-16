package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.lending.LoanApplicationId;
import com.finapp.lending.LoanOfferId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.platform.security.SecurityContext;
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
 * The origination sweep (`P11-TSK-011`; PHASE_11_PLAN.md sections 7.1 T-off and 7.5, LENDING_LIFECYCLES.md sections 4.3
 * and 4.4): every application awaiting credit's decision is taken to its offer, its decline or its undecided closure, and
 * every offer past its expiry is expired. Every instance runs it with no lease: each tick claims the due applications,
 * then the due offers, each in ONE statement on the database clock - the permit re-stamped, rows another sweeper holds
 * skipped - and takes each one's step in a transaction of its own under its row lock, every edge a conditional
 * {@code lending V005}'s triggers re-judge; so ten sweepers on one application leave one offer
 * ({@code LoanOriginationDatabaseTest}). A step that cannot act - credit has not decided, or the step failed - lets its
 * permit lapse and a later tick asks again; it never holds the page. Credit's events only pull a permit forward
 * ({@link LoanOriginationHint}). Off in test contexts ({@code finapp.lending.origination.sweeper.enabled}); the suites
 * drive {@link #sweepOnce()}.
 */
@Slf4j
public final class LoanOriginationSchedule implements SmartLifecycle {

    private final LoanOrigination origination;
    private final LendingTransactions transactions;
    private final LoanOfferMetrics metrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration permit;
    private final LendingFlowScope flow;
    private ScheduledExecutorService executor;

    public LoanOriginationSchedule(
            LoanOrigination origination,
            LendingTransactions transactions,
            LoanOfferMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit) {
        this(origination, transactions, metrics, pollInterval, batch, permit, LendingFlowScope.NONE);
    }

    /** As above, each step's span recorded in its flow's stored correlation (P11-TSK-030). */
    public LoanOriginationSchedule(
            LoanOrigination origination,
            LendingTransactions transactions,
            LoanOfferMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit,
            LendingFlowScope flow) {
        this.flow = java.util.Objects.requireNonNull(flow, "flow");
        this.origination = Objects.requireNonNull(origination, "origination");
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
     * One sweep under the system actor: the due applications claimed and each one's step taken, then the due offers
     * claimed and each one's expiry judged. Each step's meter is recorded after its transaction committed.
     *
     * @return how many applications and offers this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<LoanApplicationId> applications =
                    transactions.inTransaction(uow -> origination.claimApplications(uow, batch, permit));
            for (LoanApplicationId id : applications) {
                try {
                    LoanOrigination.Outcome step = flow.forApplication(LendingSpans.OFFER, id, () -> transactions.inTransaction(
                            uow -> origination.originate(uow, id, SecurityContext.require())));
                    metrics.record(step);
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick steps again; the class name only - never a party.
                    metrics.failed(LoanOfferMetrics.Step.APPLICATION);
                    log.warn("Loan origination step failed: {}", failure.getClass().getSimpleName());
                }
            }
            List<LoanOfferId> offers = transactions.inTransaction(uow -> origination.claimOffers(uow, batch, permit));
            for (LoanOfferId id : offers) {
                try {
                    Optional<LoanProduct> expired = flow.forOffer(LendingSpans.EXPIRE, id,
                            () -> transactions.inTransaction(uow -> origination.expire(uow, id, SecurityContext.require())));
                    expired.ifPresent(product -> metrics.record(product, LoanOfferMetrics.Outcome.EXPIRED));
                } catch (RuntimeException failure) {
                    metrics.failed(LoanOfferMetrics.Step.OFFER);
                    log.warn("Loan offer expiry failed: {}", failure.getClass().getSimpleName());
                }
            }
            return applications.size() + offers.size();
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lending-origination");
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
            log.warn("Loan origination sweep failed: {}", failure.getClass().getSimpleName());
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
