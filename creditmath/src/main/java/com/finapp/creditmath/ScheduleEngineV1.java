package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code SCHEDULE_ENGINE_V1} - the amortisation schedule of an instalment loan: fixed rate, monthly, level-payment
 * annuity with actual-day interest (`P11-TSK-006`; ADR-0093, {@code PHASE_11_PLAN.md} section 12.2; {@code INV-LND-03},
 * {@code INV-LND-09}, {@code INV-MON-01}, {@code INV-MON-03}).
 *
 * <p><strong>A pure function.</strong> No clock, no database, no locale, no hash ordering, no floating point and no
 * state: the same terms, principal, term and dates give the same schedule, byte for byte, on every instance and every
 * JVM, years later - which is the whole of its multi-instance answer. A semantic change is version 2 beside this one,
 * which stays for replay ({@link EngineVersion}).
 *
 * <p><strong>Due dates</strong> (step 1, A6). {@code D1} is the first date whose day of month is the intended day
 * {@code k} - clamped to the month's last day - at least {@code min_first_period_days} after the accrual start
 * {@code d0}; {@code Dj} is {@code j - 1} months after {@code D1}, each computed from {@code k} and clamped (31 January,
 * 28 or 29 February, 31 March), calendar days only.
 *
 * <p><strong>The level instalment</strong> (step 2, as refined by this task - ADR-0093's Follow-up). {@code A} is the
 * <em>actual-day</em> annuity: with each period's growth factor {@code 1 + r x days_j / 365} (the interest the period
 * really charges), {@code A* = P / sum_j v_j}, {@code v_j} the product of the first {@code j} factors' reciprocals - the
 * plan's {@code P x i / (1 - (1+i)^-n)} is exactly its equal-period special case ({@code days_j = 365/12}), and
 * {@code r = 0} gives {@code P / n}. Computed in exact decimal - every factor multiplied as {@code 365 + r x days_j}, no
 * scale anywhere - with ONE division, rounded once {@code UP} straight to the minor unit. Only if the per-period
 * {@code HALF_EVEN} interest roundings would still leave the final instalment above {@code A} is {@code A} raised, by
 * the least number of minor units that brings it back to at most {@code A} - so the final instalment never exceeds a
 * regular one. (The plan's formula with {@code i = r / 12} cannot keep that promise once interest is charged on actual
 * days: a 40-day first period leaves the final instalment above {@code A} - in about half of all generated loans.)
 *
 * <p><strong>The periods</strong> (step 3). {@code interest_j = round_HALF_EVEN(B(j-1) x r x days_j / 365)} through
 * {@link Act365Fixed} - the exact product, one division, one rounding, the accrual engine's own arithmetic;
 * {@code principal_j = A - interest_j}; the last principal portion is the remaining balance {@code B(n-1)}, so the final
 * instalment absorbs every rounding residue. Conservation is asserted by {@link Schedule}'s construction.
 *
 * <p><strong>Never negatively amortising</strong> (step 4). A schedule in which any principal portion would not be
 * positive is {@link ScheduleRefusal#NOT_AMORTISING} - a typed answer. Terms naming anything version 1 does not compute
 * are {@link ScheduleRefusal#CONVENTION_NOT_IMPLEMENTED}; a malformed request {@link ScheduleRefusal#INVALID_REQUEST}.
 * Nothing in here throws for a business reason.
 *
 * <p><strong>The terms door's judgement</strong> ({@link #judgeAmortisation}) answers whether ANY loan within the
 * product's bounds could fail to amortise, from the worst calendar each month of the leap cycle admits - see there.
 */
public final class ScheduleEngineV1 {

    /** The version every schedule this engine produces is named for. */
    public static final EngineVersion VERSION = EngineVersion.SCHEDULE_ENGINE_V1;

    /** The longest term the engine computes - its range, far beyond any product's (credit's longest: 60). */
    public static final int MAXIMUM_TERM_MONTHS = 600;

    /** The largest principal the engine computes, in minor units - its range, far beyond any product's. */
    public static final long MAXIMUM_PRINCIPAL_MINOR_UNITS = 1_000_000_000_000L;

    /**
     * The longest first period a recalculation computes - a year. A next due date further away is no "next" due date,
     * and the bound keeps every balance of the longest term at the largest principal far inside a minor-unit count.
     */
    public static final int MAXIMUM_RECALCULATION_FIRST_PERIOD_DAYS = 366;

    /** The longest month - the latest intended day, and the longest period between two monthly due dates. */
    static final int LONGEST_MONTH = 31;

    /** The months of the Gregorian leap cycle every later-period calendar of a monthly schedule repeats within. */
    static final int LEAP_CYCLE_MONTHS = 48;

    /** The first month of the leap cycle the door's judgement walks - January of a leap year. */
    static final YearMonth LEAP_CYCLE_START = YearMonth.of(2024, 1);

    private static final BigDecimal DAYS_PER_YEAR = Act365Fixed.DAYS_PER_YEAR;

    private ScheduleEngineV1() {}

    // ------------------------------------------------------------------ the entry points

    /**
     * The schedule of a new loan, generated at its accrual start (path W at disbursement, path X at the payout's
     * terminal outcome) or as the offer's illustrative schedule with {@code accrualStart} today (A5).
     *
     * @param terms the agreement's terms - an instalment product's, pinning this engine version
     * @param principal the principal {@code P}, in the terms' currency at its scale
     * @param termMonths the term {@code n}
     * @param repaymentDay the intended day {@code k}, 1 to 31 (the terms' allowed set is the caller's to enforce)
     * @param accrualStart {@code d0}, the day interest starts
     */
    public static ScheduleOutcome project(
            LoanTerms terms, Money principal, int termMonths, int repaymentDay, LocalDate accrualStart) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(accrualStart, "accrualStart");
        Optional<ScheduleOutcome.Refused> refused = conventions(terms)
                .or(() -> request(terms, principal, termMonths, repaymentDay, accrualStart));
        if (refused.isPresent()) {
            return refused.get();
        }
        LocalDate earliest = accrualStart.plusDays(terms.instalment().orElseThrow().minFirstPeriodDays());
        YearMonth first = YearMonth.from(earliest);
        if (onDay(first, repaymentDay).isBefore(earliest)) {
            first = first.plusMonths(1);
        }
        return build(terms, principal, repaymentDay, accrualStart, dueDates(first, repaymentDay, termMonths));
    }

    /**
     * A new schedule version after an approved contractual change (step 7 - an amendment, `P11-TSK-027`; a partial
     * prepayment, `P11-TSK-028`): {@code principal} the principal outstanding at {@code effectiveFrom} (derived from
     * postings by the caller), the remaining or new term, and {@code nextDueDate} the first due date - its period's
     * interest running from {@code effectiveFrom}. No shortest first period applies: the next due date is given.
     *
     * @param intendedDay the repayment day the due dates are computed from - {@code nextDueDate} must fall on it
     */
    public static ScheduleOutcome recalculate(
            LoanTerms terms,
            Money principal,
            int remainingMonths,
            int intendedDay,
            LocalDate effectiveFrom,
            LocalDate nextDueDate) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(nextDueDate, "nextDueDate");
        Optional<ScheduleOutcome.Refused> refused = conventions(terms)
                .or(() -> request(terms, principal, remainingMonths, intendedDay, effectiveFrom))
                .or(() -> inRange(nextDueDate));
        if (refused.isPresent()) {
            return refused.get();
        }
        YearMonth first = YearMonth.from(nextDueDate);
        if (!nextDueDate.isAfter(effectiveFrom) || !nextDueDate.equals(onDay(first, intendedDay))
                || Act365Fixed.days(effectiveFrom, nextDueDate) > MAXIMUM_RECALCULATION_FIRST_PERIOD_DAYS) {
            return invalid("a recalculation's next due date falls after its effective date, on its intended day, within"
                    + " a year");
        }
        return build(terms, principal, intendedDay, effectiveFrom, dueDates(first, intendedDay, remainingMonths));
    }

    /**
     * A new schedule version after a partial prepayment (step 7 - `P11-TSK-028`; ADR-0099 point 6, "shorter term, same
     * instalment"): {@code principal} the principal outstanding after the prepayment, {@code instalment} the superseded
     * schedule's level instalment {@code A}, and the term the LEAST number of monthly instalments, at most
     * {@code maximumMonths} (the superseded schedule's remaining ones), over which {@code A} amortises it - every period
     * but the last pays {@code A} and the last the remaining balance and its interest, never more than {@code A}. The
     * periods run from {@code effectiveFrom} with {@code nextDueDate} the first due date, exactly as
     * {@link #recalculate}'s.
     *
     * <p>Only when {@code A} cannot amortise the principal within {@code maximumMonths} - a history of late payment left
     * more principal outstanding than the projection, and the prepayment was smaller than that excess - is the term kept
     * and the instalment this engine's own least ({@link #recalculate} over {@code maximumMonths}), necessarily above
     * {@code A}: the term is never lengthened. Pure and deterministic like every entry point, so replay re-derives the
     * version from the same inputs. No figure of {@link #project} or {@link #recalculate} changes.
     *
     * @param instalment the level instalment to keep - positive, in the terms' currency at its scale
     * @param maximumMonths the most instalments the new schedule may have
     */
    public static ScheduleOutcome recalculateAtInstalment(
            LoanTerms terms,
            Money principal,
            Money instalment,
            int maximumMonths,
            int intendedDay,
            LocalDate effectiveFrom,
            LocalDate nextDueDate) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(instalment, "instalment");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(nextDueDate, "nextDueDate");
        Optional<ScheduleOutcome.Refused> refused = conventions(terms)
                .or(() -> request(terms, principal, maximumMonths, intendedDay, effectiveFrom))
                .or(() -> inRange(nextDueDate));
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!instalment.isPositive() || !instalment.currency().equals(principal.currency())
                || instalment.scale() != principal.scale()) {
            return invalid("the instalment kept is positive, in the principal's currency at its scale");
        }
        YearMonth first = YearMonth.from(nextDueDate);
        if (!nextDueDate.isAfter(effectiveFrom) || !nextDueDate.equals(onDay(first, intendedDay))
                || Act365Fixed.days(effectiveFrom, nextDueDate) > MAXIMUM_RECALCULATION_FIRST_PERIOD_DAYS) {
            return invalid("a recalculation's next due date falls after its effective date, on its intended day, within"
                    + " a year");
        }
        List<LocalDate> dueDates = dueDates(first, intendedDay, maximumMonths);
        BigDecimal rate = terms.nominalAnnualRate();
        // Forward under A: the balance path is the same whatever the term, so the least term is the first period in
        // which what is left and its interest fit in A.
        Money balance = principal;
        LocalDate previous = effectiveFrom;
        for (int index = 0; index < maximumMonths; index++) {
            Money interest = interest(balance, rate, Act365Fixed.days(previous, dueDates.get(index)));
            if (balance.plus(interest).compareTo(instalment) <= 0) {
                List<LocalDate> term = dueDates.subList(0, index + 1);
                return build(terms, principal, intendedDay, effectiveFrom, term, days(effectiveFrom, term), instalment);
            }
            Money portion = instalment.minus(interest);
            if (!portion.isPositive()) {
                break; // A never reduces this balance: no term at A exists.
            }
            balance = balance.minus(portion);
            previous = dueDates.get(index);
        }
        return build(terms, principal, intendedDay, effectiveFrom, dueDates);
    }

    /**
     * Whether ANY loan within {@code bounds} under {@code terms} could fail to amortise - the terms proposal door's
     * judgement ({@code 422 lending.TermsNotAmortising}, ADR-0092 section 3, ADR-0093 section 5). Empty when every loan
     * amortises.
     *
     * <p><strong>Exact, not sampled.</strong> A principal portion fails only where a period's interest reaches the
     * level instalment. With {@code A* = P / sum v_j}: {@code A*} rises with every period's days and falls as the term
     * lengthens, and the first period's interest over {@code A*} rises with the first period's days (the first factor
     * cancels from the rest). So, for each calendar of later periods a due date can open - the months of one leap cycle,
     * and for an intended day of 28 or less the calendar is the months' own lengths - the worst loan is the LONGEST term
     * at its longest first period: {@code min_first_period_days} plus the days to the next due date, less one. Two
     * bounds then hold every principal portion positive, each against {@code UP(A*)}, which every schedule's instalment
     * is at least:
     * <ol>
     *   <li>the first period - its interest over the longest first period below {@code UP(A*)} of that calendar;</li>
     *   <li>every later period - interest on at most the whole principal over the calendar's longest month below
     *       {@code UP(A*)} of the same calendar with the SHORTEST first period.</li>
     * </ol>
     *
     * <p><strong>With a margin, for every principal between the bounds and every start date</strong> (the Phase 11 gate's
     * R5, A3-M3). The judgement once compared the ROUNDED figures at the bounds' two principals only - and rounding varies
     * between them: at 23.10 % the door admitted, a real EUR 500.19 loan over 60 months on day 1 from 2028-12-18 repays no
     * principal in its first instalment ({@code ScheduleEngineV1Test#theOldDoorsGap}). A refusal at a disbursement or at a
     * payout's conclusion has no business outcome - the start date is that day's, the principal the decision's - so the
     * door now admits only terms under which NO such loan can refuse. Each bound is judged in exact arithmetic at the
     * smallest principal, where it is tightest, since every exact quantity is the principal's own multiple:
     * <ol>
     *   <li>the first period: {@code A* - P x r x d1 / 365 > 1/2} minor unit - so {@code UP(A*) >= A*} exceeds the
     *       period's {@code HALF_EVEN} interest, at most half a unit above its exact value;</li>
     *   <li>every later period: the same margin on the whole principal over the longest later month, against {@code A*}
     *       of the shortest first period;</li>
     *   <li>no balance exhausted before the last instalment: {@code A* > 5/2 x T(n-1) x g(n)}, where {@code g(j) = 1 + r x
     *       d(j) / 365} and {@code T(j) = g(j) x T(j-1) + 1} ({@code T(1) = 1}) - the instalment is {@code UP(A*)} or one
     *       unit more (the least level instalment never needs more: at {@code UP(A*) + 1} the final instalment is at most
     *       {@code A*}), so each balance is less than {@code 5/2 x T(j)} below its exact value, which is at least
     *       {@code A* / g(n)}. {@code A*} grows with the first period's factor and the right side does not, so the
     *       shortest first period is the tightest; the longest term the worst.</li>
     * </ol>
     * Linear in the principal, each holds at every principal from the smallest up once it holds there - so no loan the
     * product can decide, disbursed or paid out on any day, is refused by the engine, and the offer's own judgement
     * (`P11-TSK-011`) is a second line only.
     */
    public static Optional<ScheduleOutcome.Refused> judgeAmortisation(LoanTerms terms, LoanProductBounds.Bounds bounds) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(bounds, "bounds");
        Optional<ScheduleOutcome.Refused> convention = conventions(terms);
        if (convention.isPresent()) {
            return convention;
        }
        CurrencyCode currency = terms.currency();
        if (!bounds.minimumPrincipal().currency().equals(currency)) {
            throw new IllegalStateException("a product's bounds are in its terms' currency");
        }
        int n = Math.min(bounds.maximumTermMonths(), MAXIMUM_TERM_MONTHS);
        if (n < 2) {
            return Optional.empty();
        }
        int shortest = terms.instalment().orElseThrow().minFirstPeriodDays();
        BigDecimal rate = terms.nominalAnnualRate();
        for (long[] calendar : laterCalendars(terms.allowedDays(), shortest, n)) {
            long longestFirst = calendar[0];
            long longestLater = 0;
            for (int index = 1; index < calendar.length; index++) {
                longestLater = Math.max(longestLater, calendar[index]);
            }
            long[] shortestFirst = calendar.clone();
            shortestFirst[0] = shortest;
            BigDecimal principal = BigDecimal.valueOf(bounds.minimumPrincipal().minorUnits());
            BigDecimal[] longest = growthAndDiscount(rate, calendar);
            BigDecimal[] shortestOne = growthAndDiscount(rate, shortestFirst);
            if (!aboveInterestByHalf(principal, rate, longest, longestFirst)) {
                return Optional.of(new ScheduleOutcome.Refused(ScheduleRefusal.NOT_AMORTISING, "a " + n
                        + "-month loan whose first period runs " + longestFirst
                        + " days could repay no principal in its first instalment"));
            }
            if (!aboveInterestByHalf(principal, rate, shortestOne, longestLater)) {
                return Optional.of(new ScheduleOutcome.Refused(ScheduleRefusal.NOT_AMORTISING, "a " + n
                        + "-month loan could repay no principal in an instalment after its first"));
            }
            if (!neverExhausted(principal, rate, shortestOne, shortestFirst)) {
                return Optional.of(new ScheduleOutcome.Refused(ScheduleRefusal.NOT_AMORTISING, "a " + n
                        + "-month loan at the smallest principal could repay its whole balance before its last"
                        + " instalment"));
            }
        }
        return Optional.empty();
    }

    /** {@code F(n)} and {@code S(n)} of {@link #annuityUp}, exact - {@code A* = P x F / S} - for the door's bounds. */
    private static BigDecimal[] growthAndDiscount(BigDecimal rate, long[] days) {
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal discounted = BigDecimal.ZERO;
        BigDecimal yearPower = BigDecimal.ONE;
        for (long periodDays : days) {
            BigDecimal factor = factor(rate, periodDays);
            yearPower = yearPower.multiply(DAYS_PER_YEAR);
            growth = growth.multiply(factor);
            discounted = discounted.multiply(factor).add(yearPower);
        }
        return new BigDecimal[] {growth, discounted};
    }

    /**
     * {@code P x F / S - P x r x days / 365 > 1/2}, {@code P} in minor units - cross-multiplied by {@code 2 x 365 x S},
     * exact: no division, no rounding.
     */
    private static boolean aboveInterestByHalf(BigDecimal principal, BigDecimal rate, BigDecimal[] fs, long days) {
        BigDecimal two = BigDecimal.valueOf(2);
        BigDecimal left = two.multiply(DAYS_PER_YEAR).multiply(principal).multiply(fs[0])
                .subtract(two.multiply(principal).multiply(rate).multiply(BigDecimal.valueOf(days)).multiply(fs[1]));
        return left.compareTo(DAYS_PER_YEAR.multiply(fs[1])) > 0;
    }

    /**
     * {@code P x F / S > 5/2 x T(n-1) x g(n)}, exact: with {@code f(j) = 365 + r x d(j)}, {@code g(j) = f(j) / 365} and
     * {@code T(j) = W(j) / 365^(j-1)} - {@code W(1) = 1}, {@code W(j) = f(j) x W(j-1) + 365^(j-1)} - it is
     * {@code 2 x P x F x 365^(n-1) > 5 x W(n-1) x f(n) x S}.
     */
    private static boolean neverExhausted(BigDecimal principal, BigDecimal rate, BigDecimal[] fs, long[] days) {
        int n = days.length;
        BigDecimal w = BigDecimal.ONE;
        BigDecimal yearPower = BigDecimal.ONE;
        for (int j = 2; j <= n - 1; j++) {
            yearPower = yearPower.multiply(DAYS_PER_YEAR);
            w = factor(rate, days[j - 1]).multiply(w).add(yearPower);
        }
        BigDecimal left = BigDecimal.valueOf(2).multiply(principal).multiply(fs[0]).multiply(DAYS_PER_YEAR.pow(n - 1));
        BigDecimal right = BigDecimal.valueOf(5).multiply(w).multiply(factor(rate, days[n - 1])).multiply(fs[1]);
        return left.compareTo(right) > 0;
    }

    private static BigDecimal factor(BigDecimal rate, long days) {
        return DAYS_PER_YEAR.add(rate.multiply(BigDecimal.valueOf(days)));
    }

    // ------------------------------------------------------------------ the computation

    private static ScheduleOutcome build(
            LoanTerms terms, Money principal, int intendedDay, LocalDate start, List<LocalDate> dueDates) {
        BigDecimal rate = terms.nominalAnnualRate();
        long[] days = days(start, dueDates);
        Money instalment = leastLevelInstalment(principal, rate, days, annuityUp(principal, rate, days));
        return build(terms, principal, intendedDay, start, dueDates, days, instalment);
    }

    /** Each period's actual days, the first from {@code start}. */
    private static long[] days(LocalDate start, List<LocalDate> dueDates) {
        long[] days = new long[dueDates.size()];
        LocalDate previous = start;
        for (int index = 0; index < days.length; index++) {
            days[index] = Act365Fixed.days(previous, dueDates.get(index));
            previous = dueDates.get(index);
        }
        return days;
    }

    /** The schedule under the level {@code instalment} - refused if a balance runs out early or a portion is not positive. */
    private static ScheduleOutcome build(LoanTerms terms, Money principal, int intendedDay, LocalDate start,
            List<LocalDate> dueDates, long[] days, Money instalment) {
        CurrencyCode currency = terms.currency();
        BigDecimal rate = terms.nominalAnnualRate();
        int n = dueDates.size();
        Path path = simulate(principal, rate, days, instalment);
        if (path.exhaustedBefore() > 0) {
            return new ScheduleOutcome.Refused(ScheduleRefusal.NOT_AMORTISING, "a " + n
                    + "-month schedule would repay its whole balance before instalment " + path.exhaustedBefore());
        }
        List<SchedulePeriod> periods = new ArrayList<>(n);
        Money opening = principal;
        LocalDate previous = start;
        for (int index = 0; index < n; index++) {
            Money interest = path.interest()[index];
            Money principalPortion = index == n - 1 ? opening : instalment.minus(interest);
            if (!principalPortion.isPositive()) {
                return new ScheduleOutcome.Refused(ScheduleRefusal.NOT_AMORTISING, "instalment " + (index + 1)
                        + " of a " + n + "-month schedule would repay no principal");
            }
            Money closing = opening.minus(principalPortion);
            periods.add(new SchedulePeriod(index + 1, previous, dueDates.get(index), days[index], opening, interest,
                    principalPortion, interest.plus(principalPortion), closing));
            opening = closing;
            previous = dueDates.get(index);
        }
        return new ScheduleOutcome.Projected(
                new Schedule(VERSION, currency, principal, rate, start, intendedDay, instalment, periods));
    }

    /**
     * {@code A* = P / sum_j v_j}, rounded once {@code UP} to the minor unit. Exact throughout: with
     * {@code f_j = 365 + r x days_j}, {@code sum_j v_j = S_n / F_n} where {@code F_j = F_(j-1) x f_j} and
     * {@code S_j = S_(j-1) x f_j + 365^j} - so {@code A* = P x F_n / S_n}, products of exact decimals and ONE division.
     */
    static Money annuityUp(Money principal, BigDecimal rate, long[] days) {
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal discounted = BigDecimal.ZERO;
        BigDecimal yearPower = BigDecimal.ONE;
        for (long periodDays : days) {
            BigDecimal factor = DAYS_PER_YEAR.add(rate.multiply(BigDecimal.valueOf(periodDays)));
            yearPower = yearPower.multiply(DAYS_PER_YEAR);
            growth = growth.multiply(factor);
            discounted = discounted.multiply(factor).add(yearPower);
        }
        CurrencyCode currency = principal.currency();
        // The one division, rounded once, UP, straight to the minor unit (INV-MON-03): never the double rounding of a
        // scale-20 quotient rounded again.
        return Money.of(principal.toBigDecimal().multiply(growth).divide(discounted, currency.minorUnits(),
                RoundingMode.UP), currency);
    }

    /**
     * The least instalment, not below {@code floor}, whose schedule's final instalment is at most it (or whose balance
     * is exhausted early - refused by the caller). Both conditions only become true as the instalment grows - a larger
     * instalment leaves every later balance no larger, and the period interest rounds monotonically - so a gallop and a
     * bisection find the least, in a few simulations; almost always {@code floor} itself.
     */
    private static Money leastLevelInstalment(Money principal, BigDecimal rate, long[] days, Money floor) {
        long low = floor.minorUnits();
        if (feasible(principal, rate, days, low)) {
            return floor;
        }
        long step = 1;
        long high = low + step;
        while (!feasible(principal, rate, days, high)) {
            low = high;
            step = step * 2;
            high = low + step;
        }
        while (high - low > 1) {
            long middle = low + (high - low) / 2;
            if (feasible(principal, rate, days, middle)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return Money.ofMinorUnits(high, principal.currency());
    }

    private static boolean feasible(Money principal, BigDecimal rate, long[] days, long instalmentMinorUnits) {
        Money instalment = Money.ofMinorUnits(instalmentMinorUnits, principal.currency());
        Path path = simulate(principal, rate, days, instalment);
        return path.exhaustedBefore() > 0 || path.finalInstalment().compareTo(instalment) <= 0;
    }

    /**
     * The balances under {@code instalment}: each period's interest on the balance it opens with, the final instalment
     * {@code B(n-1) + interest_n}, or the instalment before which the balance ran out ({@code 0} if it never did).
     */
    private static Path simulate(Money principal, BigDecimal rate, long[] days, Money instalment) {
        Money[] interest = new Money[days.length];
        Money balance = principal;
        for (int index = 0; index < days.length; index++) {
            if (!balance.isPositive()) {
                return new Path(interest, balance, index + 1);
            }
            interest[index] = interest(balance, rate, days[index]);
            if (index < days.length - 1) {
                balance = balance.minus(instalment.minus(interest[index]));
            }
        }
        return new Path(interest, balance.plus(interest[days.length - 1]), 0);
    }

    private record Path(Money[] interest, Money finalInstalment, int exhaustedBefore) {}

    /** {@code round_HALF_EVEN(base x r x days / 365)} - the accrual engine's own arithmetic ({@link Act365Fixed}). */
    private static Money interest(Money base, BigDecimal rate, long days) {
        return Act365Fixed.roundedOnce(Act365Fixed.exactInterest(base, rate, days), base.currency());
    }

    // ------------------------------------------------------------------ the calendar

    private static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    private static List<LocalDate> dueDates(YearMonth first, int day, int count) {
        List<LocalDate> dates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            dates.add(onDay(first.plusMonths(index), day));
        }
        return dates;
    }

    /**
     * Every distinct calendar a monthly schedule of {@code n} periods can have, for the door: for each allowed day and
     * each first due month of one leap cycle, the longest first period that month admits followed by the later
     * periods' actual days. In walking order, de-duplicated - for days of 28 or less every allowed day gives the same.
     */
    private static List<long[]> laterCalendars(List<Integer> allowedDays, int shortestFirst, int n) {
        Set<List<Long>> seen = new LinkedHashSet<>();
        List<long[]> calendars = new ArrayList<>();
        for (int day : allowedDays) {
            for (int offset = 0; offset < LEAP_CYCLE_MONTHS; offset++) {
                YearMonth month = LEAP_CYCLE_START.plusMonths(offset);
                LocalDate firstDue = onDay(month, day);
                long[] calendar = new long[n];
                calendar[0] = shortestFirst + Act365Fixed.days(onDay(month.minusMonths(1), day), firstDue) - 1;
                LocalDate previous = firstDue;
                for (int index = 1; index < n; index++) {
                    LocalDate due = onDay(month.plusMonths(index), day);
                    calendar[index] = Act365Fixed.days(previous, due);
                    previous = due;
                }
                List<Long> key = new ArrayList<>(n);
                for (long value : calendar) {
                    key.add(value);
                }
                if (seen.add(key)) {
                    calendars.add(calendar);
                }
            }
        }
        return calendars;
    }

    // ------------------------------------------------------------------ the refusals

    /** What version 1 computes: an instalment product's terms pinning it, fixed, monthly, ACT/365F, UP and HALF_EVEN. */
    static Optional<ScheduleOutcome.Refused> conventions(LoanTerms terms) {
        if (terms.product().kind() != LoanKind.INSTALMENT || terms.instalment().isEmpty()) {
            return convention("a schedule is computed for an instalment product's terms");
        }
        if (terms.instalment().get().scheduleEngine() != VERSION) {
            return convention("the terms pin another schedule engine version");
        }
        if (terms.rateType() != RateType.FIXED
                || terms.product().frequency() != RepaymentFrequency.MONTHLY
                || terms.dayCount() != DayCountConvention.ACT_365F
                || terms.instalmentRounding() != RoundingMode.UP
                || terms.interestRounding() != RoundingMode.HALF_EVEN) {
            return convention("the terms name a rate type, frequency, day count or rounding mode version 1 does not"
                    + " compute");
        }
        return Optional.empty();
    }

    private static Optional<ScheduleOutcome.Refused> request(
            LoanTerms terms, Money principal, int termMonths, int day, LocalDate start) {
        CurrencyCode currency = terms.currency();
        if (!principal.currency().equals(currency) || principal.scale() != currency.minorUnits()
                || !principal.isPositive() || principal.minorUnits() > MAXIMUM_PRINCIPAL_MINOR_UNITS) {
            return Optional.of(invalid("the principal is positive, in the terms' currency at its scale, within the"
                    + " engine's range"));
        }
        if (termMonths < 1 || termMonths > MAXIMUM_TERM_MONTHS) {
            return Optional.of(invalid("the term is 1 to " + MAXIMUM_TERM_MONTHS + " months"));
        }
        if (day < 1 || day > LONGEST_MONTH) {
            return Optional.of(invalid("the intended day is a day of a month, 1 to " + LONGEST_MONTH));
        }
        return inRange(start);
    }

    /** A date the engine's calendar computes - years 1 to 9900, so no due date of the longest term leaves year 9999. */
    private static Optional<ScheduleOutcome.Refused> inRange(LocalDate date) {
        if (date.getYear() < 1 || date.getYear() > 9_900) {
            return Optional.of(invalid("the dates fall within the years 1 to 9900"));
        }
        return Optional.empty();
    }

    private static Optional<ScheduleOutcome.Refused> convention(String detail) {
        return Optional.of(new ScheduleOutcome.Refused(ScheduleRefusal.CONVENTION_NOT_IMPLEMENTED, detail));
    }

    private static ScheduleOutcome.Refused invalid(String detail) {
        return new ScheduleOutcome.Refused(ScheduleRefusal.INVALID_REQUEST, detail);
    }
}
