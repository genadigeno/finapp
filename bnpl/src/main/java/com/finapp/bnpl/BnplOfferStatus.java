package com.finapp.bnpl;

/**
 * A BNPL offer's lifecycle (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.3, {@code PHASE_12_PLAN.md} section 5.2;
 * built by `P12-TSK-011`, accepted by `-013`).
 *
 * <p>Born {@code OFFERED}; {@code ACCEPTED} by the customer while {@code expires_at > statement_timestamp()} - the
 * acceptance <em>is</em> the BNPL purchase: the decision consumed, agreement version 1, the purchase financed and the
 * obligation born beside it, the checkout session completed - {@code DECLINED} by the customer, or {@code EXPIRED} by the
 * system at {@code expires_at <= statement_timestamp()}, exactly one of {@code ACCEPTED} and {@code EXPIRED} at the
 * boundary. Every state but the first is terminal; never {@code EXPIRED -> ACCEPTED}.
 */
public enum BnplOfferStatus {

    /** Acceptable until it expires. */
    OFFERED,

    /** Terminal: accepted - the purchase, its financing and the obligation exist. */
    ACCEPTED,

    /** Terminal: declined by the customer; the unconsumed decision lapses at its own validity. */
    DECLINED,

    /** Terminal: expired on the database clock; nothing is released, and the session stays open for another instrument. */
    EXPIRED
}
