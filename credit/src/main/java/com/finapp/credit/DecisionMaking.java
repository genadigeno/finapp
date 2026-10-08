package com.finapp.credit;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The deciding transaction (`P10-TSK-016`; PHASE_10_PLAN.md section 12.7, ADR-0087; {@code INV-CRD-06},
 * {@code INV-CRD-09}, {@code INV-CRD-02}, {@code INV-HIST-04}, {@code INV-CRD-01}): an {@code EVALUATED} request's
 * decision recorded exactly once, in one transaction, under the lock order's first two elements.
 *
 * <ol>
 *   <li><strong>The party's profile {@code FOR UPDATE}</strong> (element (1)), first - so every decision for one party
 *       serialises, and the second sees the first's reservation;
 *   <li>the request {@code FOR UPDATE} (element (2)), conditional on {@code EVALUATED} and unexpired on the database's
 *       clock (the expiry and the system decision are complementary conditionals; the trigger re-judges both);
 *   <li>the party's standing and the consent gate re-read - either lost abandons the request, nothing decided;
 *   <li>the pinned versions {@code FOR SHARE} (element (5)), whatever has been activated since;
 *   <li>the reserved exposure re-read under the profile lock - when it differs from the snapshot's, a successor snapshot
 *       (the same records, the new exposure; the next sequence), assessed and evaluated under the same pinned versions;
 *   <li>the evaluation's outcome: an approval or a decline is recorded - the decision and its reasons, the request
 *       {@code DECIDED}, {@code credit.CreditDecisionRecorded} and {@code credit.DecisionRecorded}; <strong>a referral
 *       opens the underwriting case</strong> (`P10-TSK-018`, ADR-0089 point 1) - born {@code OPEN} once per request with
 *       its basis evaluation, the referral's ceiling and the product's four-eyes threshold, the request
 *       {@code IN_REVIEW}, {@code credit.ManualReviewRequired}.
 * </ol>
 *
 * <p>Steps 3-5 ({@link #basisWithin}) and the recording ({@link #recordWithin}) are shared with a person's decision
 * ({@link UnderwritingCases}), which runs the same transaction under the case's lock and with the person as decider -
 * one deciding transaction, two deciders.
 *
 * <p>Ten deciders on one request: the profile lock queues them, the first records, the rest find the request
 * {@code DECIDED} (or {@code IN_REVIEW}) and do nothing - and {@code UNIQUE (decision_request_id)} beneath, on the decision
 * and on the case. The observer is told after the commit.
 */
@RequiredArgsConstructor
public final class DecisionMaking implements Decider {

    static final String RECORDED_EVENT = "credit.CreditDecisionRecorded";
    static final String REVIEW_EVENT = "credit.ManualReviewRequired";
    static final String TARGET_TYPE = "credit_decision";
    static final String CASE_TYPE = "underwriting_case";

    @NonNull private final TransactionRunner transactions;
    @NonNull private final DecisionRequestStore requests;
    @NonNull private final CreditProfiles<Connection> profiles;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final ScorecardStore scorecards;
    @NonNull private final SnapshotFreezer freezer;
    @NonNull private final CreditAssessments assessments;
    @NonNull private final CreditAssessmentStore assessmentStore;
    @NonNull private final PolicyEvaluations evaluations;
    @NonNull private final PolicyEvaluationStore evaluationStore;
    @NonNull private final JdbcCreditDecisions decisions;
    @NonNull private final UnderwritingCaseStore cases;
    @NonNull private final CreditPartyStanding<Connection> standing;
    @NonNull private final CreditConsentGate<Connection> consents;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final DecisionObserver observer;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What the deciding transaction found under its locks, before it records anything. */
    sealed interface Basis permits Basis.Abandoned, Basis.Ready {

        /** The party's standing or a consent was lost: the request is closed {@code ABANDONED}, nothing decided. */
        record Abandoned(ClosureReason reason) implements Basis {}

        /** The pinned policy, the snapshot a decision is made from - a successor when the reservation moved - and its evaluation. */
        record Ready(CreditPolicyStore.PolicyVersion policy, DecisionSnapshot snapshot, PolicyEvaluation evaluation)
                implements Basis {}
    }

    /** A recorded decision with what its observer is told once the transaction commits. */
    public record Recorded(CreditDecision decision, int policyVersion, Duration latency) {}

    @Override
    public Decided decide(DecisionRequestId id, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        Actor platform = SecurityContext.require();
        AtomicReference<Recorded> recorded = new AtomicReference<>();
        Decided decided = transactions.inTransaction(uow -> decideWithin(uow, id, platform, correlation, recorded));
        if (recorded.get() != null) {
            observe(recorded.get());
        }
        return decided;
    }

    /** Tells the observer of a committed decision - the caller's after-commit step. */
    void observe(Recorded recorded) {
        observer.recorded(recorded.decision(), recorded.policyVersion(), recorded.latency());
    }

    private Decided decideWithin(
            Connection uow, DecisionRequestId id, Actor platform, CorrelationId correlation, AtomicReference<Recorded> recorded) {
        Optional<UUID> party = requests.partyOf(uow, id);
        if (party.isEmpty() || profiles.lockForDecision(uow, party.get()).isEmpty()) {
            return Decided.NOTHING;
        }
        Optional<DecisionRequestStore.Locked> locked = requests.lock(uow, id);
        if (locked.isEmpty() || locked.get().request().status() != DecisionRequestStatus.EVALUATED || locked.get().expired()) {
            return Decided.NOTHING;
        }
        DecisionRequest request = locked.get().request();
        Basis basis = basisWithin(uow, request, platform, correlation);
        if (!(basis instanceof Basis.Ready ready)) {
            return Decided.ABANDONED;
        }
        EvaluationResult result = ready.evaluation().result();
        if (result.outcome() == EvaluationOutcome.REFER) {
            return refer(uow, request, ready, platform, correlation) ? Decided.REFERRED : Decided.NOTHING;
        }
        DecisionOutcome outcome = result.outcome() == EvaluationOutcome.APPROVE ? DecisionOutcome.APPROVED
                : DecisionOutcome.DECLINED;
        Optional<Recorded> made = recordWithin(uow, request, DecisionRequestStatus.EVALUATED, ready, outcome,
                result.approved(), result.reasons(), platform.id(), platform.type().name(), platform, correlation);
        if (made.isEmpty()) {
            return Decided.NOTHING;
        }
        recorded.set(made.get());
        return Decided.DECIDED;
    }

    /**
     * Steps 3-5 under the caller's profile and request locks: the standing and the consent gate re-read (either lost
     * closes the request {@code ABANDONED}), the pinned versions {@code FOR SHARE}, and the reserved exposure re-read -
     * a successor snapshot assessed and evaluated when it moved.
     */
    Basis basisWithin(Connection uow, DecisionRequest request, Actor actor, CorrelationId correlation) {
        RequestClosures closures = new RequestClosures(requests, outbox, ids, clock);
        if (!standing.inGoodStanding(uow, request.party())) {
            closures.close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.STANDING_LOST), actor,
                    correlation);
            return new Basis.Abandoned(ClosureReason.STANDING_LOST);
        }
        PinnedVersions pinned = request.pinned()
                .orElseThrow(() -> new IllegalStateException("an evaluated request carries its pinned versions"));
        CreditPolicyVersionId policyId = CreditPolicyVersionId.of(pinned.policyVersion());
        if (!policies.sharePinned(uow, policyId)
                || !scorecards.sharePinned(uow, ScorecardModelVersionId.of(pinned.modelVersion()))) {
            throw new IllegalStateException("the request pins a version that does not exist");
        }
        CreditPolicyStore.PolicyVersion pinnedPolicy = policies.policy(uow, policyId).orElseThrow();
        CreditPolicy policy = pinnedPolicy.policy();
        for (CreditSourceKind kind : policy.sourceKinds()) {
            if (!consents.permits(uow, request.party(), kind)) {
                closures.close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.CONSENT_WITHDRAWN),
                        actor, correlation);
                return new Basis.Abandoned(ClosureReason.CONSENT_WITHDRAWN);
            }
        }
        DecisionSnapshot snapshot = freezer.latest(uow, request.id().value())
                .orElseThrow(() -> new IllegalStateException("an evaluated request has its snapshot"));
        Money reserved = freezer.reservedFor(uow, request.party(), request.application().product().currency());
        PolicyEvaluation evaluation;
        if (reserved.equals(reservedIn(snapshot))) {
            CreditAssessment assessment = assessmentStore.bySnapshot(uow, snapshot.id())
                    .orElseThrow(() -> new IllegalStateException("an evaluated request's snapshot is assessed"));
            evaluation = evaluationStore.byAssessment(uow, assessment.id())
                    .orElseThrow(() -> new IllegalStateException("an evaluated request's assessment is evaluated"));
        } else {
            // The reservation moved since the evaluation: a successor snapshot decides (section 12.7).
            snapshot = freezer.successor(uow, snapshot, reserved).snapshot();
            CreditAssessment assessment = assessments.assess(uow, snapshot, CreditAssessments.Terms.of(policy), correlation)
                    .assessment();
            evaluation = evaluations.evaluate(uow, snapshot, assessment).evaluation();
        }
        return new Basis.Ready(pinnedPolicy, snapshot, evaluation);
    }

    /**
     * Records the decision under the caller's locks: the decision and its reasons, the request {@code from -> DECIDED},
     * {@code credit.CreditDecisionRecorded} and {@code credit.DecisionRecorded} by {@code actor}; empty when the request
     * already had a decision.
     */
    Optional<Recorded> recordWithin(
            Connection uow,
            DecisionRequest request,
            DecisionRequestStatus from,
            Basis.Ready basis,
            DecisionOutcome outcome,
            Optional<Money> approved,
            List<ReasonCode> reasons,
            String decidedBy,
            String decidedByType,
            Actor actor,
            CorrelationId correlation) {
        CreditDecisionId decisionId = CreditDecisionId.next(ids);
        if (!decisions.insert(uow, new JdbcCreditDecisions.NewDecision(decisionId, request, basis.snapshot(), outcome,
                approved, reasons, request.application().product().decisionValidity(), decidedBy, decidedByType))) {
            return Optional.empty();
        }
        if (!requests.transition(uow, request.id(), EnumSet.of(from), DecisionRequestStatus.DECIDED, Optional.empty(),
                actor, Optional.empty())) {
            throw new IllegalStateException("the locked request moved under its own lock");
        }
        CreditDecision decision = decisions.byId(uow, decisionId)
                .orElseThrow(() -> new IllegalStateException("a decision is readable once written"));
        publish(uow, decision, correlation);
        audit.append(uow, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                CreditAuditAction.DECISION_RECORDED,
                TARGET_TYPE,
                decisionId.value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("decision " + decisionId.value() + " for request " + request.id().value() + ": "
                        + outcome.name())));
        return Optional.of(new Recorded(decision, basis.policy().row().version(),
                Duration.between(request.submittedAt(), decision.decidedAt())));
    }

    /**
     * The referral: the case born {@code OPEN} with its basis, the referral's ceiling and the product's threshold, the
     * request {@code EVALUATED -> IN_REVIEW}, and {@code credit.ManualReviewRequired}; false when the request already had
     * its case.
     */
    private boolean refer(
            Connection uow, DecisionRequest request, Basis.Ready basis, Actor platform, CorrelationId correlation) {
        UnderwritingCaseId caseId = UnderwritingCaseId.next(ids);
        Money ceiling = UnderwritingCases.ceiling(basis.evaluation().result(), basis.policy().policy());
        if (!cases.open(uow, new UnderwritingCaseStore.NewCase(caseId, request, basis.evaluation().id(), ceiling,
                request.application().product().fourEyesThreshold()), platform)) {
            return false;
        }
        if (!requests.transition(uow, request.id(), EnumSet.of(DecisionRequestStatus.EVALUATED),
                DecisionRequestStatus.IN_REVIEW, Optional.empty(), platform, Optional.empty())) {
            throw new IllegalStateException("the locked request moved under its own lock");
        }
        // Identifiers and catalogue codes only - never an attribute. One field per code, so no count of codes can
        // outgrow a payload value.
        List<ReasonCode> reasons = basis.evaluation().result().reasons();
        EventPayload payload = EventPayload.of()
                .with("decisionRequestId", request.id().value().toString())
                .with("caseId", caseId.value().toString())
                .with("referralReasonCount", Integer.toString(reasons.size()));
        for (int i = 0; i < reasons.size(); i++) {
            payload = payload.with("referralReasonCode" + (i + 1), reasons.get(i).code());
        }
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        REVIEW_EVENT,
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        caseId,
                        CASE_TYPE,
                        clock.instant(),
                        CreditDataCollection.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
        return true;
    }

    private static Money reservedIn(DecisionSnapshot snapshot) {
        return ((AttributeValue.MoneyValue) snapshot.content().attribute(CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE)
                .value()).value();
    }

    /** {@code credit.CreditDecisionRecorded} - identifiers, the outcome, minor units; never an attribute or a score. */
    private void publish(Connection uow, CreditDecision decision, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("decisionRequestId", decision.decisionRequest().toString())
                .with("decisionId", decision.id().value().toString())
                .with("outcome", decision.outcome().name())
                .with("currency", decision.requested().currency().code())
                .with("validUntilEpochMilli", Long.toString(decision.validUntil().toEpochMilli()))
                .with("policyVersionId", decision.versions().policyVersion().toString())
                .with("modelVersionId", decision.versions().modelVersion().toString())
                .with("engineVersion", Integer.toString(decision.versions().engineVersion()))
                .with("snapshotSha256", decision.snapshotSha256Hex());
        if (decision.approved().isPresent()) {
            payload = payload.with("approvedMinor", Long.toString(decision.approved().get().minorUnits()));
        }
        if (decision.termMonths().isPresent()) {
            payload = payload.with("termMonths", decision.termMonths().get().toString());
        }
        if (!decision.reasons().isEmpty()) {
            // Catalogue codes hold hyphens and never an underscore, so the underscore separates them unambiguously.
            payload = payload.with("reasonCodes",
                    decision.reasons().stream().map(ReasonCode::code).collect(Collectors.joining("_")));
        }
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        RECORDED_EVENT,
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        decision.id(),
                        TARGET_TYPE,
                        clock.instant(),
                        CreditDataCollection.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
