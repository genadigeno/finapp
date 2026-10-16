package com.finapp.lending;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * {@link EngineVersion#STATEMENT_ENGINE_V1}: what a credit line's cycle bills and its minimum payment (`P11-TSK-009`;
 * ADR-0100 point 6, A23, A24, {@code PHASE_11_PLAN.md} section 12.6; {@code INV-LND-09}, {@code INV-MON-01}, and the
 * line's analogue of {@code INV-LND-03} - the principal billed never exceeds the drawn principal not yet due).
 *
 * <p><strong>The cycle</strong> ({@link #cycle}, A23). Every cycle ends on the agreement's statement day {@code s}
 * (1 to 28, so every month has it and nothing is clamped - 28 February is a statement date like any other); its end is
 * the statement date, the next cycle's first day, and the {@link AccrualPeriod} its accruals are rounded within. The
 * first cycle runs from the day the line opened to the first statement date after it; each later one, a calendar month
 * from one statement date to the next.
 *
 * <p><strong>The statement</strong> ({@link #state}, A24). With {@code I} the cycle's posted accruals summed, {@code F}
 * the fees the statement carries, {@code N} the drawn principal not yet due and {@code D} the whole drawn principal
 * ({@code N} plus principal already due from earlier statements):
 * <pre>
 *   principal billed = min(N, max(round_UP(ratio x D), floor - I - F, 0))
 *   minimum payment  = I + F + principal billed
 *   due date         = statement date + payment-due days
 * </pre>
 * So the whole minimum payment is at least the floor, or everything the statement could bill ({@code I + F + N}) when
 * that is less; it never exceeds it; the floor is not a principal floor (ADR-0100's Follow-up). {@code ratio x D} is
 * exact (basis points move the decimal point, no division) and rounded once {@code UP} to the minor unit - the terms'
 * instalment rounding ({@link LoanTerms}); everything else is integer minor units, added, subtracted and compared.
 *
 * <p><strong>What the caller owns</strong> (`P11-TSK-022`): {@code N} and {@code D}, derived from journal lines under
 * the line's lock ({@code LOAN_PRINCIPAL} and {@code LOAN_PRINCIPAL_DUE}); the cycle's accruals, every one existing
 * before the statement is computed (the sweep's completeness, {@code INV-LND-02}); the fees assessed in the cycle and
 * not yet carried (zero until `P11-TSK-025`); posting, applying any credit balance, and the balances - opening and
 * closing figures are the ledger's, never this engine's.
 *
 * <p><strong>What it refuses</strong> ({@link StatementRefused}), computing nothing: terms naming a convention or
 * engine version this version does not implement, or a value outside its bounds - never falling back to another
 * version ({@code INV-LND-09}); a cycle that is not one of the statement day's; an amount below zero, in another
 * currency or scale, or beyond the engine's range; and accruals that are not one chain of the cycle's own by
 * {@code ACCRUAL_ENGINE_V1} - outside the cycle, out of order, or summing to other than their last accrual's
 * once-rounded cumulative figure ({@link AccrualEngineV1#postedThrough}), which a dropped or foreign row breaks.
 *
 * <p>A pure function of its arguments: no clock, no locale, no hash order, no state, no floating point - the same
 * statement on every instance and in every JVM ({@code StatementEngineV1PropertiesTest}'s two-JVM comparison). A
 * semantic change is {@code STATEMENT_ENGINE_V2} beside this class, never an edit to it.
 */
public final class StatementEngineV1 {

    /** The engine version this class is - named on every statement it computes. */
    public static final EngineVersion VERSION = EngineVersion.STATEMENT_ENGINE_V1;

    /** The ratio's rounding this version implements: once, {@code UP} (away from zero) to the minor unit. */
    static final RoundingPolicy IMPLEMENTED_MINIMUM_ROUNDING = RoundingPolicy.AWAY_FROM_ZERO;

    /** The largest amount the engine computes, in minor units - its range, far beyond any line's. */
    public static final long MAXIMUM_MINOR_UNITS = 1_000_000_000_000L;

    /** The years the engine's calendar computes - so no cycle of a line leaves year 9999. */
    static final int FIRST_YEAR = 1;

    static final int LAST_YEAR = 9_900;

    /** A basis point's decimal places: {@code ratio x D} is {@code D x bps} with the point moved, never divided. */
    private static final int BASIS_POINT_PLACES = 4;

    private StatementEngineV1() {}

    // ------------------------------------------------------------------ the entry points

    /**
     * The statement cycle holding {@code date} of a line opened on {@code openedOn}: from the opening to the first
     * statement date after it, then from each statement date to the next. The accrual sweep rounds a date's interest
     * within it (`P11-TSK-016`); the statement step bills it once its end has come (`P11-TSK-022`).
     *
     * @throws StatementRefused when the terms are refused, or {@code date} is before {@code openedOn} or outside the
     *     engine's years
     */
    public static AccrualPeriod cycle(StatementTerms terms, LocalDate openedOn, LocalDate date) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(openedOn, "openedOn");
        Objects.requireNonNull(date, "date");
        judge(terms);
        inRange(openedOn);
        inRange(date);
        if (date.isBefore(openedOn)) {
            throw new StatementRefused(StatementRefused.Reason.CYCLE_INVALID, "a line's cycles start when it opens");
        }
        int day = terms.statementDay();
        LocalDate firstStatement = YearMonth.from(openedOn).atDay(day);
        if (!firstStatement.isAfter(openedOn)) {
            firstStatement = firstStatement.plusMonths(1);
        }
        if (date.isBefore(firstStatement)) {
            return new AccrualPeriod(openedOn, firstStatement);
        }
        LocalDate start = YearMonth.from(date).atDay(day);
        if (start.isAfter(date)) {
            start = start.minusMonths(1);
        }
        return new AccrualPeriod(start, start.plusMonths(1));
    }

    /**
     * The statement of {@code cycle}: the interest its accruals posted, the fees it carries, the minimum payment's
     * principal part and the minimum payment, due {@code paymentDueDays} after the statement date. An empty cycle -
     * nothing drawn, nothing accrued, no fee - is an empty statement: every figure zero, never a negative one.
     *
     * @param cycle a cycle of {@code terms}' statement day - ending on it, at most a month long
     * @param accruals the cycle's accruals by {@code ACCRUAL_ENGINE_V1}, oldest first, every one of them
     * @param feesCarried the fees assessed in the cycle and not yet carried by a statement
     * @param notDuePrincipal {@code N}: drawn principal not yet due ({@code LOAN_PRINCIPAL})
     * @param duePrincipal principal already due from earlier statements, unpaid ({@code LOAN_PRINCIPAL_DUE}) - with
     *     {@code N}, the drawn principal {@code D} the ratio is taken of
     * @throws StatementRefused when the terms, the cycle, an amount or the accruals are refused
     */
    public static Statement state(
            StatementTerms terms,
            AccrualPeriod cycle,
            List<InterestAccrual> accruals,
            Money feesCarried,
            Money notDuePrincipal,
            Money duePrincipal) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(accruals, "accruals");
        Objects.requireNonNull(feesCarried, "feesCarried");
        Objects.requireNonNull(notDuePrincipal, "notDuePrincipal");
        Objects.requireNonNull(duePrincipal, "duePrincipal");
        judge(terms);
        judgeCycle(cycle, terms.statementDay());
        CurrencyCode currency = terms.currency();
        judgeAmount(feesCarried, currency);
        judgeAmount(notDuePrincipal, currency);
        judgeAmount(duePrincipal, currency);

        Money interest = interestOf(List.copyOf(accruals), cycle, currency);
        Money drawn = notDuePrincipal.plus(duePrincipal);
        Money ratioPart = Money.of(drawn.toBigDecimal()
                .multiply(BigDecimal.valueOf(terms.minimumPaymentRatioBps()).movePointLeft(BASIS_POINT_PLACES)),
                currency, IMPLEMENTED_MINIMUM_ROUNDING);
        Money floorPart = terms.minimumPaymentFloor().minus(interest).minus(feesCarried);
        Money principal = least(notDuePrincipal, greatest(greatest(ratioPart, floorPart), Money.zero(currency)));
        Money minimum = interest.plus(feesCarried).plus(principal);
        return new Statement(cycle, interest, feesCarried, principal, minimum,
                cycle.endExclusive().plusDays(terms.paymentDueDays()), VERSION);
    }

    // ------------------------------------------------------------------ the interest

    /**
     * The cycle's posted accruals summed - which, for one chain of the cycle's accruals, is its last accrual's exact
     * cumulative figure rounded once ({@link AccrualEngineV1#postedThrough}); a list for which the two differ is not
     * one chain (a row dropped from its middle, or one from another chain).
     */
    private static Money interestOf(List<InterestAccrual> accruals, AccrualPeriod cycle, CurrencyCode currency) {
        Money sum = Money.zero(currency);
        LocalDate previous = null;
        for (InterestAccrual accrual : accruals) {
            Objects.requireNonNull(accrual, "accrual");
            if (!accrual.period().equals(cycle) || accrual.engine() != AccrualEngineV1.VERSION
                    || !accrual.base().currency().equals(currency) || accrual.base().scale() != currency.minorUnits()
                    || accrual.base().minorUnits() > MAXIMUM_MINOR_UNITS) {
                throw new StatementRefused(StatementRefused.Reason.ACCRUALS_INVALID,
                        "an accrual is the cycle's own, by the accrual engine version 1, in the line's currency, on a"
                                + " base within the engine's range");
            }
            if (previous != null && !accrual.date().isAfter(previous)) {
                throw new StatementRefused(StatementRefused.Reason.ACCRUALS_INVALID,
                        "the cycle's accruals are oldest first, one a date");
            }
            sum = sum.plus(accrual.posted());
            previous = accrual.date();
        }
        if (!accruals.isEmpty() && !sum.equals(AccrualEngineV1.postedThrough(accruals.get(accruals.size() - 1)))) {
            throw new StatementRefused(StatementRefused.Reason.ACCRUALS_INVALID,
                    "the accruals' postings sum to their last cumulative figure rounded once - one chain, every row");
        }
        return sum;
    }

    // ------------------------------------------------------------------ the judgements

    /** The conventions this version implements, and nothing else; the bounds its arithmetic rests on. */
    private static void judge(StatementTerms terms) {
        if (terms.statementEngine() != VERSION) {
            throw new StatementRefused(StatementRefused.Reason.UNIMPLEMENTED_CONVENTION,
                    "the terms pin another statement engine version");
        }
        if (terms.minimumPaymentRounding() != IMPLEMENTED_MINIMUM_ROUNDING.mode()) {
            throw new StatementRefused(StatementRefused.Reason.UNIMPLEMENTED_CONVENTION,
                    "the terms name a minimum-payment rounding this engine version does not implement");
        }
        if (terms.statementDay() < 1 || terms.statementDay() > LoanTerms.LAST_ALLOWED_DAY) {
            throw new StatementRefused(StatementRefused.Reason.TERMS_OUT_OF_BOUNDS,
                    "the statement day is 1 to " + LoanTerms.LAST_ALLOWED_DAY + " - a day every month has");
        }
        Money floor = terms.minimumPaymentFloor();
        if (!floor.currency().equals(terms.currency()) || floor.scale() != terms.currency().minorUnits()
                || !floor.isPositive() || floor.minorUnits() > MAXIMUM_MINOR_UNITS) {
            throw new StatementRefused(StatementRefused.Reason.TERMS_OUT_OF_BOUNDS,
                    "the floor is positive, in the line's currency at its scale, within the engine's range");
        }
        if (terms.minimumPaymentRatioBps() < 1 || terms.minimumPaymentRatioBps() > 10_000) {
            throw new StatementRefused(StatementRefused.Reason.TERMS_OUT_OF_BOUNDS,
                    "the ratio is 1 to 10000 basis points");
        }
        if (terms.paymentDueDays() < 1 || terms.paymentDueDays() >= LoanTerms.LAST_ALLOWED_DAY) {
            throw new StatementRefused(StatementRefused.Reason.TERMS_OUT_OF_BOUNDS,
                    "the payment falls due 1 to " + (LoanTerms.LAST_ALLOWED_DAY - 1) + " days after the statement");
        }
    }

    /** A cycle of the statement day: ending on it, starting at most a month before - the opening or a statement date. */
    private static void judgeCycle(AccrualPeriod cycle, int statementDay) {
        inRange(cycle.start());
        inRange(cycle.endExclusive());
        if (cycle.endExclusive().getDayOfMonth() != statementDay
                || cycle.start().isBefore(cycle.endExclusive().minusMonths(1))) {
            throw new StatementRefused(StatementRefused.Reason.CYCLE_INVALID,
                    "a cycle ends on the statement day, at most a month after it starts");
        }
    }

    private static void judgeAmount(Money amount, CurrencyCode currency) {
        if (!amount.currency().equals(currency) || amount.scale() != currency.minorUnits() || amount.isNegative()
                || amount.minorUnits() > MAXIMUM_MINOR_UNITS) {
            throw new StatementRefused(StatementRefused.Reason.AMOUNT_INVALID,
                    "an amount is not negative, in the line's currency at its scale, within the engine's range");
        }
    }

    private static void inRange(LocalDate date) {
        if (date.getYear() < FIRST_YEAR || date.getYear() > LAST_YEAR) {
            throw new StatementRefused(StatementRefused.Reason.CYCLE_INVALID,
                    "the dates fall within the years " + FIRST_YEAR + " to " + LAST_YEAR);
        }
    }

    private static Money least(Money a, Money b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Money greatest(Money a, Money b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
