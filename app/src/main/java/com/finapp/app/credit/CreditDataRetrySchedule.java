package com.finapp.app.credit;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.TickPhase;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataRequestId;
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
 * The credit data retry (`P10-TSK-006`; ADR-0085 point 5): re-asks every data request still {@code REQUESTED} past
 * its permit (its opener crashed after Tx1) or {@code UNAVAILABLE} past its permit and before its deadline, and
 * reports each unavailable request past its deadline exactly once. Every instance runs it with no lease: each tick
 * claims the due requests in ONE statement on the database clock - the permit re-stamped, rows another sweeper holds
 * skipped - so ten sweepers ask disjoint sets, each re-ask under the SAME reference after re-reading the gate; the
 * report is a conditional flag, so ten sweepers emit one event. Off in test contexts
 * ({@code finapp.credit.data.retry.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class CreditDataRetrySchedule implements SmartLifecycle {

    private final CreditDataCollection collection;
    private final CreditFlowScope flows;
    private final IdGenerator ids;
    private final Duration pollInterval;
    private final int batch;
    private final ScheduleDrain drain = new ScheduleDrain("credit-data-retry");

    public CreditDataRetrySchedule(
            CreditDataCollection collection, CreditFlowScope flows, IdGenerator ids, Duration pollInterval, int batch) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.flows = Objects.requireNonNull(flows, "flows");
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
     * One sweep under the system actor: the due requests re-asked, then the overdue ones reported.
     *
     * @return how many data requests this call claimed for a re-ask
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<CreditDataRequestId> claimed = collection.claimDue(batch);
            for (CreditDataRequestId id : claimed) {
                try {
                    // The re-ask's own correlation; its pull's span linked to the submission (P10-TSK-020).
                    CorrelationId step = CorrelationId.generate(ids);
                    flows.forDataRequest(id, step, () -> collection.retry(id, step));
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick asks again; the class name only.
                    log.warn("Credit data retry failed: {}", failure.getClass().getSimpleName());
                }
            }
            collection.reportOverdue(batch, CorrelationId.generate(ids));
            return claimed.size();
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, TickPhase.firstDelayMillis(pollInterval), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("Credit data retry sweep failed: {}", failure.getClass().getSimpleName());
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

    /** Spring's form: no new tick starts, and it calls back once the tick in flight has drained, so a phase drains in parallel. */
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

    /** The longest {@link #stop()} takes, below {@code spring.lifecycle.timeout-per-shutdown-phase} ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return drain.stopBound();
    }
}
