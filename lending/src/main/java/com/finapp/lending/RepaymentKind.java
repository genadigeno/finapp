package com.finapp.lending;

/**
 * Where a repayment came from (`P11-TSK-018`; {@code lending V014}'s {@code repayment_kind_is_known},
 * {@code LENDING_LIFECYCLES.md} section 4.8) - every kind is money from the borrower's wallet, allocated by the same
 * engine and posted by the same rule (rule 5). Part of the requester's idempotency arbiter: one repayment per loan, kind
 * and key.
 */
public enum RepaymentKind {

    /** The borrower's own instruction through the repayment door (`P11-TSK-018`). */
    CUSTOMER,

    /** Collected on the due date by the servicing step, per the agreement (`P11-TSK-019`). */
    AUTO_COLLECTION,

    /** The one repayment of a payoff's execution (`P11-TSK-026`). */
    PAYOFF,

    /**
     * Principal not yet due paid early by the borrower's own instruction, re-scheduling the loan - agreement and schedule
     * version n+1 in its transaction (`P11-TSK-028`, {@link Prepayments}); never reversed.
     */
    PREPAYMENT
}
