package com.finapp.credit;

/**
 * What a recorded Credit Decision concluded (ADR-0087 section 4).
 *
 * <p><strong>Two members, and never a third.</strong> A decision approves or declines. A referral
 * to a person is an <em>evaluation's</em> outcome - the evaluator's {@code REFER}
 * ({@code P10-TSK-013}) opens an underwriting case, and the person's conclusion is then a decision
 * of one of these two kinds. Keeping the two outcomes in distinct types from the first class is
 * {@code INV-CRD-04}: an evaluation outcome can never be stored as a decision by accident, because
 * no value of this type means "refer".
 */
public enum DecisionOutcome {

    /** Credit may be offered, up to the decision's approved amount, until the decision lapses. */
    APPROVED,

    /** Credit may not be offered on this request; the decision carries its adverse reason codes. */
    DECLINED
}
