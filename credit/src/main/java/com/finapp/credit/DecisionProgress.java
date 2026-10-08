package com.finapp.credit;

import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A decision request driven from submission to evaluation (`P10-TSK-015`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1,
 * ADR-0087; {@code INV-CRD-03}, {@code INV-CRD-06}, {@code INV-CRD-08}, {@code INV-CRD-10}, {@code INV-HIST-04}) -
 * leaderless, crash-safe, on the database clock.
 *
 * <p><strong>The claim</strong> ({@link #claimDue}): due open requests, oldest permit first, in one statement that
 * re-stamps each permit and skips rows another sweeper holds - disjoint pages, and a step that cannot act simply lets its
 * permit lapse. <strong>Each step</strong> ({@link #step}) is a transaction of its own under the request's row lock
 * (lock order element (2)), and every edge it takes is a conditional the trigger re-judges - so ten sweepers on one
 * request take each edge once and the losers find the edge taken. Recovery is from the rows alone: whatever crashed,
 * the next step reads the state and continues.
 *
 * <p>In order, every step: the party's <em>standing</em> (lost: {@code ABANDONED}, {@code STANDING_LOST} - never
 * expired); the <em>expiry</em> on the database clock ({@code EXPIRED}); then the state's own edge -
 * <ul>
 *   <li>{@code SUBMITTED -> COLLECTING}: a current consent basis for every source kind the policy in force reads (one
 *       gone since submission: {@code ABANDONED}, {@code CONSENT_WITHDRAWN}); the {@code ACTIVE} policy and scorecard
 *       {@code FOR SHARE} (element (5)), pinned once with the engine version; one data request opened per source kind -
 *       asked after the commit, a crash covered by the retry sweep. A policy reading no source passes on to
 *       {@code READY} in the same transaction;
 *   <li>{@code COLLECTING -> READY}: every source kind the pinned policy reads answered - received, or unavailable past
 *       its deadline (its attributes then {@code ABSENT} for the fallback rule, {@code INV-CRD-10}); a withdrawal
 *       abandons the request;
 *   <li>{@code READY -> EVALUATED}: the pinned versions {@code FOR SHARE} even if since retired; the consent gate re-read
 *       for every kind (a withdrawal after a source answered abandons the request, nothing frozen); the freeze - a stale
 *       record re-collects ({@code READY -> COLLECTING}, the one backward edge, {@code INV-CRD-08}); the assessment and
 *       the evaluation, each born once.
 * </ul>
 * An {@code EVALUATED} request is handed to the {@link Decider} (`P10-TSK-016`) once the step's transaction is released.
 *
 * <p><strong>An {@code IN_REVIEW} request is its case's</strong> (`P10-TSK-018`, ADR-0089 point 7): the step locks the
 * case after the request (lock order element (3)) and acts only while the case is {@code OPEN} - the party's standing
 * lost abandons the request and closes the case ({@code STANDING_LOST}); the validity passed expires the request and
 * closes the case ({@code EXPIRED}). A taken case ({@code ASSIGNED}, {@code AWAITING_SECOND}) is its person's: the step
 * leaves it alone, so a taken case is never expired or abandoned under its underwriter, and the assignment and the
 * expiry, taking the same two locks in the same order, leave exactly one of {@code ASSIGNED}, {@code EXPIRED}.
 */
@Slf4j
@RequiredArgsConstructor
public final class DecisionProgress {

    @NonNull private final TransactionRunner transactions;
    @NonNull private final DecisionRequestStore requests;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final ScorecardStore scorecards;
    @NonNull private final DecisionSnapshotStore snapshots;
    @NonNull private final CreditDataCollection collection;
    @NonNull private final SnapshotFreezer freezer;
    @NonNull private final CreditAssessments assessments;
    @NonNull private final PolicyEvaluations evaluations;
    @NonNull private final CreditPartyStanding<Connection> standing;
    @NonNull private final CreditConsentGate<Connection> consents;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final Decider decider;
    @NonNull private final UnderwritingCaseStore cases;

    /** What one step did. */
    public enum Step {
        /** Nothing: the request is closed, or another step took the edge first. */
        NOTHING,
        /** The state's edge is not yet due - a source still answering; the permit lapses and a later step re-reads. */
        WAITING,
        COLLECTING,
        READY,
        RECOLLECTING,
        EVALUATED,
        /** An {@code EVALUATED} request handed to the deciding step - internal to {@link #step}, never returned. */
        DECIDING,
        DECIDED,
        /** The evaluation refers: the case is open and the request {@code IN_REVIEW} (`P10-TSK-018`). */
        REFERRED,
        EXPIRED,
        ABANDONED
    }

    /** Claims at most {@code limit} due requests, re-stamping each permit {@code permit} ahead. */
    public List<DecisionRequestId> claimDue(int limit, Duration permit) {
        Objects.requireNonNull(permit, "permit");
        return transactions.inTransaction(uow -> requests.claimDue(uow, limit, permit));
    }

    /** Takes {@code id}'s next step, if one is due, as the platform; then asks whatever it opened. */
    public Step step(DecisionRequestId id, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        Actor platform = SecurityContext.require();
        List<CreditDataRequestId> asks = new ArrayList<>();
        Step step = transactions.inTransaction(uow -> stepWithin(uow, id, platform, correlation, asks));
        if (step == Step.DECIDING) {
            // The claim transaction is released; the deciding transaction opens profile-first (element (1)).
            step = switch (decider.decide(id, correlation)) {
                case DECIDED -> Step.DECIDED;
                case REFERRED -> Step.REFERRED;
                case ABANDONED -> Step.ABANDONED;
                case NOTHING -> Step.NOTHING;
            };
        }
        for (CreditDataRequestId ask : asks) {
            try {
                collection.ask(ask, correlation);
            } catch (RuntimeException failure) {
                // The retry sweep re-asks under the same reference; the class name only.
                log.warn("A credit data ask after a progress step failed: {}", failure.getClass().getSimpleName());
            }
        }
        return step;
    }

    private Step stepWithin(
            Connection uow, DecisionRequestId id, Actor platform, CorrelationId correlation, List<CreditDataRequestId> asks) {
        Optional<DecisionRequestStore.Locked> locked = requests.lock(uow, id);
        if (locked.isEmpty() || !locked.get().request().status().open()) {
            return Step.NOTHING;
        }
        DecisionRequest request = locked.get().request();
        if (request.status() == DecisionRequestStatus.IN_REVIEW) {
            return review(uow, request, locked.get().expired(), platform, correlation);
        }
        if (!standing.inGoodStanding(uow, request.party())) {
            return close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.STANDING_LOST), platform,
                    correlation);
        }
        if (locked.get().expired()) {
            return close(uow, request, DecisionRequestStatus.EXPIRED, Optional.empty(), platform, correlation);
        }
        return switch (request.status()) {
            case SUBMITTED -> collect(uow, request, platform, correlation, asks);
            case COLLECTING -> ready(uow, request, platform, correlation);
            case READY -> evaluate(uow, request, platform, correlation, asks);
            case EVALUATED -> Step.DECIDING; // the deciding step, in a transaction of its own (P10-TSK-016)
            default -> throw new IllegalStateException("an open request in " + request.status());
        };
    }

    // ------------------------------------------------------------------ IN_REVIEW: an OPEN case closes with its request

    private Step review(Connection uow, DecisionRequest request, boolean expired, Actor platform, CorrelationId correlation) {
        UnderwritingCase locked = cases.lockByRequest(uow, request.id())
                .orElseThrow(() -> new IllegalStateException("an IN_REVIEW request has its case"));
        if (locked.status() != UnderwritingCaseStatus.OPEN) {
            return Step.WAITING; // a taken case is its person's (ADR-0089 points 6-7)
        }
        Step step;
        String reason;
        if (!standing.inGoodStanding(uow, request.party())) {
            step = close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.STANDING_LOST), platform,
                    correlation);
            reason = ClosureReason.STANDING_LOST.name();
        } else if (expired) {
            step = close(uow, request, DecisionRequestStatus.EXPIRED, Optional.empty(), platform, correlation);
            reason = DecisionRequestStatus.EXPIRED.name();
        } else {
            return Step.WAITING;
        }
        if (!cases.move(uow, locked, UnderwritingCases.closure(reason, locked), platform)) {
            throw new IllegalStateException("the locked case moved under its own lock");
        }
        return step;
    }

    // ------------------------------------------------------------------ SUBMITTED -> COLLECTING

    private Step collect(
            Connection uow, DecisionRequest request, Actor platform, CorrelationId correlation, List<CreditDataRequestId> asks) {
        CreditProduct product = request.application().product();
        Optional<CreditPolicyVersionId> policyId = policies.shareActive(uow, product);
        Optional<ScorecardModelVersionId> modelId = scorecards.shareActive(uow, ScorecardFamily.RETAIL_SCORECARD);
        if (policyId.isEmpty() || modelId.isEmpty()) {
            return Step.WAITING; // nothing in force to pin - the permit lapses and a later step reads again
        }
        CreditPolicy policy = policy(uow, policyId.get());
        if (!consented(uow, request, policy)) {
            return close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.CONSENT_WITHDRAWN),
                    platform, correlation);
        }
        PinnedVersions pinned = new PinnedVersions(policyId.get().value(), modelId.get().value(), PolicyEvaluatorV1.VERSION);
        if (!requests.pin(uow, request.id(), pinned, platform)) {
            return Step.NOTHING;
        }
        for (CreditSourceKind kind : policy.sourceKinds()) {
            CreditDataCollection.Opened opened = collection.openWithin(uow,
                    new CreditDataCollection.Opening(request.id().value(), request.party(), product, kind), correlation);
            if (!(opened instanceof CreditDataCollection.Opened.Requested requested)) {
                throw new IllegalStateException("the consent read moments ago in this transaction refused the opening");
            }
            asks.add(requested.id());
        }
        if (policy.sourceKinds().isEmpty()) {
            move(uow, request, DecisionRequestStatus.COLLECTING, DecisionRequestStatus.READY, platform);
            return Step.READY;
        }
        return Step.COLLECTING;
    }

    // ------------------------------------------------------------------ COLLECTING -> READY

    private Step ready(Connection uow, DecisionRequest request, Actor platform, CorrelationId correlation) {
        CreditPolicy policy = policy(uow, pinnedPolicy(request));
        List<DecisionSnapshotStore.DataRequestState> states = snapshots.lockDataRequestsOf(uow, request.id().value());
        boolean answered = true;
        for (CreditSourceKind kind : policy.sourceKinds()) {
            Optional<DecisionSnapshotStore.DataRequestState> latest = latest(states, kind);
            if (latest.isEmpty()) {
                answered = false;
                continue;
            }
            switch (latest.get().status()) {
                case CONSENT_WITHDRAWN -> {
                    return close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.CONSENT_WITHDRAWN),
                            platform, correlation);
                }
                case RECEIVED -> { }
                case UNAVAILABLE -> answered &= latest.get().pastDeadline();
                case REQUESTED -> answered = false;
            }
        }
        if (!answered) {
            return Step.WAITING;
        }
        move(uow, request, DecisionRequestStatus.COLLECTING, DecisionRequestStatus.READY, platform);
        return Step.READY;
    }

    // ------------------------------------------------------------------ READY -> EVALUATED

    private Step evaluate(
            Connection uow, DecisionRequest request, Actor platform, CorrelationId correlation, List<CreditDataRequestId> asks) {
        PinnedVersions pinned = request.pinned()
                .orElseThrow(() -> new IllegalStateException("a READY request carries its pinned versions"));
        CreditPolicyVersionId policyId = CreditPolicyVersionId.of(pinned.policyVersion());
        if (!policies.sharePinned(uow, policyId)
                || !scorecards.sharePinned(uow, ScorecardModelVersionId.of(pinned.modelVersion()))) {
            throw new IllegalStateException("the request pins a version that does not exist");
        }
        CreditPolicy policy = policy(uow, policyId);
        if (!consented(uow, request, policy)) {
            // A withdrawal after a source answered: nothing is frozen (section 14 row 31).
            return close(uow, request, DecisionRequestStatus.ABANDONED, Optional.of(ClosureReason.CONSENT_WITHDRAWN),
                    platform, correlation);
        }
        DecisionRequest.Application application = request.application();
        SnapshotFreezer.Freeze freeze = freezer.freeze(uow, new SnapshotFreezer.FreezeInput(request.id().value(),
                request.party(), application.product(), application.requested(), application.termMonths(),
                application.declaredMonthlyIncome(), application.declaredMonthlyExpenditure(), pinned,
                policy.maximumDataAge(), 1), correlation);
        return switch (freeze) {
            case SnapshotFreezer.Freeze.Frozen frozen -> {
                CreditAssessment assessment = assessments.assess(uow, frozen.snapshot(),
                        CreditAssessments.Terms.of(policy), correlation).assessment();
                evaluations.evaluate(uow, frozen.snapshot(), assessment);
                move(uow, request, DecisionRequestStatus.READY, DecisionRequestStatus.EVALUATED, platform);
                yield Step.EVALUATED;
            }
            case SnapshotFreezer.Freeze.Recollecting recollecting -> {
                // INV-CRD-08: a stale record never decides - collection re-opens under a new reference.
                recollecting.reopened().values().forEach(opened -> {
                    if (opened instanceof CreditDataCollection.Opened.Requested requested) {
                        asks.add(requested.id());
                    }
                });
                move(uow, request, DecisionRequestStatus.READY, DecisionRequestStatus.COLLECTING, platform);
                yield Step.RECOLLECTING;
            }
            case SnapshotFreezer.Freeze.ConsentWithdrawn withdrawn -> close(uow, request, DecisionRequestStatus.ABANDONED,
                    Optional.of(ClosureReason.CONSENT_WITHDRAWN), platform, correlation);
            case SnapshotFreezer.Freeze.NotReady notReady -> Step.WAITING;
        };
    }

    // ------------------------------------------------------------------ plumbing

    private boolean consented(Connection uow, DecisionRequest request, CreditPolicy policy) {
        for (CreditSourceKind kind : policy.sourceKinds()) {
            if (!consents.permits(uow, request.party(), kind)) {
                return false;
            }
        }
        return true;
    }

    private CreditPolicy policy(Connection uow, CreditPolicyVersionId id) {
        return policies.policy(uow, id)
                .orElseThrow(() -> new IllegalStateException("a pinned or active policy version is unreadable"))
                .policy();
    }

    private static CreditPolicyVersionId pinnedPolicy(DecisionRequest request) {
        return CreditPolicyVersionId.of(request.pinned()
                .orElseThrow(() -> new IllegalStateException("a collecting request carries its pinned versions"))
                .policyVersion());
    }

    private static Optional<DecisionSnapshotStore.DataRequestState> latest(
            List<DecisionSnapshotStore.DataRequestState> states, CreditSourceKind kind) {
        return states.stream()
                .filter(state -> state.kind() == kind)
                .max(Comparator.comparing(DecisionSnapshotStore.DataRequestState::requestedAt)
                        .thenComparing(state -> state.id().value()));
    }

    private void move(
            Connection uow, DecisionRequest request, DecisionRequestStatus from, DecisionRequestStatus to, Actor platform) {
        if (!requests.transition(uow, request.id(), EnumSet.of(from), to, Optional.empty(), platform, Optional.empty())) {
            throw new IllegalStateException("the locked request moved under its own lock");
        }
    }

    private Step close(
            Connection uow,
            DecisionRequest request,
            DecisionRequestStatus to,
            Optional<ClosureReason> reason,
            Actor platform,
            CorrelationId correlation) {
        new RequestClosures(requests, outbox, ids, clock).close(uow, request, to, reason, platform, correlation);
        return to == DecisionRequestStatus.EXPIRED ? Step.EXPIRED : Step.ABANDONED;
    }
}
