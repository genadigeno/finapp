package com.finapp.creditmath;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What an accrual engine reads of a loan's terms (`P11-TSK-007`; ADR-0094, {@code INV-LND-09}): the currency, the
 * nominal annual rate and the declared conventions - the day count, the interest rounding mode and the accrual engine
 * version - exactly as the terms (and, from `P11-TSK-012`, the agreement that pins them) name them. Shape only: whether
 * an engine version implements them is that engine's judgement ({@link AccrualRefused}), so a later version can accept
 * what version 1 refuses.
 *
 * <p>Built through this canonical constructor - by lending's {@code LoanEngines.accrualTerms(LoanTerms)} (the
 * {@code of(LoanTerms)} this record held until `P12-TSK-002` moved it to {@code creditmath}) and by {@code bnpl}'s
 * pay-in-N terms.
 *
 * <p>{@code toString} names the engine version only - never the rate ({@code INV-AUD-02}).
 *
 * @param currency the loan's one currency
 * @param nominalAnnualRate the nominal annual rate as a decimal fraction
 * @param dayCount the declared day count
 * @param interestRounding the declared rounding of accrued interest
 * @param accrualEngine the accrual engine version the figures must come from
 */
public record AccrualTerms(
        CurrencyCode currency,
        BigDecimal nominalAnnualRate,
        DayCountConvention dayCount,
        RoundingMode interestRounding,
        EngineVersion accrualEngine) {

    public AccrualTerms {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(interestRounding, "interestRounding");
        Objects.requireNonNull(accrualEngine, "accrualEngine");
    }

    /** The engine version only - never the rate. */
    @Override
    public String toString() {
        return "AccrualTerms[" + accrualEngine + "]";
    }
}
