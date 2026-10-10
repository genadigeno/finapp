package com.finapp.credit;

import java.sql.Connection;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Re-derives a recorded decision from its sealed inputs (`P10-TSK-019`; PHASE_10_PLAN.md section 12.8, ADR-0087;
 * {@code INV-CRD-01}, {@code INV-CRD-07}, {@code INV-CRD-05}, {@code INV-HIST-04}) - and writes nothing.
 *
 * <h2>Only what was pinned</h2>
 *
 * <p>The snapshot the decision names, its policy, scorecard and engine versions - each read by the id the snapshot
 * pinned, whatever has been activated, retired or rejected since. A replayer that read the policy in force would agree
 * with every decision until the first activation and then lie; this one cannot, because it never asks what is in force.
 *
 * <ol>
 *   <li><strong>The seal</strong> ({@link Divergence#HASH}): the SHA-256 recomputed over the stored canonical text must
 *       equal the snapshot row's digest and the decision's, the snapshot's pinned versions the decision's, and the
 *       snapshot's request the decision's own (the Phase 10 to 11 transition; credit {@code V017} beneath). A broken
 *       seal stops the replay - inputs that are not the ones decided on prove nothing either way.
 *   <li><strong>The re-run</strong>: the assessment's arithmetic ({@link CreditAssessments#figures}, the same function
 *       production runs) and the pinned engine over the pinned policy, in memory.
 *   <li><strong>The comparison</strong>: a system decision against its re-derived outcome, approved amount and ordered
 *       reason codes; a person's decision (its request's case {@code DECIDED}) by re-deriving the evaluation it rested on
 *       - its snapshot's stored evaluation - and verifying the decision equal to the case's recorded one. A person's
 *       judgement is verified, never re-derived.
 * </ol>
 *
 * <p>A decision that cannot be re-run - a pinned row missing, an engine this build does not hold, a rule reading what
 * the snapshot never held - is {@link Divergence#UNREPLAYABLE}, a verdict like any other, never an exception that would
 * hide every other decision's.
 */
@RequiredArgsConstructor
public final class DecisionReplayer {

    @NonNull private final JdbcCreditDecisions decisions;
    @NonNull private final DecisionSnapshotStore snapshots;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final ScorecardStore scorecards;
    @NonNull private final CreditAssessmentStore assessments;
    @NonNull private final PolicyEvaluationStore evaluations;
    @NonNull private final UnderwritingCaseStore cases;
    @NonNull private final EngineVersions engines;

    /** What a replay concluded. */
    public enum Verdict {
        /** Every compared element equal. */
        IDENTICAL,
        /** At least one differs - an incident, never repaired by an {@code UPDATE}. */
        DIVERGED
    }

    /** What differs - by kind alone, never by value. */
    public enum Divergence {
        /** The seal: the canonical text's digest, the stored digests or the pinned versions disagree. */
        HASH,
        /** The outcome. */
        OUTCOME,
        /** The approved amount. */
        AMOUNT,
        /** The reason codes, or their order. */
        REASONS,
        /** The decision could not be re-run at all. */
        UNREPLAYABLE
    }

    /** One decision's replay. */
    public record Replay(
            CreditDecisionId decision, Verdict verdict, Set<Divergence> divergences, PinnedVersions versions, boolean byPerson) {
        public Replay {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(divergences, "divergences");
            Objects.requireNonNull(versions, "versions");
            divergences = Set.copyOf(divergences);
            if ((verdict == Verdict.IDENTICAL) != divergences.isEmpty()) {
                throw new IllegalArgumentException("a replay is IDENTICAL exactly when nothing differs");
            }
        }
    }

    /** Replays decision {@code id} on {@code unitOfWork} - empty when no such decision. */
    public Optional<Replay> replay(Connection unitOfWork, CreditDecisionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        return decisions.byId(unitOfWork, id).map(decision -> replay(unitOfWork, decision));
    }

    /** Replays {@code decision} on {@code unitOfWork}. */
    Replay replay(Connection unitOfWork, CreditDecision decision) {
        Optional<UnderwritingCase> decidedCase = cases.byRequest(unitOfWork, DecisionRequestId.of(decision.decisionRequest()))
                .filter(found -> found.status() == UnderwritingCaseStatus.DECIDED);
        boolean byPerson = decidedCase.isPresent();
        Set<Divergence> divergences = EnumSet.noneOf(Divergence.class);

        Optional<DecisionSnapshotStore.StoredSnapshot> stored = snapshots.snapshotById(unitOfWork, decision.snapshot());
        if (stored.isEmpty()) {
            return verdict(decision, byPerson, EnumSet.of(Divergence.UNREPLAYABLE));
        }
        byte[] recomputed = CanonicalSnapshot.sha256(stored.get().canonical());
        if (!Arrays.equals(recomputed, stored.get().sha256()) || !Arrays.equals(recomputed, decision.snapshotSha256())) {
            return verdict(decision, byPerson, EnumSet.of(Divergence.HASH));
        }
        DecisionSnapshot snapshot;
        try {
            snapshot = new DecisionSnapshot(stored.get().id(), stored.get().sequence(), stored.get().format(),
                    stored.get().canonical(), stored.get().sha256(), stored.get().frozenAt(),
                    CanonicalSnapshot.parse(stored.get().canonical()));
        } catch (IllegalArgumentException unreadable) {
            return verdict(decision, byPerson, EnumSet.of(Divergence.HASH));
        }
        PinnedVersions pinned = snapshot.content().versions();
        if (!pinned.equals(decision.versions())) {
            return verdict(decision, byPerson, EnumSet.of(Divergence.HASH));
        }
        // INV-CRD-06 (the Phase 10 to 11 transition): the sealed inputs are the decision's own request's - a decision
        // naming another request's snapshot, however intact that seal, proves nothing about this decision.
        if (!snapshot.content().decisionRequest().equals(decision.decisionRequest())) {
            return verdict(decision, byPerson, EnumSet.of(Divergence.HASH));
        }

        CreditAssessment storedAssessment;
        EvaluationResult rederived;
        try {
            CreditPolicy policy = policies.policy(unitOfWork, CreditPolicyVersionId.of(pinned.policyVersion()))
                    .orElseThrow(() -> new IllegalStateException("the pinned policy version does not exist"))
                    .policy();
            Scorecard scorecard = scorecards.model(unitOfWork, ScorecardModelVersionId.of(pinned.modelVersion()))
                    .orElseThrow(() -> new IllegalStateException("the pinned scorecard version does not exist"))
                    .scorecard();
            PolicyEvaluator engine = engines.engine(pinned.engineVersion());
            storedAssessment = assessments.bySnapshot(unitOfWork, snapshot.id())
                    .orElseThrow(() -> new IllegalStateException("a decision's snapshot is assessed"));
            CreditAssessment assessment = CreditAssessments.figures(storedAssessment.id(), snapshot, scorecard,
                    CreditAssessments.Terms.of(policy), storedAssessment.assessedAt());
            rederived = engine.evaluate(snapshot.content(), assessment, policy);
        } catch (RuntimeException cannotRun) {
            return verdict(decision, byPerson, EnumSet.of(Divergence.UNREPLAYABLE));
        }

        if (byPerson) {
            // The evaluation the person decided on, re-derived; then the decision verified against the case's record.
            Optional<PolicyEvaluation> basis = evaluations.byAssessment(unitOfWork, storedAssessment.id());
            if (basis.isEmpty()) {
                return verdict(decision, byPerson, EnumSet.of(Divergence.UNREPLAYABLE));
            }
            EvaluationResult recorded = basis.get().result();
            compare(divergences, rederived.outcome() == recorded.outcome(), rederived.approved().equals(recorded.approved()),
                    rederived.reasons().equals(recorded.reasons()));
            UnderwritingCase.FirstDecision judgement = decidedCase.get().first()
                    .orElseThrow(() -> new IllegalStateException("a DECIDED case records its decision"));
            compare(divergences, decision.outcome() == judgement.outcome(), decision.approved().equals(judgement.approved()),
                    decision.reasons().equals(judgement.reasons()));
        } else {
            Optional<DecisionOutcome> outcome = switch (rederived.outcome()) {
                case APPROVE -> Optional.of(DecisionOutcome.APPROVED);
                case DECLINE, HARD_DECLINE -> Optional.of(DecisionOutcome.DECLINED);
                case REFER -> Optional.empty(); // the platform never decides a referral
            };
            compare(divergences, outcome.filter(decision.outcome()::equals).isPresent(),
                    rederived.approved().equals(decision.approved()), rederived.reasons().equals(decision.reasons()));
        }
        return verdict(decision, byPerson, divergences);
    }

    private static void compare(Set<Divergence> divergences, boolean outcome, boolean amount, boolean reasons) {
        if (!outcome) {
            divergences.add(Divergence.OUTCOME);
        }
        if (!amount) {
            divergences.add(Divergence.AMOUNT);
        }
        if (!reasons) {
            divergences.add(Divergence.REASONS);
        }
    }

    private static Replay verdict(CreditDecision decision, boolean byPerson, Set<Divergence> divergences) {
        return new Replay(decision.id(), divergences.isEmpty() ? Verdict.IDENTICAL : Verdict.DIVERGED, divergences,
                decision.versions(), byPerson);
    }

    /** Every decision this reading holds, oldest first - for {@link CreditReplayProof}. */
    List<CreditDecisionId> decisionIds(Connection unitOfWork) {
        return decisions.ids(unitOfWork);
    }
}
