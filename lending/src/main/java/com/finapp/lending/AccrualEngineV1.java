package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link EngineVersion#ACCRUAL_ENGINE_V1}: daily interest whose period sum is the period's exact interest rounded once
 * (`P11-TSK-007`; ADR-0094 points 2-3, owner decision L5, {@code PHASE_11_PLAN.md} section 12.3; {@code INV-LND-09},
 * the arithmetic half of {@code INV-LND-02}, {@code INV-MON-01}).
 *
 * <p><strong>The arithmetic, fixed by this version.</strong> {@code ACT_365F} simple interest on the base the caller
 * supplies ({@link Act365Fixed}): a date's exact interest is {@code base x r / 365} at scale 20 - the division once a
 * day. Within a period - an instalment period of a loan or a statement cycle of a line ({@link AccrualPeriod}) - the
 * exact figures accumulate, {@code cum_exact(d) = cum_exact(prev) + base(d) x r / 365}, and a date posts the change in
 * the once-rounded cumulative figure, {@code posted(d) = round_HALF_EVEN(cum_exact(d)) - round_HALF_EVEN(cum_exact(prev))}.
 * The postings of a period therefore telescope to its exact interest rounded <em>once</em>: no residue accumulates
 * across days, and on an on-time path the period's sum is the schedule's {@code interest_j} to the minor unit
 * ({@code AccrualMatchesSchedulePropertiesTest}). Rounding each day on its own instead would leak up to half a minor
 * unit a day ({@code INV-LND-09}'s why). 29 February accrues one ordinary day at {@code /365}.
 *
 * <p><strong>What the caller owns.</strong> The base - principal outstanding ({@code LOAN_PRINCIPAL +
 * LOAN_PRINCIPAL_DUE} with value date on or before the date, read under the account lock), never interest or fees (A4);
 * the period; which dates are accruable and that every one is accrued exactly once, oldest first (the sweep,
 * `P11-TSK-016`, {@code INV-LND-02}); and whether a zero posting writes an entry (it does not - the row only). A date a
 * period has no accrual for (a credit line's day at zero principal) contributes nothing to the period's figure.
 *
 * <p><strong>What it refuses</strong> ({@link AccrualRefused}), computing nothing: terms naming a day count, a rounding
 * mode or an engine version this version does not implement - never falling back to another version (a figure is
 * re-derived years later by the version its agreement pins, {@code INV-LND-05}); a rate outside {@code 0..1} or past
 * {@link LoanTerms#RATE_PLACES} places (the bound the arithmetic's exactness rests on, {@link Act365Fixed}); a negative
 * base, or one in another currency or scale; a date outside its period; and a predecessor that is not an earlier accrual
 * of the same period by this version - so a date already accrued, or one accrued out of order, computes nothing.
 *
 * <p>A pure function of its arguments: no clock, no locale, no hash order, no state, no floating point - the same
 * figure on every instance and in every JVM ({@code AccrualMatchesSchedulePropertiesTest}'s two-JVM comparison). A
 * semantic change is {@code ACCRUAL_ENGINE_V2} beside this class, never an edit to it.
 */
public final class AccrualEngineV1 {

    /** The engine version this class is - named on every accrual it computes. */
    public static final EngineVersion VERSION = EngineVersion.ACCRUAL_ENGINE_V1;

    /** The interest rounding this version implements: cumulative, {@code HALF_EVEN}. */
    static final RoundingMode IMPLEMENTED_INTEREST_ROUNDING = RoundingMode.HALF_EVEN;

    private AccrualEngineV1() {}

    /**
     * The accrual of {@code date} on {@code base}, within {@code period}, after {@code previous} - the period's latest
     * accrual before {@code date}, or empty when {@code date} is the period's first accrual (the reset).
     *
     * @throws AccrualRefused when the terms, the base, the date or the predecessor are refused
     */
    public static InterestAccrual accrue(
            AccrualTerms terms, AccrualPeriod period, Optional<InterestAccrual> previous, LocalDate date, Money base) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(base, "base");
        judge(terms);
        judgeBase(base, terms.currency());
        if (!period.contains(date)) {
            throw new AccrualRefused(AccrualRefused.Reason.DATE_OUTSIDE_PERIOD, "the date lies outside its period");
        }
        BigDecimal before = previous.map(prior -> judgePredecessor(prior, period, date, terms.currency()).cumulativeExact())
                .orElse(BigDecimal.ZERO.setScale(Act365Fixed.EXACT_SCALE));
        BigDecimal cumulative = before.add(Act365Fixed.exactInterest(base, terms.nominalAnnualRate(), 1));
        Money posted = Act365Fixed.roundedOnce(cumulative, terms.currency())
                .minus(Act365Fixed.roundedOnce(before, terms.currency()));
        return new InterestAccrual(date, period, base, cumulative, posted, VERSION);
    }

    /**
     * What the period of {@code accrual} has posted through its date: its exact cumulative interest rounded once - the
     * sum of the period's postings, and at the period's last date the interest a billing makes due (`P11-TSK-017`).
     */
    public static Money postedThrough(InterestAccrual accrual) {
        Objects.requireNonNull(accrual, "accrual");
        if (accrual.engine() != VERSION) {
            throw new AccrualRefused(AccrualRefused.Reason.UNIMPLEMENTED_CONVENTION,
                    "the accrual was computed by another engine version");
        }
        return Act365Fixed.roundedOnce(accrual.cumulativeExact(), accrual.base().currency());
    }

    /**
     * The accrual a payoff projects (`P11-TSK-026`; ADR-0094 point 10): every date after {@code last} - or from the
     * first period's start when nothing has accrued - through {@code through}, on the current {@code base}, each period
     * resetting at its start. Exactly the figures {@link #accrue} posts for those dates if the base does not change, so
     * a quote good through {@code G} projects through {@code G - 1} what the payoff's own accrual will post. Empty when
     * {@code through} is already accrued.
     *
     * @param periods ascending, non-overlapping, holding every projected date (and {@code last}'s period, if any)
     * @throws AccrualRefused when the terms, the base, the periods or {@code last} are refused
     */
    public static Projection project(
            AccrualTerms terms, List<AccrualPeriod> periods, Optional<InterestAccrual> last, LocalDate through,
            Money base) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(periods, "periods");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(through, "through");
        Objects.requireNonNull(base, "base");
        judge(terms);
        judgeBase(base, terms.currency());
        List<AccrualPeriod> spans = List.copyOf(periods);
        if (spans.isEmpty()) {
            throw new AccrualRefused(AccrualRefused.Reason.PERIOD_INVALID, "a projection runs over at least one period");
        }
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i).start().isBefore(spans.get(i - 1).endExclusive())) {
                throw new AccrualRefused(AccrualRefused.Reason.PERIOD_INVALID,
                        "a projection's periods ascend without overlapping");
            }
        }
        List<InterestAccrual> accruals = new ArrayList<>();
        Money interest = Money.zero(terms.currency());
        Optional<InterestAccrual> previous = last;
        LocalDate date = last.map(prior -> prior.date().plusDays(1)).orElse(spans.get(0).start());
        while (!date.isAfter(through)) {
            AccrualPeriod period = periodOf(spans, date);
            if (previous.isPresent() && previous.get().period().contains(date) && !previous.get().period().equals(period)) {
                throw new AccrualRefused(AccrualRefused.Reason.PERIOD_INVALID,
                        "the periods disagree with the last accrual's period");
            }
            Optional<InterestAccrual> predecessor = previous.filter(prior -> prior.period().equals(period));
            InterestAccrual accrual = accrue(terms, period, predecessor, date, base);
            accruals.add(accrual);
            interest = interest.plus(accrual.posted());
            previous = Optional.of(accrual);
            date = date.plusDays(1);
        }
        return new Projection(List.copyOf(accruals), interest);
    }

    /**
     * A payoff's projected accrual: each projected date's figures, oldest first, and the interest they post in all.
     * {@code toString} names the count only.
     */
    public record Projection(List<InterestAccrual> accruals, Money interest) {

        public Projection {
            accruals = List.copyOf(Objects.requireNonNull(accruals, "accruals"));
            Objects.requireNonNull(interest, "interest");
        }

        @Override
        public String toString() {
            return "Projection[" + accruals.size() + " dates]";
        }
    }

    // ------------------------------------------------------------------ the judgements

    /** The conventions this version implements, and nothing else. */
    private static void judge(AccrualTerms terms) {
        if (terms.accrualEngine() != VERSION) {
            throw new AccrualRefused(AccrualRefused.Reason.UNIMPLEMENTED_CONVENTION,
                    "the terms pin another accrual engine version");
        }
        // Exhaustive by construction: a new day count is a compile error here until a version decides it.
        boolean implemented = switch (terms.dayCount()) {
            case ACT_365F -> true;
        };
        if (!implemented || terms.interestRounding() != IMPLEMENTED_INTEREST_ROUNDING) {
            throw new AccrualRefused(AccrualRefused.Reason.UNIMPLEMENTED_CONVENTION,
                    "the terms name a day count or interest rounding this engine version does not implement");
        }
        BigDecimal rate = terms.nominalAnnualRate();
        if (rate.signum() < 0 || rate.compareTo(LoanTerms.MAXIMUM_RATE) > 0
                || rate.stripTrailingZeros().scale() > LoanTerms.RATE_PLACES) {
            throw new AccrualRefused(AccrualRefused.Reason.RATE_OUT_OF_BOUNDS,
                    "the rate is 0 to 1 with at most " + LoanTerms.RATE_PLACES + " places");
        }
    }

    private static void judgeBase(Money base, CurrencyCode currency) {
        if (!base.currency().equals(currency) || base.scale() != currency.minorUnits()) {
            throw new AccrualRefused(AccrualRefused.Reason.CURRENCY_MISMATCH,
                    "the base is in the terms' currency, at its scale");
        }
        if (base.isNegative()) {
            throw new AccrualRefused(AccrualRefused.Reason.NEGATIVE_BASE, "the base is not negative");
        }
    }

    private static InterestAccrual judgePredecessor(
            InterestAccrual previous, AccrualPeriod period, LocalDate date, CurrencyCode currency) {
        if (previous.engine() != VERSION || !previous.period().equals(period) || !previous.date().isBefore(date)
                || !previous.base().currency().equals(currency)) {
            throw new AccrualRefused(AccrualRefused.Reason.OUT_OF_ORDER,
                    "the predecessor is an earlier accrual of the same period by this engine version");
        }
        return previous;
    }

    private static AccrualPeriod periodOf(List<AccrualPeriod> periods, LocalDate date) {
        for (AccrualPeriod period : periods) {
            if (period.contains(date)) {
                return period;
            }
        }
        throw new AccrualRefused(AccrualRefused.Reason.DATE_OUTSIDE_PERIOD, "no period holds a projected date");
    }
}
