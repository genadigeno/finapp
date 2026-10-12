package com.finapp.lending;

/**
 * What a billing bills (`P11-TSK-017`, {@code lending V012}; ADR-0094 point 6).
 */
public enum BillingKind {

    /** An instalment of the loan's latest schedule version - its period's accrued interest and its principal portion. */
    INSTALMENT,

    /**
     * After the final due date, a period of the monthly calendar continued ({@link InstalmentCalendar}): the interest
     * accrued in it on principal still outstanding, made due at its end - interest only, every principal being due
     * already. Billed only when something accrued in it.
     */
    POST_MATURITY
}
