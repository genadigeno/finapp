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
 * Every assessment of a day replayed (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3; ADR-0109 decision 4;
 * {@code INV-RSK-01}; {@code DESIGN-023} D7) - the per-assessment replay ({@link AssessmentReplay}) run over the whole
 * day, on ONE read-only {@code REPEATABLE READ} snapshot, page by page in identifier order. Each assessment replays
 * under <strong>its own pinned pair</strong> - the policy version it pinned, by id, and the engine it names - never the
 * version active now: a policy activated mid-day changes nothing of the morning's replay. The versions are memoised for
 * the run by id; they are immutable from their proposal on.
 *
 * <p>The answer counts {@code IDENTICAL} and {@code DIVERGED} per operation and names <strong>the first divergent</strong>
 * (the earliest by identifier) with the kinds that diverged - never a figure. The reader must be a person the platform can
 * place in a party (fail closed - {@link RiskAssessments.SelfDealingRefused}); an assessment of the reader's own party is
 * replayed and counted but never named ({@code withheld}, the queues' rule). A {@code DIVERGED} is a defect of the engine
 * or a corrupted row - reported, never repaired. Nothing is written but {@code risk.Replayed}, by
 * {@link #record} in the caller's transaction of its own, before the answer.
 */
public final class DailyReplay {

    /** The audit target type of a day's replay - its id the date. */
    public static final String TARGET_TYPE = "risk_assessment_day";

    /** How many assessments one page of the replay reads. */
    static final int PAGE = 200;

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

    /** The day's replay: {@code known} false for a date the platform has not had - nothing replayed. */
    public record Result(RiskDay day, boolean known, List<Operation> operations, long withheld, Optional<First> first) {
        public Result {
            Objects.requireNonNull(day, "day");
            operations = List.copyOf(operations);
            Objects.requireNonNull(first, "first");
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

        /** The day's verdict - {@code IDENTICAL} when nothing diverged. */
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

    /**
     * {@code day} replayed for {@code actor} on {@code snapshot} - nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the reader is placed in no party (fail closed)
     * @throws IllegalArgumentException the reader is not a person
     */
    public Result replay(Connection snapshot, RiskDay day, Actor actor) {
        RiskProofs.requireSnapshot(snapshot);
        Objects.requireNonNull(day, "day");
        UUID own = Queues.reader(snapshot, actingParties, actor, ACT);
        if (day.date().isBefore(EARLIEST) || day.start().isAfter(reads.databaseNow(snapshot))) {
            return new Result(day, false, List.of(), 0, Optional.empty());
        }
        Map<RiskOperation, long[]> counts = new EnumMap<>(RiskOperation.class);
        Map<RiskPolicyVersionId, Optional<RiskPolicy>> pinned = new HashMap<>();
        long withheld = 0;
        Optional<First> first = Optional.empty();
        Optional<RiskAssessmentId> after = Optional.empty();
        while (true) {
            List<RiskAssessment> page = reads.assessmentsOfDay(snapshot, day, after, PAGE);
            for (RiskAssessment assessment : page) {
                Optional<RiskPolicy> version = assessment.pin().flatMap(pin -> pinned.computeIfAbsent(pin.id(),
                        id -> read(snapshot, id)));
                AssessmentReplay.Replay replay = AssessmentReplay.replay(assessment, version);
                long[] operation = counts.computeIfAbsent(assessment.operation(), ignored -> new long[3]);
                operation[0]++;
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
            if (page.size() < PAGE) {
                break;
            }
            after = Optional.of(page.get(page.size() - 1).id());
        }
        List<Operation> operations = new ArrayList<>();
        counts.forEach((operation, figures) -> operations.add(
                new Operation(operation, figures[0], figures[1], figures[2])));
        return new Result(day, true, operations, withheld, first);
    }

    /** Records {@code result} - {@code risk.Replayed} for the day, the analyst's reason, counts and one id at most. */
    public void record(Connection unitOfWork, Result result, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.REPLAYED,
                TARGET_TYPE, result.day().toString(), Optional.of(RiskReasons.reasoned(reason)), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("date=" + result.day() + ", known=" + result.known() + ", replayed="
                        + result.replayed() + ", identical=" + result.identical() + ", diverged=" + result.diverged()
                        + ", withheld=" + result.withheld() + ", first="
                        + result.first().map(found -> found.assessment().value().toString()).orElse("none"))));
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
