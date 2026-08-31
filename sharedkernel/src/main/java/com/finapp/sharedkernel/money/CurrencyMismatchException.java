package com.finapp.sharedkernel.money;

import java.io.Serial;

/**
 * Arithmetic or comparison was attempted across two different currencies.
 *
 * <p>{@code INV-MON-04}: this is never resolved by conversion, coercion, or ignoring the
 * currency. Silent cross-currency arithmetic produces balances that look plausible and are
 * wrong, and the error is unrecoverable once it has been posted.
 *
 * <p>Carries both currencies because "currencies do not match" without saying which is a
 * diagnostic that costs someone an hour.
 */
public final class CurrencyMismatchException extends MonetaryException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CurrencyCode left;
    private final CurrencyCode right;

    CurrencyMismatchException(CurrencyCode left, CurrencyCode right, String operation) {
        super("Cannot " + operation + " " + right + " to " + left
                + ": amounts in different currencies are never combined. Converting between "
                + "them is an explicit FX operation that posts through an FX position.");
        this.left = left;
        this.right = right;
    }

    public CurrencyCode left() {
        return left;
    }

    public CurrencyCode right() {
        return right;
    }
}
