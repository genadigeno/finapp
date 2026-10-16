package com.finapp.bnpl;

/**
 * Why an obligation is {@link BnplObligationStatus#CLOSED} (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md} section 4.7,
 * {@code PHASE_12_PLAN.md} section 5.5) - always said, by the closing act, and held to it by the closure reason
 * {@code CHECK} the obligation table will carry (`P12-TSK-013`).
 */
public enum BnplClosureReason {

    /** Any closing act but a payoff or a refund - a repayment, an auto-collection, a credit-balance application, a waiver. */
    REPAID,

    /** A payoff settled every receivable before the schedule ended. */
    PAID_OFF_EARLY,

    /** A refund that left the purchase {@link BnplPurchaseStatus#FULLY_REFUNDED} settled it. */
    REFUNDED,

    /** A partial refund settled the last receivable - refunds and repayments together. */
    REFUNDED_PARTIALLY_REPAID
}
