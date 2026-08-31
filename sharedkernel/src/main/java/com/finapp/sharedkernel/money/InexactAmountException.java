package com.finapp.sharedkernel.money;

import java.math.BigDecimal;

/**
 * An amount carried more precision than its currency can represent, so converting it would
 * have required rounding.
 *
 * <p>{@code INV-MON-03}: rounding is explicit and named. Deciding whether 12.345 USD becomes
 * 12.34 or 12.35 is a decision with a rounding mode behind it, and a constructor is the wrong
 * place to make it silently — implicit rounding is the most common source of unexplainable
 * cent-level drift.
 *
 * <p>The caller's options are to supply an amount the currency can hold, or to round it
 * deliberately with a named mode (P0-TSK-010).
 */
public final class InexactAmountException extends MonetaryException {

    private static final long serialVersionUID = 1L;

    private final transient BigDecimal amount;
    private final transient CurrencyCode currency;

    InexactAmountException(BigDecimal amount, CurrencyCode currency, Throwable cause) {
        super("Amount " + amount.toPlainString() + " cannot be represented exactly in " + currency
                + ", which has " + currency.minorUnits() + " decimal place(s). Rounding it is an "
                + "explicit decision that must name a rounding mode.", cause);
        this.amount = amount;
        this.currency = currency;
    }

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }
}
