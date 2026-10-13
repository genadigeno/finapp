package com.finapp.lending;

/**
 * A waiver's machine (`P11-TSK-025`; ADR-0099, {@code INV-LND-11}): {@code PROPOSED -> APPROVED (posted) | REJECTED},
 * nothing else. Derived from {@code lending V021}'s rows - a waiver with no {@code waiver_decision} is {@code PROPOSED}, one
 * with a decision is its outcome (decided once, {@code UNIQUE (waiver_id)}).
 */
public enum WaiverStatus {

    /** Proposed by a holder of {@code LOAN_SERVICE}, awaiting a different holder of {@code LOAN_SERVICE_APPROVE}. */
    PROPOSED,

    /** Approved by someone other than its proposer - its entry posted. Terminal. */
    APPROVED,

    /** Rejected - or withdrawn by its proposer. Nothing posted. Terminal. */
    REJECTED
}
