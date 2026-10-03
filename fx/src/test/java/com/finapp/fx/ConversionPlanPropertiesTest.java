package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The residual bound, the plan identity and every scale, over a million seeded cases per policy
 * family (`P9-TSK-002`, ADR-0074 §5): every one of the twenty directed pairs over EUR, GBP, USD,
 * JPY and BHD, provider rates at the full ten decimals, each pair at its own rate scale (O7: 10
 * for a JPY source, 6 otherwise), both fixed sides, random margins, and provider counters
 * rounded under every named policy - coherent by construction, as the plan requires.
 *
 * <p><strong>Deterministic.</strong> Case {@code i} draws from {@code SplittableRandom(SEED + i)}
 * alone, so the parallel run is reproducible and a failure names the case that reproduces it.
 */
@DisplayName("ConversionPlan properties - a million cases per policy family (P9-TSK-002)")
class ConversionPlanPropertiesTest {

    private static final long CASES = 1_000_000L;
    private static final long SEED = 0x5EED_9002L;

    private static final List<CurrencyCode> CURRENCIES = List.of(
            CurrencyCode.of("EUR"), CurrencyCode.of("GBP"), CurrencyCode.of("USD"),
            CurrencyCode.of("JPY"), CurrencyCode.of("BHD"));

    /** Rough mids per one EUR - only to give each pair a realistic rate magnitude. */
    private static final List<BigDecimal> MIDS = List.of(
            new BigDecimal("1"), new BigDecimal("0.86"), new BigDecimal("1.085"),
            new BigDecimal("162.5"), new BigDecimal("0.409"));

    private static final List<RoundingPolicy> HALF =
            List.of(RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_UP);
    private static final List<RoundingPolicy> DIRECTED = List.of(
            RoundingPolicy.TOWARDS_ZERO, RoundingPolicy.AWAY_FROM_ZERO, RoundingPolicy.FLOOR,
            RoundingPolicy.CEILING);
    private static final List<RoundingPolicy> ALL = List.of(RoundingPolicy.values());

    @Test
    @DisplayName("half policies: |residual| <= 1, reached, and every invariant holds")
    void halfPolicies() {
        Stats stats = run(HALF, 1L);
        assertThat(stats.maxResidual.get()).as("the bound is reached, so the test is not vacuous")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("directed policies: |residual| <= 2, reached, and every invariant holds")
    void directedPolicies() {
        Stats stats = run(DIRECTED, 2L);
        assertThat(stats.maxResidual.get()).as("the bound is reached, so the test is not vacuous")
                .isEqualTo(2L);
    }

    private record Stats(LongAccumulator maxResidual, AtomicLong priced) {}

    private static Stats run(List<RoundingPolicy> family, long bound) {
        LongAccumulator maxResidual = new LongAccumulator(Math::max, 0L);
        AtomicLong priced = new AtomicLong();
        AtomicLong negative = new AtomicLong();
        AtomicReference<String> firstFailure = new AtomicReference<>();
        LongStream.range(0, CASES).parallel().forEach(i -> {
            if (firstFailure.get() != null) {
                return;
            }
            String failure = oneCase(i, family, bound, maxResidual, priced, negative);
            if (failure != null) {
                firstFailure.compareAndSet(null, "case " + i + ": " + failure);
            }
        });
        assertThat(firstFailure.get()).as("the first failing case").isNull();
        assertThat(priced.get() + negative.get()).isEqualTo(CASES);
        assertThat(priced.get()).as("almost every case prices").isGreaterThan(CASES * 9 / 10);
        return new Stats(maxResidual, priced);
    }

    /** One case; a non-null return names what failed. */
    private static String oneCase(
            long i, List<RoundingPolicy> family, long bound, LongAccumulator maxResidual,
            AtomicLong priced, AtomicLong negative) {
        SplittableRandom random = new SplittableRandom(SEED + i);
        int pairIndex = (int) (i % 20);
        int s = pairIndex / 4;
        int d = pairIndex % 4 >= s ? pairIndex % 4 + 1 : pairIndex % 4;
        CurrencyCode source = CURRENCIES.get(s);
        CurrencyCode destination = CURRENCIES.get(d);
        BigDecimal noise = BigDecimal.valueOf(900_000 + random.nextInt(200_001), 6);
        BigDecimal rpValue = MIDS.get(d).divide(MIDS.get(s), 30, RoundingMode.HALF_EVEN)
                .multiply(noise).setScale(ExchangeRate.MAX_SCALE, RoundingMode.DOWN);
        ExchangeRate rp = ExchangeRate.of(source, destination, rpValue);
        int rateScale = source.code().equals("JPY") ? 10 : 6;

        long spread = random.nextInt(20_000);
        long markup = random.nextInt(20_000);
        if (spread + markup == 0) {
            spread = 1;
        }
        RoundingPolicy rateRounding = ALL.get(random.nextInt(ALL.size()));
        RoundingPolicy amountRounding = family.get(random.nextInt(family.size()));
        RoundingPolicy marginRounding = family.get(random.nextInt(family.size()));
        PricingPair pair = new PricingPair(source, destination,
                new Margin(BigDecimal.valueOf(spread, 6)), new Margin(BigDecimal.valueOf(markup, 6)),
                rateScale, rateRounding, amountRounding, marginRounding,
                ConversionPlanTest.bounds(source), ConversionPlanTest.bounds(destination));

        boolean sourceFixed = random.nextBoolean();
        NotionalBounds bounds = sourceFixed ? pair.sourceBounds() : pair.destinationBounds();
        long fixedMinor = bounds.minimum().minorUnits()
                + random.nextLong(bounds.maximum().minorUnits() - bounds.minimum().minorUnits() + 1);
        CurrencyCode fixedCurrency = sourceFixed ? source : destination;
        CurrencyCode computedCurrency = sourceFixed ? destination : source;
        Money fixed = Money.ofMinorUnits(fixedMinor, fixedCurrency);
        RoundingMode providerMode = ALL.get(random.nextInt(ALL.size())).mode();
        BigDecimal counter = sourceFixed
                ? rp.exactProduct(fixed).setScale(computedCurrency.minorUnits(), providerMode)
                : fixed.toBigDecimal().divide(rpValue, computedCurrency.minorUnits(), providerMode);
        ProviderQuote quote =
                new ProviderQuote(rp, Money.of(counter, computedCurrency), Duration.ofSeconds(30));

        Result result = ConversionPlan.compute(
                sourceFixed ? FixedSide.FIXED_SOURCE : FixedSide.FIXED_DESTINATION, fixed, quote,
                pair);
        if (result instanceof Refused refused) {
            boolean upward = rateRounding == RoundingPolicy.CEILING
                    || rateRounding == RoundingPolicy.AWAY_FROM_ZERO;
            if (refused.refusal() == Refusal.MARGIN_NEGATIVE && upward) {
                negative.incrementAndGet();
                return null; // a rate rounded up past rp: the defect the refusal exists for
            }
            return "refused " + refused.refusal() + ": " + refused.detail();
        }
        Plan plan = ((Priced) result).plan();
        priced.incrementAndGet();
        long r = Math.abs(plan.residual().minorUnits());
        maxResidual.accumulate(r);
        if (r > bound) {
            return "residual " + plan.residual() + " beyond " + bound;
        }
        Money identityLeft = sourceFixed
                ? plan.customerReceives().plus(plan.margin()).plus(plan.residual())
                : plan.positionSource().plus(plan.margin()).plus(plan.residual());
        Money identityRight = sourceFixed ? plan.positionDestination() : plan.customerPays();
        if (!identityLeft.equals(identityRight)) {
            return "the plan identity fails: " + identityLeft + " vs " + identityRight;
        }
        if (!plan.spreadMargin().plus(plan.markupMargin()).equals(plan.margin())) {
            return "the attribution does not sum to the margin";
        }
        if (plan.margin().isNegative()) {
            return "a negative margin was priced";
        }
        if (plan.customerRate().value().scale() > rateScale
                || plan.internalRate().value().scale() > ExchangeRate.MAX_SCALE) {
            return "a rate beyond its scale";
        }
        boolean sameDirection = random.nextBoolean();
        BigDecimal refNoise = BigDecimal.valueOf(995_000 + random.nextInt(10_001), 6);
        ExchangeRate reference = sameDirection
                ? ExchangeRate.of(source, destination,
                        rpValue.multiply(refNoise).setScale(10, RoundingMode.DOWN))
                : ExchangeRate.of(destination, source,
                        MIDS.get(s).divide(MIDS.get(d), 30, RoundingMode.HALF_EVEN)
                                .multiply(refNoise).setScale(10, RoundingMode.DOWN));
        BigDecimal disclosed = ConversionPlan.disclosedMarginOverMid(plan.customerRate(), reference);
        if (disclosed.scale() != Margin.SCALE) {
            return "the disclosed margin is not at scale six";
        }
        return null;
    }
}
