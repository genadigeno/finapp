package com.finapp.payments;

/**
 * The payments module's sweep listings, each the key of its failure records in {@code payments.sweep_row_failure} (`P16-TSK-017`,
 * payments {@code V040}): a row that fails, or cannot be read, is backed off within its own listing only. The names are the
 * table's {@code CHECK} vocabulary exactly, so a new listing is a reviewed change to both.
 */
public enum SweepLeg {
    /** The card sweep's dispatched and unknown attempts ({@link PaymentAttemptStore#findSweepable}). */
    CARD_ATTEMPT,
    /** The card sweep's stranded authorizations ({@link PaymentAttemptStore#findStrandedAuthorizations}). */
    CARD_STRANDED,
    /** The card sweep's refunds ({@link RefundStore#findSweepable}). */
    CARD_REFUND,
    /** The return payments of the push rail ({@link RefundStore#findSweepableReturns}). */
    RETURN,
    /** The withdrawals ({@link WithdrawalStore#findSweepable}). */
    WITHDRAWAL,
    /** The dispute answers ({@link DisputeResponseStore#findSweepable}). */
    DISPUTE_ANSWER,
    /** The pay-by-bank initiations ({@link PaymentAttemptStore#findResolvableInitiations}). */
    PAY_IN
}
