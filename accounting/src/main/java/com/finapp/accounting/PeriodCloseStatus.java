package com.finapp.accounting;

/**
 * A close proposal's lifecycle (`P14-TSK-012`; {@code accounting V008}'s {@code period_close}): born {@link #PROPOSED}; one decision, {@link #APPROVED} by a second person (the period {@code
 * CLOSED}) or {@link #REJECTED} (the period {@code LOCKED} again, the proposal kept and the next a new row). Terminal.
 */
public enum PeriodCloseStatus {
    PROPOSED,
    APPROVED,
    REJECTED
}
