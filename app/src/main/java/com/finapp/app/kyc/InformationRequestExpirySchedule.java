package com.finapp.app.kyc;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.kyc.InformationRequests;
import com.finapp.kyc.TransactionRunner;
import com.finapp.platform.security.SecurityContext;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * A reviewer's request for information lapses as an event (`P17-TSK-126`, ADR-0155 decision 7; ADR-0075 section 5's pattern,
 * {@code FxQuoteExpirySchedule}'s shape). Every instance runs it with no lease: each page is one transaction whose conditional
 * {@code UPDATE} - judged on {@code statement_timestamp()}, rows another sweeper or an answer holds skipped - closes each lapsed
 * request once, returns its case to {@code IN_REVIEW} and writes its one {@code kyc.InformationRequestExpired}; ten sweepers
 * take ten pages of distinct rows. The instance's own clock decides nothing about the deadline. After each page commits, each
 * returned case's readiness is re-asked (ADR-0155 decision 8).
 *
 * <p>Off in test contexts ({@code finapp.kyc.information-request.expiry.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class InformationRequestExpirySchedule implements SmartLifecycle {

    /** The pages one tick may take before yielding to the next tick. */
    static final int MAX_PAGES = 20;

    private final InformationRequests requests;
    private final InformationRequestDesk desk;
    private final TransactionRunner transactions;
    private final Duration pollInterval;
    private final int pageSize;
    private final ScheduleDrain drain = new ScheduleDrain("kyc-information-request-expiry");

    public InformationRequestExpirySchedule(
            InformationRequests requests,
            InformationRequestDesk desk,
            TransactionRunner transactions,
            Duration pollInterval,
            int pageSize) {
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
        this.desk = Objects.requireNonNull(desk, "desk must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be 1..1000: " + pageSize);
        }
        this.pageSize = pageSize;
    }

    /**
     * Expires every lapsed request this tick can reach, page by page, each page its own transaction, each returned case's
     * readiness re-asked after its page commits.
     *
     * @return how many cases this call returned to review
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        int total = 0;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            for (int page = 0; page < MAX_PAGES && !drain.stopping(); page++) {
                InformationRequests.ExpiredPage expired = transactions.inTransaction(
                        unitOfWork -> requests.expirePage(unitOfWork, pageSize, SecurityContext.require()));
                desk.reaskReadiness(expired.returned());
                total += expired.returned().size();
                if (expired.expired() < pageSize) {
                    break;
                }
            }
        }
        return total;
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            // The next tick retries; the class name only - a JDBC message can carry values.
            log.warn("KYC information request expiry sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** This schedule's drain: its name is what a stopping instance's log calls it. */
    public ScheduleDrain drain() {
        return drain;
    }

    /** No new tick starts, and the tick in flight finishes within its bound ({@link ScheduleDrain}). */
    @Override
    public void stop() {
        drain.stop();
    }

    /** Spring's form: no new tick starts, and it calls back once the tick in flight has drained. */
    @Override
    public void stop(Runnable callback) {
        drain.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return drain.isRunning();
    }

    /** After the HTTP drain, before the consumers ({@link ShutdownOrder}). */
    @Override
    public int getPhase() {
        return ShutdownOrder.SCHEDULES;
    }

    /** The longest {@link #stop()} takes, below {@code spring.lifecycle.timeout-per-shutdown-phase}. */
    public Duration stopBound() {
        return drain.stopBound();
    }
}
