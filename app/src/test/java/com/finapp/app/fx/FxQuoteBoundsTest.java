package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FixedSide;
import com.finapp.fx.Margin;
import com.finapp.fx.NotionalBounds;
import com.finapp.fx.PricingPair;
import com.finapp.fx.QuoteIssuance;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixed leg's notional bounds under pricing policy v1 (`P9-TSK-008`; O7): for every one of
 * the twenty conversion pairs and both fixed sides, the minimum and the maximum are admitted and
 * one minor unit beyond either is refused - judged in Tx1, before any provider call.
 */
@DisplayName("the fixed leg's bounds on every pair, both sides (P9-TSK-008)")
class FxQuoteBoundsTest {

    @Test
    @DisplayName("min and max admitted, one minor unit beyond refused - 20 pairs x 2 sides x 4 amounts")
    void everyPairBothSides() {
        List<String> judged = new ArrayList<>();
        for (FxAdministrationController.PairRequest request : PricingPolicyV1.pairs()) {
            if (!request.purpose().equals("CONVERSION")) {
                continue;
            }
            PricingPair pair = pricing(request);
            for (FixedSide side : FixedSide.values()) {
                NotionalBounds bounds = side == FixedSide.FIXED_SOURCE ? pair.sourceBounds() : pair.destinationBounds();
                Money min = bounds.minimum();
                Money max = bounds.maximum();
                String where = request.source() + "-" + request.destination() + " " + side;
                assertThat(QuoteIssuance.admits(pair, side, min)).as(where + " min").isTrue();
                assertThat(QuoteIssuance.admits(pair, side, max)).as(where + " max").isTrue();
                assertThat(QuoteIssuance.admits(pair, side, Money.ofMinorUnits(min.minorUnits() - 1, min.currency())))
                        .as(where + " below min").isFalse();
                assertThat(QuoteIssuance.admits(pair, side, Money.ofMinorUnits(max.minorUnits() + 1, max.currency())))
                        .as(where + " above max").isFalse();
                judged.add(where);
            }
        }
        assertThat(judged).hasSize(40);
    }

    private static PricingPair pricing(FxAdministrationController.PairRequest request) {
        CurrencyCode source = CurrencyCode.of(request.source());
        CurrencyCode destination = CurrencyCode.of(request.destination());
        return new PricingPair(
                source, destination, Margin.of(request.spread()), Margin.of(request.markup()), request.rateScale(),
                RoundingPolicy.valueOf(request.rateRounding()), RoundingPolicy.valueOf(request.amountRounding()),
                RoundingPolicy.valueOf(request.marginRounding()),
                new NotionalBounds(Money.of(new BigDecimal(request.sourceMinimum()), source),
                        Money.of(new BigDecimal(request.sourceMaximum()), source)),
                new NotionalBounds(Money.of(new BigDecimal(request.destinationMinimum()), destination),
                        Money.of(new BigDecimal(request.destinationMaximum()), destination)));
    }
}
