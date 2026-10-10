package com.finapp.lending;

/**
 * The four computations a loan's figures come from (`P11-TSK-002`; {@code PHASE_11_PLAN.md} section 12.1), each
 * versioned by {@link EngineVersion} and pinned on the agreement, so a figure is re-derivable years later by the
 * engine that produced it.
 */
public enum LendingEngine {

    /** The amortisation schedule of an instalment loan (ADR-0093). */
    SCHEDULE,

    /** Daily interest accrual (ADR-0094, {@code INV-LND-09}). */
    ACCRUAL,

    /** The split of a repayment over fees, interest and principal (ADR-0095, {@code INV-LND-04}). */
    ALLOCATION,

    /** A credit line's monthly statement and its minimum payment (ADR-0100). */
    STATEMENT
}
