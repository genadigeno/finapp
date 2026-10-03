package com.finapp.platform.money;

import com.finapp.sharedkernel.money.ExchangeRate;

/**
 * The one column type every exchange-rate column is declared with (`P9-TSK-002`, ADR-0074 §1).
 *
 * <p>Generated from {@link ExchangeRate#MAX_PRECISION} and {@link ExchangeRate#MAX_SCALE}, so the
 * type and its columns cannot drift: a rate the domain admits always fits, and a migration that
 * embeds {@link #ddl()} verbatim cannot declare a narrower column that would ROUND a stored rate
 * - PostgreSQL rounds a {@code NUMERIC} value past its scale silently, which is exactly why the
 * domain refuses an over-scaled rate before it ever reaches one (the {@code FeeRate} →
 * {@code numeric(7,6)} precedent; {@code RateColumnsDatabaseTest} shows the column rounding).
 */
public final class RateColumns {

    private RateColumns() {}

    /** {@code NUMERIC(20,10)}: the column type for a rate's value. */
    public static String ddl() {
        return "NUMERIC(" + ExchangeRate.MAX_PRECISION + "," + ExchangeRate.MAX_SCALE + ")";
    }
}
