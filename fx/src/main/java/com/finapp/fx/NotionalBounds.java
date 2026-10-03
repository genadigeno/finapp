package com.finapp.fx;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The amounts a fixed leg may take in one currency, inclusive (ADR-0074 §8; owner decision O7:
 * EUR, GBP and USD 1.00-50,000.00, JPY 100-7,500,000, BHD 0.500-20,000.000). Bounds bind the fixed
 * leg only; the computed leg must merely be positive.
 *
 * @param minimum the smallest admitted amount, strictly positive
 * @param maximum the largest admitted amount, in the same currency and scale
 */
public record NotionalBounds(Money minimum, Money maximum) {

    public NotionalBounds {
        Objects.requireNonNull(minimum, "minimum must not be null");
        Objects.requireNonNull(maximum, "maximum must not be null");
        if (!minimum.currency().equals(maximum.currency()) || minimum.scale() != maximum.scale()) {
            throw new IllegalArgumentException(
                    "Bounds are one currency at one scale: " + minimum + " and " + maximum);
        }
        if (!minimum.isPositive() || minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Bounds need 0 < minimum <= maximum, but were " + minimum + " and " + maximum);
        }
    }

    /** True when {@code amount} is in this currency and inside the bounds, inclusive. */
    public boolean admits(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.currency().equals(minimum.currency())
                && amount.scale() == minimum.scale()
                && amount.compareTo(minimum) >= 0
                && amount.compareTo(maximum) <= 0;
    }
}
