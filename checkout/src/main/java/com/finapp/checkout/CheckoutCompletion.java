package com.finapp.checkout;

/**
 * How a paid session ended — the one vocabulary the order's announcement, the session read, the
 * order's audit record and the conversion meters share (`P12-TSK-007`).
 *
 * <p>Derived, never stored: {@link CheckoutSession#completedAs()} reads it off the state and the
 * session's two references, so there is no column that could disagree with the row it describes.
 * {@code COMPLETED} and {@code COMPLETED_LATE} are the two capture endings ({@code INV-MER-06});
 * {@code FINANCED} is the session a BNPL purchase completed from {@code OPEN} — its status is
 * {@code COMPLETED} like a captured one, because the merchant's order is the same commercial fact
 * whatever paid for it, and this vocabulary is where the difference is said once.
 */
public enum CheckoutCompletion {

    /** A capture landed on the session's payment intent inside the offer's window. */
    COMPLETED,

    /** A capture landed after the offer expired ({@code INV-MER-06}). */
    COMPLETED_LATE,

    /**
     * A BNPL purchase financed the sale (`P12-TSK-007`, ADR-0101): no payment intent, the
     * financing reference set once, the order's entry the financing entry. Never late.
     */
    FINANCED
}
