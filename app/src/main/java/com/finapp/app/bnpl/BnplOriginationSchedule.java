package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProduct;
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
 * The BNPL origination sweep (`P12-TSK-011`; PHASE_12_PLAN.md sections 7.1 T-off and 7.5, BNPL_LIFECYCLES.md sections 4.2
 * and 4.3; lending's {@code LoanOriginationSchedule}): every application awaiting credit's decision is taken to its offer,
 * its decline or its undecided closure - a session that closed while it waited among them - and every offer past its
 * expiry is expired. Every instance runs it with no lease: each tick claims the due applications, then the due offers,
 * each in ONE statement on the database clock - the permit re-stamped, rows another sweeper holds skipped - and takes each
 * one's step in a transaction of its own under its row lock (B1 the application, B2 the offer), every edge a conditional
 * {@code bnpl V004}'s triggers re-judge; so ten sweepers on one application leave one offer
 * ({@code BnplOriginationDatabaseTest#tenSweepersOfferOnce}). A step that cannot act - credit has not decided, or the step
 * failed - lets its permit lapse and a later tick asks again; it never holds the page. Credit's and checkout's events only
 * pull a permit forward ({@link BnplOriginationHint}). Off in test contexts ({@code finapp.bnpl.origination.sweeper.enabled});
 * the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class BnplOriginationSchedule implements SmartLifecycle {

    private final BnplOrigination origination;
    private final BnplTransactions transactions;
    private final BnplOriginationMetrics metrics;
    private final Duration pollInterval;
    private final int batch;
    private final Duration permit;
    private ScheduledExecutorService executor;

    public BnplOriginationSchedule(
            BnplOrigination origination,
            BnplTransactions transactions,
            BnplOriginationMetrics metrics,
            Duration pollInterval,
            int batch,
            Duration permit) {
        // The configuration judged first - a misconfigured instance refuses to start whatever it was handed.
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.permit = Objects.requireNonNull(permit, "permit");
        if (pollInterval.isNegative() || pollInterval.isZero() || permit.isNegative() || permit.isZero()) {
            throw new IllegalArgumentException("the poll interval and the permit must be positive");
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
        this.origination = Objects.requireNonNull(origination, "origination");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
            List<BnplApplicationId> applications =
                    transactions.inTransaction(uow -> origination.claimApplications(uow, batch, permit));
            for (BnplApplicationId id : applications) {
                try {
                    BnplOrigination.Outcome step = transactions.inTransaction(
                            uow -> origination.originate(uow, id, SecurityContext.require()));
                    metrics.record(step);
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick steps again; the class name only - never a party.
                    metrics.failed(BnplOriginationMetrics.Step.APPLICATION);
                    log.warn("BNPL origination step failed: {}", failure.getClass().getSimpleName());
                }
            }
            List<BnplOfferId> offers = transactions.inTransaction(uow -> origination.claimOffers(uow, batch, permit));
            for (BnplOfferId id : offers) {
                try {
                    Optional<BnplProduct> expired = transactions.inTransaction(
                            uow -> origination.expire(uow, id, SecurityContext.require()));
                    expired.ifPresent(product -> metrics.record(product, BnplOriginationMetrics.Outcome.EXPIRED));
                } catch (RuntimeException failure) {
                    metrics.failed(BnplOriginationMetrics.Step.OFFER);
                    log.warn("BNPL offer expiry failed: {}", failure.getClass().getSimpleName());
                }
            }
            return applications.size() + offers.size();
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bnpl-origination");
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
            log.warn("BNPL origination sweep failed: {}", failure.getClass().getSimpleName());
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
