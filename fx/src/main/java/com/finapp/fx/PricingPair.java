package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One direction's pricing terms under one pricing-policy version (ADR-0074 §§4, 6, 8): what the
 * conversion plan is computed under, and what every quote and trade copies ({@code INV-HIST-04}).
 *
 * <p><strong>No default anywhere.</strong> Each of the three roundings is named
 * ({@code INV-MON-03}), and the rate scale is the pair's own - at most
 * {@link ExchangeRate#MAX_SCALE}; v1 chooses 10 for the JPY-source pairs and 6 for the rest (O7).
 *
 * <p><strong>{@code spread + markup > 0} is NOT checked here</strong> - deliberately: a zero-margin
 * pair is a stored configuration the plan must refuse with its own typed outcome
 * ({@code MARGIN_UNATTRIBUTABLE}), so the refusal is observable rather than an exception. The
 * pricing-pair table's {@code CHECK} holds it for every writer when the table exists.
 *
 * @param source the currency sold
 * @param destination the currency bought
 * @param spread the dealing spread
 * @param markup the commercial markup
 * @param rateScale the decimal places the customer rate is rounded to, {@code 0..MAX_SCALE}
 * @param rateRounding the customer rate's rounding
 * @param amountRounding the customer leg's rounding
 * @param marginRounding the margin's rounding
 * @param sourceBounds the source leg's bounds, when it is the fixed one
 * @param destinationBounds the destination leg's bounds, when it is the fixed one
 */
public record PricingPair(
        CurrencyCode source,
        CurrencyCode destination,
        Margin spread,
        Margin markup,
        int rateScale,
        RoundingPolicy rateRounding,
        RoundingPolicy amountRounding,
        RoundingPolicy marginRounding,
        NotionalBounds sourceBounds,
        NotionalBounds destinationBounds) {

    public PricingPair {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(spread, "spread must not be null");
        Objects.requireNonNull(markup, "markup must not be null");
        Objects.requireNonNull(rateRounding, "rateRounding must not be null");
        Objects.requireNonNull(amountRounding, "amountRounding must not be null");
        Objects.requireNonNull(marginRounding, "marginRounding must not be null");
        Objects.requireNonNull(sourceBounds, "sourceBounds must not be null");
        Objects.requireNonNull(destinationBounds, "destinationBounds must not be null");
        if (source.equals(destination)) {
            throw new IllegalArgumentException("A pair converts between two currencies: " + source);
        }
        if (rateScale < 0 || rateScale > ExchangeRate.MAX_SCALE) {
            throw new IllegalArgumentException(
                    "A rate scale is 0.." + ExchangeRate.MAX_SCALE + ", but was " + rateScale);
        }
        if (!sourceBounds.minimum().currency().equals(source)
                || !destinationBounds.minimum().currency().equals(destination)) {
            throw new IllegalArgumentException("Each side's bounds are in that side's currency");
        }
    }

    /** {@code spread + markup}: the total fraction the customer rate gives up. */
    public BigDecimal totalMargin() {
        return spread.value().add(markup.value());
    }
}
