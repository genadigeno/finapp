package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@link AccountingPeriodStore} in memory (`P14-TSK-005`): the hermetic suites' store, holding the database's ranks the
 * machine relies on - one period per month, contiguous, born {@code OPEN}, the conditional edges, in order, one period in
 * close, the four-eyes {@code CHECK} - so a defect the database would refuse is refused here too, as an
 * {@link IllegalStateException} naming the rank. The real ranks are {@code accounting V003}'s, proven by
 * {@code AccountingPeriodDatabaseTest} (owed, C5).
 */
public final class InMemoryAccountingPeriodStore implements AccountingPeriodStore {

    private final TreeMap<YearMonth, PeriodRow> rows = new TreeMap<>();
    private final List<EventRow> history = new ArrayList<>();
    private int rowLocks;

    /** The history, in order. */
    public List<EventRow> history() {
        return List.copyOf(history);
    }

    /** How many row locks ({@code FOR UPDATE} and {@code FOR SHARE}) were taken. */
    public int rowLocks() {
        return rowLocks;
    }

    /** The month's period as stored. */
    public Optional<PeriodRow> month(YearMonth month) {
        return Optional.ofNullable(rows.get(month));
    }

    /** The earliest period at or after {@code month} no longer {@code OPEN} - the activation's re-read. */
    public Optional<YearMonth> firstPinnedFrom(YearMonth month) {
        return rows.tailMap(month, true).values().stream()
                .filter(row -> row.status() != PeriodStatus.OPEN).map(PeriodRow::month).findFirst();
    }

    /** A test's stand-in for `P14-TSK-012`'s close: the month's period set {@code CLOSED} directly. */
    public void closeForTest(YearMonth month) {
        PeriodRow row = rows.get(month);
        rows.put(month, new PeriodRow(row.id(), month, PeriodStatus.CLOSED, row.revision(), row.openedAt(),
                Optional.of(Instant.EPOCH), row.pinned(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()));
    }

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewPeriod period) {
        if (rows.containsKey(period.month())) {
            return false;
        }
        if (!rows.isEmpty() && (period.month().isAfter(rows.lastKey().plusMonths(1))
                || period.month().isBefore(rows.firstKey()))) {
            throw new IllegalStateException("accounting_period_permits_only_machine_edges: contiguous");
        }
        rows.put(period.month(), new PeriodRow(period.id(), period.month(), PeriodStatus.OPEN, 1, Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()));
        return true;
    }

    @Override
    public Optional<YearMonth> latest(Connection unitOfWork) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.lastKey());
    }

    @Override
    public Optional<PeriodRow> find(Connection unitOfWork, AccountingPeriodId id) {
        return rows.values().stream().filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public Optional<PeriodRow> lock(Connection unitOfWork, AccountingPeriodId id) {
        rowLocks++;
        return find(unitOfWork, id);
    }

    @Override
    public Optional<PeriodRow> lockShared(Connection unitOfWork, YearMonth month) {
        rowLocks++;
        return Optional.ofNullable(rows.get(month));
    }

    @Override
    public Optional<PeriodRow> firstOpen(Connection unitOfWork) {
        return rows.values().stream().filter(row -> row.status() == PeriodStatus.OPEN).findFirst();
    }

    @Override
    public Optional<PeriodRow> oldestUnclosed(Connection unitOfWork) {
        return rows.values().stream().filter(row -> row.status() != PeriodStatus.CLOSED).findFirst();
    }

    @Override
    public List<PeriodRow> recent(Connection unitOfWork, int limit) {
        return rows.descendingMap().values().stream().limit(limit).toList();
    }

    @Override
    public boolean markLocked(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, Optional<Pin> pin) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != PeriodStatus.OPEN || found.get().revision() != revision) {
            return false;
        }
        PeriodRow row = found.get();
        PeriodRow previous = rows.get(row.month().minusMonths(1));
        if (previous != null && previous.status() != PeriodStatus.CLOSED) {
            throw new IllegalStateException("accounting_period_permits_only_machine_edges: in order");
        }
        if (rows.values().stream().anyMatch(other -> other.status() == PeriodStatus.LOCKED
                || other.status() == PeriodStatus.CLOSE_PROPOSED)) {
            throw new IllegalStateException("accounting_period_one_in_close");
        }
        rows.put(row.month(), new PeriodRow(id, row.month(), PeriodStatus.LOCKED, revision, row.openedAt(),
                Optional.of(Instant.EPOCH), pin, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        return true;
    }

    @Override
    public boolean proposeUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != PeriodStatus.LOCKED || found.get().revision() != revision
                || found.get().unlockProposedBy().isPresent()) {
            return false;
        }
        PeriodRow row = found.get();
        rows.put(row.month(), new PeriodRow(id, row.month(), PeriodStatus.LOCKED, revision, row.openedAt(),
                row.lockedAt(), row.pinned(), Optional.of(actor.id()), Optional.of(Instant.EPOCH), Optional.empty(),
                Optional.empty()));
        return true;
    }

    @Override
    public boolean approveUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || !found.get().unlockPending() || found.get().revision() != revision) {
            return false;
        }
        PeriodRow row = found.get();
        if (row.unlockProposedBy().orElseThrow().equals(actor.id())) {
            throw new IllegalStateException("accounting_period_unlock_four_eyes");
        }
        rows.put(row.month(), new PeriodRow(id, row.month(), PeriodStatus.OPEN, revision, row.openedAt(),
                Optional.empty(), Optional.empty(), row.unlockProposedBy(), row.unlockProposedAt(),
                Optional.of(actor.id()), Optional.of(Instant.EPOCH)));
        return true;
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow row) {
        if (row.act() == Act.UNLOCKED && row.proposedBy().filter(row.actor().id()::equals).isPresent()) {
            throw new IllegalStateException("period_event_four_eyes");
        }
        history.add(row);
    }

    /** Every period, oldest first. */
    public List<PeriodRow> all() {
        return rows.values().stream().sorted(Comparator.comparing(PeriodRow::month)).toList();
    }
}
