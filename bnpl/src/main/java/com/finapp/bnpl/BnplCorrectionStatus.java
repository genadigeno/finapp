package com.finapp.bnpl;

/**
 * Where a servicing correction's four-eyes proposal stands (`P12-TSK-024`; {@code BNPL_LIFECYCLES.md} section 4.11):
 * {@code PROPOSED -> APPROVED} (posted) {@code | REJECTED}, nothing else - a repayment reversal's machine column and a
 * waiver's status derived from its born-once decision.
 */
public enum BnplCorrectionStatus {

    /** Awaiting a decision; nothing posted. */
    PROPOSED,

    /** Approved by someone other than its proposer; its entry posted. Terminal. */
    APPROVED,

    /** Rejected, or withdrawn by its proposer; nothing posted. Terminal. */
    REJECTED
}
