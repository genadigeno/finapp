package com.finapp.creditmath;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The span within which accrued interest is rounded once (`P11-TSK-007`; ADR-0094 point 3, {@code INV-LND-09}): an
 * instalment period {@code [D(j-1), Dj)} of a loan, or a statement cycle of a credit line - the dates whose postings
 * sum to the span's exact interest rounded once, the cumulative figure resetting at its start. Half-open: the end date
 * is the next period's first. The caller derives it from the schedule or the statement calendar; the engine only
 * rounds within it.
 *
 * @param start the period's first accrual date
 * @param endExclusive the first date after it - a due date or the next cycle's start
 */
public record AccrualPeriod(LocalDate start, LocalDate endExclusive) {

    public AccrualPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(start)) {
            throw new AccrualRefused(AccrualRefused.Reason.PERIOD_INVALID, "a period ends after it starts");
        }
    }

    /** Whether {@code date} is one of the period's dates. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && date.isBefore(endExclusive);
    }

    /** The period's actual days. */
    public long days() {
        return Act365Fixed.days(start, endExclusive);
    }
}
