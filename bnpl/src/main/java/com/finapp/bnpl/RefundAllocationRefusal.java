package com.finapp.bnpl;

/**
 * Why the refund allocation engine split no refund (`P12-TSK-009`; ADR-0105 decision 1) - the refund amount is the
 * client's, so each is a typed answer, never an exception a caller must parse. An inconsistent purchase position is not
 * among them: it cannot be constructed ({@link RefundPosition}).
 */
public enum RefundAllocationRefusal {

    /** The refund is zero or negative - there is nothing to return. */
    REFUND_NOT_POSITIVE,

    /**
     * The refund exceeds what remains of the purchase, {@code R > G − refundedBefore} ({@code INV-BNPL-07}) - a fully
     * refunded purchase among them. The refund doors answer it {@code 422 bnpl.RefundExceedsPurchase} (a purchase
     * already {@code FULLY_REFUNDED} is their {@code 409 bnpl.PurchaseFullyRefunded}, judged on its status first).
     */
    REFUND_EXCEEDS_PURCHASE,

    /** The refund is not in the purchase's currency, or not at its scale - a refund is in the purchase's currency. */
    CURRENCY_MISMATCH
}
