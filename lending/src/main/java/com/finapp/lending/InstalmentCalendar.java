package com.finapp.lending;

import com.finapp.creditmath.AccrualPeriod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * The accrual periods of an instalment loan (`P11-TSK-016`; ADR-0094 point 3, {@code INV-LND-09}): the schedule's
 * instalment periods {@code [D(j-1), Dj)} - within which the accrual engine rounds once - read from the latest schedule
 * version's rows, and after the final due date the same monthly calendar continued: {@code Dj} for {@code j > n} is
 * {@code j - 1} months after {@code D1} on the intended day, clamped to the month's last day (the schedule engine's own
 * due-date rule, ADR-0093 step 1). A loan whose principal is still outstanding after its final due date keeps accruing -
 * on principal due, never on interest (A4) - and those accruals are rounded per month, not across an unbounded span.
 *
 * <p>Pure: the periods are the caller's (the store reads them from {@code loan_instalment}); no clock, no state.
 *
 * @param periods the schedule's periods, oldest first, each starting where the previous fell due
 * @param intendedDay the repayment day the due dates are computed from
 */
public record InstalmentCalendar(List<AccrualPeriod> periods, int intendedDay) {

    public InstalmentCalendar {
        periods = List.copyOf(Objects.requireNonNull(periods, "periods"));
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("a schedule has at least one period");
        }
        for (int i = 1; i < periods.size(); i++) {
            if (!periods.get(i).start().equals(periods.get(i - 1).endExclusive())) {
                throw new IllegalArgumentException("a schedule's periods chain: each starts where the previous fell due");
            }
        }
        if (intendedDay < 1 || intendedDay > 31) {
            throw new IllegalArgumentException("the intended day is 1 to 31");
        }
    }

    /** The first period's start - the accrual start the schedule was projected from. */
    public LocalDate start() {
        return periods.get(0).start();
    }

    /**
     * The period holding {@code date}: a scheduled instalment period, or after the final due date the monthly calendar
     * continued.
     *
     * @throws IllegalArgumentException when {@code date} is before the schedule's start
     */
    public AccrualPeriod periodOf(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(start())) {
            throw new IllegalArgumentException("a date before the schedule's start has no accrual period");
        }
        for (AccrualPeriod period : periods) {
            if (period.contains(date)) {
                return period;
            }
        }
        YearMonth firstDue = YearMonth.from(periods.get(0).endExclusive());
        int sequence = periods.size();
        LocalDate previous = periods.get(sequence - 1).endExclusive();
        while (true) {
            LocalDate due = onDay(firstDue.plusMonths(sequence), intendedDay);
            if (date.isBefore(due)) {
                return new AccrualPeriod(previous, due);
            }
            previous = due;
            sequence++;
        }
    }

    private static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
