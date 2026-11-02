package com.finapp.app.accounting;

import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetId;
import com.finapp.accounting.ValuationRateSetStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ValuationRateReads} over {@link ValuationRateSetStore}'s two reads (`P14-TSK-015`; {@code INV-ACC-11}): the set and
 * its rates, both {@code SELECT}, on the generation's read-only snapshot - the snapshot's restricted view refuses anything
 * else. Here rather than in {@code accounting} for the {@code LedgerTrialBalanceReads} reason: {@code ReportingNeverWritesTest}
 * follows accounting's implementations of a port a report class holds, and the store's implementation also decides sets;
 * a report class holds only this port, and this adapter uses only the reads.
 */
final class PinnedValuationRates implements ValuationRateReads {

    private final ValuationRateSetStore store;

    PinnedValuationRates(ValuationRateSetStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<PinnedSet> set(Connection snapshot, UUID id) {
        ValuationRateSetId setId = ValuationRateSetId.of(id);
        return store.find(snapshot, setId).map(row -> new PinnedSet(row.id().value(), row.period(), row.month(),
                row.status(), row.asOf(), row.maxAgeHours(), store.rates(snapshot, setId)));
    }
}
