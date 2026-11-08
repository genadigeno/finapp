package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRuns;
import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.ShutdownOrder;
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
 * Accounting's one leaderless schedule (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 8.4; {@code INV-ACC-09}): every
 * instance runs every leg with no leader; each writes only through insert-if-absent, a {@code SKIP LOCKED} claim under
 * a lease or a conditional edge. Registered in {@code DISTRIBUTED_EXECUTION.md} section 3 and {@code
 * NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS}.
 *
 * <ul>
 *   <li><strong>Open</strong> ({@link #openOnce}): {@code AccountingPeriods.openNext} - insert-if-absent, one month per
 * month however many instances ask.
 *   <li><strong>Checks</strong> ({@link #checksOnce}): every lock with no run gets one, then a {@link CloseCheckWorker}
 * takes attempts one at a time until none is claimable, up to a batch.
 *   <li><strong>Orphans</strong> ({@link #orphansOnce}): {@code ReportRunGenerator.completeOrphaned(1, ...)} until none
 * is left, up to a batch - <strong>one run per call</strong> (`P14-TSK-008`'s review, M-6): a call reclaims its runs in
 * one transaction and generates them in turn, so a limit above one would start every later run's lease before its
 * generation and let another instance overtake it.
 *   <li><strong>Inquiry</strong> ({@link #inquiryOnce}, `P14-TSK-018`): {@code RegulatorySubmissionDriver.inquireDue} - up to a bound, each due {@code SUBMITTED} return claimed
 * {@code FOR UPDATE SKIP LOCKED} under a lease, asked of its adapter on no transaction, and its answer recorded. It meters its own failed claims, {@code leg=inquiry}, from its first run.
 * </ul>
 *
 * <p>A leg on its own thread, so a two-hour check never delays the opening of a month; one thread more for the
 * heartbeats, so no busy leg can starve a lease. A leg that fails is counted and logged by class, and ends only itself.
 * Off in test contexts ({@code finapp.accounting.sweeper.enabled}); the suites drive the legs directly.
 */
@Slf4j
public final class AccountingCloseSchedule implements SmartLifecycle {

    static final String OPEN_LEG = "open";
    static final String CHECKS_LEG = "checks";
    static final String ORPHANS_LEG = "orphans";
    static final String INQUIRY_LEG = "inquiry";

    /** Four legs and the heartbeats - a thread each. */
    private static final int THREADS = 5;

    /** The orphan leg's limit per call (`P14-TSK-008`'s review, M-6). */
    static final int ONE_RUN = 1;

    /** The schedule's cadences and batches, each refused at start-up unless positive. */
    public record Cadence(Duration openPoll, Duration checksPoll, Duration orphansPoll, Duration inquiryPoll, int checksBatch, int orphansBatch) {

        /** An hour to open a month; thirty seconds to claim; a minute for orphans and for inquiries; a run's nine checks and a few orphans. */
        public static final Cadence STANDARD =
                new Cadence(Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(1), 18, 10);

        public Cadence {
            Objects.requireNonNull(openPoll, "openPoll");
            Objects.requireNonNull(checksPoll, "checksPoll");
            Objects.requireNonNull(orphansPoll, "orphansPoll");
            Objects.requireNonNull(inquiryPoll, "inquiryPoll");
            if (openPoll.toMillis() < 1 || checksPoll.toMillis() < 1 || orphansPoll.toMillis() < 1 || inquiryPoll.toMillis() < 1) {
                throw new IllegalArgumentException("the close schedule's polls are positive");
            }
            if (checksBatch < 1 || orphansBatch < 1) {
                throw new IllegalArgumentException("the close schedule's batches are positive");
            }
        }

        /** The inquiry leg at a minute. */
        public Cadence(Duration openPoll, Duration checksPoll, Duration orphansPoll, int checksBatch, int orphansBatch) {
            this(openPoll, checksPoll, orphansPoll, Duration.ofMinutes(1), checksBatch, orphansBatch);
        }
    }

    /** The orphan leg's callable - {@code ReportRunGenerator.completeOrphaned}: how many runs one call reclaimed. */
    @FunctionalInterface
    public interface Orphans {
        int complete(int limit, Actor actor);
    }

    /** The inquiry leg's callable - {@code RegulatorySubmissionDriver.inquireDue}: how many answers one tick recorded. */
    @FunctionalInterface
    public interface Inquiries {

        /** Nothing to ask - a suite's schedule that drives no regulatory return. */
        Inquiries NONE = () -> 0;

        int inquireDue();
    }

    private final AccountingPeriods periods;
    private final CloseRuns runs;
    private final CloseCheckWorker worker;
    private final Orphans orphans;
    private final Inquiries inquiries;
    private final AccountingTransactions transactions;
    private final CloseRunMetrics metrics;
    private final IdGenerator ids;
    private final Cadence cadence;
    private final AtomicReference<ScheduledExecutorService> executor = new AtomicReference<>();
    private final ScheduleDrain drain = new ScheduleDrain("accounting-close");

    public AccountingCloseSchedule(AccountingPeriods periods, CloseRuns runs, CloseChecks checks,
            Orphans orphans, AccountingTransactions transactions, CloseRunMetrics metrics,
            AccountingFlowScope flow, IdGenerator ids, Cadence cadence) {
        this(periods, runs, checks, orphans, Inquiries.NONE, transactions, metrics, flow, ids, cadence);
    }

    public AccountingCloseSchedule(AccountingPeriods periods, CloseRuns runs, CloseChecks checks,
            Orphans orphans, Inquiries inquiries, AccountingTransactions transactions, CloseRunMetrics metrics,
            AccountingFlowScope flow, IdGenerator ids, Cadence cadence) {
        this.periods = Objects.requireNonNull(periods, "periods");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.orphans = Objects.requireNonNull(orphans, "orphans");
        this.inquiries = Objects.requireNonNull(inquiries, "inquiries");
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
     * The check leg, under the system actor: every lock's run started, then attempts until none is claimable or the
     * batch is done.
     *
     * @return how many attempts this call took
     */
    public int checksOnce() {
        return leg(CHECKS_LEG, () -> inSystemScope(system -> {
            transactions.inTransaction(uow -> runs.startMissing(uow, system));
            int taken = 0;
            while (taken < cadence.checksBatch() && !drain.stopping() && worker.workOne(system)) {
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
            while (reclaimed < cadence.orphansBatch() && !drain.stopping() && orphans.complete(ONE_RUN, system) > 0) {
                reclaimed++;
            }
            return reclaimed;
        }));
    }

    /**
     * The inquiry leg: the due {@code SUBMITTED} returns, each claimed, asked and recorded as the platform by the driver, which enters that scope itself because the sending door drives it too.
     *
     * @return how many answers this call recorded
     */
    public int inquiryOnce() {
        return leg(INQUIRY_LEG, inquiries::inquireDue);
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
            Thread thread = new Thread(runnable, drain.name());
            thread.setDaemon(true);
            return thread;
        });
        executor.set(started);
        for (Leg leg : List.of(new Leg(cadence.openPoll(), this::openOnce), new Leg(cadence.checksPoll(), this::checksOnce),
                new Leg(cadence.orphansPoll(), this::orphansOnce), new Leg(cadence.inquiryPoll(), this::inquiryOnce))) {
            // At once, not after a poll: an hour's open leg would otherwise never run on a fleet restarted within the hour.
            started.scheduleWithFixedDelay(leg.work()::getAsInt, 0, leg.poll().toMillis(), TimeUnit.MILLISECONDS);
        }
        drain.started(started);
    }

    private record Leg(Duration poll, IntSupplier work) {}

    /** This schedule's drain: its name is what a stopping instance's log calls it. */
    public ScheduleDrain drain() {
        return drain;
    }

    /**
     * No new leg starts, a leg in a claim loop ends before its next claim, and the leg in flight finishes within its bound
     * ({@link ScheduleDrain}; `P15-TSK-008`, `P15-TSK-009`). The heartbeat, a periodic task on this executor, stops with it, so a check still running is no
     * longer renewed: one that outlives its lease is fenced by holder and reclaimed by another instance, as a dead
     * worker's is.
     */
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
