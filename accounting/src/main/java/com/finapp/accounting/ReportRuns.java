package com.finapp.accounting;

import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The report run's machine - claim, reclaim, complete, fail, supersede (`P14-TSK-008`; ADR-0115 decisions 2-3;
 * {@code PHASE_14_PLAN.md} sections 6, 8.1 T-report, 8.3 row 7, 8.4 leg (c); {@code INV-ACC-04}, {@code INV-ACC-14}).
 * Every method runs on the caller's writable unit of work; the generation itself is not here - it runs on a separate,
 * read-only connection ({@link ReportGeneration}), between a claim's commit and a completion's.
 *
 * <p><strong>Claim</strong> ({@link #claim}, T-claim): the period row {@code FOR SHARE} (A1) - so no lock or unlock
 * moves it under the claim - fixes the basis ({@code FINAL} for a period no longer {@code OPEN}, {@code PROVISIONAL}
 * otherwise); then the question's latest run: one that still answers it is the answer (a {@code GENERATING} run past its
 * lease reclaimed on the way); otherwise the next revision is born by insert-or-read on the plan's key - ten claimers,
 * one {@code INSERT}, nine read-backs (A4). The caller generates only a run it {@link Claimed#mine holds}.
 *
 * <p><strong>Which run answers.</strong> A {@code FINAL}-basis run answers while it is {@code GENERATING} or
 * {@code FINAL} under the period's present lock; a {@code PROVISIONAL}-basis run while {@code GENERATING}, and once
 * completed only while its cut is younger than the freshness window on the database's clock and its period's revision is
 * unchanged ({@link Provisional}, `P14-TSK-009`) - past it a later ask is a new snapshot; a {@code FAILED} or
 * {@code SUPERSEDED} run never - "a new revision allowed".
 *
 * <p><strong>Complete</strong> ({@link #complete}, T-complete): the period {@code FOR SHARE} again and the lock re-checked
 * for a FINAL run - an unlock since the claim fails it {@code PERIOD_MOVED}; then one conditional {@code UPDATE} from
 * {@code GENERATING} at the attempt the caller holds, carrying status, content and hash; the audit record and
 * {@code accounting.ReportRunCompleted} in the same commit. A second completer (a slow generator after a reclaim)
 * converges on the row, writing nothing. Never a half run.
 *
 * <p><strong>Supersede</strong> ({@link #supersedeFinal}): the unlock's (and the reopen's) - every FINAL run of the period.
 *
 * <p><strong>A translated run</strong> (`P14-TSK-015`; ADR-0116 decision 3): a run asks for translation by naming a
 * valuation rate set in its parameters, where its definition {@link ReportDefinition#takesValuationRateSet takes one}. The
 * claim refuses it unless the set is the period's approved one ({@link ValuationSetNotApproved}), FINAL or PROVISIONAL,
 * under the period row {@code FOR SHARE}: an approval takes that row {@code FOR UPDATE}, so the read cannot race one, and
 * approved is terminal, so it cannot go stale. A plain run reads no set.
 */
@RequiredArgsConstructor
public final class ReportRuns implements ReportRunSupersession {

    /** Published at every completion - codes, numbers and the content's hash, never a figure. */
    public static final String COMPLETED_EVENT = "accounting.ReportRunCompleted";

    /** The audit records' and the event's target type. */
    public static final String TARGET_TYPE = "report_run";

    static final String PRODUCER = "accounting";
    static final int EVENT_VERSION = 1;

    @NonNull private final ReportRunStore store;
    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final GlMappingStore mappings;
    @NonNull private final ReportDefinitions definitions;
    @NonNull private final Leases leases;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final Provisional provisional;
    @NonNull private final ApprovedSets approvedSets;

    /** As the generated constructor, a completed PROVISIONAL run reused within {@link Provisional#STANDARD}'s window. */
    public ReportRuns(ReportRunStore store, AccountingPeriodStore periods, GlMappingStore mappings,
            ReportDefinitions definitions, Leases leases, AuditWriter<Connection> audit, OutboxWriter<Connection> outbox,
            IdGenerator ids, Clock clock) {
        this(store, periods, mappings, definitions, leases, audit, outbox, ids, clock, Provisional.STANDARD);
    }

    /** As the generated constructor, with no way to a period's approved set: every run naming one is refused. */
    public ReportRuns(ReportRunStore store, AccountingPeriodStore periods, GlMappingStore mappings,
            ReportDefinitions definitions, Leases leases, AuditWriter<Connection> audit, OutboxWriter<Connection> outbox,
            IdGenerator ids, Clock clock, Provisional provisional) {
        this(store, periods, mappings, definitions, leases, audit, outbox, ids, clock, provisional, ApprovedSets.NONE);
    }

    // ------------------------------------------------------------------ configuration and outcomes

    /** A period's approved valuation rate set, read on the caller's unit of work - the claim's check (`P14-TSK-015`). */
    @FunctionalInterface
    public interface ApprovedSets {

        /** Fail closed: no period has an approved set, so every run naming one is refused. */
        ApprovedSets NONE = (unitOfWork, period) -> Optional.empty();

        /** The id of {@code period}'s approved set, when it has one - at most one exists. */
        Optional<UUID> of(Connection unitOfWork, AccountingPeriodId period);

        /** The store's reading - production's. */
        static ApprovedSets of(ValuationRateSetStore store) {
            Objects.requireNonNull(store, "store");
            return (unitOfWork, period) -> store.approvedFor(unitOfWork, period).map(row -> row.id().value());
        }
    }

    /**
     * How long a completed PROVISIONAL run keeps answering its question (`P14-TSK-009`, routed by `P14-TSK-008`'s review,
     * M-8): every ask after a completion used to birth a new revision - a new snapshot of up to 8 MB - so an open period
     * asked in a loop grew without bound. Now the latest PROVISIONAL run answers while its cut is younger than
     * {@code freshness} on the <strong>database's</strong> clock (never an instance's) and its period's revision is
     * unchanged; past it the next ask is a new revision, as before. So a question costs at most one PROVISIONAL row per
     * window, ten claimers past the window still birth one (the key's insert-or-read). An owner item: the window's length,
     * against a hard cap per question or a retention sweep.
     */
    public record Provisional(Duration freshness) {

        /** Fifteen minutes - at most 96 PROVISIONAL revisions a day for one question asked without pause. */
        public static final Provisional STANDARD = new Provisional(Duration.ofMinutes(15));

        public Provisional {
            Objects.requireNonNull(freshness, "freshness");
            if (freshness.compareTo(Duration.ofSeconds(1)) < 0 || freshness.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("a PROVISIONAL run's freshness is one second to a day - a window of"
                        + " none would leave the revisions unbounded");
            }
        }
    }

    /**
     * The generator's lease (the database's clock), how many times a run is reclaimed before it is failed
     * {@code ATTEMPTS_EXHAUSTED}, and the generation's time bound (`P14-TSK-008`'s review, I-1). A lease cannot be renewed,
     * so a generation that outlived it would be overtaken by its own reclaim at every attempt - each overtaken attempt's
     * completion refused, each new one starting a long snapshot of its own, until the attempts ran out with up to that many
     * snapshots open at once. So the generation is <strong>bounded below the lease</strong>: its snapshot spends at most
     * {@code generationTimeout} ({@code statement_timeout} re-armed to what is left before every statement), and the
     * configuration is refused unless that leaves a fifth of the lease ({@link #MARGIN_DIVISOR}) for the claim before it
     * and the completion after it. A live generator ends - completed, or failed {@code GENERATION_TIMED_OUT} - inside its
     * lease; only a dead one is reclaimed.
     */
    public record Leases(Duration lease, int maxAttempts, Duration generationTimeout) {

        /** The lease's share kept for the claim and the completion around a generation: a fifth. */
        public static final int MARGIN_DIVISOR = 5;

        /** Five minutes, five attempts, a generation of at most four minutes. */
        public static final Leases STANDARD = new Leases(Duration.ofMinutes(5), 5, Duration.ofMinutes(4));

        public Leases {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(generationTimeout, "generationTimeout");
            if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("a report run's lease is positive and at most a day");
            }
            if (maxAttempts < 1 || maxAttempts > 100) {
                throw new IllegalArgumentException("a report run is attempted 1 to 100 times");
            }
            if (generationTimeout.toMillis() < 1
                    || generationTimeout.compareTo(lease.minus(lease.dividedBy(MARGIN_DIVISOR))) > 0) {
                throw new IllegalArgumentException("a report generation's time bound is at least a millisecond and at"
                        + " most the lease less a fifth of it - a generation must end inside its lease");
            }
        }
    }

    /** A claim's outcome: the run answering the question; {@code born} when this claim inserted it; {@code mine} when
     * this caller holds its lease and so generates it. */
    public record Claimed(ReportRunStore.RunRow run, boolean born, boolean mine) {}

    /** A completion's or a failure's outcome: the run as it stands; {@code moved} when this call made the edge. */
    public record Concluded(ReportRunStore.RunRow run, boolean moved) {}

    // ------------------------------------------------------------------ refusals

    /** No definition of the code (or the code and version) is held by this build. */
    public static final class DefinitionUnknown extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DefinitionUnknown() {
            super("no report definition of this code is held by this build");
        }
    }

    /** The parameters are not what the definition takes. */
    public static final class ParametersInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ParametersInvalid(String detail) {
            super(detail);
        }
    }

    /**
     * The run names a valuation rate set that is not its period's approved one, or the period has none. Names the period,
     * never a rate.
     */
    public static final class ValuationSetNotApproved extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ValuationSetNotApproved(YearMonth month) {
            super("a translated report run names its period's approved valuation rate set, and " + month
                    + " holds no such approved set");
        }
    }

    /** No report run has this id. */
    public static final class RunNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RunNotFound() {
            super("no report run answers this request");
        }
    }

    // ------------------------------------------------------------------ claim

    /**
     * Claims the run answering {@code code}'s current version for {@code periodId} and {@code parameters} (T-claim).
     *
     * @throws DefinitionUnknown when the build holds no definition of {@code code}
     * @throws ParametersInvalid when the parameters name what the definition does not take, or the rate set is missing
     *     or unwanted
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws ValuationSetNotApproved when the rate set named is not the period's approved one - FINAL or PROVISIONAL
     */
    public Claimed claim(Connection unitOfWork, String code, AccountingPeriodId periodId, RunParameters parameters,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(periodId, "periodId");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(correlation, "correlation");
        requester(actor);
        ReportDefinition definition = definitions.current(code).orElseThrow(DefinitionUnknown::new);
        accepts(definition, parameters);
        // A1: the period FOR SHARE - its month frozen from birth, so the unlocked read only names the row.
        YearMonth month = periods.find(unitOfWork, periodId).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, month)
                .orElseThrow(AccountingPeriods.PeriodNotFound::new);
        RunBasis basis = RunBasis.of(period.status());
        // Under the row an approval takes FOR UPDATE; approved is terminal. Nothing written, the same for either basis.
        if (parameters.valuationRateSet().isPresent()
                && !parameters.valuationRateSet().equals(approvedSets.of(unitOfWork, periodId))) {
            throw new ValuationSetNotApproved(month);
        }
        ReportRunStore.RunKey key = new ReportRunStore.RunKey(definition.code(), definition.version(), periodId,
                parameters.hash());
        Optional<ReportRunStore.RunRow> latest = store.latest(unitOfWork, key);
        if (latest.isPresent() && (answers(latest.get(), basis, period) || fresh(unitOfWork, latest.get(), period))) {
            ReportRunStore.RunRow run = latest.get();
            if (run.status() != RunStatus.GENERATING || !expired(unitOfWork, run)) {
                return new Claimed(run, false, false);
            }
            if (run.attempt() < leases.maxAttempts()) {
                return reclaimed(unitOfWork, run)
                        .map(held -> new Claimed(held, false, true))
                        .orElseGet(() -> new Claimed(reread(unitOfWork, run.id()), false, false));
            }
            Concluded exhausted = fail(unitOfWork, run.id(), run.attempt(), RunFailure.ATTEMPTS_EXHAUSTED, actor,
                    run.correlation());
            // The failure moved nothing: a slow attempt concluded, or another claimer reclaimed, between the lease's read
            // and the failure. A run that still answers is the answer - never a second FINAL run for one question.
            if (!exhausted.moved() && answers(exhausted.run(), basis, period)) {
                return new Claimed(exhausted.run(), false, false);
            }
        }
        int revision = latest.map(run -> run.revision() + 1).orElse(1);
        // The pin: the lock's for a FINAL run; the version in force at the claim for a PROVISIONAL one.
        Optional<AccountingPeriodStore.Pin> pin = basis == RunBasis.FINAL
                ? period.pinned()
                : mappings.inForceFor(unitOfWork, month)
                        .map(version -> new AccountingPeriodStore.Pin(version.id(), version.version()));
        boolean born = store.insertIfAbsent(unitOfWork, new ReportRunStore.NewRun(ReportRunId.next(ids), key, revision,
                month, period.revision(), parameters, basis,
                basis == RunBasis.FINAL ? period.lockedAt() : Optional.empty(),
                pin.map(AccountingPeriodStore.Pin::id), pin.map(AccountingPeriodStore.Pin::version), actor,
                leases.lease(), correlation));
        // Born here, or by a claimer that won the key: either way the row at the key is the run (A4's read-back).
        ReportRunStore.RunRow run = store.at(unitOfWork, key, revision)
                .orElseThrow(() -> new IllegalStateException("a claimed report run is held at its key"));
        return new Claimed(run, born, born);
    }

    // ------------------------------------------------------------------ the lease

    /**
     * The orphan leg (`P14-TSK-011` schedules it in {@code AccountingCloseSchedule}, PHASE_14_PLAN.md section 8.4 (c)):
     * up to {@code limit} {@code GENERATING} runs past their lease, {@code FOR UPDATE SKIP LOCKED} so ten instances take
     * disjoint rows; each reclaimed by the conditional {@code UPDATE} - one attempt more, a new lease - or, past the
     * attempts' bound, failed {@code ATTEMPTS_EXHAUSTED}.
     *
     * @param actor the leg's actor - the platform's, established by the schedule's system scope
     * @return the runs this call now holds - the caller generates and completes each
     */
    public List<ReportRunStore.RunRow> reclaimExpired(Connection unitOfWork, int limit, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        requester(actor);
        if (limit < 1) {
            throw new IllegalArgumentException("the orphan leg takes at least one run");
        }
        List<ReportRunStore.RunRow> held = new ArrayList<>();
        for (ReportRunStore.RunRow run : store.expired(unitOfWork, limit)) {
            if (run.attempt() >= leases.maxAttempts()) {
                fail(unitOfWork, run.id(), run.attempt(), RunFailure.ATTEMPTS_EXHAUSTED, actor, run.correlation());
                continue;
            }
            reclaimed(unitOfWork, run).ifPresent(held::add);
        }
        return held;
    }

    /**
     * Takes the run over if it is {@code GENERATING} and abandoned - past its lease (a replayed request whose first flight
     * died): one attempt more by the conditional reclaim, or failed {@code ATTEMPTS_EXHAUSTED} past the bound.
     *
     * @return the run this call now holds - empty when it is live, concluded, exhausted, or another reclaimer won
     * @throws RunNotFound when no run has this id
     */
    public Optional<ReportRunStore.RunRow> takeOver(Connection unitOfWork, ReportRunId id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        requester(actor);
        ReportRunStore.RunRow run = store.find(unitOfWork, id).orElseThrow(RunNotFound::new);
        if (run.status() != RunStatus.GENERATING || !expired(unitOfWork, run)) {
            return Optional.empty();
        }
        if (run.attempt() >= leases.maxAttempts()) {
            fail(unitOfWork, run.id(), run.attempt(), RunFailure.ATTEMPTS_EXHAUSTED, actor, run.correlation());
            return Optional.empty();
        }
        return reclaimed(unitOfWork, run);
    }

    // ------------------------------------------------------------------ complete and fail

    /**
     * Completes the run at {@code attempt} with {@code generated} (T-complete): a FINAL run only while its period holds
     * the lock it was claimed under - otherwise it fails {@code PERIOD_MOVED}. One conditional edge, the audit record and
     * {@code accounting.ReportRunCompleted}; a run no longer {@code GENERATING} at {@code attempt} converges.
     *
     * @throws RunNotFound when no run has this id
     */
    public Concluded complete(Connection unitOfWork, ReportRunId id, int attempt, ReportGeneration.Generated generated,
            Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(generated, "generated");
        requester(actor);
        ReportRunStore.RunRow run = store.find(unitOfWork, id).orElseThrow(RunNotFound::new);
        // A1 before A4: the period FOR SHARE - an unlock waits for this commit, or this waits for the unlock's.
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, run.period())
                .orElseThrow(() -> new IllegalStateException("a report run's period is never deleted"));
        if (run.status() == RunStatus.GENERATING && run.attempt() == attempt && run.basis() == RunBasis.FINAL
                && !holdsTheLock(run, period)) {
            return fail(unitOfWork, id, attempt, RunFailure.PERIOD_MOVED, actor, run.correlation());
        }
        if (!store.complete(unitOfWork, id, attempt, run.basis().completed(), generated.content(), generated.sha256())) {
            return new Concluded(reread(unitOfWork, id), false);
        }
        ReportRunStore.RunRow done = reread(unitOfWork, id);
        audit(unitOfWork, actor, AccountingAuditAction.REPORT_GENERATED, done, AuditOutcome.SUCCEEDED,
                summary(done) + ", status=" + done.status().name(), done.correlation());
        EventPayload payload = EventPayload.of()
                .with("definition", done.definition())
                .with("definitionVersion", Integer.toString(done.definitionVersion()))
                .with("period", done.period().toString())
                .with("periodRevision", Integer.toString(done.periodRevision()))
                .with("revision", Integer.toString(done.revision()))
                .with("status", done.status().name())
                .with("contentSha256", generated.sha256());
        if (done.mappingVersion().isPresent()) {
            payload = payload.with("mappingVersion", Integer.toString(done.mappingVersion().get()));
        }
        if (done.valuationRateSetId().isPresent()) {
            payload = payload.with("valuationRateSet", done.valuationRateSetId().get().toString());
        }
        publish(unitOfWork, done, payload);
        return new Concluded(done, true);
    }

    /**
     * Fails the run at {@code attempt} with {@code failure} - recorded {@code accounting.ReportGenerated}
     * {@code FAILED}; a run no longer {@code GENERATING} at {@code attempt} converges. A new revision may then be asked.
     *
     * @throws RunNotFound when no run has this id
     */
    public Concluded fail(Connection unitOfWork, ReportRunId id, int attempt, RunFailure failure, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(failure, "failure");
        requester(actor);
        if (!store.fail(unitOfWork, id, attempt, failure)) {
            return new Concluded(store.find(unitOfWork, id).orElseThrow(RunNotFound::new), false);
        }
        ReportRunStore.RunRow failed = reread(unitOfWork, id);
        audit(unitOfWork, actor, AccountingAuditAction.REPORT_GENERATED, failed, AuditOutcome.FAILED,
                summary(failed) + ", failure=" + failure.name(), correlation);
        return new Concluded(failed, true);
    }

    // ------------------------------------------------------------------ supersede

    @Override
    public int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        return store.supersedeFinal(unitOfWork, period, correlation);
    }

    // ------------------------------------------------------------------ reads and their records

    /** The run, unlocked. */
    public Optional<ReportRunStore.RunRow> find(Connection unitOfWork, ReportRunId id) {
        return store.find(unitOfWork, id);
    }

    /** The run's issued content and hash - empty while it has none. */
    public Optional<ReportRunStore.Content> content(Connection unitOfWork, ReportRunId id) {
        return store.content(unitOfWork, id);
    }

    /** The definition a run regenerates from - exactly its code and version. */
    public Optional<ReportDefinition> definitionOf(ReportRunStore.RunRow run) {
        return definitions.find(run.definition(), run.definitionVersion());
    }

    /** Records an operator's read of a run's content ({@code accounting.ReportRead}) - before it is served. */
    public void recordRead(Connection unitOfWork, ReportRunStore.RunRow run, Actor actor, CorrelationId correlation) {
        audit(unitOfWork, actor, AccountingAuditAction.REPORT_READ, run, AuditOutcome.SUCCEEDED,
                summary(run) + ", status=" + run.status().name(), correlation);
    }

    /** Records a regeneration's comparison ({@code accounting.ReportRegenerated}): {@code IDENTICAL} or {@code DIVERGED}. */
    public void recordRegeneration(Connection unitOfWork, ReportRunStore.RunRow run, boolean identical, Actor actor,
            CorrelationId correlation) {
        audit(unitOfWork, actor, AccountingAuditAction.REPORT_REGENERATED, run, AuditOutcome.SUCCEEDED,
                summary(run) + ", result=" + (identical ? "IDENTICAL" : "DIVERGED"), correlation);
    }

    // ------------------------------------------------------------------ plumbing

    /** Whether {@code run} still answers its question for a period in {@code period}'s state. */
    private static boolean answers(ReportRunStore.RunRow run, RunBasis basis, AccountingPeriodStore.PeriodRow period) {
        if (run.basis() != basis) {
            return false;
        }
        if (basis == RunBasis.PROVISIONAL) {
            return run.status() == RunStatus.GENERATING;
        }
        boolean underThisLock = holdsTheLock(run, period);
        if (run.status() == RunStatus.FINAL && !underThisLock) {
            throw new IllegalStateException("a FINAL report run stands under a lock its period no longer holds: the"
                    + " supersession of the period's return to OPEN was bypassed");
        }
        return underThisLock && (run.status() == RunStatus.GENERATING || run.status() == RunStatus.FINAL);
    }

    /**
     * A completed PROVISIONAL run still answering an ask for its {@code OPEN} period: its cut inside the freshness window on
     * the database's clock, the period's revision unchanged (`P14-TSK-009`, M-8 - {@link Provisional}).
     */
    private boolean fresh(Connection unitOfWork, ReportRunStore.RunRow run, AccountingPeriodStore.PeriodRow period) {
        return period.status() == PeriodStatus.OPEN && run.basis() == RunBasis.PROVISIONAL
                && run.status() == RunStatus.PROVISIONAL && run.periodRevision() == period.revision()
                && store.cutWithin(unitOfWork, run.id(), provisional.freshness());
    }

    /** A FINAL-basis run's period is not {@code OPEN} and holds the very lock the run was claimed under. */
    private static boolean holdsTheLock(ReportRunStore.RunRow run, AccountingPeriodStore.PeriodRow period) {
        return period.status() != PeriodStatus.OPEN && run.periodLockedAt().isPresent()
                && run.periodLockedAt().equals(period.lockedAt());
    }

    /** Past its lease on the database's clock - the store's own judgement, never this JVM's. */
    private boolean expired(Connection unitOfWork, ReportRunStore.RunRow run) {
        return store.pastLease(unitOfWork, run.id());
    }

    private Optional<ReportRunStore.RunRow> reclaimed(Connection unitOfWork, ReportRunStore.RunRow run) {
        return store.reclaim(unitOfWork, run.id(), run.attempt(), leases.lease())
                ? Optional.of(reread(unitOfWork, run.id()))
                : Optional.empty();
    }

    private ReportRunStore.RunRow reread(Connection unitOfWork, ReportRunId id) {
        return store.find(unitOfWork, id).orElseThrow(RunNotFound::new);
    }

    /** The definition takes these parameters; a rate set only where it may translate, and always where it must. */
    private static void accepts(ReportDefinition definition, RunParameters parameters) {
        Set<String> taken = definition.parameterNames();
        for (String name : parameters.values().keySet()) {
            if (!taken.contains(name)) {
                throw new ParametersInvalid("the definition takes no parameter named " + name);
            }
        }
        if (definition.needsValuationRateSet() && parameters.valuationRateSet().isEmpty()) {
            throw new ParametersInvalid("the definition translates and needs a valuation rate set");
        }
        if (parameters.valuationRateSet().isPresent() && !definition.takesValuationRateSet()) {
            throw new ParametersInvalid("the definition does not translate and takes no valuation rate set");
        }
    }

    /** A person, or the platform - never a service or a merchant. */
    private static void requester(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE && actor.type() != ActorType.SYSTEM) {
            throw new IllegalArgumentException("a report run is asked by a person or the platform - never a service or a"
                    + " merchant");
        }
    }

    private static String summary(ReportRunStore.RunRow run) {
        return "definition=" + run.definition() + ", version=" + run.definitionVersion() + ", period=" + run.period()
                + ", revision=" + run.revision() + ", basis=" + run.basis().name()
                + run.valuationRateSetId().map(set -> ", valuationRateSet=" + set).orElse("");
    }

    private void publish(Connection unitOfWork, ReportRunStore.RunRow run, EventPayload payload) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        COMPLETED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        run.id(),
                        TARGET_TYPE,
                        clock.instant(),
                        PRODUCER,
                        run.correlation(),
                        CausationId.of(run.correlation().value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            AccountingAuditAction action,
            ReportRunStore.RunRow run,
            AuditOutcome outcome,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        run.id().value().toString(),
                        Optional.empty(),
                        outcome,
                        correlation,
                        Optional.of(summary)));
    }
}
