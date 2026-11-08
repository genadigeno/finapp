package com.finapp.app.kyc;

import com.finapp.app.lifecycle.DrainedSchedule;
import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreenings;
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

/**
 * The counterparty screening retry (`P9-TSK-016`, ADR-0081 point 5): asks the provider again for every
 * screening still {@code REQUESTED} (its caller crashed or outlived its permit) or {@code UNAVAILABLE}
 * (its backoff elapsed). Every instance runs it with no lease: each tick claims the due screenings in ONE
 * statement on the database clock - the permit moved forward, rows another sweeper holds skipped - so ten
 * sweepers ask disjoint sets, and each answer is decided under the row lock with the attempt's primary key
 * as the second arbiter. Off in test contexts ({@code finapp.kyc.counterparty.sweeper.enabled}); the suites
 * drive {@link #sweepOnce()}.
 */
@Slf4j
public final class CounterpartyScreeningRetrySchedule implements DrainedSchedule {

    private final CounterpartyScreenings screenings;
    private final IdGenerator ids;
    private final Duration pollInterval;
    private final int batch;
    private final ScheduleDrain drain = new ScheduleDrain("kyc-counterparty-retry");

    public CounterpartyScreeningRetrySchedule(
            CounterpartyScreenings screenings, IdGenerator ids, Duration pollInterval, int batch) {
        this.screenings = Objects.requireNonNull(screenings, "screenings must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
    }

    /**
     * One sweep under the system actor.
     *
     * @return how many screenings this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            List<CounterpartyScreeningId> claimed = screenings.claimDue(batch);
            for (CounterpartyScreeningId id : claimed) {
                try {
                    screenings.retry(id, CorrelationId.generate(ids));
                } catch (RuntimeException failure) {
                    // The claim's permit lapses and a later tick asks again; the class name only.
                    log.warn("Counterparty screening retry failed: {}", failure.getClass().getSimpleName());
                }
            }
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
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("Counterparty screening retry sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public ScheduleDrain drain() {
        return drain;
    }
}
