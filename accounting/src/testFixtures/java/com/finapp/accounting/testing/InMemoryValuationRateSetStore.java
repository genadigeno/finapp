package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateSetId;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ValuationRateSetStore} in memory (`P14-TSK-014`): the hermetic suites' store, holding the database's ranks the
 * domain relies on - born {@code PROPOSED} whole, for a {@code LOCKED} period; the one conditional edge; the four-eyes
 * {@code CHECK}; at most one {@code APPROVED} set per period; rates never changed - so a defect the database would refuse
 * is refused here too, as an {@link IllegalStateException} naming the rank. The real ranks are {@code accounting V005}'s,
 * proven by {@code ValuationRateSetDatabaseTest} (owed, C5).
 */
public final class InMemoryValuationRateSetStore implements ValuationRateSetStore {

    private final Map<ValuationRateSetId, SetRow> rows = new LinkedHashMap<>();
    private final Map<ValuationRateSetId, List<ValuationRate>> rates = new LinkedHashMap<>();
    private final Map<ValuationRateSetId, Optional<String>> reasons = new LinkedHashMap<>();
    private Function<AccountingPeriodId, Optional<PeriodStatus>> periodStatus = id -> Optional.of(PeriodStatus.LOCKED);
    private int rowLocks;

    /** The periods the edge trigger reads: a set is born, and approved, only for a {@code LOCKED} one. */
    public InMemoryValuationRateSetStore watching(InMemoryAccountingPeriodStore periods) {
        this.periodStatus = id -> periods.find(null, id).map(AccountingPeriodStore.PeriodRow::status);
        return this;
    }

    /** Every set, in birth order. */
    public List<SetRow> all() {
        return List.copyOf(rows.values());
    }

    @Override
    public List<SetRow> ofPeriod(Connection unitOfWork, AccountingPeriodId period, int limit) {
        return rows.values().stream().filter(row -> row.period().equals(period)).limit(limit).toList();
    }

    @Override
    public Map<ValuationRateSetStatus, Long> countByStatus(Connection unitOfWork, AccountingPeriodId period) {
        Map<ValuationRateSetStatus, Long> counts = new EnumMap<>(ValuationRateSetStatus.class);
        rows.values().stream().filter(row -> row.period().equals(period))
                .forEach(row -> counts.merge(row.status(), 1L, Long::sum));
        return counts;
    }

    /** The capture reason stored with the set - held, never served. */
    public Optional<String> captureReason(ValuationRateSetId id) {
        return reasons.getOrDefault(id, Optional.empty());
    }

    /** How many set rows were locked {@code FOR UPDATE}. */
    public int rowLocks() {
        return rowLocks;
    }

    @Override
    public void insert(Connection unitOfWork, NewSet set) {
        if (rows.containsKey(set.id())) {
            throw new IllegalStateException("valuation_rate_set_pk");
        }
        if (periodStatus.apply(set.content().period()).filter(PeriodStatus.LOCKED::equals).isEmpty()) {
            throw new IllegalStateException("valuation_rate_set_permits_only_machine_edges: a LOCKED period");
        }
        rows.put(set.id(), new SetRow(set.id(), set.content().period(), set.content().month(), set.content().asOf(),
                set.content().staleness().hours(), set.content().rates().size(), ValuationRateSetStatus.PROPOSED,
                set.capturedBy().id(), Instant.EPOCH, Optional.empty(), Optional.empty()));
        rates.put(set.id(), List.copyOf(set.content().rates()));
        reasons.put(set.id(), set.reason());
    }

    @Override
    public Optional<SetRow> find(Connection unitOfWork, ValuationRateSetId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<SetRow> lock(Connection unitOfWork, ValuationRateSetId id) {
        rowLocks++;
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<SetRow> approvedFor(Connection unitOfWork, AccountingPeriodId period) {
        return rows.values().stream()
                .filter(row -> row.period().equals(period) && row.status() == ValuationRateSetStatus.APPROVED)
                .findFirst();
    }

    @Override
    public List<ValuationRate> rates(Connection unitOfWork, ValuationRateSetId id) {
        return new ArrayList<>(rates.getOrDefault(id, List.of()));
    }

    @Override
    public boolean decide(
            Connection unitOfWork, ValuationRateSetId id, ValuationRateSetStatus to, Actor actor, String reason) {
        SetRow row = rows.get(id);
        if (row == null || row.status() != ValuationRateSetStatus.PROPOSED) {
            return false;
        }
        if (!row.status().canMoveTo(to)) {
            throw new IllegalStateException("valuation_rate_set_permits_only_machine_edges: " + row.status() + " -> "
                    + to);
        }
        if (to == ValuationRateSetStatus.APPROVED) {
            if (actor.id().equals(row.capturedBy())) {
                throw new IllegalStateException("valuation_rate_set_four_eyes");
            }
            if (approvedFor(unitOfWork, row.period()).isPresent()) {
                throw new IllegalStateException("valuation_rate_set_one_approved_per_period");
            }
            if (periodStatus.apply(row.period()).filter(PeriodStatus.LOCKED::equals).isEmpty()) {
                throw new IllegalStateException("valuation_rate_set_permits_only_machine_edges: approved while LOCKED");
            }
        }
        rows.put(id, new SetRow(row.id(), row.period(), row.month(), row.asOf(), row.maxAgeHours(), row.rateCount(), to,
                row.capturedBy(), row.capturedAt(), Optional.of(actor.id()), Optional.of(Instant.EPOCH)));
        return true;
    }
}
