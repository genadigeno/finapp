package com.finapp.creditmath;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The {@code ACT_365F} simple-interest arithmetic both version-1 engines compute with (`P11-TSK-007`; ADR-0094 point 2,
 * ADR-0093 section 3, owner decision L5; {@code INV-LND-09}, {@code INV-MON-01}): actual days over a fixed 365-day
 * year, interest simple on the base the caller supplies.
 *
 * <p><strong>One concept, one owner.</strong> {@link AccrualEngineV1} accrues {@code exactInterest(base, rate, 1)} a
 * day and rounds the period's sum once; {@code SCHEDULE_ENGINE_V1} rounds {@code exactInterest(B, rate, days)} once
 * per period. Because both read these functions they cannot disagree on what a day, a year or the one division is.
 *
 * <p><strong>Why the scale-20 division is not a second rounding.</strong> With the base at its currency's scale
 * {@code s} and the rate at no more than {@link #RATE_PLACES} places, the exact quotient is
 * {@code N / (365 x 10^(s+8))} for an integer {@code N}: it either terminates within 20 places (and the division is
 * exact) or lies at least {@code 1 / (730 x 10^(s+8))} from every half-minor-unit tie - many orders of magnitude beyond
 * the division's {@code 0.5 x 10^-20}. Rounding the scale-20 figure to the minor unit therefore always equals rounding
 * the exact rational once. The same bound is why a constant-base period's sum of daily figures rounds to the schedule's
 * figure whenever the period's length is not a multiple of 73 days (every period of a monthly loan or statement cycle
 * is shorter than 73 days) - {@code AccrualMatchesSchedulePropertiesTest}.
 *
 * <p>Plain arithmetic: the conventions are judged by each engine against its terms, not here. No state, no clock, no
 * locale, no floating point - the same on every instance.
 */
public final class Act365Fixed {

    /**
     * The scale of every exact interest figure - fixed by the engine versions that read it. Public since `P12-TSK-002`
     * moved the engines to {@code creditmath}: lending reads its stored cumulative figures back at this scale.
     */
    public static final int EXACT_SCALE = 20;

    /**
     * The largest nominal annual rate the arithmetic computes - a decimal fraction, 100 % (`P12-TSK-002`). The engines'
     * RANGE, which a product's terms door stays within: lending's {@code LoanTerms} declares its own bound, equal to this
     * today - the value {@link AccrualEngineV1} judged against before the move, so no refusal moved.
     */
    public static final BigDecimal MAXIMUM_RATE = BigDecimal.ONE;

    /**
     * The most decimal places a nominal annual rate may carry - the bound the exactness argument above rests on
     * (`P12-TSK-002`; lending's {@code LoanTerms} declares the same eight for its terms door, as {@link #MAXIMUM_RATE}).
     */
    public static final int RATE_PLACES = 8;

    /** The rounding of the one division to {@link #EXACT_SCALE}. */
    static final RoundingMode EXACT_ROUNDING = RoundingMode.HALF_EVEN;

    /** The fixed year: 365 every day, 29 February an ordinary day. */
    static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private Act365Fixed() {}

    /** The actual days in {@code [from, toExclusive)}; 29 February counts as one ordinary day. */
    static long days(LocalDate from, LocalDate toExclusive) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (toExclusive.isBefore(from)) {
            throw new IllegalArgumentException("a day count runs forwards");
        }
        return ChronoUnit.DAYS.between(from, toExclusive);
    }

    /**
     * {@code base x rate x days / 365}: the product exact, one division at {@link #EXACT_SCALE} {@link #EXACT_ROUNDING}.
     *
     * @throws IllegalArgumentException for a negative base, rate or day count
     */
    static BigDecimal exactInterest(Money base, BigDecimal annualRate, long days) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(annualRate, "annualRate");
        if (base.isNegative() || annualRate.signum() < 0 || days < 0) {
            throw new IllegalArgumentException("interest is computed on a base, a rate and days that are not negative");
        }
        return base.toBigDecimal()
                .multiply(annualRate)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_PER_YEAR, EXACT_SCALE, EXACT_ROUNDING);
    }

    /** {@code exact} rounded once, {@code HALF_EVEN}, to the currency's minor unit. */
    static Money roundedOnce(BigDecimal exact, CurrencyCode currency) {
        return Money.of(exact, currency, RoundingPolicy.HALF_EVEN);
    }
}
