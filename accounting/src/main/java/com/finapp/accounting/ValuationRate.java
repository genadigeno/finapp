package com.finapp.accounting;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One rate of a valuation rate set (`P14-TSK-014`; {@code INV-ACC-11}; ADR-0116 decision 2): {@code EUR/quote} - units
 * of {@code quote} per one euro - exactly as fx's reference stored it ({@code NUMERIC(20,10)}, a {@code BigDecimal} end to
 * end, never floating point, {@code INV-MON-01}), with its provenance: the source's code, the snapshot's id in
 * {@code fx.rate_snapshot}, the source's observation instant and the database's receipt instant. A translation (`-015`)
 * divides a quote-currency amount by it; it is never inverted, rounded or re-scaled here.
 */
public record ValuationRate(
        CurrencyCode base,
        CurrencyCode quote,
        BigDecimal rate,
        String source,
        UUID snapshotId,
        Instant observedAt,
        Instant receivedAt) {

    /** fx's source shape ({@code fx V002}'s {@code rate_snapshot_source_shape}); {@code accounting V005} holds it too. */
    static final Pattern SOURCE = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    /** {@code NUMERIC(20,10)}: ten integer digits, ten fractional. */
    static final int SCALE = 10;

    static final int INTEGER_DIGITS = 10;

    public ValuationRate {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(quote, "quote must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        if (!ValuationRates.FUNCTIONAL.equals(base) || base.equals(quote)) {
            throw new IllegalArgumentException("a valuation rate is EUR-based, of another currency: not " + base + "/"
                    + quote);
        }
        if (!fits(rate)) {
            throw new IllegalArgumentException("a valuation rate is positive and fits NUMERIC(20,10)");
        }
        if (!SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("a valuation rate's source is a reference source's code");
        }
    }

    /** True for a positive rate {@code NUMERIC(20,10)} holds exactly - no digit lost on the way in or out. */
    static boolean fits(BigDecimal rate) {
        return rate.signum() > 0 && rate.scale() <= SCALE && rate.precision() - rate.scale() <= INTEGER_DIGITS;
    }

    /** The pair, {@code EUR/GBP} - a code, never the rate. */
    public String pair() {
        return base.code() + "/" + quote.code();
    }
}
