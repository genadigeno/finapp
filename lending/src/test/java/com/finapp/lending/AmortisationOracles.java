package com.finapp.lending;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The battery's own arithmetic (`P11-TST-002`): every figure {@link AmortisationWorld} stores is computed here, from the
 * plan's and the ADRs' statements of the rules - never by calling {@link ScheduleEngineV1}, {@link AccrualEngineV1},
 * {@link StatementEngineV1#state} or {@link AllocationEngineV1}. The production engines then re-derive those stored
 * figures through {@link LoanReplayProof}; {@code IDENTICAL} is two independent derivations agreeing, figure by figure.
 *
 * <p>Amounts are {@code long} minor units at EUR's scale 2; every rounding is named and happens once; no floating point
 * anywhere ({@code INV-MON-01}). Two ALTERNATE arithmetics stand beside the standard ones - the accrual rounded through a
 * scale-3 intermediate, and the minimum payment without its cap - so the battery can write an account under a stored
 * engine number with arithmetic the engine does not compute, and predict exactly which accounts that changes.
 */
final class AmortisationOracles {

    /** The fixed year of {@code ACT_365F} (ADR-0094 point 2). */
    static final BigDecimal YEAR = BigDecimal.valueOf(365);

    /** The scale of a cumulative exact figure (ADR-0094 point 3). */
    static final int EXACT_SCALE = 20;

    /** EUR's minor units. */
    static final int SCALE = 2;

    private AmortisationOracles() {}

    // ------------------------------------------------------------------ the calendar (A6, A23)

    /** The intended day of {@code month}, clamped to its last day. */
    static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    /**
     * The due dates of an {@code n}-instalment schedule from {@code start}: {@code D1} the first intended day (clamped) at
     * least {@code minimumFirst} days on, each later one a calendar month after it on the intended day, clamped.
     */
    static List<LocalDate> dueDates(LocalDate start, int minimumFirst, int day, int n) {
        LocalDate earliest = start.plusDays(minimumFirst);
        YearMonth first = YearMonth.from(earliest);
        if (onDay(first, day).isBefore(earliest)) {
            first = first.plusMonths(1);
        }
        List<LocalDate> dates = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            dates.add(onDay(first.plusMonths(j), day));
        }
        return List.copyOf(dates);
    }

    /** The due date of the {@code sequence}-th period of the calendar (1-based), continued monthly past the term. */
    static LocalDate continuedDue(LocalDate firstDue, int day, int sequence) {
        return onDay(YearMonth.from(firstDue).plusMonths(sequence - 1L), day);
    }

    /** A line's first statement date: the statement day of the opening month if after the opening, else of the next. */
    static LocalDate firstStatement(LocalDate openedOn, int statementDay) {
        LocalDate first = YearMonth.from(openedOn).atDay(statementDay);
        return first.isAfter(openedOn) ? first : first.plusMonths(1);
    }

    // ------------------------------------------------------------------ the schedule (ADR-0093 as built)

    /** One period of a schedule, in minor units. */
    record Period(int sequence, LocalDate start, LocalDate due, long days, long opening, long interest, long principal,
            long amount, long closing) {}

    /**
     * A schedule: the level instalment {@code A}, {@code UP(A*)} it started from, and its periods.
     *
     * @param annuityUp {@code UP(A*)} - {@code A} is raised above it only when the final instalment would exceed it
     */
    record Plan(long principal, long instalment, long annuityUp, LocalDate start, int day, List<Period> periods) {

        Plan {
            periods = List.copyOf(periods);
        }

        long finalInstalment() {
            return periods.get(periods.size() - 1).amount();
        }

        long totalInterest() {
            long total = 0;
            for (Period period : periods) {
                total += period.interest();
            }
            return total;
        }

        boolean raised() {
            return instalment > annuityUp;
        }
    }

    /**
     * The schedule of {@code principal} over {@code dues} from {@code start}: {@code A* = P·F_n / S_n} with
     * {@code f_j = 365 + r·days_j}, {@code F_j = F_(j-1)·f_j}, {@code S_j = S_(j-1)·f_j + 365^j} - exact - then ONE integer
     * ceiling division to the minor unit; each period's interest {@code round_HALF_EVEN(B·r·days/365)} by one division;
     * {@code A} raised by single minor units while the final instalment exceeds it.
     *
     * @throws IllegalStateException for a schedule that does not amortise - the generator never asks for one
     */
    static Plan schedule(long principal, BigDecimal rate, LocalDate start, int day, List<LocalDate> dues) {
        long[] days = new long[dues.size()];
        LocalDate previous = start;
        for (int j = 0; j < days.length; j++) {
            days[j] = ChronoUnit.DAYS.between(previous, dues.get(j));
            previous = dues.get(j);
        }
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal discounted = BigDecimal.ZERO;
        BigDecimal yearPower = BigDecimal.ONE;
        for (long periodDays : days) {
            BigDecimal factor = YEAR.add(rate.multiply(BigDecimal.valueOf(periodDays)));
            yearPower = yearPower.multiply(YEAR);
            growth = growth.multiply(factor);
            discounted = discounted.multiply(factor).add(yearPower);
        }
        long annuityUp = BigDecimal.valueOf(principal).multiply(growth).divide(discounted, 0, RoundingMode.CEILING)
                .longValueExact();
        long instalment = annuityUp;
        while (true) {
            List<Period> periods = periods(principal, rate, start, dues, days, instalment);
            if (periods != null) {
                return new Plan(principal, instalment, annuityUp, start, day, periods);
            }
            instalment++;
            if (instalment - annuityUp > 1_000_000) {
                throw new IllegalStateException("no level instalment keeps the final one at most it");
            }
        }
    }

    /** The periods under {@code instalment}, or {@code null} when the final instalment would exceed it. */
    private static List<Period> periods(long principal, BigDecimal rate, LocalDate start, List<LocalDate> dues,
            long[] days, long instalment) {
        List<Period> periods = new ArrayList<>(days.length);
        long balance = principal;
        LocalDate previous = start;
        for (int j = 0; j < days.length; j++) {
            if (balance <= 0) {
                throw new IllegalStateException("the balance ran out before instalment " + (j + 1));
            }
            long interest = interest(balance, rate, days[j]);
            boolean last = j == days.length - 1;
            long portion = last ? balance : instalment - interest;
            if (portion <= 0) {
                throw new IllegalStateException("instalment " + (j + 1) + " repays no principal");
            }
            if (last && balance + interest > instalment) {
                return null;
            }
            periods.add(new Period(j + 1, previous, dues.get(j), days[j], balance, interest, portion, interest + portion,
                    balance - portion));
            balance -= portion;
            previous = dues.get(j);
        }
        return periods;
    }

    /** {@code round_HALF_EVEN(balance·r·days/365)} in minor units - the exact product, one division. */
    static long interest(long balanceMinor, BigDecimal rate, long days) {
        return BigDecimal.valueOf(balanceMinor).multiply(rate).multiply(BigDecimal.valueOf(days))
                .divide(YEAR, 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    // ------------------------------------------------------------------ the accrual (ADR-0094 points 2-3)

    /** A day's exact interest on {@code baseMinor}: {@code base·r/365}, one division at scale 20 {@code HALF_EVEN}. */
    static BigDecimal daily(long baseMinor, BigDecimal rate) {
        return BigDecimal.valueOf(baseMinor, SCALE).multiply(rate).divide(YEAR, EXACT_SCALE, RoundingMode.HALF_EVEN);
    }

    /** {@code cumulative} rounded once, {@code HALF_EVEN}, to the minor unit. */
    static long roundedOnce(BigDecimal cumulative) {
        return cumulative.setScale(SCALE, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    /** The ALTERNATE: {@code cumulative} rounded through a scale-3 intermediate first - the probe's double rounding. */
    static long roundedTwice(BigDecimal cumulative) {
        return cumulative.setScale(SCALE + 1, RoundingMode.HALF_EVEN).setScale(SCALE, RoundingMode.HALF_EVEN)
                .unscaledValue().longValueExact();
    }

    /** What a date posts: the change in the period's once-rounded cumulative figure. */
    static long posted(BigDecimal before, BigDecimal cumulative) {
        return roundedOnce(cumulative) - roundedOnce(before);
    }

    /** What a date posts under the ALTERNATE double rounding. */
    static long postedTwice(BigDecimal before, BigDecimal cumulative) {
        return roundedTwice(cumulative) - roundedTwice(before);
    }

    // ------------------------------------------------------------------ the statement (ADR-0100 point 6, A24)

    /**
     * {@code UP(ratio·D)}: basis points move the decimal point; one integer ceiling division. {@code D} is never negative
     * under the standard arithmetic; under the UNCAPPED alternate a statement can bill principal never drawn, and a payment
     * of it leaves {@code D} below zero - that record is already a predicted divergence, so nothing is refused here.
     */
    static long ratioPart(long drawnMinor, int ratioBps) {
        return Math.ceilDiv(Math.multiplyExact(drawnMinor, ratioBps), 10_000L);
    }

    /** {@code max(UP(ratio·D), floor - I - F, 0)} - the minimum payment's principal part before its cap. */
    static long uncappedPrincipal(long drawnMinor, long interest, long fees, long floor, int ratioBps) {
        return Math.max(Math.max(ratioPart(drawnMinor, ratioBps), floor - interest - fees), 0);
    }

    /** {@code min(N, max(UP(ratio·D), floor - I - F, 0))}. */
    static long cappedPrincipal(long notDue, long drawnMinor, long interest, long fees, long floor, int ratioBps) {
        return Math.min(notDue, uncappedPrincipal(drawnMinor, interest, fees, floor, ratioBps));
    }

    // ------------------------------------------------------------------ the allocation (ADR-0095, L6, A26)

    /** What one billed item still has due, per component. */
    record Due(UUID item, LocalDate dueDate, long fees, long interest, long principal) {}

    /** One allocated part: a component of an item, principal not yet due, or credit. */
    record Line(AllocationLine.Target target, UUID item, AllocationComponent component, long amount) {}

    /**
     * {@code amount} over {@code items}: oldest due date first, within each fees, interest, principal, each up to what it
     * has due; then - a line's treatment - principal not yet due; the rest held as credit.
     */
    static List<Line> allocate(long amount, List<Due> items, long notDue, boolean payDown) {
        List<Due> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(Due::dueDate));
        List<Line> lines = new ArrayList<>();
        long remaining = amount;
        for (Due item : sorted) {
            for (AllocationComponent component : List.of(AllocationComponent.FEES, AllocationComponent.INTEREST,
                    AllocationComponent.PRINCIPAL)) {
                long due = switch (component) {
                    case FEES -> item.fees();
                    case INTEREST -> item.interest();
                    case PRINCIPAL -> item.principal();
                };
                long paid = Math.min(remaining, due);
                if (paid > 0) {
                    lines.add(new Line(AllocationLine.Target.DUE_ITEM, item.item(), component, paid));
                    remaining -= paid;
                }
            }
        }
        if (payDown) {
            long paidDown = Math.min(remaining, notDue);
            if (paidDown > 0) {
                lines.add(new Line(AllocationLine.Target.NOT_DUE_PRINCIPAL, null, null, paidDown));
                remaining -= paidDown;
            }
        }
        if (remaining > 0) {
            lines.add(new Line(AllocationLine.Target.CREDIT_BALANCE, null, null, remaining));
        }
        return lines;
    }
}
