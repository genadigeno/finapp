package com.finapp.accounting;

/**
 * A reopen proposal's lifecycle (`P14-TSK-012`; {@code accounting V008}'s {@code period_reopen}, widened by {@code V011}): born {@link #PROPOSED}; one decision, each terminal - {@link #APPROVED} by a
 * second person, the period {@code OPEN} at the next revision; {@link #REJECTED} by a second person, or {@link #WITHDRAWN} by its proposer (`P14-TSK-024`, {@link PeriodRequestEnd}), the period left
 * {@code CLOSED} at the same revision and free for a new proposal. At most one {@code PROPOSED} per period and one {@code APPROVED} per revision.
 */
public enum PeriodReopenStatus {
    PROPOSED,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
