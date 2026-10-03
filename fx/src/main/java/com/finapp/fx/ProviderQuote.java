package com.finapp.fx;

import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.Objects;

/**
 * A provider's firm quote, as the plan reads it (ADR-0074 §3): its rate, the counter amount it
 * STATES for the fixed leg, and how long it holds.
 *
 * <p>The stated counter is the provider's own rounding of its own product. The plan's position
 * legs copy it - accepted only if coherent - so a confirmed cover closes {@code FX_POSITION}
 * exactly and the provider's rounding never masquerades as market P&L. {@code validFor} bounds the
 * quote's expiry ({@code P9-TSK-007}); the plan itself never reads a clock.
 *
 * @param rate the provider's rate, source to destination, at most ten decimals
 * @param statedCounter the provider's stated amount of the computed leg's currency
 * @param validFor how long the provider holds the price, strictly positive
 */
public record ProviderQuote(ExchangeRate rate, Money statedCounter, Duration validFor) {

    public ProviderQuote {
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(statedCounter, "statedCounter must not be null");
        Objects.requireNonNull(validFor, "validFor must not be null");
        if (validFor.isNegative() || validFor.isZero()) {
            throw new IllegalArgumentException("A firm quote holds for a positive time: " + validFor);
        }
    }
}
