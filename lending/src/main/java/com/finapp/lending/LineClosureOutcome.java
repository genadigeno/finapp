package com.finapp.lending;

/**
 * What a credit line's closure request did at the request (`P11-TSK-023`; {@code lending V018}'s
 * {@code line_closure_request_outcome_is_known}, ADR-0100 point 8, {@code PHASE_11_PLAN.md} section 5.3).
 */
public enum LineClosureOutcome {

    /** Something was outstanding: the line moved {@code ACTIVE -> CLOSING} - no further draws, closed once nothing is owed. */
    CLOSING,

    /** Nothing was outstanding: the line closed {@code CLOSED_BY_CUSTOMER} at the request, its credit balance refunded. */
    CLOSED
}
