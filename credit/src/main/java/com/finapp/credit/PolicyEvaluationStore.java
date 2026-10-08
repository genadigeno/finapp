package com.finapp.credit;

import java.sql.Connection;
import java.util.Optional;

/** The policy evaluation's persistence (`P10-TSK-013`, {@code credit V009}) - on the caller's unit of work. */
public interface PolicyEvaluationStore {

    /**
     * Inserts the evaluation and, when this call wrote it, every rule's result beside it - unless its assessment already
     * has one ({@code ON CONFLICT (assessment_id) DO NOTHING}); true when this call wrote it. {@code evaluatedAt} is the
     * database's, whatever the argument holds.
     */
    boolean insert(Connection unitOfWork, PolicyEvaluation evaluation);

    /** The assessment's evaluation, with its rule results, if it has one. */
    Optional<PolicyEvaluation> byAssessment(Connection unitOfWork, CreditAssessmentId assessment);
}
