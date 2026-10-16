package com.finapp.bnpl;

/**
 * A BNPL purchase's lifecycle - the financed commercial fact (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.5,
 * {@code PHASE_12_PLAN.md} section 5.3; built by `P12-TSK-013`, refunded by `-021` and `-023`).
 *
 * <p>Named for the canonical term - the <strong>BNPL Purchase</strong>, never a bare "purchase" beside checkout's order
 * and the payment it is not ({@code PHASE_12_PLAN.md} section 3.1); the table keeps its short form, {@code purchase}.
 *
 * <p>Born {@code AUTHORISED} in the acceptance and moved {@code FINANCED} in the same transaction, beside the merchant
 * financing record and its entry - never committed {@code AUTHORISED}; {@code FULLY_REFUNDED} when refunds total the
 * gross. A partial refund leaves it {@code FINANCED} (partially refunded is derived, not a state). Terminal:
 * {@code FULLY_REFUNDED}; never {@code FINANCED -> AUTHORISED}.
 */
public enum BnplPurchaseStatus {

    /** Transient: the platform's commitment to pay the merchant, kept for history - the financing posts in the same transaction. */
    AUTHORISED,

    /** The merchant financed: its payable credited the gross less the merchant discount, the obligation born beside it. */
    FINANCED,

    /** Terminal: refunds total the gross. */
    FULLY_REFUNDED
}
