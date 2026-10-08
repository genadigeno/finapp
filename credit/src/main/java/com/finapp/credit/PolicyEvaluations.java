package com.finapp.credit;

import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Evaluates an assessment against its pinned policy, once (`P10-TSK-013`; {@code INV-CRD-06}, {@code INV-CRD-01},
 * {@code INV-CRD-04}, {@code INV-HIST-04}): the policy is the version the snapshot PINNED, read by its id whatever has
 * been activated since; the engine is the version the snapshot pinned, from {@link EngineVersions}; the result is
 * recorded in one {@code policy_evaluation} row with every rule's result beside it.
 *
 * <p><strong>Ten evaluators, one row.</strong> {@code INSERT ... ON CONFLICT (assessment_id) DO NOTHING}, then read:
 * every caller answers with the one stored evaluation. A second computation that disagrees with the stored one is a
 * defect, never a silent replacement. No event: the decision that follows carries the outcome (`P10-TSK-016`).
 */
@RequiredArgsConstructor
public final class PolicyEvaluations {

    @NonNull private final PolicyEvaluationStore evaluations;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final EngineVersions engines;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The outcome: the stored evaluation, and whether this call found it already written. */
    public record Evaluated(PolicyEvaluation evaluation, boolean replayed) {}

    /** Evaluates {@code assessment} of {@code snapshot} under the snapshot's pinned policy and engine. */
    public Evaluated evaluate(Connection unitOfWork, DecisionSnapshot snapshot, CreditAssessment assessment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(assessment, "assessment");
        if (!snapshot.verifies()) {
            throw new IllegalStateException("the snapshot's text does not verify against its hash (INV-CRD-07)");
        }
        if (!assessment.snapshot().equals(snapshot.id())) {
            throw new IllegalStateException("the assessment is another snapshot's");
        }
        SnapshotContent content = snapshot.content();
        CreditPolicyVersionId pinned = CreditPolicyVersionId.of(content.versions().policyVersion());
        CreditPolicyStore.PolicyVersion policy = policies.policy(unitOfWork, pinned)
                .orElseThrow(() -> new IllegalStateException("the snapshot pins a policy version that does not exist"));
        if (policy.effectiveFrom().isEmpty()) {
            throw new IllegalStateException("the snapshot pins a policy version that was never active");
        }
        EvaluationResult result = engines.engine(content.versions().engineVersion())
                .evaluate(content, assessment, policy.policy());
        PolicyEvaluation computed = new PolicyEvaluation(PolicyEvaluationId.next(ids), assessment.id(),
                content.decisionRequest(), pinned, result, clock.instant());
        boolean written = evaluations.insert(unitOfWork, computed);
        PolicyEvaluation stored = evaluations.byAssessment(unitOfWork, assessment.id())
                .orElseThrow(() -> new IllegalStateException("an evaluation is readable once written"));
        if (!stored.sameConclusion(computed)) {
            throw new IllegalStateException(
                    "the assessment's stored evaluation disagrees with this computation: an evaluation is born once (INV-CRD-06)");
        }
        return new Evaluated(stored, !written);
    }
}
