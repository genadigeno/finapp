package com.finapp.lending;

/**
 * What a waiver forgives (`P11-TSK-025`, {@code lending V021}'s {@code waiver.kind}; A14, {@code PHASE_11_PLAN.md} section
 * 12.8 rules 8, 8a and 9) - each posts its own way, and each is bounded by what is due now under the loan's row lock.
 */
public enum WaiverKind {

    /**
     * A fee's unpaid remainder (rule 8): {@link com.finapp.ledger.ReversalService} of the assessment's rule 4 entry - {@code DR
     * LOAN_FEE_INCOME / CR LOAN_FEES_DUE} - bounded by that entry ({@code INV-REV-02}) and by what the fee still owes.
     */
    FEE,

    /**
     * A fee already paid, refunded to the borrower's credit balance (rule 8a): {@code DR LOAN_FEE_INCOME / CR
     * LOAN_CREDIT_BALANCE}, bounded by what was paid of it less what was refunded.
     */
    FEE_REFUND,

    /**
     * Billed interest on a billed item (rule 9): {@code DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE}, bounded by what the
     * item still owes of it. Accrued, unbilled interest is never waived - a billing's interest is exactly its period's accruals.
     */
    INTEREST
}
