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
 *       {@code DECIDED}, {@code credit.CreditDecisionRecorded} and {@code credit.DecisionRecorded}; a referral waits at
 *       {@code EVALUATED} for its case (`P10-TSK-018`).
 * </ol>
 *
 * <p>Ten deciders on one request: the profile lock queues them, the first records, the rest find the request
 * {@code DECIDED} and do nothing - and {@code UNIQUE (decision_request_id)} beneath. The observer is told after the commit.
 */
@RequiredArgsConstructor
public final class DecisionMaking implements Decider {

    static final String RECORDED_EVENT = "credit.CreditDecisionRecorded";
    static final String TARGET_TYPE = "credit_decision";

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
    @NonNull private final CreditPartyStanding<Connection> standing;
    @NonNull private final CreditConsentGate<Connection> consents;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final DecisionObserver observer;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    @Override
    public Decided decide(DecisionRequestId id, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        Actor platform = SecurityContext.require();
        AtomicReference<CreditDecision> recorded = new AtomicReference<>();
        AtomicReference<Duration> latency = new AtomicReference<>();
        AtomicReference<Integer> policyVersion = new AtomicReference<>();
        Decided decided = transactions.inTransaction(uow -> decideWithin(uow, id, platform, correlation, recorded, latency, policyVersion));
        if (recorded.get() != null) {
            observer.recorded(recorded.get(), policyVersion.get(), latency.get());
        }
        return decided;
    }

    private Decided decideWithin(
            Connection uow,
            DecisionRequestId id,
            Actor platform,
            CorrelationId correlation,
            AtomicReference<CreditDecision> recorded,
            AtomicReference<Duration> latency,
            AtomicReference<Integer> policyVersion) {
        Optional<UUID> party = requests.partyOf(uow, id);
        if (party.isEmpty() || profiles.lockForDecision(uow, party.get()).isEmpty()) {
            return Decided.NOTHING;
        }
        Optional<DecisionRequestStore.Locked> locked = requests.lock(uow, id);
        if (locked.isEmpty() || locked.get().request().status() != DecisionRequestStatus.EVALUATED || locked.get().expired()) {
            return Decided.NOTHING;
        }
        DecisionRequest request = locked.get().request();
        RequestClosures closures = new RequestClosures(requests, outbox, ids, clock);
        if (!standing.inGoodStanding(uow, request.party())) {
            closures.close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.STANDING_LOST), platform,
                    correlation);
            return Decided.ABANDONED;
        }
        PinnedVersions pinned = request.pinned()
                .orElseThrow(() -> new IllegalStateException("an EVALUATED request carries its pinned versions"));
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
                        platform, correlation);
                return Decided.ABANDONED;
            }
        }
        DecisionSnapshot snapshot = freezer.latest(uow, request.id().value())
                .orElseThrow(() -> new IllegalStateException("an EVALUATED request has its snapshot"));
        Money reserved = freezer.reservedFor(uow, request.party(), request.application().product().currency());
        EvaluationResult result;
        if (reserved.equals(reservedIn(snapshot))) {
            CreditAssessment assessment = assessmentStore.bySnapshot(uow, snapshot.id())
                    .orElseThrow(() -> new IllegalStateException("an EVALUATED request's snapshot is assessed"));
            result = evaluationStore.byAssessment(uow, assessment.id())
                    .orElseThrow(() -> new IllegalStateException("an EVALUATED request's assessment is evaluated"))
                    .result();
        } else {
            // The reservation moved since the evaluation: a successor snapshot decides (section 12.7).
            snapshot = freezer.successor(uow, snapshot, reserved).snapshot();
            CreditAssessment assessment = assessments.assess(uow, snapshot, CreditAssessments.Terms.of(policy), correlation)
                    .assessment();
            result = evaluations.evaluate(uow, snapshot, assessment).evaluation().result();
        }
        if (result.outcome() == EvaluationOutcome.REFER) {
            return Decided.REFERRED;
        }
        DecisionOutcome outcome = result.outcome() == EvaluationOutcome.APPROVE ? DecisionOutcome.APPROVED
                : DecisionOutcome.DECLINED;
        CreditDecisionId decisionId = CreditDecisionId.next(ids);
        if (!decisions.insert(uow, new JdbcCreditDecisions.NewDecision(decisionId, request, snapshot, outcome,
                result.approved(), result.reasons(), request.application().product().decisionValidity(), platform.id(),
                platform.type().name()))) {
            return Decided.NOTHING;
        }
        if (!requests.transition(uow, request.id(), EnumSet.of(DecisionRequestStatus.EVALUATED),
                DecisionRequestStatus.DECIDED, Optional.empty(), platform, Optional.empty())) {
            throw new IllegalStateException("the locked request moved under its own lock");
        }
        CreditDecision decision = decisions.byId(uow, decisionId)
                .orElseThrow(() -> new IllegalStateException("a decision is readable once written"));
        publish(uow, decision, correlation);
        audit.append(uow, new AuditRecord(
                AuditId.next(ids),
                platform,
                clock.instant(),
                CreditAuditAction.DECISION_RECORDED,
                TARGET_TYPE,
                decisionId.value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("decision " + decisionId.value() + " for request " + request.id().value() + ": "
                        + outcome.name())));
        recorded.set(decision);
        policyVersion.set(pinnedPolicy.row().version());
        latency.set(Duration.between(request.submittedAt(), decision.decidedAt()));
        return Decided.DECIDED;
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
