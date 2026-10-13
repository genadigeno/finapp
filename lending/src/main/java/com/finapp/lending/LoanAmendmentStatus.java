package com.finapp.lending;

/**
 * An amendment's machine (`P11-TSK-027`; ADR-0099 points 1 and 5, {@code INV-LND-11}): {@code PROPOSED -> APPROVED ->
 * ACCEPTED | LAPSED}, {@code PROPOSED -> REJECTED | LAPSED}. Derived from {@code lending V023}'s rows and the database
 * clock, never written: no {@code loan_amendment_decision} - {@code PROPOSED}; a decision - its outcome; an agreement
 * version naming the amendment - {@code ACCEPTED}; and {@code LAPSED} once the agreement version it amends is no longer the
 * loan's latest (another amendment, a prepayment), the loan is no longer {@code ACTIVE} (a payoff closed it) or - approved
 * - its offer expired unaccepted. A lapse is a fact of the rows and the clock, so no sweeper writes it.
 */
public enum LoanAmendmentStatus {

    /** Proposed by a holder of {@code LOAN_SERVICE}, awaiting a different holder of {@code LOAN_SERVICE_APPROVE}. */
    PROPOSED,

    /** Approved by someone other than its proposer: offered to the customer until its expiry. */
    APPROVED,

    /** Accepted by the customer: agreement and schedule version n+1. Terminal. */
    ACCEPTED,

    /** Rejected - or withdrawn by its proposer. Terminal. */
    REJECTED,

    /** The agreement it amends moved, the loan closed, or its offer expired unaccepted. Terminal. */
    LAPSED
}
