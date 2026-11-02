package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a translated report reads of its pinned valuation rate set (`P14-TSK-015`; ADR-0116 decision 3; {@code INV-ACC-11},
 * {@code INV-ACC-14}) - the set and its rates by id, on the generation's read-only snapshot, {@code SELECT} only, locking
 * nothing. The way out of report code that the {@code TrialBalanceReads} port is: a report class cannot hold
 * {@link ValuationRateSetStore}, whose JDBC implementation decides sets, so {@code app} implements this over it
 * ({@code ReportingNeverWritesTest} stops at the port).
 *
 * <p>A set is immutable from its approval ({@code accounting V005}), so what this answers for an approved set is the same
 * in every snapshot - which is what lets a FINAL translated run regenerate byte-identical.
 */
public interface ValuationRateReads {

    /** The set with this id as {@code snapshot} holds it, with its rates - empty when no set has this id. */
    Optional<PinnedSet> set(Connection snapshot, UUID id);

    /**
     * A valuation rate set as a report reads it - no person, no reason.
     *
     * @param id the set
     * @param period the period it values
     * @param month that period's month
     * @param status its status - a report translates at {@code APPROVED} only
     * @param asOf the instant it is as of: the first instant after the period, UTC
     * @param maxAgeHours the staleness bound it was captured under
     * @param rates its rates, with their provenance
     */
    record PinnedSet(
            UUID id,
            AccountingPeriodId period,
            YearMonth month,
            ValuationRateSetStatus status,
            Instant asOf,
            int maxAgeHours,
            List<ValuationRate> rates) {

        public PinnedSet {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(asOf, "asOf");
            rates = List.copyOf(rates);
        }
    }
}
