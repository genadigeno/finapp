package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PeriodRequestEnd;
import com.finapp.accounting.PeriodStatus;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link AccountingPeriodStore} in memory (`P14-TSK-005`): the hermetic suites' store, holding the database's ranks the
 * machine relies on - one period per month, contiguous, born {@code OPEN}, the conditional edges, in order, one period in
 * close, the four-eyes {@code CHECK}, the close's and the reopen's edges, and an unlock's end (`P14-TSK-024`: one per request, the four eyes, only the pending
 * proposal, cleared beside it) - so a defect the database would refuse is refused here too, as an {@link IllegalStateException} naming the rank. The real ranks
 * are {@code accounting V003}'s, {@code V008}'s and {@code V011}'s, proven by {@code AccountingPeriodDatabaseTest}, {@code PeriodCloseDatabaseTest} and
 * {@code PeriodReopenDatabaseTest} (owed, C5).
 */
public final class InMemoryAccountingPeriodStore implements AccountingPeriodStore {

    /** Concurrent, so a leg reading the periods never fails on another leg's birth of one (`AccountingCloseScheduleTest#everyLegRunsAtStart`, found by `P14-TSK-012`'s fresh run). */
    private final ConcurrentSkipListMap<YearMonth, PeriodRow> rows = new ConcurrentSkipListMap<>();
    private final List<EventRow> history = new CopyOnWriteArrayList<>();
    /** Each unlock's end, in order (`P14-TSK-024`). */
    private final List<UnlockEndRow> ends = new CopyOnWriteArrayList<>();
    /** Each proposal's and each end's own stamp, as the database's clock_timestamp() would give it: two requests never share an instant. */
    private long stamps;
    private int rowLocks;
    /** Each lock's own stamp, as the database's clock_timestamp() would give it (`P14-TSK-008`: a lock instance). */
    private long lockInstances;
    private LocalDate today = LocalDate.EPOCH;

    /** What the database's clock reads today; a suite that reads an age sets it (`P14-TSK-020`). */
    public void databaseToday(LocalDate day) {
        this.today = day;
    }

    /** The history, in order. */
    public List<EventRow> history() {
        return List.copyOf(history);
    }

    /** Every unlock's end, in order (`P14-TSK-024`). */
    public List<UnlockEndRow> unlockEnds() {
        return List.copyOf(ends);
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
    public LocalDate today(Connection unitOfWork) {
        return today;
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
                Optional.of(Instant.EPOCH.plusMillis(++lockInstances)), pin, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
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
                row.lockedAt(), row.pinned(), Optional.of(actor.id()), Optional.of(stamp()), Optional.empty(),
                Optional.empty()));
        return true;
    }

    @Override
    public boolean endUnlock(Connection unitOfWork, NewUnlockEnd end) {
        Optional<PeriodRow> found = find(unitOfWork, end.period());
        if (found.isEmpty() || !found.get().unlockPending() || found.get().revision() != end.revision()
                || !found.get().unlockProposedBy().orElseThrow().equals(end.proposedBy())) {
            return false;
        }
        PeriodRow row = found.get();
        boolean own = end.endedBy().id().equals(end.proposedBy());
        if ((end.outcome() == PeriodRequestEnd.REJECTED) == own) {
            throw new IllegalStateException("period_unlock_end_four_eyes");
        }
        if (ends.stream().anyMatch(other -> other.period().equals(end.period()) && other.lockedAt().equals(row.lockedAt().orElseThrow())
                && other.proposedAt().equals(row.unlockProposedAt().orElseThrow()))) {
            throw new IllegalStateException("period_unlock_end_once");
        }
        ends.add(new UnlockEndRow(end.id(), end.period(), row.revision(), row.lockedAt().orElseThrow(), end.proposedBy(), row.unlockProposedAt().orElseThrow(), end.outcome(),
                end.endedBy().id(), stamp()));
        rows.put(row.month(), new PeriodRow(row.id(), row.month(), PeriodStatus.LOCKED, row.revision(), row.openedAt(), row.lockedAt(), row.pinned(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        return true;
    }

    @Override
    public Optional<UnlockEndRow> latestUnlockEnd(Connection unitOfWork, AccountingPeriodId id) {
        Optional<Instant> lockedAt = find(unitOfWork, id).flatMap(PeriodRow::lockedAt);
        return ends.stream().filter(end -> end.period().equals(id) && lockedAt.filter(end.lockedAt()::equals).isPresent()).reduce((earlier, later) -> later);
    }

    private Instant stamp() {
        return Instant.EPOCH.plusSeconds(++stamps);
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
    public boolean markCloseProposed(Connection unitOfWork, AccountingPeriodId id, int revision) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != PeriodStatus.LOCKED || found.get().revision() != revision
                || found.get().unlockProposedBy().isPresent()) {
            return false;
        }
        return moved(found.get(), PeriodStatus.CLOSE_PROPOSED);
    }

    @Override
    public boolean markClosed(Connection unitOfWork, AccountingPeriodId id, int revision) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != PeriodStatus.CLOSE_PROPOSED || found.get().revision() != revision) {
            return false;
        }
        return moved(found.get(), PeriodStatus.CLOSED);
    }

    @Override
    public boolean markCloseRejected(Connection unitOfWork, AccountingPeriodId id, int revision) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != PeriodStatus.CLOSE_PROPOSED || found.get().revision() != revision) {
            return false;
        }
        return moved(found.get(), PeriodStatus.LOCKED);
    }

    @Override
    public boolean markReopened(Connection unitOfWork, AccountingPeriodId id, int revision) {
        Optional<PeriodRow> found = find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != PeriodStatus.CLOSED || found.get().revision() != revision) {
            return false;
        }
        PeriodRow row = found.get();
        PeriodRow next = rows.get(row.month().plusMonths(1));
        if (next != null && next.status() != PeriodStatus.OPEN) {
            throw new IllegalStateException("accounting_period_permits_only_machine_edges: only the latest closed period reopens");
        }
        rows.put(row.month(), new PeriodRow(id, row.month(), PeriodStatus.OPEN, revision + 1, row.openedAt(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        return true;
    }

    private boolean moved(PeriodRow row, PeriodStatus to) {
        rows.put(row.month(), new PeriodRow(row.id(), row.month(), to, row.revision(), row.openedAt(), row.lockedAt(), row.pinned(), row.unlockProposedBy(),
                row.unlockProposedAt(), row.unlockApprovedBy(), row.unlockApprovedAt()));
        return true;
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow row) {
        boolean fourEyes = row.act() == Act.UNLOCKED || row.act() == Act.CLOSED || row.act() == Act.REOPENED || row.act() == Act.UNLOCK_REJECTED || row.act() == Act.REOPEN_REJECTED;
        boolean withdrawn = row.act() == Act.UNLOCK_WITHDRAWN || row.act() == Act.REOPEN_WITHDRAWN;
        boolean holds = fourEyes ? row.proposedBy().filter(proposer -> !proposer.equals(row.actor().id())).isPresent()
                : withdrawn ? row.proposedBy().filter(row.actor().id()::equals).isPresent()
                : row.proposedBy().isEmpty();
        if (!holds) {
            throw new IllegalStateException("period_event_four_eyes");
        }
        history.add(row);
    }

    /** Every period, oldest first. */
    public List<PeriodRow> all() {
        return rows.values().stream().sorted(Comparator.comparing(PeriodRow::month)).toList();
    }
}
