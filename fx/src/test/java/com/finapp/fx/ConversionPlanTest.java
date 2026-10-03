package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.fx.ConversionPlan.Plan;
import com.finapp.fx.ConversionPlan.Priced;
import com.finapp.fx.ConversionPlan.Refusal;
import com.finapp.fx.ConversionPlan.Refused;
import com.finapp.fx.ConversionPlan.Result;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code PHASE_9_PLAN.md} §12.2's worked figures, reproduced to the minor unit, and every typed
 * refusal (`P9-TSK-002`). Policy v1: spread 0.003500, markup 0.001500, rate rounding
 * {@code TOWARDS_ZERO}, amount and margin rounding {@code HALF_EVEN}, rate scale 6 for every case
 * here (none has a JPY source). Every figure was re-derived with exact decimals before it was
 * written down.
 */
@DisplayName("ConversionPlan - the one pure pricing function (P9-TSK-002)")
class ConversionPlanTest {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final CurrencyCode USD = CurrencyCode.of("USD");
    static final CurrencyCode GBP = CurrencyCode.of("GBP");
    static final CurrencyCode JPY = CurrencyCode.of("JPY");
    static final CurrencyCode BHD = CurrencyCode.of("BHD");
    static final Duration SIXTY_SECONDS = Duration.ofSeconds(60);

    static Money money(String amount, CurrencyCode currency) {
        return Money.of(new BigDecimal(amount), currency);
    }

    static ExchangeRate rate(CurrencyCode s, CurrencyCode d, String value) {
        return ExchangeRate.of(s, d, new BigDecimal(value));
    }

    /** O7's bounds for one currency. */
    static NotionalBounds bounds(CurrencyCode currency) {
        return switch (currency.code()) {
            case "JPY" -> new NotionalBounds(money("100", JPY), money("7500000", JPY));
            case "BHD" -> new NotionalBounds(money("0.500", BHD), money("20000.000", BHD));
            default -> new NotionalBounds(money("1.00", currency), money("50000.00", currency));
        };
    }

    /** Policy v1's pair for a direction, at O7's rate scale (10 for a JPY source, 6 otherwise). */
    static PricingPair v1(CurrencyCode source, CurrencyCode destination) {
        return pair(source, destination, "0.003500", "0.001500", source.equals(JPY) ? 10 : 6,
                RoundingPolicy.TOWARDS_ZERO, RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_EVEN);
    }

    static PricingPair pair(
            CurrencyCode source, CurrencyCode destination, String spread, String markup,
            int rateScale, RoundingPolicy rate, RoundingPolicy amount, RoundingPolicy margin) {
        return new PricingPair(source, destination, Margin.of(spread), Margin.of(markup), rateScale,
                rate, amount, margin, bounds(source), bounds(destination));
    }

    static Plan priced(Result result) {
        assertThat(result).isInstanceOf(Priced.class);
        return ((Priced) result).plan();
    }

    static Refusal refusal(Result result) {
        assertThat(result).isInstanceOf(Refused.class);
        return ((Refused) result).refusal();
    }

    @Nested
    @DisplayName("PHASE_9_PLAN.md §12.2's worked figures, to the minor unit")
    class WorkedFigures {

        @Test
        @DisplayName("EUR→USD, FIXED_SOURCE 1,000.00 EUR: Dc 1,079.60, M 5.43 = 3.80 / 1.63,"
                + " residual −0.01 USD")
        void eurToUsdFixedSource() {
            Plan plan = priced(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1000.00", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1085.02", USD),
                            SIXTY_SECONDS),
                    v1(EUR, USD)));
            assertThat(plan.customerRate().value()).isEqualByComparingTo("1.079598");
            assertThat(plan.customerPays()).isEqualTo(money("1000.00", EUR));
            assertThat(plan.customerReceives()).isEqualTo(money("1079.60", USD));
            assertThat(plan.positionSource()).isEqualTo(money("1000.00", EUR));
            assertThat(plan.positionDestination()).as("the provider's stated counter")
                    .isEqualTo(money("1085.02", USD));
            assertThat(plan.margin()).isEqualTo(money("5.43", USD));
            assertThat(plan.spreadMargin()).isEqualTo(money("3.80", USD));
            assertThat(plan.markupMargin()).isEqualTo(money("1.63", USD));
            assertThat(plan.residual()).isEqualTo(money("-0.01", USD));
            assertThat(plan.computedCurrency()).isEqualTo(USD);
            assertThat(plan.internalRate().value()).as("§12.4's dealing rate")
                    .isEqualByComparingTo("1.0812264160");
            assertThat(plan.internalRate().value().scale()).isEqualTo(10);
            assertThat(ConversionPlan.disclosedMarginOverMid(
                            plan.customerRate(), rate(EUR, USD, "1.085200")))
                    .as("§12.4's disclosed margin over mid 1.085200")
                    .isEqualByComparingTo("0.005162");
        }

        @Test
        @DisplayName("EUR→USD, FIXED_DESTINATION 1,000.00 USD: Sc 926.27, M 4.63 = 3.24 / 1.39,"
                + " residual 0.00 EUR")
        void eurToUsdFixedDestination() {
            Plan plan = priced(ConversionPlan.compute(FixedSide.FIXED_DESTINATION,
                    money("1000.00", USD),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("921.64", EUR),
                            SIXTY_SECONDS),
                    v1(EUR, USD)));
            assertThat(plan.customerPays()).as("1000 ÷ 1.079598, one division")
                    .isEqualTo(money("926.27", EUR));
            assertThat(plan.customerReceives()).isEqualTo(money("1000.00", USD));
            assertThat(plan.positionSource()).as("the provider's stated counter")
                    .isEqualTo(money("921.64", EUR));
            assertThat(plan.positionDestination()).isEqualTo(money("1000.00", USD));
            assertThat(plan.margin()).isEqualTo(money("4.63", EUR));
            assertThat(plan.spreadMargin()).isEqualTo(money("3.24", EUR));
            assertThat(plan.markupMargin()).isEqualTo(money("1.39", EUR));
            assertThat(plan.residual()).isEqualTo(money("0.00", EUR));
            assertThat(plan.computedCurrency()).isEqualTo(EUR);
        }

        @Test
        @DisplayName("USD→JPY (2→0) 250.00 USD: Dc 37,281, M 187 = 131 / 56, residual +1 JPY")
        void usdToJpy() {
            Plan plan = priced(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("250.00", USD),
                    new ProviderQuote(rate(USD, JPY, "149.8742"), money("37469", JPY),
                            SIXTY_SECONDS),
                    v1(USD, JPY)));
            assertThat(plan.customerRate().value()).isEqualByComparingTo("149.124829");
            assertThat(plan.customerReceives()).isEqualTo(money("37281", JPY));
            assertThat(plan.margin()).isEqualTo(money("187", JPY));
            assertThat(plan.spreadMargin()).isEqualTo(money("131", JPY));
            assertThat(plan.markupMargin()).isEqualTo(money("56", JPY));
            assertThat(plan.residual()).isEqualTo(money("1", JPY));
        }

        @Test
        @DisplayName("EUR→BHD (2→3) 1,234.57 EUR: Dc 505.168, M 2.540 = 1.778 / 0.762,"
                + " residual −0.001 BHD")
        void eurToBhd() {
            Plan plan = priced(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1234.57", EUR),
                    new ProviderQuote(rate(EUR, BHD, "0.411242"), money("507.707", BHD),
                            SIXTY_SECONDS),
                    v1(EUR, BHD)));
            assertThat(plan.customerRate().value()).isEqualByComparingTo("0.409185");
            assertThat(plan.customerReceives()).isEqualTo(money("505.168", BHD));
            assertThat(plan.margin()).isEqualTo(money("2.540", BHD));
            assertThat(plan.spreadMargin()).isEqualTo(money("1.778", BHD));
            assertThat(plan.markupMargin()).isEqualTo(money("0.762", BHD));
            assertThat(plan.residual()).isEqualTo(money("-0.001", BHD));
        }

        @Test
        @DisplayName("BHD→JPY (3→0) 12.345 BHD: Dc 4,883, M 25 = 18 / 7 (the 17.5 / 7.5 tie goes to"
                + " spread, the earlier part), residual −1 JPY")
        void bhdToJpy() {
            Plan plan = priced(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("12.345", BHD),
                    new ProviderQuote(rate(BHD, JPY, "397.5121"), money("4907", JPY),
                            SIXTY_SECONDS),
                    v1(BHD, JPY)));
            assertThat(plan.customerRate().value()).isEqualByComparingTo("395.524539");
            assertThat(plan.customerReceives()).isEqualTo(money("4883", JPY));
            assertThat(plan.margin()).isEqualTo(money("25", JPY));
            assertThat(plan.spreadMargin()).isEqualTo(money("18", JPY));
            assertThat(plan.markupMargin()).isEqualTo(money("7", JPY));
            assertThat(plan.residual()).isEqualTo(money("-1", JPY));
        }
    }

    @Nested
    @DisplayName("the typed refusals - never a shipped price")
    class Refusals {

        @Test
        @DisplayName("a stated counter a full minor unit from the exact product is incoherent;"
                + " one strictly inside is not")
        void incoherentProvider() {
            // 1085.024 exact: 1085.02 and 1085.03 are both within 0.01; 1085.04 is 0.016 away.
            assertThat(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1000.00", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1085.03", USD),
                            SIXTY_SECONDS),
                    v1(EUR, USD))).isInstanceOf(Priced.class);
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1000.00", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1085.04", USD),
                            SIXTY_SECONDS),
                    v1(EUR, USD))))
                    .isEqualTo(Refusal.PROVIDER_QUOTE_INCOHERENT);
            // Destination-fixed, by cross-multiplication: |921.66 × 1.085024 − 1000| = 0.0202 >
            // 1.085024 × 0.01.
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_DESTINATION,
                    money("1000.00", USD),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("921.66", EUR),
                            SIXTY_SECONDS),
                    v1(EUR, USD))))
                    .isEqualTo(Refusal.PROVIDER_QUOTE_INCOHERENT);
        }

        @Test
        @DisplayName("a zero-margin pair is MARGIN_UNATTRIBUTABLE, whatever the amounts")
        void zeroMarginPairRefused() {
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1000.00", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1085.02", USD),
                            SIXTY_SECONDS),
                    pair(EUR, USD, "0", "0", 6, RoundingPolicy.TOWARDS_ZERO,
                            RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_EVEN))))
                    .isEqualTo(Refusal.MARGIN_UNATTRIBUTABLE);
        }

        @Test
        @DisplayName("a customer rate rounded ABOVE the provider's makes a negative margin:"
                + " MARGIN_NEGATIVE, a defect")
        void negativeMarginRefused() {
            // rate scale 0, CEILING: rc = ceil(1.085024 × 0.995) = 2 > rp.
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1000.00", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1085.02", USD),
                            SIXTY_SECONDS),
                    pair(EUR, USD, "0.003500", "0.001500", 0, RoundingPolicy.CEILING,
                            RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_EVEN))))
                    .isEqualTo(Refusal.MARGIN_NEGATIVE);
        }

        @Test
        @DisplayName("a fixed leg outside its bounds, or a computed leg that rounds to nothing, is"
                + " AMOUNT_OUT_OF_RANGE")
        void amountOutOfRange() {
            ProviderQuote quote = new ProviderQuote(rate(EUR, USD, "1.085024"),
                    money("54251.20", USD), SIXTY_SECONDS);
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_SOURCE,
                    money("50000.01", EUR), quote, v1(EUR, USD))))
                    .isEqualTo(Refusal.AMOUNT_OUT_OF_RANGE);
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("0.99", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1.07", USD),
                            SIXTY_SECONDS),
                    v1(EUR, USD))))
                    .isEqualTo(Refusal.AMOUNT_OUT_OF_RANGE);
            // A rate scale of 0 rounding 0.0068 towards zero: rc = 0, nothing delivered.
            assertThat(refusal(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("100", JPY),
                    new ProviderQuote(rate(JPY, USD, "0.0068"), money("0.68", USD), SIXTY_SECONDS),
                    pair(JPY, USD, "0.003500", "0.001500", 0, RoundingPolicy.TOWARDS_ZERO,
                            RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_EVEN))))
                    .isEqualTo(Refusal.AMOUNT_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("a residual past the policy's proven bound is PLAN_INVARIANT_VIOLATED - the"
                + " bound is 1 when amount and margin both round to nearest, 2 otherwise")
        void residualBoundIsThePolicyFamilys() {
            assertThat(ConversionPlan.residualBound(RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_UP))
                    .isEqualTo(1L);
            assertThat(ConversionPlan.residualBound(RoundingPolicy.HALF_EVEN, RoundingPolicy.FLOOR))
                    .isEqualTo(2L);
            assertThat(ConversionPlan.residualBound(RoundingPolicy.CEILING, RoundingPolicy.HALF_EVEN))
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("a caller's defect is an IllegalArgumentException, never a pricing outcome")
        void callerDefectsThrow() {
            ProviderQuote quote = new ProviderQuote(rate(EUR, USD, "1.085024"),
                    money("1085.02", USD), SIXTY_SECONDS);
            assertThatIllegalArgumentException().as("a quote for another pair")
                    .isThrownBy(() -> ConversionPlan.compute(FixedSide.FIXED_SOURCE,
                            money("1000.00", GBP), new ProviderQuote(rate(GBP, USD, "1.27"),
                                    money("1270.00", USD), SIXTY_SECONDS), v1(EUR, USD)));
            assertThatIllegalArgumentException().as("the fixed amount in the wrong currency")
                    .isThrownBy(() -> ConversionPlan.compute(FixedSide.FIXED_SOURCE,
                            money("1000.00", USD), quote, v1(EUR, USD)));
            assertThatIllegalArgumentException().as("an amount at a scale its currency has not")
                    .isThrownBy(() -> ConversionPlan.compute(FixedSide.FIXED_SOURCE,
                            Money.ofPersisted(100000, EUR, 3), quote, v1(EUR, USD)));
        }
    }

    @Nested
    @DisplayName("what the plan always holds")
    class Holds {

        @Test
        @DisplayName("the plan identity and the attribution sum hold exactly, both sides")
        void identityAndAttribution() {
            Plan fs = priced(ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1000.00", EUR),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("1085.02", USD),
                            SIXTY_SECONDS),
                    v1(EUR, USD)));
            assertThat(fs.customerReceives().plus(fs.margin()).plus(fs.residual()))
                    .as("Dp = Dc + M + r").isEqualTo(fs.positionDestination());
            assertThat(fs.spreadMargin().plus(fs.markupMargin())).isEqualTo(fs.margin());
            Plan fd = priced(ConversionPlan.compute(FixedSide.FIXED_DESTINATION,
                    money("1000.00", USD),
                    new ProviderQuote(rate(EUR, USD, "1.085024"), money("921.64", EUR),
                            SIXTY_SECONDS),
                    v1(EUR, USD)));
            assertThat(fd.positionSource().plus(fd.margin()).plus(fd.residual()))
                    .as("Sc = Sp + M + r").isEqualTo(fd.customerPays());
            assertThat(fd.spreadMargin().plus(fd.markupMargin())).isEqualTo(fd.margin());
        }

        @Test
        @DisplayName("the same inputs give the same plan - the basis of replay")
        void deterministic() {
            Result first = ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1234.57", EUR),
                    new ProviderQuote(rate(EUR, BHD, "0.411242"), money("507.707", BHD),
                            SIXTY_SECONDS),
                    v1(EUR, BHD));
            Result second = ConversionPlan.compute(FixedSide.FIXED_SOURCE, money("1234.57", EUR),
                    new ProviderQuote(rate(EUR, BHD, "0.411242"), money("507.707", BHD),
                            SIXTY_SECONDS),
                    v1(EUR, BHD));
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("the disclosed margin needs no inversion in either direction, and refuses an"
                + " unrelated reference")
        void disclosedMarginBothDirections() {
            ExchangeRate rc = rate(EUR, USD, "1.079598");
            assertThat(ConversionPlan.disclosedMarginOverMid(rc, rate(USD, EUR, "0.921489")))
                    .as("1 − 1.079598 × 0.921489 = 0.005162318…, an exact product")
                    .isEqualByComparingTo("0.005162");
            assertThat(ConversionPlan.disclosedMarginOverMid(rc, rate(EUR, USD, "1.085200"))
                    .scale()).isEqualTo(6);
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ConversionPlan.disclosedMarginOverMid(rc, rate(GBP, USD, "1.27")));
        }

        @Test
        @DisplayName("a margin with a seventh decimal, or of ten percent, is refused - never rounded")
        void marginRefusesOverPrecision() {
            assertThatIllegalArgumentException().isThrownBy(() -> Margin.of("0.0035001"));
            assertThatIllegalArgumentException().isThrownBy(() -> Margin.of("0.1"));
            assertThatIllegalArgumentException().isThrownBy(() -> Margin.of("-0.000001"));
            assertThat(Margin.of("0.003500").attributionWeight()).isEqualTo(3500L);
            assertThat(Margin.of("0.0015").attributionWeight()).isEqualTo(1500L);
            assertThat(Margin.of("0.003500")).isEqualTo(Margin.of("0.0035"));
        }
    }
}
