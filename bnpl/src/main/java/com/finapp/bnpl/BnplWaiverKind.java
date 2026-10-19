package com.finapp.bnpl;

/**
 * What a BNPL waiver forgives (`P12-TSK-024`; {@code bnpl V016}'s {@code bnpl_waiver_kind_is_known}): a late fee's unpaid
 * remainder only - F7, the {@code ReversalService} of the assessment's F4 entry, bounded. No interest waiver: {@code
 * PHASE_12_PLAN.md} section 12.11 has no rule for one ({@code BNPL_LIFECYCLES.md} section 7).
 */
public enum BnplWaiverKind {

    /** A fee assessment's unpaid remainder - {@code DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE}. */
    FEE
}
