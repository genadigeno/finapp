package com.finapp.fx;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One (pair, purpose) of a pricing policy version (`P9-TSK-007`, ADR-0075 §7): the providers in
 * sourcing order, the {@link PricingPair} the conversion arithmetic consumes (spread, markup, rate
 * scale, the three named roundings, each side's notional bounds), the quote window, the cover
 * margin, the plausibility band (a fraction - 150 bps is {@code 0.015}) and the reference maximum
 * age. Every bound {@code fx V004} holds is judged here first, so a malformed pair is refused
 * before any write.
 */
public record PolicyPair(
        PricingPurpose purpose,
        List<String> providers,
        PricingPair pricing,
        Duration window,
        Duration coverMargin,
        BigDecimal band,
        Duration referenceMaxAge) {

    public PolicyPair {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(pricing, "pricing must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(coverMargin, "coverMargin must not be null");
        Objects.requireNonNull(band, "band must not be null");
        Objects.requireNonNull(referenceMaxAge, "referenceMaxAge must not be null");
        providers = List.copyOf(providers);
        if (providers.isEmpty() || providers.size() > 8 || providers.stream().distinct().count() != providers.size()) {
            throw new IllegalArgumentException("a pair names 1..8 distinct providers in sourcing order");
        }
        if (pricing.totalMargin().signum() <= 0 || pricing.totalMargin().compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("a pair's spread + markup is above 0 and below 1");
        }
        if (pricing.spread().value().scale() > 6 || pricing.markup().value().scale() > 6) {
            throw new IllegalArgumentException("spread and markup carry at most 6 decimals");
        }
        if (window.compareTo(Duration.ofSeconds(5)) < 0 || window.compareTo(Duration.ofHours(1)) > 0
                || window.toNanos() % 1_000_000_000L != 0) {
            throw new IllegalArgumentException("a window is 5 s .. 1 h in whole seconds");
        }
        if (coverMargin.isNegative() || coverMargin.compareTo(Duration.ofMinutes(10)) > 0
                || coverMargin.toNanos() % 1_000_000_000L != 0) {
            throw new IllegalArgumentException("a cover margin is 0 .. 10 min in whole seconds");
        }
        if (band.signum() <= 0 || band.compareTo(BigDecimal.ONE) >= 0 || band.scale() > 6) {
            throw new IllegalArgumentException("a band is a fraction above 0 and below 1, at most 6 decimals");
        }
        if (referenceMaxAge.compareTo(Duration.ofSeconds(1)) < 0
                || referenceMaxAge.compareTo(Duration.ofDays(1)) > 0
                || referenceMaxAge.toNanos() % 1_000_000_000L != 0) {
            throw new IllegalArgumentException("a reference maximum age is 1 s .. 1 day in whole seconds");
        }
    }
}
