package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pricing policy v1's content is the phase plan's O7, row for row (`P9-TSK-007`). */
@DisplayName("pricing policy v1 is O7 (P9-TSK-007)")
class PricingPolicyV1Test {

    private final List<FxAdministrationController.PairRequest> pairs = PricingPolicyV1.pairs();

    @Test
    @DisplayName("every directional pair of the five currencies, under both purposes, once: 40 rows")
    void everyDirectionalPairOnce() {
        assertThat(pairs).hasSize(40);
        assertThat(pairs.stream().map(p -> p.source() + p.destination() + p.purpose()).collect(Collectors.toSet()))
                .hasSize(40);
        assertThat(pairs).noneMatch(p -> p.source().equals(p.destination()));
    }

    @Test
    @DisplayName("the margins, the roundings and the timings hold on every row")
    void theUniformTerms() {
        assertThat(pairs).allSatisfy(p -> {
            assertThat(p.spread()).isEqualTo("0.003500");
            assertThat(p.markup()).isEqualTo("0.001500");
            assertThat(p.rateRounding()).isEqualTo("TOWARDS_ZERO");
            assertThat(p.amountRounding()).isEqualTo("HALF_EVEN");
            assertThat(p.marginRounding()).isEqualTo("HALF_EVEN");
            assertThat(p.coverMarginSeconds()).isEqualTo(10L);
            assertThat(p.referenceMaxAgeSeconds()).isEqualTo(120L);
            assertThat(p.providers()).containsExactly("fx-sim-a");
            assertThat(p.windowSeconds()).isEqualTo(p.purpose().equals("CONVERSION") ? 30L : 60L);
        });
        assertThat(PricingPolicyV1.request("v1").openQuoteCap()).isEqualTo(5);
    }

    @Test
    @DisplayName("the rate scale is 10 only from JPY; the band is 150 bps only among EUR, GBP and USD")
    void theScaleAndTheBand() {
        assertThat(pairs.stream().filter(p -> p.rateScale() == 10))
                .hasSize(8)
                .allMatch(p -> p.source().equals("JPY"));
        assertThat(pairs.stream().filter(p -> p.band().equals("0.015")))
                .hasSize(12)
                .allMatch(p -> !p.source().matches("JPY|BHD") && !p.destination().matches("JPY|BHD"));
        assertThat(pairs.stream().filter(p -> p.band().equals("0.03"))).hasSize(28);
    }

    @Test
    @DisplayName("each currency's notional bounds, at its own scale")
    void theBounds() {
        FxAdministrationController.PairRequest jpyBhd = pairs.stream()
                .filter(p -> p.source().equals("JPY") && p.destination().equals("BHD"))
                .findFirst()
                .orElseThrow();
        assertThat(List.of(jpyBhd.sourceMinimum(), jpyBhd.sourceMaximum(), jpyBhd.destinationMinimum(),
                        jpyBhd.destinationMaximum()))
                .containsExactly("100", "7500000", "0.500", "20000.000");
        FxAdministrationController.PairRequest eurUsd = pairs.stream()
                .filter(p -> p.source().equals("EUR") && p.destination().equals("USD"))
                .findFirst()
                .orElseThrow();
        assertThat(List.of(eurUsd.sourceMinimum(), eurUsd.sourceMaximum()))
                .containsExactly("1.00", "50000.00");
    }
}
