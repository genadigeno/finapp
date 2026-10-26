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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The analyst's reads of a sealed assessment (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 9, 11;
 * {@code RISK_LIFECYCLES.md} section 4.2; {@code INV-RSK-01}, {@code INV-RSK-08}; bnpl's {@code BnplExplanations}):
 * the read, and the replay from the sealed inputs under the pinned policy version and engine - each audited, neither
 * writing anything else.
 *
 * <ul>
 *   <li><strong>{@link #read}</strong> - on the caller's unit of work: the self-dealing judgement, the assessment whole,
 *       and {@code risk.AssessmentRead} appended to the same unit of work, so nothing is served unrecorded.</li>
 *   <li><strong>{@link #replay}</strong> - on the caller's read-only {@code REPEATABLE READ} snapshot: the assessment and
 *       its pinned version read at one instant, {@link AssessmentReplay} run over them; the caller then records
 *       {@code risk.Replayed} with the analyst's reason by {@link #recordReplay}, in a transaction of its own, before
 *       answering.</li>
 * </ul>
 *
 * <p><strong>Self-dealing fails closed</strong> (section 11; the Phase 12 → 13 gate's A7-6): an analyst the platform cannot
 * place in a party is refused before anything is read; one whose party is the assessment's party is refused before
 * anything is served - {@link SelfDealingRefused}, which the caller records {@code FAILED} in a transaction of its own
 * ({@link #recordRefusal}). An unknown assessment is {@link AssessmentNotFound}, the uniform {@code 404}. What is served
 * is CONFIDENTIAL and never logged.
 */
public final class RiskAssessments {

    /** The audit target type of every assessment read. */
    public static final String TARGET_TYPE = "risk_assessment";

    private final RiskAssessmentStore store;
    private final RiskPolicies policies;
    private final RiskActingParty actingParties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public RiskAssessments(RiskAssessmentStore store, RiskPolicies policies, RiskActingParty actingParties,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** No assessment has this id ({@code 404 risk.NotFound}). */
    public static final class AssessmentNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AssessmentNotFound() {
            super("no risk assessment answers the request");
        }
    }

    /** The reader may not read this assessment ({@code 403 risk.SelfDealingRefused}) - nothing was served. */
    public static final class SelfDealingRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        /**
         * Why: the reader's own party, or a reader the platform cannot place in any party - or (`P13-TSK-015`, an
         * alert's suppression) a subject whose party the platform cannot place, so the judgement cannot be made and
         * fails closed.
         */
        public enum Cause { OWN_PARTY, UNPLACEABLE, SUBJECT_UNPLACEABLE }

        private final Cause refusal;
        private final String act;

        SelfDealingRefused(Cause refusal, String act) {
            super("a holder never reads a risk assessment of their own party; nothing was served");
            this.refusal = refusal;
            this.act = act;
        }

        public Cause refusal() {
            return refusal;
        }

        /** The act refused - {@code assessment-read} or {@code assessment-replay}. */
        public String act() {
            return act;
        }
    }

    /**
     * {@code id} read for {@code actor}, {@code risk.AssessmentRead} appended to {@code unitOfWork}.
     *
     * @throws SelfDealingRefused the assessment is the reader's own party's, or the reader is placed in no party
     * @throws AssessmentNotFound no such assessment
     */
    public RiskAssessment read(Connection unitOfWork, RiskAssessmentId id, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        RiskAssessment assessment = judged(unitOfWork, id, actor, "assessment-read");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.ASSESSMENT_READ, TARGET_TYPE, id.value().toString(), Optional.empty(),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("operation=" + assessment.operation().name()
                        + ", decision=" + assessment.decision().name() + ", seal="
                        + (assessment.sealHolds() ? "HOLDS" : "BROKEN"))));
        return assessment;
    }

    /**
     * {@code assessment} explained (`P13-TSK-023`; {@link Explanation}) on the read's own unit of work: its sealed rows
     * and its pinned version's band list - the version read by id, immutable; never the engine. A version that cannot be
     * read leaves the bands unnamed.
     */
    public Explanation explanation(Connection unitOfWork, RiskAssessment assessment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(assessment, "assessment");
        return Explanation.of(assessment, pinned(unitOfWork, assessment));
    }

    /**
     * {@code id} replayed for {@code actor} on the caller's read-only snapshot - nothing recorded here.
     *
     * @throws SelfDealingRefused the assessment is the reader's own party's, or the reader is placed in no party
     * @throws AssessmentNotFound no such assessment
     */
    public AssessmentReplay.Replay replay(Connection snapshot, RiskAssessmentId id, Actor actor) {
        RiskAssessment assessment = judged(snapshot, id, actor, "assessment-replay");
        return AssessmentReplay.replay(assessment, pinned(snapshot, assessment));
    }

    /** The content of the version {@code assessment} pinned, when it pinned one and it can be read. */
    private Optional<RiskPolicy> pinned(Connection unitOfWork, RiskAssessment assessment) {
        return assessment.pin().flatMap(pin -> {
            try {
                return Optional.of(policies.version(unitOfWork, pin.id()).policy());
            } catch (RiskPolicyAdministration.PolicyNotFound absent) {
                return Optional.empty();
            }
        });
    }

    /** Records {@code replay} - its verdict and the kinds that diverged, never a figure - with the analyst's reason. */
    public void recordReplay(Connection unitOfWork, AssessmentReplay.Replay replay, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(replay, "replay");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.REPLAYED,
                TARGET_TYPE, replay.assessment().value().toString(), Optional.of(RiskReasons.reasoned(reason)),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("verdict=" + replay.verdict().name() + ", diverged="
                        + (replay.diverged().isEmpty() ? "none" : replay.diverged().stream()
                                .map(Enum::name).collect(Collectors.joining("|"))))));
    }

    /** The analyst's reason, judged before anything is read: present, bounded, never an instrument's shape. */
    public static String reasoned(String reason) {
        return RiskReasons.reasoned(reason);
    }

    /**
     * Records a refused read or replay {@code FAILED} on {@code unitOfWork} - a transaction of its own, after the act was
     * abandoned, so the attempt survives. Its target the assessment; the summary the act and the cause - never the party.
     */
    public void recordRefusal(Connection unitOfWork, RiskAssessmentId id, SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, id.value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation,
                Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    private RiskAssessment judged(Connection unitOfWork, RiskAssessmentId id, Actor actor, String act) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Optional<UUID> own = actingParties.partyOf(unitOfWork, actor);
        if (own.isEmpty()) {
            throw new SelfDealingRefused(SelfDealingRefused.Cause.UNPLACEABLE, act);
        }
        RiskAssessment assessment = store.read(unitOfWork, id).orElseThrow(AssessmentNotFound::new);
        if (assessment.subjects().party().equals(own.get())) {
            throw new SelfDealingRefused(SelfDealingRefused.Cause.OWN_PARTY, act);
        }
        return assessment;
    }
}
