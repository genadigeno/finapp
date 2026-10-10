package com.finapp.lending;

/**
 * Why a loan is {@link LoanStatus#CLOSED} (`P11-TSK-002`; {@code LENDING_LIFECYCLES.md} section 4.5) - always said,
 * by the closure reason {@code CHECK} the loan table will carry (`P11-TSK-012`).
 */
public enum ClosureReason {

    /** The last receivable was repaid on schedule. */
    REPAID,

    /** A payoff settled every receivable before the schedule ended (`P11-TSK-026`). */
    PAID_OFF_EARLY,

    /** Revolving: the customer's closure request, completed when nothing remained outstanding (`P11-TSK-023`). */
    CLOSED_BY_CUSTOMER
}
