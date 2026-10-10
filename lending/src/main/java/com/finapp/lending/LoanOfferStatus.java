package com.finapp.lending;

/**
 * A loan offer's lifecycle (`P11-TSK-002`; {@code LENDING_LIFECYCLES.md} section 4.4, built by `P11-TSK-011` and
 * `-012`).
 *
 * <p>Born {@code OFFERED}; {@code ACCEPTED} by the customer while {@code expires_at > statement_timestamp()} (the
 * decision consumed, agreement version 1 and the loan born beside it), {@code DECLINED} by the customer, or
 * {@code EXPIRED} by the system at {@code expires_at <= statement_timestamp()} - exactly one at the boundary. Every
 * state but the first is terminal; never {@code EXPIRED -> ACCEPTED}.
 */
public enum LoanOfferStatus {

    /** Acceptable until it expires. */
    OFFERED,

    /** Terminal: accepted - the loan exists. */
    ACCEPTED,

    /** Terminal: declined by the customer; the unconsumed decision lapses at its own validity. */
    DECLINED,

    /** Terminal: expired on the database clock; nothing is released. */
    EXPIRED
}
