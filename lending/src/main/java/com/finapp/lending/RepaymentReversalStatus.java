package com.finapp.lending;

/**
 * A repayment reversal's machine (`P11-TSK-020`; {@code lending V016}'s {@code repayment_reversal_status_is_known},
 * {@code LENDING_LIFECYCLES.md} section 4.9, ADR-0099 point 1): {@code PROPOSED -> APPROVED (posted) | REJECTED} and nothing
 * else. {@code APPROVED} and {@code REJECTED} are terminal.
 */
public enum RepaymentReversalStatus {
    /** Proposed by a holder of {@code LOAN_SERVICE}, with a reason, awaiting a different person's decision. */
    PROPOSED,
    /** Approved by a holder of {@code LOAN_SERVICE_APPROVE} other than its proposer: rule 10 posted, the rows negated. */
    APPROVED,
    /** Rejected - or withdrawn by its proposer; nothing was posted and the repayment may be proposed again. */
    REJECTED
}
