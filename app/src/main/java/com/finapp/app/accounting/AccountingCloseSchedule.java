package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRuns;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Accounting's one leaderless schedule (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 8.4; {@code INV-ACC-09}): every instance runs
 * every leg with no leader; each writes only through insert-if-absent, a {@code SKIP LOCKED} claim under a lease or a conditional edge.
 * Registered in {@code DISTRIBUTED_EXECUTION.md} section 3 and {@code NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}.
 *
 * <ul>
 *   <li><strong>Open</strong> ({@link #openOnce}): {@code AccountingPeriods.openNext} - insert-if-absent, one month per month however many
 *       instances ask.
 *   <li><strong>Checks</strong> ({@link #checksOnce}): every lock with no run gets one, then a {@link CloseCheckWorker} takes attempts
 *       one at a time until none is claimable, up to a batch.
 *   <li><strong>Orphans</strong> ({@link #orphansOnce}): {@code ReportRunGenerator.completeOrphaned(1, ...)} until none is left, up to a
 *       batch - <strong>one run per call</strong> (`P14-TSK-008`'s review, M-6): a call reclaims its runs in one transaction and generates
 *       them in turn, so a limit above one would start every later run's lease before its generation and let another instance overtake it.
 * </ul>
 *
 * <p>A leg on its own thread, so a two-hour check never delays the opening of a month; one thread more for the heartbeats, so no busy leg
 * can starve a lease. A leg that fails is counted and logged by class, and ends only itself. Off in test contexts
 * ({@code finapp.accounting.sweeper.enabled}); the suites drive the legs directly. The regulatory inquiry leg is `P14-TSK-018`'s.
 */
@Slf4j
public final class AccountingCloseSchedule implements SmartLifecycle {

    static final String OPEN_LEG = "open";
    static final String CHECKS_LEG = "checks";
    static final String ORPHANS_LEG = "orphans";

    /** Three legs and the heartbeats - a thread each. */
    private static final int THREADS = 4;

    /** The orphan leg's limit per call (`P14-TSK-008`'s review, M-6). */
    static final int ONE_RUN = 1;

    /** The schedule's cadences and batches, each refused at start-up unless positive. */
    public record Cadence(Duration openPoll, Duration checksPoll, Duration orphansPoll, int checksBatch, int orphansBatch) {

        /** An hour to open a month; thirty seconds to claim; a minute for orphans; a run's nine checks and a few orphans. */
        public static final Cadence STANDARD =
                new Cadence(Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofMinutes(1), 18, 10);

        public Cadence {
            Objects.requireNonNull(openPoll, "openPoll");
            Objects.requireNonNull(checksPoll, "checksPoll");
            Objects.requireNonNull(orphansPoll, "orphansPoll");
            if (openPoll.toMillis() < 1 || checksPoll.toMillis() < 1 || orphansPoll.toMillis() < 1) {
                throw new IllegalArgumentException("the close schedule's polls are positive");
            }
            if (checksBatch < 1 || orphansBatch < 1) {
                throw new IllegalArgumentException("the close schedule's batches are positive");
            }
        }
    }

    /** The orphan leg's callable - {@code ReportRunGenerator.completeOrphaned}: how many runs one call reclaimed. */
    @FunctionalInterface
    public interface Orphans {
        int complete(int limit, Actor actor);
    }

    private final AccountingPeriods periods;
    private final CloseRuns runs;
    private final CloseCheckWorker worker;
    private final Orphans orphans;
    private final AccountingTransactions transactions;
    private final CloseRunMetrics metrics;
    private final IdGenerator ids;
    private final Cadence cadence;
    private final AtomicReference<ScheduledExecutorService> executor = new AtomicReference<>();

    public AccountingCloseSchedule(AccountingPeriods periods, CloseRuns runs, CloseChecks checks,
            Orphans orphans, AccountingTransactions transactions, CloseRunMetrics metrics,
            AccountingFlowScope flow, IdGenerator ids, Cadence cadence) {
        this.periods = Objects.requireNonNull(periods, "periods");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.orphans = Objects.requireNonNull(orphans, "orphans");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.cadence = Objects.requireNonNull(cadence, "cadence");
        this.worker = new CloseCheckWorker(runs, Objects.requireNonNull(checks, "checks"), transactions, metrics,
                Objects.requireNonNull(flow, "flow"), this::heartbeat);
    }

    /**
     * The open leg, under the system actor.
     *
     * @return how many months this call opened
     */
    public int openOnce() {
        return leg(OPEN_LEG, () -> inSystemScope(system -> transactions.inTransaction(uow ->
                periods.openNext(uow, system, CorrelationId.generate(ids))).size()));
    }

    /**
     * The check leg, under the system actor: every lock's run started, then attempts until none is claimable or the batch is done.
     *
     * @return how many attempts this call took
     */
    public int checksOnce() {
        return leg(CHECKS_LEG, () -> inSystemScope(system -> {
            transactions.inTransaction(uow -> runs.startMissing(uow, system));
            int taken = 0;
            while (taken < cadence.checksBatch() && worker.workOne(system)) {
                taken++;
            }
            return taken;
        }));
    }

    /**
     * The orphan leg, under the system actor: one expired run per call to the generator.
     *
     * @return how many runs this call reclaimed
     */
    public int orphansOnce() {
        return leg(ORPHANS_LEG, () -> inSystemScope(system -> {
            int reclaimed = 0;
            while (reclaimed < cadence.orphansBatch() && orphans.complete(ONE_RUN, system) > 0) {
                reclaimed++;
            }
            return reclaimed;
        }));
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static int inSystemScope(java.util.function.ToIntFunction<Actor> work) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem()) {
            return work.applyAsInt(SecurityContext.require());
        }
    }

    /** One leg: a failure is counted and logged by class - a JDBC message can carry data - and ends only this leg's tick. */
    private int leg(String leg, IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException failure) {
            metrics.legFailed(leg);
            log.warn("The accounting close schedule's {} leg failed: {}", leg, failure.getClass().getSimpleName());
            return 0;
        }
    }

    /** A heartbeat on the schedule's executor; none before it starts - the suites drive a worker with no timer. */
    private Runnable heartbeat(Duration every, BooleanSupplier beat) {
        ScheduledExecutorService running = executor.get();
        if (running == null || running.isShutdown()) {
            return () -> { };
        }
        AtomicReference<ScheduledFuture<?>> handle = new AtomicReference<>();
        handle.set(running.scheduleWithFixedDelay(() -> {
            if (!beat.getAsBoolean()) {
                ScheduledFuture<?> self = handle.get();
                if (self != null) {
                    self.cancel(false);
                }
            }
        }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS));
        return () -> handle.get().cancel(false);
    }

    @Override
    public void start() {
        ScheduledExecutorService started = Executors.newScheduledThreadPool(THREADS, runnable -> {
            Thread thread = new Thread(runnable, "accounting-close");
            thread.setDaemon(true);
            return thread;
        });
        executor.set(started);
        for (Leg leg : List.of(new Leg(cadence.openPoll(), this::openOnce), new Leg(cadence.checksPoll(), this::checksOnce),
                new Leg(cadence.orphansPoll(), this::orphansOnce))) {
            started.scheduleWithFixedDelay(leg.work()::getAsInt, leg.poll().toMillis(), leg.poll().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private record Leg(Duration poll, IntSupplier work) {}

    @Override
    public void stop() {
        ScheduledExecutorService running = executor.get();
        if (running != null) {
            running.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        ScheduledExecutorService running = executor.get();
        return running != null && !running.isShutdown();
    }
}
