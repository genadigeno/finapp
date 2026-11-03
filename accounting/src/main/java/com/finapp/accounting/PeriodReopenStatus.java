package com.finapp.accounting;

/**
 * A reopen proposal's lifecycle (`P14-TSK-012`; {@code accounting V008}'s {@code period_reopen}): born {@link #PROPOSED}; {@link #APPROVED} by a second person, the period {@code OPEN} at the next revision. No
 * door withdraws a pending one: an owner item. It stands harmlessly, and its approval judges everything again.
 */
public enum PeriodReopenStatus {
    PROPOSED,
    APPROVED
}
