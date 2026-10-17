package com.finapp.checkout;

/**
 * Why a session cannot be completed by financing (`P12-TSK-007`, {@code PHASE_12_PLAN.md}
 * section 5.2a) — judged by {@link CheckoutSession#financingRefusalAt} under the session's row
 * lock, on the database clock, and answered as a value so the caller refuses with nothing written
 * rather than parsing an exception.
 */
public enum FinancingRefusal {

    /**
     * The session has left {@code OPEN}: a card or wallet confirmation moved it to
     * {@code PAYMENT_PENDING} first, it was already completed or financed, or it expired or was
     * abandoned. Checkout's own machine is the judge.
     */
    NOT_OPEN,

    /**
     * Still {@code OPEN} by its row but at or past its deadline on the database clock — the
     * sweeper simply has not arrived. A financing never lands on a dead offer.
     */
    EXPIRED
}
