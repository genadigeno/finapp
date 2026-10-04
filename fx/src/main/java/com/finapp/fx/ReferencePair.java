package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Objects;

/**
 * One canonical reference pair (`P9-TSK-005`, ADR-0075 §1): the base currency priced in the quote
 * currency, in market direction. Each unordered pair is held once - a reference is never divided
 * to serve the other direction (ADR-0074: no inversion); the quote path judges the inverse by the
 * exact product instead.
 */
public record ReferencePair(CurrencyCode base, CurrencyCode quote) {

    public ReferencePair {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(quote, "quote must not be null");
        if (base.equals(quote)) {
            throw new IllegalArgumentException("A pair prices one currency in another: " + base);
        }
    }

    public static ReferencePair of(String base, String quote) {
        return new ReferencePair(CurrencyCode.of(base), CurrencyCode.of(quote));
    }

    /** {@code EUR/USD} - the gauge's tag value, a category shared by everyone. */
    public String code() {
        return base.code() + "/" + quote.code();
    }

    @Override
    public String toString() {
        return code();
    }
}
