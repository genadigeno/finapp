package com.finapp.lending;

/**
 * The two account kinds one {@code loan} table holds (`P11-TSK-002`; ADR-0090 point 3, ADR-0100).
 *
 * <p>Frozen at the loan's birth: the kind decides which edges its lifecycle admits
 * ({@code LENDING_LIFECYCLES.md} section 4.5) - an instalment loan is born
 * {@link LoanStatus#PENDING_DISBURSEMENT} and never {@link LoanStatus#CLOSING}; a revolving line is
 * born {@link LoanStatus#ACTIVE} and never {@code PENDING_DISBURSEMENT}. Both share the six per-loan
 * ledger accounts, the accrual and allocation engines, delinquency, corrections and payoff.
 */
public enum LoanKind {

    /** Principal arrives once, by disbursement, and falls due as billed instalments (ADR-0093, ADR-0097). */
    INSTALMENT,

    /** Principal arrives per draw against a limit and falls due on monthly statements (ADR-0100). */
    REVOLVING
}
