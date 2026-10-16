package com.finapp.bnpl;

/**
 * Why a BNPL application is {@link BnplApplicationStatus#CLOSED_UNDECIDED} (`P12-TSK-002`; {@code BNPL_LIFECYCLES.md}
 * section 4.2, {@code PHASE_12_PLAN.md} sections 5.1 and 12.1) - always said, by the reason {@code CHECK} the application
 * table will carry (`P12-TSK-010`). {@code DECLINED} and {@code WITHDRAWN} are states, not reasons.
 */
public enum BnplApplicationClosureReason {

    /** Credit's decision request was cancelled without a decision. */
    CREDIT_REQUEST_CANCELLED,

    /** Credit's decision request lapsed at its validity (an hour for a BNPL product) without a decision. */
    CREDIT_REQUEST_EXPIRED,

    /** Credit's decision request was abandoned without a decision. */
    CREDIT_REQUEST_ABANDONED,

    /** The checkout session is no longer {@code OPEN} or is past its expiry - expired, abandoned or paid by another instrument. */
    SESSION_CLOSED,

    /** The decision approved less than the purchase: the price is fixed, so a reduced approval is never offered (section 12.1). */
    APPROVED_BELOW_PURCHASE,

    /** No {@code ACTIVE} terms version for the product at the offering instant. */
    PRODUCT_NOT_OFFERED,

    /** The approval lapsed at its own validity before it could be offered. */
    DECISION_LAPSED
}
