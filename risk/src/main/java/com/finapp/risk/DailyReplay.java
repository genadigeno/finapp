package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The assessments of a day replayed, a bounded page at a time (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3;
 * ADR-0109 decision 4; {@code INV-RSK-01}; {@code DESIGN-023} D7) - the per-assessment replay ({@link AssessmentReplay})
 * run over the day in identifier order, on ONE read-only {@code REPEATABLE READ} snapshot per page. Each assessment
 * replays under <strong>its own pinned pair</strong> - the policy version it pinned, by id, and the engine it names -
 * never the version active now: a policy activated mid-day changes nothing of the morning's replay. The versions are
 * memoised for the page by id; they are immutable from their proposal on.
 *
 * <p><strong>Bounded and resumable</strong> (the review's I3): one call replays at most {@code limit} assessments
 * ({@link #DEFAULT_LIMIT} unless named, {@link #MAX_LIMIT} at most) after the cursor {@code after} - an assessment of the
 * day - and answers the cursor to continue from when more remain. So a call's work and its snapshot's age are bounded
 * however large the day, a client's retry repeats one bounded page rather than the whole day, and ten instances each
 * serving retries hold at most a page's read each - no lock, no write, nothing to stack. The whole day is the pages in
 * order; the day replays {@code IDENTICAL} when every page does.
 *
 * <p>The answer counts {@code IDENTICAL} and {@code DIVERGED} per operation and names <strong>the page's first
 * divergent</strong> (the earliest by identifier) with the kinds that diverged - never a figure. The reader must be a person
 * the platform can place in a party (fail closed - {@link RiskAssessments.SelfDealingRefused}); an assessment of the
 * reader's own party is replayed and counted but never named ({@code withheld}, the queues' rule). A {@code DIVERGED} is
 * a defect of the engine or a corrupted row - reported, never repaired. Nothing is written but {@code risk.Replayed}, by
 * {@link #record} in the caller's transaction of its own, before the answer.
 */
public final class DailyReplay {

    /** The audit target type of a day's replay - its id the date. */
    public static final String TARGET_TYPE = "risk_assessment_day";

    /** How many assessments one statement reads. */
    static final int PAGE = 200;

    /** The assessments one call replays unless it names fewer. */
    public static final int DEFAULT_LIMIT = 200;

    /** The most assessments one call replays - a larger limit is answered with this many. */
    public static final int MAX_LIMIT = 1000;

    /** The first day a risk assessment could have been sealed - an earlier date is answered unknown. */
    public static final LocalDate EARLIEST = LocalDate.of(2026, 1, 1);

    private static final String ACT = "assessment-day-replay";

    /** One operation's replays. */
    public record Operation(RiskOperation operation, long replayed, long identical, long diverged) {
        public Operation {
            Objects.requireNonNull(operation, "operation");
        }
    }

    /** The first divergent assessment the reader may be shown: its id, its operation and what diverged. */
    public record First(RiskAssessmentId assessment, RiskOperation operation, List<AssessmentReplay.Divergence> diverged,
            OptionalInt firstDivergedRule) {
        public First {
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(operation, "operation");
            diverged = List.copyOf(diverged);
            Objects.requireNonNull(firstDivergedRule, "firstDivergedRule");
        }
    }

    /**
     * One page of the day's replay: {@code known} false for a date the platform has not had - nothing replayed;
     * {@code after} the cursor it continued from, {@code next} the cursor to continue with while more remain.
     */
    public record Result(RiskDay day, boolean known, List<Operation> operations, long withheld, Optional<First> first,
            Optional<RiskAssessmentId> after, Optional<RiskAssessmentId> next) {
        public Result {
            Objects.requireNonNull(day, "day");
            operations = List.copyOf(operations);
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(after, "after");
            Objects.requireNonNull(next, "next");
        }

        public long replayed() {
            return operations.stream().mapToLong(Operation::replayed).sum();
        }

        public long identical() {
            return operations.stream().mapToLong(Operation::identical).sum();
        }

        public long diverged() {
            return operations.stream().mapToLong(Operation::diverged).sum();
        }

        /** The page's verdict - {@code IDENTICAL} when nothing of it diverged. */
        public AssessmentReplay.Outcome verdict() {
            return diverged() == 0 ? AssessmentReplay.Outcome.IDENTICAL : AssessmentReplay.Outcome.DIVERGED;
        }
    }

    private final RiskProofReads reads;
    private final RiskPolicies policies;
    private final RiskActingParty actingParties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public DailyReplay(RiskProofReads reads, RiskPolicies policies, RiskActingParty actingParties,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The first page of {@code day} for {@code actor}, {@link #DEFAULT_LIMIT} assessments at most. */
    public Result replay(Connection snapshot, RiskDay day, Actor actor) {
        return replay(snapshot, day, actor, Optional.empty(), DEFAULT_LIMIT);
    }

    /**
     * At most {@code limit} assessments of {@code day} after {@code after}, replayed for {@code actor} on
     * {@code snapshot} - nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the reader is placed in no party (fail closed)
     * @throws IllegalArgumentException the reader is not a person; a limit below one; a cursor not of the day
     */
    public Result replay(Connection snapshot, RiskDay day, Actor actor, Optional<RiskAssessmentId> after, int limit) {
        RiskProofs.requireSnapshot(snapshot);
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(after, "after");
        if (limit < 1) {
            throw new IllegalArgumentException("a replay page holds at least one assessment");
        }
        if (after.isPresent() && !day.holds(after.get().value())) {
            throw new IllegalArgumentException("a replay's cursor is an assessment of the day it replays");
        }
        int bound = Math.min(limit, MAX_LIMIT);
        UUID own = Queues.reader(snapshot, actingParties, actor, ACT);
        if (day.date().isBefore(EARLIEST) || day.start().isAfter(reads.databaseNow(snapshot))) {
            return new Result(day, false, List.of(), 0, Optional.empty(), after, Optional.empty());
        }
        Map<RiskOperation, long[]> counts = new EnumMap<>(RiskOperation.class);
        Map<RiskPolicyVersionId, Optional<RiskPolicy>> pinned = new HashMap<>();
        long withheld = 0;
        Optional<First> first = Optional.empty();
        Optional<RiskAssessmentId> cursor = after;
        int replayed = 0;
        boolean exhausted = false;
        while (replayed < bound) {
            int requested = Math.min(PAGE, bound - replayed);
            List<RiskAssessment> page = reads.assessmentsOfDay(snapshot, day, cursor, requested);
            for (RiskAssessment assessment : page) {
                Optional<RiskPolicy> version = assessment.pin().flatMap(pin -> pinned.computeIfAbsent(pin.id(),
                        id -> read(snapshot, id)));
                AssessmentReplay.Replay replay = AssessmentReplay.replay(assessment, version);
                long[] operation = counts.computeIfAbsent(assessment.operation(), ignored -> new long[3]);
                operation[0]++;
                replayed++;
                cursor = Optional.of(assessment.id());
                if (replay.verdict() == AssessmentReplay.Outcome.IDENTICAL) {
                    operation[1]++;
                    continue;
                }
                operation[2]++;
                if (assessment.subjects().party().equals(own)) {
                    withheld++;
                } else if (first.isEmpty()) {
                    first = Optional.of(new First(assessment.id(), assessment.operation(), replay.diverged(),
                            replay.firstDivergedRule()));
                }
            }
            if (page.size() < requested) {
                exhausted = true;
                break;
            }
        }
        Optional<RiskAssessmentId> next = Optional.empty();
        if (!exhausted && replayed > 0 && !reads.assessmentsOfDay(snapshot, day, cursor, 1).isEmpty()) {
            next = cursor;
        }
        List<Operation> operations = new ArrayList<>();
        counts.forEach((operation, figures) -> operations.add(
                new Operation(operation, figures[0], figures[1], figures[2])));
        return new Result(day, true, operations, withheld, first, after, next);
    }

    /** Records {@code result} - {@code risk.Replayed} for the day, the analyst's reason, counts and ids only. */
    public void record(Connection unitOfWork, Result result, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.REPLAYED,
                TARGET_TYPE, result.day().toString(), Optional.of(RiskReasons.reasoned(reason)), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("date=" + result.day() + ", known=" + result.known() + ", after="
                        + result.after().map(id -> id.value().toString()).orElse("none") + ", replayed="
                        + result.replayed() + ", identical=" + result.identical() + ", diverged=" + result.diverged()
                        + ", withheld=" + result.withheld() + ", first="
                        + result.first().map(found -> found.assessment().value().toString()).orElse("none")
                        + ", next=" + result.next().map(id -> id.value().toString()).orElse("none"))));
    }

    /** Records a refused replay {@code FAILED} ({@code risk.SelfDealingRefused}) - a transaction of the caller's own. */
    public void recordRefusal(Connection unitOfWork, RiskDay day, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, day.toString(), Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    private Optional<RiskPolicy> read(Connection snapshot, RiskPolicyVersionId id) {
        try {
            return Optional.of(policies.version(snapshot, id).policy());
        } catch (RiskPolicyAdministration.PolicyNotFound absent) {
            return Optional.empty();
        }
    }
}
