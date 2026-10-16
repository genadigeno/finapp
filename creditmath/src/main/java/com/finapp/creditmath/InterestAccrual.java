package com.finapp.creditmath;

import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One date's accrued interest, as an accrual engine computed it (`P11-TSK-007`; ADR-0094 points 3-4,
 * {@code INV-LND-09}) - the figures `P11-TSK-016`'s {@code interest_accrual} row records: the date, the base it accrued
 * on, the period's exact cumulative interest through the date, what the date posts, and the engine version that
 * computed it.
 *
 * <p>{@code posted} is the change in the period's once-rounded cumulative figure, so a period's postings sum to its exact
 * interest rounded once. A zero {@code posted} is still an accrual - the row is written and no entry posted, the caller
 * deciding (ADR-0094 point 3).
 *
 * <p>{@code toString} names the date and the engine only - accrual amounts are {@code RESTRICTED-FINANCIAL}, never in a
 * log line ({@code INV-AUD-02}).
 *
 * @param date the accrual date
 * @param period the period it is rounded within
 * @param base the principal outstanding it accrued on - never interest or fees (A4)
 * @param cumulativeExact the period's exact interest through {@code date}, at scale 20
 * @param posted what {@code date} posts, in minor units, zero or positive
 * @param engine the engine version that computed it
 */
public record InterestAccrual(
        LocalDate date,
        AccrualPeriod period,
        Money base,
        BigDecimal cumulativeExact,
        Money posted,
        EngineVersion engine) {

    public InterestAccrual {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(cumulativeExact, "cumulativeExact");
        Objects.requireNonNull(posted, "posted");
        Objects.requireNonNull(engine, "engine");
        if (!period.contains(date)) {
            throw new AccrualRefused(AccrualRefused.Reason.DATE_OUTSIDE_PERIOD, "an accrual date lies in its period");
        }
        if (engine.engine() != LendingEngine.ACCRUAL) {
            throw new IllegalArgumentException("an accrual is computed by an accrual engine");
        }
        if (cumulativeExact.scale() != Act365Fixed.EXACT_SCALE || cumulativeExact.signum() < 0) {
            throw new IllegalArgumentException("the cumulative figure is at scale " + Act365Fixed.EXACT_SCALE
                    + " and not negative");
        }
        if (base.isNegative() || posted.isNegative() || !posted.currency().equals(base.currency())
                || posted.scale() != base.scale()) {
            throw new IllegalArgumentException("an accrual's base and posting are not negative, in one currency");
        }
    }

    /** The date and the engine only. */
    @Override
    public String toString() {
        return "InterestAccrual[" + date + ", " + engine + "]";
    }
}
