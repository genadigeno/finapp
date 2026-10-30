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
 * <p>At least one business day: the ledger's cutoff never covers today (`P14-TSK-004`), so a period is never lockable on
 * its own last day. At most {@link #MAX_BUSINESS_DAYS}: a grace past a month would let two periods await their lock at
 * once, which the in-order close does not admit.
 */
public record LockGrace(int businessDays) {

    /** C9's recommended default. */
    public static final int DEFAULT_BUSINESS_DAYS = 2;

    /** About a month of business days. */
    public static final int MAX_BUSINESS_DAYS = 20;

    public LockGrace {
        if (businessDays < 1 || businessDays > MAX_BUSINESS_DAYS) {
            throw new IllegalArgumentException("the lock grace is 1 to " + MAX_BUSINESS_DAYS + " business days, not "
                    + businessDays);
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
