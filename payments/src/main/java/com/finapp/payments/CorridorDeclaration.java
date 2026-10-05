package com.finapp.payments;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * What a corridor rail declares beyond its {@link RailCapabilities} (`P9-TSK-014`, ADR-0080
 * section 1): the corridor-specific facts live here, so {@code RailCapabilities} keeps its shape and
 * every existing declaration stays unchanged.
 *
 * <p>The rail's <strong>counterparty is named by its rail id</strong>: the corridor rail
 * {@code corridor-sim-a} clears on {@code CORRIDOR_CLEARING(corridor-sim-a)} (ADR-0078, ADR-0082) -
 * one code, so the declaration, the ledger's registry row and the settlement source cannot disagree.
 *
 * @param rail the corridor rail this declaration belongs to
 * @param coverage every (destination country, destination currency) the provider delivers to
 * @param returnWindow how long after delivery a credit may still come back
 * @param decisionDeadline how long a received-but-undecided credit may wait for the provider's word
 * @param deliveryEstimate how long an accepted credit normally takes to be delivered
 * @param chargeBearer who bears the provider's charges - always the platform, so the beneficiary
 *     receives exactly the instructed amount
 */
public record CorridorDeclaration(
        RailId rail,
        Set<Coverage> coverage,
        Duration returnWindow,
        Duration decisionDeadline,
        Duration deliveryEstimate,
        ChargeBearer chargeBearer) {

    /** One delivered (country, currency). */
    public record Coverage(CountryCode country, CurrencyCode currency) {
        public Coverage {
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }

    /** Who bears the provider's charges. */
    public enum ChargeBearer {
        /** The platform: the instructed amount arrives whole (SWIFT's {@code OUR}). */
        OUR
    }

    public CorridorDeclaration {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(coverage, "coverage must not be null");
        Objects.requireNonNull(returnWindow, "returnWindow must not be null");
        Objects.requireNonNull(decisionDeadline, "decisionDeadline must not be null");
        Objects.requireNonNull(deliveryEstimate, "deliveryEstimate must not be null");
        Objects.requireNonNull(chargeBearer, "chargeBearer must not be null");
        coverage = Set.copyOf(coverage);
        if (coverage.isEmpty()) {
            throw new IllegalArgumentException(
                    "a corridor rail covers at least one (country, currency): a rail that reaches"
                            + " nowhere is not declared");
        }
        for (Duration bound : java.util.List.of(returnWindow, decisionDeadline, deliveryEstimate)) {
            if (bound.isZero() || bound.isNegative()) {
                throw new IllegalArgumentException("a corridor's declared durations are positive");
            }
        }
    }

    /** The counterparty whose clearing position the rail settles on: its rail id's value. */
    public String counterparty() {
        return rail.value();
    }

    /**
     * Refuses a declaration incoherent with its rail's (`P9-TSK-014`): the same rail, a rail that
     * refunds nothing ({@code RefundMode.NONE} - credits only), and coverage only in currencies the
     * rail carries.
     */
    public void requireCoherentWith(PaymentRail declared) {
        Objects.requireNonNull(declared, "declared must not be null");
        if (!declared.id().equals(rail)) {
            throw new IllegalArgumentException(
                    "the corridor declaration of '" + rail.value() + "' was matched to rail '"
                            + declared.id().value() + "'");
        }
        RailCapabilities capabilities = declared.capabilities();
        if (capabilities.refundMode() != RailCapabilities.RefundMode.NONE) {
            throw new IllegalArgumentException(
                    "a corridor rail carries credits only and declares RefundMode.NONE: '"
                            + rail.value() + "' declares " + capabilities.refundMode()
                            + " (ADR-0080 section 1)");
        }
        for (Coverage covered : coverage) {
            if (capabilities.currencies().map(carried -> !carried.contains(covered.currency())).orElse(false)) {
                throw new IllegalArgumentException(
                        "corridor '" + rail.value() + "' covers " + covered.country() + "/"
                                + covered.currency() + " in a currency its rail does not carry");
            }
        }
    }
}
