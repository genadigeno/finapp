package com.finapp.accounting;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * fx's reference rate snapshots as a valuation rate set captures them (`P14-TSK-001` declared it; `P14-TSK-014` gives it
 * its methods and {@code app} its implementation over {@code fx.rate_snapshot}; {@code PHASE_14_PLAN.md} section 13.4;
 * ADR-0116 decision 2; owner decisions C1, C7; {@code INV-ACC-11}) - per valued currency the last observation of the
 * {@link #FUNCTIONAL} pair strictly before an instant, with its source, {@code observed_at}, {@code received_at} and
 * snapshot id; the rate {@code NUMERIC(20,10)} exactly as fx stores it, never floating point ({@code INV-MON-01}).
 * <strong>Never a customer quote, an executed trade or a cover rate</strong> ({@code INV-FX-05} kept: ADR-0075's rate
 * chain keeps those as execution evidence; the reference is the one link that is never executable).
 *
 * <p><strong>Read-only</strong>, on the caller's unit of work, locking nothing. The domain trusts nothing it answers:
 * {@link ValuationRateSet#judge} re-judges every observation's pair, instant and rate and refuses the whole capture on
 * any doubt.
 */
public interface ValuationRates {

    /** The functional and reporting currency every valued pair is based on (owner decision C1). */
    CurrencyCode FUNCTIONAL = CurrencyCode.of("EUR");

    /**
     * The currencies a set must value - every currency the platform posts in other than {@link #FUNCTIONAL}, each once,
     * in a stable order. Never empty.
     */
    List<CurrencyCode> valued();

    /**
     * Per currency of {@link #valued()}, in its order, the last reference observation of {@code EUR/<currency>} observed
     * strictly before {@code before} - empty when the reference holds none.
     */
    List<Observed> lastObservedBefore(Connection unitOfWork, Instant before);

    /** One valued currency and what the reference holds for it. */
    record Observed(CurrencyCode currency, Optional<Observation> observation) {

        public Observed {
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(observation, "observation must not be null");
        }
    }

    /**
     * A reference observation as fx stored it: its snapshot id, the source's code, the pair in fx's canonical direction,
     * the rate (units of {@code quote} per one {@code base}) exactly as stored, the source's observation instant and the
     * database's receipt instant.
     */
    record Observation(
            UUID snapshotId,
            String source,
            CurrencyCode base,
            CurrencyCode quote,
            BigDecimal rate,
            Instant observedAt,
            Instant receivedAt) {

        public Observation {
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(base, "base must not be null");
            Objects.requireNonNull(quote, "quote must not be null");
            Objects.requireNonNull(rate, "rate must not be null");
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        }
    }
}
