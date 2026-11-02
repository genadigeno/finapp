package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The close run's machine - start, claim, renew, defer, conclude, re-run (`P14-TSK-011`; ADR-0114 decision 4; {@code
 * PHASE_14_PLAN.md} sections 6, 8.1 T-check, 8.3 rows 5-6, 13.5; {@code INV-ACC-09}, {@code INV-ACC-05}). Every method
 * runs on the caller's writable unit of work; the judgement itself is not here ({@link CloseChecks}) and runs outside
 * any transaction of the machine's, between a claim's commit and a conclusion's. Nothing here posts.
 *
 * <p><strong>Start</strong> ({@link #startMissing}): every {@code LOCKED} period under a lock no run answers - the
 * period {@code FOR SHARE} (A1), the run insert-if-absent on {@code close_run_one_per_lock}, then its nine attempts,
 * one transaction: ten instances, one run. Born by the schedule rather than inside T-lock (a recorded deviation): the
 * lock is the ledger's protection and waits for nothing.
 *
 * <p><strong>Claim</strong> ({@link #claim}): one statement - {@code FOR UPDATE SKIP LOCKED} under a lease on the
 * database's clock - so ten workers take disjoint attempts and no row is held across a judgement. A dead worker's lease
 * lapses and the next claim takes the attempt with one reclaim more; past {@link Leases#maxReclaims} the claim comes
 * back {@link ClaimedCheck#exhausted()}, to be concluded {@code FAILED} unjudged - a check whose workers keep dying
 * never passes and never hangs.
 *
 * <p><strong>The lease and a long run</strong> (`P14-TSK-010`'s review, M-1: {@code AccountingChecks.run} is bounded
 * per statement and page, not in total). Decided: <strong>the worker renews, and the renewal is bounded</strong>. A
 * short lease reclaims a dead worker's check quickly; a live worker renews it ({@link #renew}) for as long as the run
 * lasts, up to {@link Leases#maxRun} from its claim on the database's clock. Past that the renewal is refused, the
 * lease lapses, another worker reclaims and the slow worker's conclusion is refused by the fence - so a run of any
 * length is held while it lives, a hung one is overtaken, and every path ends in a verdict or {@code
 * WORKERS_EXHAUSTED}, none in a pass.
 *
 * <p><strong>Conclude</strong> ({@link #conclude}, one check per transaction): (A1) the period {@code FOR SHARE}, (A4)
 * the run {@code FOR UPDATE}, then one conditional edge naming the {@code holder} - a claim overtaken moves nothing. A
 * period no longer under the run's lock concludes {@code FAILED} {@link #PERIOD_MOVED}, whatever the judge found.
 *
 * <p><strong>{@code OPEN_ACCOUNTING_BREAKS} has no stale pass</strong>: judged before a raising check raised, it would
 * pass while a break is open. So it is not claimable while a raising check of its run is {@code PENDING}; it is judged
 * <em>inside</em> its own conclusion, after the run's row lock ({@link #concludeOpenBreaks}); and any later conclusion
 * of a raising check - serialised on the same row - births a new attempt of it when its latest has concluded. Either
 * order, the last judgement saw every raise.
 *
 * <p><strong>Re-run</strong> ({@link #rerun}, a person's): a new attempt, never an edit; at most one {@code PENDING}
 * per check. Conditional outcomes, attempt rows unique, history frozen by {@code accounting V007}.
 */
@RequiredArgsConstructor
public final class CloseRuns {

    /** The audit records' target type. */
    public static final String TARGET_TYPE = "close_run";

    /** The verdicts this machine itself gives - it judges no check. */
    static final String JUDGED_BY = "close-run:v1";

    /** The judge's own version for the open breaks' count. */
    static final String BREAKS_JUDGED_BY = "accounting-breaks:v1";

    /** The attempt's period no longer holds the run's lock - an unlock since the claim. */
    public static final String PERIOD_MOVED = "PERIOD_MOVED";

    /** The attempt's workers kept dying past the bound. */
    public static final String WORKERS_EXHAUSTED = "WORKERS_EXHAUSTED";

    /** The month's breaks left {@code OPEN}. */
    public static final String OPEN_BREAKS = "OPEN_BREAKS";

    @NonNull private final CloseRunStore store;
    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final AccountingBreakStore breaks;
    @NonNull private final Leases leases;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ configuration and outcomes

    /**
     * The check leg's lease (the database's clock), how often a live worker renews it, how long in all it may ({@code
     * maxRun}), how many times an attempt is reclaimed before it is failed, and how long a deferred attempt waits.
     * Refused at start-up unless a renewal comes at least three times per lease - one lost renewal must not cost the
     * lease - and the run's bound is at least one lease.
     */
    public record Leases(Duration lease, Duration renewEvery, Duration maxRun, int maxReclaims, Duration deferral) {

        /** The renewals a lease holds at least. */
        public static final int RENEWALS_PER_LEASE = 3;

        /** Two minutes, renewed every thirty seconds, two hours in all, three reclaims, thirty seconds' deferral. */
        public static final Leases STANDARD =
                new Leases(Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(2), 3, Duration.ofSeconds(30));

        public Leases {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(renewEvery, "renewEvery");
            Objects.requireNonNull(maxRun, "maxRun");
            Objects.requireNonNull(deferral, "deferral");
            if (lease.toMillis() < 1 || lease.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("a close check's lease is positive and at most a day");
            }
            if (renewEvery.toMillis() < 1 || renewEvery.compareTo(lease.dividedBy(RENEWALS_PER_LEASE)) > 0) {
                throw new IllegalArgumentException("a close check's lease is renewed at least " + RENEWALS_PER_LEASE
                        + " times per lease");
            }
            if (maxRun.compareTo(lease) < 0) {
                throw new IllegalArgumentException("a close check's run is bounded at one lease or more");
            }
            if (maxReclaims < 0 || maxReclaims > 100) {
                throw new IllegalArgumentException("a close check is reclaimed 0 to 100 times");
            }
            if (deferral.toMillis() < 1) {
                throw new IllegalArgumentException("a deferred close check waits a positive time");
            }
        }
    }

    /** An attempt this worker holds - {@code holder} the fence; {@code exhausted} when it is concluded unjudged. */
    public record ClaimedCheck(CloseCheckId id, CloseRunId run, AccountingPeriodId period, YearMonth month,
            CloseCheckCode check, int attempt, UUID holder, int reclaims, boolean exhausted, CorrelationId correlation,
            Actor actor) {}

    /** A conclusion's outcome: {@code moved} false when the claim was overtaken; {@code tookFor} once per run, when all concluded. */
    public record Concluded(CloseCheckStatus status, boolean moved, Optional<Duration> tookFor) {}

    /** A re-run's receipt. */
    public record Rerun(CloseRunId run, YearMonth month, CloseCheckCode check, int attempt) {}

    /** One check of a run, as a reader sees it: its attempts in all, newest first. */
    public record CheckView(CloseCheckCode check, int attempts, CloseRunStore.AttemptRow latest,
            List<CloseRunStore.AttemptRow> history) {}

    /** A run, as a reader sees it. {@code current} when its period holds its lock; {@code complete} as {@link Readiness} judges. */
    public record RunView(CloseRunStore.RunRow run, PeriodStatus periodStatus, boolean current, boolean complete,
            long openBreaks, List<CheckView> checks) {}

    /**
     * Whether a close may be proposed on this run (`P14-TSK-012`'s read, {@code INV-ACC-09}): the run answers the
     * period's present lock, every check's latest attempt is {@code PASSED} or {@code DISCLOSED}, and - re-read live,
     * so a stale attempt can never make a run ready - no break of the month is {@code OPEN}.
     */
    public record Readiness(Optional<CloseRunId> run, boolean complete, List<CloseCheckCode> notReady, long openBreaks) {

        public Readiness {
            notReady = List.copyOf(notReady);
        }
    }

    // ------------------------------------------------------------------ refusals

    /** The period's present lock has no close run yet - the check leg births it within a poll. */
    public static final class RunNotStarted extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RunNotStarted() {
            super("the period's present lock has no close run yet");
        }
    }

    /** The check's latest attempt has not concluded - a re-run would be a second one running. */
    public static final class CheckPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CheckPending() {
            super("the check's latest attempt has not concluded");
        }
    }

    // ------------------------------------------------------------------ start

    /**
     * Births the run and its nine attempts of every {@code LOCKED} period under a lock no run answers -
     * insert-if-absent, so ten instances, one run; a loser writes nothing.
     *
     * @param actor the platform's - the schedule's system scope; every attempt's first requester
     * @return the runs this call started
     */
    public List<CloseRunId> startMissing(Connection unitOfWork, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        List<CloseRunId> started = new ArrayList<>();
        for (AccountingPeriodId id : store.lockedWithoutRun(unitOfWork)) {
            // A1: the period FOR SHARE, re-read - its month frozen from birth, so the unlocked read only names the row.
            YearMonth month = periods.find(unitOfWork, id).orElseThrow(() -> new IllegalStateException(
                    "a period is never deleted")).month();
            AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, month).orElseThrow(
                    () -> new IllegalStateException("a period is never deleted"));
            if (period.status() != PeriodStatus.LOCKED || period.lockedAt().isEmpty()) {
                continue;
            }
            CloseRunId run = CloseRunId.next(ids);
            CorrelationId correlation = CorrelationId.generate(ids);
            if (!store.insertRunIfAbsent(unitOfWork, new CloseRunStore.NewRun(run, id, period.lockedAt().get(),
                    period.revision(), correlation))) {
                continue;
            }
            for (CloseCheckCode check : CloseCheckCode.values()) {
                store.insertAttempt(unitOfWork, new CloseRunStore.NewAttempt(CloseCheckId.next(ids), run, check, 1,
                        actor, Optional.empty(), correlation));
            }
            started.add(run);
        }
        return started;
    }

    // ------------------------------------------------------------------ the claim and the lease

    /**
     * One claimable attempt, held by this call for one lease - the caller commits at once and judges outside the
     * transaction.
     *
     * @return the attempt as claimed; empty when nothing is claimable for this worker
     */
    public Optional<ClaimedCheck> claim(Connection unitOfWork, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        UUID holder = ids.next();
        return store.claimNext(unitOfWork, holder, leases.lease()).map(attempt -> {
            CloseRunStore.RunRow run = store.run(unitOfWork, attempt.run()).orElseThrow(
                    () -> new IllegalStateException("a close run is never deleted"));
            return new ClaimedCheck(attempt.id(), run.id(), run.period(), run.month(), attempt.check(), attempt.attempt(),
                    holder, attempt.reclaims(), attempt.reclaims() > leases.maxReclaims(), attempt.correlation(), actor);
        });
    }

    /** A live worker's renewal; {@code false} when it no longer holds the attempt or is past {@link Leases#maxRun}. */
    public boolean renew(Connection unitOfWork, ClaimedCheck claimed) {
        return store.renew(unitOfWork, claimed.id(), claimed.holder(), leases.lease(), leases.maxRun());
    }

    /** The judge cannot answer yet (a report run another worker generates): the attempt given back, claimable after the wait. */
    public boolean defer(Connection unitOfWork, ClaimedCheck claimed) {
        return store.defer(unitOfWork, claimed.id(), claimed.holder(), leases.deferral());
    }

    /** How often a live worker renews. */
    public Duration renewEvery() {
        return leases.renewEvery();
    }

    // ------------------------------------------------------------------ conclude

    /**
     * Concludes the attempt this worker holds with {@code verdict} - one transaction.
     *
     * @return the outcome; {@link Concluded#moved()} false when the claim was overtaken and nothing was written
     */
    public Concluded conclude(Connection unitOfWork, ClaimedCheck claimed, CheckVerdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        if (verdict.check() != claimed.check()) {
            throw new IllegalArgumentException("a verdict concludes its own check");
        }
        return concluding(unitOfWork, claimed, () -> verdict);
    }

    /** {@code OPEN_ACCOUNTING_BREAKS}, judged here - after the run's row lock, so no raising conclusion stands behind it. */
    public Concluded concludeOpenBreaks(Connection unitOfWork, ClaimedCheck claimed) {
        if (claimed.check() != CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
            throw new IllegalArgumentException("only OPEN_ACCOUNTING_BREAKS is judged in its conclusion");
        }
        return concluding(unitOfWork, claimed, () -> openBreaks(unitOfWork, claimed.month()));
    }

    /** {@code claimed}'s workers kept dying: {@code FAILED}, unjudged. */
    public Concluded concludeExhausted(Connection unitOfWork, ClaimedCheck claimed) {
        return concluding(unitOfWork, claimed, () -> CheckVerdict.failed(claimed.check(), JUDGED_BY,
                List.of(WORKERS_EXHAUSTED), CanonicalJson.object().put("reclaims", claimed.reclaims())));
    }

    private Concluded concluding(Connection unitOfWork, ClaimedCheck claimed, Supplier<CheckVerdict> judged) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(claimed, "claimed");
        // A1 before A4: an unlock waits for this commit, or this waits for the unlock's and finds the lock gone.
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, claimed.month()).orElseThrow(
                () -> new IllegalStateException("a period is never deleted"));
        CloseRunStore.RunRow run = store.lockRun(unitOfWork, claimed.run()).orElseThrow(
                () -> new IllegalStateException("a close run is never deleted"));
        boolean holdsTheLock = holdsTheLock(run, period);
        CheckVerdict verdict = holdsTheLock ? judged.get() : CheckVerdict.failed(claimed.check(), JUDGED_BY,
                List.of(PERIOD_MOVED), CanonicalJson.object().put("period", claimed.month().toString()));
        if (!store.conclude(unitOfWork, claimed.id(), claimed.holder(), verdict)) {
            return new Concluded(verdict.status(), false, Optional.empty());
        }
        if (claimed.check().raisesBreaks() && holdsTheLock) {
            rejudgeOpenBreaks(unitOfWork, run, claimed.actor());
        }
        return new Concluded(verdict.status(), true, store.markChecksConcluded(unitOfWork, run.id()));
    }

    /** A raising check concluded under the run's lock: the breaks' last judgement may not have seen it - judge them again. */
    private void rejudgeOpenBreaks(Connection unitOfWork, CloseRunStore.RunRow run, Actor actor) {
        CloseRunStore.AttemptRow latest = store.latestAttempt(unitOfWork, run.id(), CloseCheckCode.OPEN_ACCOUNTING_BREAKS)
                .orElseThrow(() -> new IllegalStateException("a run is born with every check"));
        if (latest.status().concluded()) {
            store.insertAttempt(unitOfWork, new CloseRunStore.NewAttempt(CloseCheckId.next(ids), run.id(),
                    CloseCheckCode.OPEN_ACCOUNTING_BREAKS, latest.attempt() + 1, actor, Optional.empty(),
                    run.correlation()));
        }
    }

    /** The month's breaks by status: any {@code OPEN} blocks; {@code ACKNOWLEDGED} ones are disclosed, four-eyes and reasoned. */
    private CheckVerdict openBreaks(Connection unitOfWork, YearMonth month) {
        Map<BreakStatus, Long> counts = counted(unitOfWork, month);
        long open = counts.get(BreakStatus.OPEN);
        long acknowledged = counts.get(BreakStatus.ACKNOWLEDGED);
        CanonicalJson.Obj evidence = CanonicalJson.object().put("open", open).put("acknowledged", acknowledged)
                .put("resolved", counts.get(BreakStatus.RESOLVED));
        if (open > 0) {
            return CheckVerdict.failed(CloseCheckCode.OPEN_ACCOUNTING_BREAKS, BREAKS_JUDGED_BY, List.of(OPEN_BREAKS), evidence);
        }
        return acknowledged > 0
                ? CheckVerdict.disclosed(CloseCheckCode.OPEN_ACCOUNTING_BREAKS, BREAKS_JUDGED_BY, evidence)
                : CheckVerdict.passed(CloseCheckCode.OPEN_ACCOUNTING_BREAKS, BREAKS_JUDGED_BY, evidence);
    }

    private Map<BreakStatus, Long> counted(Connection unitOfWork, YearMonth month) {
        Map<BreakStatus, Long> counts = new EnumMap<>(BreakStatus.class);
        for (BreakStatus status : BreakStatus.values()) {
            counts.put(status, 0L);
        }
        counts.putAll(breaks.countByStatus(unitOfWork, month));
        return counts;
    }

    // ------------------------------------------------------------------ re-run

    /**
     * A person's re-run: the check's next attempt, {@code PENDING} - a worker claims it. Under (A1) the period {@code
     * FOR SHARE} and (A4) the run {@code FOR UPDATE}: two re-runs, one new attempt.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code LOCKED}
     * @throws RunNotStarted when its present lock has no run yet
     * @throws CheckPending when the check's latest attempt has not concluded
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Rerun rerun(Connection unitOfWork, AccountingPeriodId id, CloseCheckCode check, Actor actor, String reason,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        YearMonth month = periods.find(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, month).orElseThrow(
                AccountingPeriods.PeriodNotFound::new);
        if (period.status() != PeriodStatus.LOCKED || period.lockedAt().isEmpty()) {
            throw new AccountingPeriods.PeriodStale("the period is " + period.status().name()
                    + "; a close check is re-run while it is LOCKED");
        }
        CloseRunId named = store.runUnder(unitOfWork, id, period.lockedAt().get()).orElseThrow(RunNotStarted::new).id();
        CloseRunStore.RunRow run = store.lockRun(unitOfWork, named).orElseThrow(
                () -> new IllegalStateException("a close run is never deleted"));
        CloseRunStore.AttemptRow latest = store.latestAttempt(unitOfWork, run.id(), check).orElseThrow(
                () -> new IllegalStateException("a run is born with every check"));
        if (!latest.status().concluded()) {
            throw new CheckPending();
        }
        int attempt = latest.attempt() + 1;
        store.insertAttempt(unitOfWork, new CloseRunStore.NewAttempt(CloseCheckId.next(ids), run.id(), check, attempt, actor,
                Optional.of(reasoned), correlation));
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                AccountingAuditAction.CLOSE_CHECK_RERUN, TARGET_TYPE, run.id().value().toString(), Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("period=" + month + ", check=" + check.name()
                        + ", attempt=" + attempt)));
        return new Rerun(run.id(), month, check, attempt);
    }

    // ------------------------------------------------------------------ reads

    /** The period's latest run with every check's attempts - empty when the period has none. */
    public Optional<RunView> view(Connection unitOfWork, AccountingPeriodId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        AccountingPeriodStore.PeriodRow period = periods.find(unitOfWork, id).orElseThrow(
                AccountingPeriods.PeriodNotFound::new);
        YearMonth month = period.month();
        return store.latestRun(unitOfWork, id).map(run -> {
            List<CloseRunStore.AttemptRow> attempts = store.attempts(unitOfWork, run.id());
            List<CheckView> checks = Arrays.stream(CloseCheckCode.values())
                    .map(check -> checkView(check, attempts))
                    .flatMap(Optional::stream)
                    .toList();
            long open = counted(unitOfWork, month).get(BreakStatus.OPEN);
            boolean current = holdsTheLock(run, period);
            return new RunView(run, period.status(), current, current && notReady(checks).isEmpty() && open == 0, open, checks);
        });
    }

    /** What `P14-TSK-012`'s proposal asks: {@link Readiness} of the period's present lock, read in the caller's transaction. */
    public Readiness readiness(Connection unitOfWork, AccountingPeriodId id) {
        Optional<RunView> view = view(unitOfWork, id);
        if (view.isEmpty() || !view.get().current()) {
            return new Readiness(Optional.empty(), false, List.of(CloseCheckCode.values()),
                    view.map(RunView::openBreaks).orElse(0L));
        }
        return new Readiness(Optional.of(view.get().run().id()), view.get().complete(), notReady(view.get().checks()),
                view.get().openBreaks());
    }

    /** Records an operator's read of a run ({@code accounting.CloseRunRead}) - before it is served. */
    public void recordRead(Connection unitOfWork, RunView view, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                AccountingAuditAction.CLOSE_RUN_READ, TARGET_TYPE, view.run().id().value().toString(), Optional.empty(),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("period=" + view.run().month() + ", current="
                        + view.current() + ", complete=" + view.complete())));
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<CheckView> checkView(CloseCheckCode check, List<CloseRunStore.AttemptRow> attempts) {
        List<CloseRunStore.AttemptRow> history = attempts.stream().filter(attempt -> attempt.check() == check).toList();
        return history.isEmpty()
                ? Optional.empty()
                : Optional.of(new CheckView(check, history.get(0).attempt(), history.get(0), history));
    }

    private static List<CloseCheckCode> notReady(List<CheckView> checks) {
        List<CloseCheckCode> notReady = new ArrayList<>(Arrays.asList(CloseCheckCode.values()));
        checks.stream().filter(view -> view.latest().status().readyForClose())
                .forEach(view -> notReady.remove(view.check()));
        return notReady;
    }

    /** The period is past {@code OPEN} and holds the very lock the run was born under. */
    private static boolean holdsTheLock(CloseRunStore.RunRow run, AccountingPeriodStore.PeriodRow period) {
        return period.status() != PeriodStatus.OPEN && period.lockedAt().filter(run.lockedAt()::equals).isPresent();
    }

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a close check's re-run is a person's");
        }
    }
}
