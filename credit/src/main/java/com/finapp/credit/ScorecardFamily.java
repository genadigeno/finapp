package com.finapp.credit;

/**
 * The scorecard model families (`P10-TSK-011`, ADR-0086 section 3). <strong>Closed</strong>: a family is a reviewed
 * code change with its migration's {@code CHECK}; a statistical family is out of Phase 10's scope (PHASE_10_PLAN.md
 * section 17).
 */
public enum ScorecardFamily {

    /** The retail points table - version 1 seeded by {@code credit V006} as a proposal. */
    RETAIL_SCORECARD
}
