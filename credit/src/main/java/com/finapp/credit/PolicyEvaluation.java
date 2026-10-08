package com.finapp.credit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One assessment's policy evaluation, as recorded (`P10-TSK-013`, {@code credit V009}; {@code INV-CRD-04},
 * {@code INV-CRD-06}): what the pinned policy concluded under the pinned engine - a record of its own, distinct from the
 * decision that follows it, born once per assessment and never changed.
 *
 * @param policyVersion the pinned policy version the result was evaluated under
 * @param evaluatedAt the database's statement time at the insert
 */
public record PolicyEvaluation(
        PolicyEvaluationId id,
        CreditAssessmentId assessment,
        UUID decisionRequest,
        CreditPolicyVersionId policyVersion,
        EvaluationResult result,
        Instant evaluatedAt) {

    public PolicyEvaluation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(decisionRequest, "decisionRequest");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    /** Whether {@code other} records the same conclusion about the same assessment - identity and time aside. */
    public boolean sameConclusion(PolicyEvaluation other) {
        return assessment.equals(other.assessment)
                && decisionRequest.equals(other.decisionRequest)
                && policyVersion.equals(other.policyVersion)
                && result.equals(other.result);
    }

    @Override
    public String toString() {
        return "PolicyEvaluation[" + id + ", " + result + "]";
    }
}
