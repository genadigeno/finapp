package com.finapp.creditmath;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What a schedule engine reads of an agreement's terms (`P12-TSK-002`; ADR-0093, ADR-0101 decision 5, ADR-0102 decision
 * 3, {@code INV-LND-09}): the currency, the nominal annual rate, the shortest first period and the declared conventions -
 * the day count, the instalment and interest rounding modes and the schedule engine version - exactly as the terms name
 * them. The product-agnostic input {@link ScheduleEngineV1} computes from, so a product of another context (pay-in-N)
 * can be scheduled by the same engine without the engine seeing its vocabulary.
 *
 * <p><strong>Shape only</strong>, the {@link AccrualTerms} precedent: whether an engine version implements these
 * conventions, and whether the rate and the shortest first period are within its range, is that engine's judgement - a
 * typed {@link ScheduleOutcome.Refused}, never an exception - so a later version can accept what version 1 refuses.
 * What a <em>product</em> must be for its terms to reach here (lending's instalment kind, its fixed rate, its monthly
 * frequency) is that product's mapping's to judge before it builds one ({@code lending}'s {@code LoanEngines}).
 *
 * <p>{@code toString} names the engine version only - never the rate ({@code INV-AUD-02}).
 *
 * @param currency the agreement's one currency
 * @param nominalAnnualRate the nominal annual rate as a decimal fraction
 * @param minFirstPeriodDays the shortest first period: the first due date is at least this many days after the start
 * @param dayCount the declared day count
 * @param instalmentRounding the declared rounding of the level instalment
 * @param interestRounding the declared rounding of each period's interest
 * @param scheduleEngine the schedule engine version the figures must come from
 */
public record ScheduleTerms(
        CurrencyCode currency,
        BigDecimal nominalAnnualRate,
        int minFirstPeriodDays,
        DayCountConvention dayCount,
        RoundingMode instalmentRounding,
        RoundingMode interestRounding,
        EngineVersion scheduleEngine) {

    public ScheduleTerms {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(instalmentRounding, "instalmentRounding");
        Objects.requireNonNull(interestRounding, "interestRounding");
        Objects.requireNonNull(scheduleEngine, "scheduleEngine");
    }

    /** The engine version only - never the rate. */
    @Override
    public String toString() {
        return "ScheduleTerms[" + scheduleEngine + "]";
    }
}
