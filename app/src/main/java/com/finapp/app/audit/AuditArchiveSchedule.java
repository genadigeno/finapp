package com.finapp.app.audit;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.platform.audit.archive.AuditArchival;
import com.finapp.platform.audit.archive.AuditArchiveDatabaseException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the {@link AuditArchival} on a fixed delay, on every instance that has a store (`P16-TSK-010`; ADR-0135). No leader: one
 * claimer at a time by the archive's head row {@code FOR UPDATE SKIP LOCKED}, and one segment in flight under a lease on the
 * database's clock, so a replica that finds either held does nothing this tick (the source-join census's shape). Registered in
 * {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS} and {@code DISTRIBUTED_EXECUTION.md} section 3.
 *
 * <p><strong>A tick is bounded.</strong> Up to {@link #MAX_SEGMENTS} segments, stopping at the first attempt that did not archive one
 * (nothing to do, held elsewhere, the store down, a copy that differs) or when this instance is stopping. Each database call borrows
 * one connection from the instance's pool and returns it before the store is called, so no connection waits on the store; each store
 * call is bounded by the store's timeout. Correctness never depends on the drain: a segment interrupted mid-copy is taken over when
 * its lease lapses.
 */
@Slf4j
public final class AuditArchiveSchedule implements SmartLifecycle {

    /** The segments one tick may archive before yielding to the next tick. */
    public static final int MAX_SEGMENTS = 20;

    private final AuditArchival archival;
    private final Duration interval;
    private final AuditArchiveMeters meters;
    private final ScheduleDrain drain = new ScheduleDrain("audit-archive");

    /** @param interval the delay between one tick's end and the next's start */
    public AuditArchiveSchedule(AuditArchival archival, Duration interval, AuditArchiveMeters meters) {
        this.archival = Objects.requireNonNull(archival, "archival");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * One tick.
     *
     * @return how many segments it archived
     * @throws AuditArchiveDatabaseException if the database ended an attempt; the segments before it stand
     */
    public int archiveOnce() {
        int archived = 0;
        for (int attempt = 0; attempt < MAX_SEGMENTS && !drain.stopping(); attempt++) {
            AuditArchival.Result result = archival.archiveOne();
            meters.record(result);
            switch (result.outcome()) {
                case IDLE -> {
                    return archived;
                }
                case ARCHIVED -> {
                    archived++;
                    log.info("Audit archive segment {} archived ({} rows)", result.segment(), result.rows());
                }
                case STORE_UNAVAILABLE -> {
                    log.warn("Audit archive segment {}: the store did not answer; the segment waits for its lease", result.segment());
                    return archived;
                }
                case MISMATCH -> {
                    // OPERATIONS_RUNBOOK.md 11.50: an object at the segment's key that is not its rows; never marked, so the archive
                    // stops here until a person reads why.
                    log.error("Audit archive segment {}: the copy read back is missing or differs from its rows; not marked",
                            result.segment());
                    return archived;
                }
                case FENCED -> {
                    log.info("Audit archive segment {} was taken over by another instance before this one marked it", result.segment());
                    return archived;
                }
                case REFUSED -> {
                    log.error("Audit archive segment {}: the database refused the copy's digests as not its rows'; not marked",
                            result.segment());
                    return archived;
                }
            }
        }
        return archived;
    }

    void archiveQuietly() {
        try {
            archiveOnce();
        } catch (AuditArchiveDatabaseException failure) {
            // The operation and SQLState alone: 55000 a column not reviewed, XX001 a queued row with no audit record (11.50).
            meters.failed();
            log.warn("The audit archive's {} failed: SQLState {}", failure.operation(), failure.sqlState());
        } catch (RuntimeException failure) {
            // The class only: a message can carry values.
            meters.failed();
            log.warn("The audit archive failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::archiveQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        drain.started(executor);
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
