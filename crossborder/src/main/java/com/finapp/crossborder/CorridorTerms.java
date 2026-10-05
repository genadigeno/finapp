package com.finapp.crossborder;

import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One corridor's terms in a policy version (`P9-TSK-015`, ADR-0080 section 4, O7) - what an offer
 * will freeze and a completion post (`P9-TSK-018`/`-019`).
 *
 * @param key the corridor (S, D, destination country)
 * @param rails the candidate rails, in policy order - each must be declared by the build and cover
 *     (country, D), judged by {@link CorridorPolicyProposal#validate} against {@link CorridorDirectory}
 * @param feeFixed the transfer fee's fixed part, in S at S's scale ({@code INV-MON-01})
 * @param feeMargin the transfer fee's proportional part, a fraction {@code 0 <= m < 1} with at most
 *     six decimals ({@code 0.000000} is "+ 0 bps")
 * @param feeRounding how the proportional part rounds to S's scale - named ({@code INV-MON-03})
 * @param maximum the largest payment the corridor carries, in D at D's scale
 * @param screeningValidity how long a beneficiary's screening clearance stays good for payment
 * @param deliveryEstimate how long the corridor normally takes to deliver - whole hours
 * @param requiredData what the corridor needs about the beneficiary or the payment
 */
public record CorridorTerms(
        CorridorKey key,
        List<String> rails,
        Money feeFixed,
        BigDecimal feeMargin,
        RoundingPolicy feeRounding,
        Money maximum,
        Duration screeningValidity,
        Duration deliveryEstimate,
        Set<RequiredData> requiredData) {

    /** A rail's id shape - payments' {@code RailId}, restated (crossborder has no payments edge). */
    private static final Pattern RAIL = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    static final Duration MAX_HOURS = Duration.ofHours(720);

    public CorridorTerms {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(rails, "rails must not be null");
        Objects.requireNonNull(feeFixed, "feeFixed must not be null");
        Objects.requireNonNull(feeMargin, "feeMargin must not be null");
        Objects.requireNonNull(feeRounding, "feeRounding must not be null");
        Objects.requireNonNull(maximum, "maximum must not be null");
        Objects.requireNonNull(screeningValidity, "screeningValidity must not be null");
        Objects.requireNonNull(deliveryEstimate, "deliveryEstimate must not be null");
        Objects.requireNonNull(requiredData, "requiredData must not be null");
        rails = List.copyOf(rails);
        requiredData = Set.copyOf(requiredData);
        if (rails.isEmpty() || rails.size() > 8) {
            throw new IllegalArgumentException("a corridor names 1..8 candidate rails");
        }
        if (Set.copyOf(rails).size() != rails.size()) {
            throw new IllegalArgumentException("a corridor names each candidate rail once");
        }
        for (String rail : rails) {
            if (!RAIL.matcher(rail).matches()) {
                throw new IllegalArgumentException("a rail is named [a-z][a-z0-9-]{0,31}");
            }
        }
        if (!feeFixed.currency().equals(key.source()) || feeFixed.scale() != key.source().minorUnits()) {
            throw new IllegalArgumentException("the transfer fee is priced in the corridor's source currency, at its scale");
        }
        if (feeFixed.isNegative()) {
            throw new IllegalArgumentException("the transfer fee is never negative");
        }
        if (feeMargin.signum() < 0 || feeMargin.compareTo(BigDecimal.ONE) >= 0 || feeMargin.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("the fee margin is a fraction 0 <= m < 1 with at most six decimals");
        }
        // Canonical at the column's scale, so terms read back equal the terms proposed.
        feeMargin = feeMargin.setScale(6);
        if (!maximum.currency().equals(key.destination()) || maximum.scale() != key.destination().minorUnits()) {
            throw new IllegalArgumentException("the maximum per payment is priced in the corridor's destination currency, at its scale");
        }
        if (!maximum.isPositive()) {
            throw new IllegalArgumentException("the maximum per payment is positive");
        }
        requireWholeHours(screeningValidity, "the screening validity");
        requireWholeHours(deliveryEstimate, "the delivery estimate");
    }

    private static void requireWholeHours(Duration value, String what) {
        if (value.isNegative() || value.isZero() || value.compareTo(MAX_HOURS) > 0
                || value.toMinutesPart() != 0 || value.toSecondsPart() != 0 || value.toNanosPart() != 0) {
            throw new IllegalArgumentException(what + " is 1..720 whole hours");
        }
    }
}
