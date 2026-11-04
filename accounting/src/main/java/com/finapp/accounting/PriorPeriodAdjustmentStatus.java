package com.finapp.accounting;

/**
 * A prior-period adjustment's machine (`P14-TSK-013`; {@code accounting V009}'s {@code prior_period_adjustment_status_is_known}):
 * {@code PROPOSED -> POSTED | REJECTED}, each final.
 */
public enum PriorPeriodAdjustmentStatus {

    /** Nothing has posted; it awaits a second person, and may no longer be able to post. */
    PROPOSED,

    /** A second person approved it and the ledger's entry committed in that transaction. */
    POSTED,

    /** Declined or withdrawn, the ledger's proposal with it. Corrects nothing. */
    REJECTED
}
