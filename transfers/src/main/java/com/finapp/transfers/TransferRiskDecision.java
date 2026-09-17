package com.finapp.transfers;

/**
 * The risk-decision seam (`ROADMAP.md` §Refinement 2: introduced Phase 4, implemented
 * Phase 13). A <strong>required parameter</strong> of the execution command with no defaulted
 * overload, consulted <strong>in-lock</strong> — {@link TransferLimitCheck}'s contract and
 * reasoning, verbatim, for the sibling control.
 */
public interface TransferRiskDecision {

    /** Judges {@code transfer} under the held source lock. Phase 4 permits everything. */
    void check(Transfer transfer);
}
