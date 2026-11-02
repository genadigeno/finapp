package com.finapp.accounting;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The calendar grace before a period may lock (`P14-TSK-005`; owner decision C9's recommended default, awaiting
 * ratification: "a period lockable no earlier than its end + a configured grace (default 2 business days)"): a period is
 * lockable <strong>from</strong> the day {@code businessDays} business days after its last day, that day included. A
 * business day is Monday to Friday - no holiday calendar exists on the platform, recorded as such; the grace is
 * configuration ({@code finapp.accounting.period.lock-grace-business-days}).
 *
 * <p>At least {@link #MIN_BUSINESS_DAYS} (`P14-TSK-011`; `P14-TSK-006`'s review, M-1): lending posts "today" on the servicing
 * zone's business date, zones to -14:00, so a lock under a grace of one, at 00:00 UTC on the day after the period's end, would
 * refuse a western zone's same-day postings for hours. Two business days put the lock's first instant past the period's last day
 * in every zone, so the ledger's cutoff never covers anyone's today. At most {@link #MAX_BUSINESS_DAYS}: a grace past a month
 * would let two periods await their lock at once, which the in-order close does not admit.
 */
public record LockGrace(int businessDays) {

    /** C9's recommended default. */
    public static final int DEFAULT_BUSINESS_DAYS = 2;

    /** The least that keeps a lock from covering a western servicing zone's today. */
    public static final int MIN_BUSINESS_DAYS = 2;

    /** About a month of business days. */
    public static final int MAX_BUSINESS_DAYS = 20;

    public LockGrace {
        if (businessDays < MIN_BUSINESS_DAYS || businessDays > MAX_BUSINESS_DAYS) {
            throw new IllegalArgumentException("the lock grace is " + MIN_BUSINESS_DAYS + " to " + MAX_BUSINESS_DAYS
                    + " business days, not " + businessDays);
        }
    }

    /** C9's default grace. */
    public static LockGrace standard() {
        return new LockGrace(DEFAULT_BUSINESS_DAYS);
    }

    /** The first day the period may lock: its last day plus the grace, counted in business days. */
    public LocalDate lockableFrom(YearMonth period) {
        LocalDate day = period.atEndOfMonth();
        int counted = 0;
        while (counted < businessDays) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                counted++;
            }
        }
        return day;
    }

    /** True from {@link #lockableFrom} on. */
    public boolean lockable(YearMonth period, LocalDate today) {
        return !today.isBefore(lockableFrom(period));
    }
}
