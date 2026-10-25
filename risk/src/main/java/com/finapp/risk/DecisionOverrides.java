package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The decision override, under four eyes (`P13-TSK-012`; T-ovr, {@code PHASE_13_PLAN.md} sections 4, 5, 7.1, 11, 12.3;
 * {@code RISK_LIFECYCLES.md} section 4.2a; ADR-0108 decision 7, owner decision C7; {@code INV-RSK-04},
 * {@code INV-RSK-07}, {@code INV-AUD-04}) - a {@code RISK_OVERRIDE} holder's second, human judgement recorded beside a
 * sealed assessment, never in it.
 *
 * <ul>
 *   <li><strong>{@link #propose}</strong> - of a {@code DENY}, {@code HOLD} or {@code REVIEW} assessment, with a reason
 *       code of {@link OverrideReason} and a text, and a {@link OverrideResolution}: the {@link OverrideEffect} the
 *       approval will issue is fixed now ({@link OverrideEffect#of}); an {@code ALLOW} or a {@code CHALLENGE} is never
 *       overridden ({@link ActionNotProposable}). <strong>C7 is fixed now too</strong> ({@link #requiresSecondPerson}): a
 *       second holder for a {@code DENY}, at or above the pinned version's override threshold, and - failing closed -
 *       wherever the threshold cannot be compared. One standing override per assessment (the store's partial
 *       uniques).</li>
 *   <li><strong>{@link #approve}</strong> - the row under its lock (R4), {@code PROPOSED} only; the proposer refused
 *       where a second person is required ({@link SelfApprovalRefused} - the caller records it {@code FAILED} in a
 *       transaction of its own; {@code risk V007}'s four-eyes {@code CHECK} holds every writer alone); below the
 *       threshold the proposer's own reasoned approval completes it, audited as such. The approval <em>issues</em> the
 *       action into the action machine in its own transaction ({@link ActionIssuer} - {@link RiskActions#issued}: the
 *       {@code risk_action} row under the override's own id, so its key is {@code risk.action:<override id>}, announced
 *       {@code risk.RiskActionRequested}; `P13-TSK-017`'s fold); the dispatch is T-act's, after the commit, never
 *       here.</li>
 *   <li><strong>{@link #reject}</strong> - anyone, the proposer's withdrawal included; issues nothing - the decision
 *       stands.</li>
 *   <li><strong>{@link #read}</strong> / {@link #readOne} - the assessment the officer overrides and its overrides,
 *       served under the override desk's own permission (roles are a partition: the officer holds no analyst's read),
 *       audited {@code risk.AssessmentRead} on the read's own transaction.</li>
 * </ul>
 *
 * <p><strong>Self-dealing fails closed on every act and read</strong> ({@code PHASE_13_PLAN.md} section 11): an officer
 * the platform cannot place in a party is refused before anything is read; one whose party is the assessment's party
 * before anything is served or written ({@link RiskAssessments.SelfDealingRefused}, recorded {@code FAILED} by
 * {@link #recordRefusal(Connection, RiskAssessmentId, RiskAssessments.SelfDealingRefused, Actor, CorrelationId)}).
 * <strong>The assessment is never written</strong>: this class holds no write path to it - it reads it, and the four
 * assessment tables refuse every {@code UPDATE} for every writer ({@code risk V007}).
 */
public final class DecisionOverrides {

    /** The audit and event target type of every override act. */
    public static final String TARGET_TYPE = "decision_override";

    private final DecisionOverrideStore store;
    private final RiskAssessmentStore assessments;
    private final RiskPolicies policies;
    private final RiskActingParty actingParties;
    private final AuditWriter<Connection> audit;
    private final ActionIssuer issuer;
    private final IdGenerator ids;
    private final Clock clock;

    public DecisionOverrides(DecisionOverrideStore store, RiskAssessmentStore assessments, RiskPolicies policies,
            RiskActingParty actingParties, AuditWriter<Connection> audit, ActionIssuer issuer, IdGenerator ids,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ outcomes

    /** What a read serves: the assessment and its overrides, oldest first. */
    public record Served(RiskAssessment assessment, List<DecisionOverride> overrides) {
        public Served {
            Objects.requireNonNull(assessment, "assessment");
            overrides = List.copyOf(Objects.requireNonNull(overrides, "overrides"));
        }
    }

    /** A decision's outcome - the override after it; {@code replayed} when the same person's retry converged. */
    public record Decided(DecisionOverride override, boolean replayed) {
        public Decided {
            Objects.requireNonNull(override, "override");
        }
    }

    // ------------------------------------------------------------------ refusals

    /** No assessment or override answers the request - or the override is not the named assessment's. */
    public static final class OverrideNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        OverrideNotFound() {
            super("no risk record answers the request");
        }
    }

    /**
     * The override cannot be proposed: the decision is an {@code ALLOW} or a {@code CHALLENGE}, the resolution asks a
     * refusal of something not held, or the assessment already has a standing override.
     */
    public static final class ActionNotProposable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActionNotProposable(String detail) {
            super(detail);
        }
    }

    /** The override is no longer {@code PROPOSED} - decided by somebody else, or differently. */
    public static final class OverrideStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        OverrideStale(OverrideStatus status) {
            super("the override is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to approve where C7 requires a second holder ({@code INV-RSK-07}, {@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(DecisionOverride override) {
            super("an override at or above the threshold, or of a DENY, is approved by a second RISK_OVERRIDE holder;"
                    + " the proposer may reject it");
            this.target = override.id().value().toString();
            this.summary = "assessment=" + override.assessment().value() + ", effect=" + override.effect().name()
                    + ", act=override:approve";
        }

        /** The override the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the override, its effect and the act - never an amount or a person. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes an override of {@code assessmentId}'s decision by {@code actor}: the reason judged first, then the
     * actor's party, the assessment, the effect, C7's four eyes from the pinned version, and the row born
     * {@code PROPOSED} with {@code risk.OverrideProposed}.
     *
     * @throws RiskReasons.ReasonRequired for a reason code outside {@link OverrideReason}, or a missing, unbounded or
     *     instrument-bearing text
     * @throws RiskAssessments.SelfDealingRefused the officer's own party's assessment, or an officer placed in no party
     * @throws OverrideNotFound no such assessment
     * @throws ActionNotProposable an {@code ALLOW}, a {@code CHALLENGE}, a refusal asked of what is not held, or a
     *     standing override
     */
    public DecisionOverride propose(Connection unitOfWork, RiskAssessmentId assessmentId, OverrideResolution resolution,
            String reasonCode, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        OverrideReason code = reasonCode(reasonCode);
        String reasoned = RiskReasons.reasoned(reason);
        RiskAssessment assessment = judged(unitOfWork, assessmentId, actor, "override-propose");
        OverrideEffect effect = OverrideEffect.of(assessment.operation(), assessment.decision(), resolution)
                .orElseThrow(() -> new ActionNotProposable(notProposable(assessment.decision(), resolution)));
        Optional<UUID> target = effect.dispatches()
                ? Optional.of(UUID.fromString(assessment.commandRef().value()))
                : Optional.empty();
        boolean secondPerson = requiresSecondPerson(assessment, pinned(unitOfWork, assessment));
        DecisionOverrideId id = DecisionOverrideId.next(ids);
        if (!store.insert(unitOfWork, new DecisionOverrideStore.NewOverride(id, assessmentId, effect, target, code,
                reasoned, secondPerson, actor, correlation))) {
            throw new ActionNotProposable("the assessment already has a standing override - a live proposal or an"
                    + " approved one; decide that one");
        }
        DecisionOverride proposed = store.find(unitOfWork, id).orElseThrow(() -> new IllegalStateException(
                "an override inserted in this transaction is not found in it"));
        append(unitOfWork, actor, RiskAuditAction.OVERRIDE_PROPOSED, proposed, Optional.of(reasoned),
                "assessment=" + assessmentId.value() + ", decision=" + assessment.decision().name() + ", effect="
                        + effect.name() + ", reasonCode=" + code.name() + ", secondPerson="
                        + (secondPerson ? "REQUIRED" : "NOT_REQUIRED"),
                correlation);
        return proposed;
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves {@code overrideId} by {@code actor} with {@code reason}: under the row's lock, {@code PROPOSED} only, the
     * proposer refused where C7 requires a second holder; the approval issues the proposed effect - a dispatchable one
     * into the action machine through {@link ActionIssuer} (born {@code APPROVED} under this override's id, due at once,
     * announced {@code risk.RiskActionRequested}; `P13-TSK-017`) - audited {@code risk.OverrideApproved}. Nothing is
     * dispatched here. The same person's retry converges.
     *
     * @throws RiskReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     * @throws RiskAssessments.SelfDealingRefused the officer's own party's assessment, or an officer placed in no party
     * @throws OverrideNotFound no such override of the named assessment
     * @throws OverrideStale the override is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused the proposer approving where a second holder is required
     */
    public Decided approve(Connection unitOfWork, RiskAssessmentId assessmentId, DecisionOverrideId overrideId,
            String reason, Actor actor, CorrelationId correlation) {
        String reasoned = RiskReasons.reasoned(reason);
        DecisionOverride row = lockedFor(unitOfWork, assessmentId, overrideId, actor, "override-approve");
        if (row.status() == OverrideStatus.APPROVED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row, true);
        }
        if (row.status() != OverrideStatus.PROPOSED) {
            throw new OverrideStale(row.status());
        }
        boolean byProposer = row.proposedBy().equals(actor.id());
        if (byProposer && row.requiresSecondPerson()) {
            throw new SelfApprovalRefused(row);
        }
        DecisionOverride decided = store.approve(unitOfWork, overrideId, actor, reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"));
        append(unitOfWork, actor, RiskAuditAction.OVERRIDE_APPROVED, decided, Optional.of(reasoned),
                "assessment=" + assessmentId.value() + ", effect=" + decided.effect().name() + ", secondPerson="
                        + (decided.requiresSecondPerson() ? "REQUIRED" : "NOT_REQUIRED") + ", byProposer=" + byProposer,
                correlation);
        // The issued action, into the machine in this same transaction - decision_override_issues_its_action holds it.
        issuer.issue(unitOfWork, decided, correlation);
        DecisionOverride approved = store.find(unitOfWork, overrideId).orElseThrow(() -> new IllegalStateException(
                "an override approved in this transaction is not found in it"));
        return new Decided(approved, false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects {@code overrideId} by {@code actor} - anyone, the proposer's withdrawal included - with {@code reason};
     * issues nothing. The same person's retry converges.
     *
     * @throws RiskReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     * @throws RiskAssessments.SelfDealingRefused the officer's own party's assessment, or an officer placed in no party
     * @throws OverrideNotFound no such override of the named assessment
     * @throws OverrideStale the override is no longer {@code PROPOSED}
     */
    public Decided reject(Connection unitOfWork, RiskAssessmentId assessmentId, DecisionOverrideId overrideId,
            String reason, Actor actor, CorrelationId correlation) {
        String reasoned = RiskReasons.reasoned(reason);
        DecisionOverride row = lockedFor(unitOfWork, assessmentId, overrideId, actor, "override-reject");
        if (row.status() == OverrideStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row, true);
        }
        if (row.status() != OverrideStatus.PROPOSED) {
            throw new OverrideStale(row.status());
        }
        DecisionOverride rejected = store.reject(unitOfWork, overrideId, actor, reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"));
        append(unitOfWork, actor, RiskAuditAction.OVERRIDE_REJECTED, rejected, Optional.of(reasoned),
                "assessment=" + assessmentId.value() + ", effect=" + rejected.effect().name() + ", byProposer="
                        + rejected.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(rejected, false);
    }

    // ------------------------------------------------------------------ reads

    /**
     * {@code assessmentId} and every override of it, read for {@code actor} - {@code risk.AssessmentRead} appended to
     * {@code unitOfWork}, so nothing is served unrecorded.
     */
    public Served read(Connection unitOfWork, RiskAssessmentId assessmentId, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        RiskAssessment assessment = judged(unitOfWork, assessmentId, actor, "override-read");
        Served served = new Served(assessment, store.ofAssessment(unitOfWork, assessmentId));
        recordRead(unitOfWork, served, actor, correlation);
        return served;
    }

    /** {@code assessmentId} and its override {@code overrideId} alone, read for {@code actor} and recorded. */
    public Served readOne(Connection unitOfWork, RiskAssessmentId assessmentId, DecisionOverrideId overrideId,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(overrideId, "overrideId");
        Objects.requireNonNull(correlation, "correlation");
        RiskAssessment assessment = judged(unitOfWork, assessmentId, actor, "override-read");
        DecisionOverride override = store.find(unitOfWork, overrideId)
                .filter(found -> found.assessment().equals(assessmentId))
                .orElseThrow(OverrideNotFound::new);
        Served served = new Served(assessment, List.of(override));
        recordRead(unitOfWork, served, actor, correlation);
        return served;
    }

    /**
     * {@code overrideId} as it stands now - the receipt of the act its caller has just performed or replayed, the
     * issued action's outcome included; no lock, no audit (the act was audited, and no assessment is served).
     */
    public Optional<DecisionOverride> current(Connection unitOfWork, DecisionOverrideId overrideId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.find(unitOfWork, Objects.requireNonNull(overrideId, "overrideId"));
    }

    // ------------------------------------------------------------------ the refusals, recorded

    /**
     * Records a refused self-approval {@code FAILED} ({@code risk.SelfApprovalRefused}) on {@code unitOfWork} - the
     * caller's own transaction, after the act rolled back, so the attempt is kept ({@code INV-RSK-07}).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(),
                refused.summary(), correlation);
    }

    /**
     * Records a refused act or read {@code FAILED} ({@code risk.SelfDealingRefused}) on {@code unitOfWork} - a
     * transaction of its own, after the act was abandoned. Its target the assessment; the summary the act and the cause -
     * never the party.
     */
    public void recordRefusal(Connection unitOfWork, RiskAssessmentId assessmentId,
            RiskAssessments.SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, RiskAssessments.TARGET_TYPE, assessmentId.value().toString(),
                Optional.empty(), AuditOutcome.FAILED, correlation,
                Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    // ------------------------------------------------------------------ C7

    /**
     * C7, failing closed: a second {@code RISK_OVERRIDE} holder is required for a {@code DENY}, at or above the pinned
     * version's override threshold, and wherever that threshold cannot be compared with the judged amount - no pinned
     * version, no threshold, another currency or scale. {@code risk V009}'s birth rule derives the same.
     */
    public static boolean requiresSecondPerson(RiskAssessment assessment, Optional<RiskPolicy> pinned) {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(pinned, "pinned");
        if (assessment.decision() == RiskDecision.DENY) {
            return true;
        }
        Optional<Money> threshold = pinned.flatMap(RiskPolicy::overrideThreshold);
        if (threshold.isEmpty()) {
            return true;
        }
        Money judged = assessment.amount();
        if (!threshold.get().currency().equals(judged.currency()) || threshold.get().scale() != judged.scale()) {
            return true;
        }
        return judged.minorUnits() >= threshold.get().minorUnits();
    }

    // ------------------------------------------------------------------ plumbing

    private static OverrideReason reasonCode(String code) {
        return OverrideReason.named(code).orElseThrow(() -> new RiskReasons.ReasonRequired(
                "an override's reason code is one of the closed vocabulary: " + java.util.Arrays.toString(
                        OverrideReason.values())));
    }

    private static String notProposable(RiskDecision decision, OverrideResolution resolution) {
        return switch (decision) {
            case ALLOW -> "an ALLOW is never overridden - the money has moved; a case is the remedy";
            case CHALLENGE -> "a CHALLENGE is never overridden - it is the customer's to satisfy";
            default -> "a " + decision.name() + " has nothing held to " + resolution.name().toLowerCase(
                    java.util.Locale.ROOT) + " - its command is already refused";
        };
    }

    /** The pinned version's content, or empty when none is named or it cannot be read - C7 then fails closed. */
    private Optional<RiskPolicy> pinned(Connection unitOfWork, RiskAssessment assessment) {
        return assessment.pin().flatMap(pin -> {
            try {
                return Optional.of(policies.version(unitOfWork, pin.id()).policy());
            } catch (RiskPolicyAdministration.PolicyNotFound absent) {
                return Optional.empty();
            }
        });
    }

    /**
     * The self-dealing judgement, then the assessment: an officer placed in no party refused before anything is read,
     * one of the assessment's own party before anything is served or written.
     */
    private RiskAssessment judged(Connection unitOfWork, RiskAssessmentId assessmentId, Actor actor, String act) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(actor, "actor");
        Optional<UUID> own = actingParties.partyOf(unitOfWork, actor);
        if (own.isEmpty()) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE, act);
        }
        RiskAssessment assessment = assessments.read(unitOfWork, assessmentId).orElseThrow(OverrideNotFound::new);
        if (assessment.subjects().party().equals(own.get())) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, act);
        }
        return assessment;
    }

    /** The self-dealing judgement over the named assessment, then the override under its lock (R4) - the named one's. */
    private DecisionOverride lockedFor(Connection unitOfWork, RiskAssessmentId assessmentId,
            DecisionOverrideId overrideId, Actor actor, String act) {
        Objects.requireNonNull(overrideId, "overrideId");
        judged(unitOfWork, assessmentId, actor, act);
        return store.lock(unitOfWork, overrideId)
                .filter(found -> found.assessment().equals(assessmentId))
                .orElseThrow(OverrideNotFound::new);
    }

    private void recordRead(Connection unitOfWork, Served served, Actor actor, CorrelationId correlation) {
        RiskAssessment assessment = served.assessment();
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.ASSESSMENT_READ, RiskAssessments.TARGET_TYPE, assessment.id().value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of("operation="
                        + assessment.operation().name() + ", decision=" + assessment.decision().name() + ", seal="
                        + (assessment.sealHolds() ? "HOLDS" : "BROKEN") + ", door=override, overrides="
                        + served.overrides().size())));
    }

    private void append(Connection unitOfWork, Actor actor, RiskAuditAction action, DecisionOverride override,
            Optional<String> reason, String summary, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                override.id().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }
}
