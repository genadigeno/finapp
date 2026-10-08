package com.finapp.app.credit;

import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionRequestId;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The decision request's progress (`P10-TSK-015`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1): drives every open
 * request from submission to evaluation, and expires what is not decided in time. Every instance runs it with no lease:
 * each tick claims the due requests in ONE statement on the database clock - the permit re-stamped, rows another sweeper
 * holds skipped - and takes each one's next step in a transaction of its own under the request's row lock, every edge a
 * conditional the trigger re-judges; so ten sweepers take each edge once (`DecisionOrchestrationDatabaseTest`). Off in
 * test contexts ({@code finapp.credit.progress.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class CreditDecisionProgressSchedule implements SmartLifecycle {

    private final DecisionProgress progress;
    private final IdGenerator ids;
    private final Duration pollInterval;
    private final int batch;
    private final Duration permit;
    private ScheduledExecutorService executor;

    public CreditDecisionProgressSchedule(
            DecisionProgress progress, IdGenerator ids, Duration pollInterval, int batch, Duration permit) {
        this.progress = Objects.requireNonNull(progress, "progress");
        this.permit = Objects.requireNonNull(permit, "permit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
    }

    /**
     * One sweep under the system actor: the due requests claimed, each one's next step taken.
     *
     * @return how many requests this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<DecisionRequestId> claimed = progress.claimDue(batch, permit);
            for (DecisionRequestId id : claimed) {
                try {
                    progress.step(id, CorrelationId.generate(ids));
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick steps again; the class name only - never a party.
                    log.warn("Credit decision progress step failed: {}", failure.getClass().getSimpleName());
                }
            }
            return claimed.size();
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "credit-decision-progress");
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
            log.warn("Credit decision progress sweep failed: {}", failure.getClass().getSimpleName());
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
