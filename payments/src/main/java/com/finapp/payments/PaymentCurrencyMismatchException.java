package com.finapp.payments;

import com.finapp.sharedkernel.money.CurrencyCode;

/**
 * The commanded amount is not in the wallet's currency — the create command's authoritative
 * resolution refusing a request's claim ({@code P5-TSK-009}; the composite-FK half is
 * {@code P5-TSK-008}'s recorded deferral). Names the currencies, never an amount
 * ({@code INV-AUD-02}). Nothing was written.
 */
public final class PaymentCurrencyMismatchException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public PaymentCurrencyMismatchException(CurrencyCode commanded, CurrencyCode wallet) {
        super("a payment must be in the wallet's currency: commanded " + commanded
                + " against a " + wallet + " wallet");
    }
}
