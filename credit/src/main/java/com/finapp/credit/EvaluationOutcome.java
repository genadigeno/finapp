package com.finapp.credit;

/**
 * What a policy evaluation concludes (`P10-TSK-013`; PHASE_10_PLAN.md section 12.6, {@code INV-CRD-04}): the
 * evaluator's word, kept apart from the decision's {@link DecisionOutcome} - a {@code REFER} is a question for a person,
 * not an answer, and the decision that follows it (`P10-TSK-016`, `-018`) is a record of its own.
 *
 * <p>The severity order is engine 1's semantics ({@code HARD_DECLINE > DECLINE > REFER > APPROVE}): an evaluation
 * concludes the most severe effect any rule triggered. Changing it is a new engine version, never an edit here.
 */
public enum EvaluationOutcome {

    APPROVE(0),
    REFER(1),
    DECLINE(2),
    HARD_DECLINE(3);

    private final int severity;

    EvaluationOutcome(int severity) {
        this.severity = severity;
    }

    /** Engine 1's rank: higher is more severe. */
    public int severity() {
        return severity;
    }

    /** Whether this outcome judges against the applicant, and so must carry a reason ({@code INV-CRD-02}). */
    public boolean adverse() {
        return this != APPROVE;
    }

    /** The more severe of this and {@code other}. */
    public EvaluationOutcome severest(EvaluationOutcome other) {
        return other.severity > severity ? other : this;
    }

    /** The outcome a triggered {@code effect} argues for; a cap argues for an approval of less. */
    public static EvaluationOutcome of(PolicyEffect effect) {
        return switch (effect) {
            case HARD_DECLINE -> HARD_DECLINE;
            case DECLINE -> DECLINE;
            case REFER -> REFER;
            case CAP_AMOUNT -> APPROVE;
        };
    }
}
