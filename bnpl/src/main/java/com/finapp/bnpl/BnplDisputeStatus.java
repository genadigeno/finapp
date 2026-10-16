package com.finapp.bnpl;

/**
 * A BNPL dispute's lifecycle - the customer contesting the purchase with the platform, no network involved
 * (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.10, {@code PHASE_12_PLAN.md} section 5.8; built by `P12-TSK-023`).
 *
 * <p>Opened {@code OPEN}, the disputed amount held; {@code MERCHANT_RESPONDED} on the merchant's evidence, then resolved
 * by an operator; {@code RESOLVED_REFUND} directly when the merchant accepts liability or lets the response deadline
 * pass; {@code WITHDRAWN} by the customer from either open state. A refund outcome <em>is</em> a BNPL refund, under the
 * refund's one bound ({@code INV-BNPL-08}). Terminal: {@code RESOLVED_REFUND}, {@code RESOLVED_UPHELD},
 * {@code WITHDRAWN}; never {@code OPEN -> RESOLVED_UPHELD} and never back to {@code OPEN}.
 */
public enum BnplDisputeStatus {

    /** The customer's dispute, awaiting the merchant's response; the disputed amount held. */
    OPEN,

    /** The merchant responded with evidence; an operator resolves. */
    MERCHANT_RESPONDED,

    /** Terminal: resolved as a refund - the merchant liable, executed as a BNPL refund. */
    RESOLVED_REFUND,

    /** Terminal: the purchase upheld - the hold lifted, nothing re-billed. */
    RESOLVED_UPHELD,

    /** Terminal: withdrawn by the customer - the hold lifted. */
    WITHDRAWN
}
