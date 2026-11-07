package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.PeriodCloseId;
import com.finapp.accounting.PeriodCloseStatus;
import com.finapp.accounting.PeriodCloseStore;
import com.finapp.accounting.PeriodReopenId;
import com.finapp.accounting.PeriodReopenStatus;
import com.finapp.accounting.PeriodRequestEnd;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link PeriodCloseStore} in memory (`P14-TSK-012`): the hermetic suites' store, holding the database's ranks the machine relies on - one pending proposal per period, one approved per revision, the
 * four-eyes {@code CHECK}s, one decision, one pending reopen per period and one approved per revision, an end four-eyed or the proposer's own (`P14-TSK-024`), the record append-only - so a defect the
 * database would refuse is refused here too, as an {@link IllegalStateException} naming the rank. A frozen attempt is read from the run store's own row, as the database's join reads it. The real ranks are
 * {@code accounting V008}'s and {@code V011}'s, proven by {@code PeriodCloseDatabaseTest} and {@code PeriodReopenDatabaseTest} (owed, C5).
 */
public final class InMemoryPeriodCloseStore implements PeriodCloseStore {

    private final Map<PeriodCloseId, CloseRow> closes = new LinkedHashMap<>();
    private final Map<PeriodCloseId, List<CheckRow>> checks = new LinkedHashMap<>();
    private final Map<PeriodReopenId, ReopenRow> reopens = new LinkedHashMap<>();
    private final AtomicLong clock = new AtomicLong();
    private final InMemoryAccountingPeriodStore periods;
    /** Where a frozen attempt is read from, as {@code close_check} is joined. */
    private final CloseRunStore runs;

    public InMemoryPeriodCloseStore(InMemoryAccountingPeriodStore periods, CloseRunStore runs) {
        this.periods = periods;
        this.runs = runs;
    }

    /** Every proposal, in birth order. */
    public List<CloseRow> closes() {
        return List.copyOf(closes.values());
    }

    /** Every reopen proposal, in birth order. */
    public List<ReopenRow> reopens() {
        return List.copyOf(reopens.values());
    }

    /** A suite's transaction: the rows as they stand now, back on {@code run} - what a rollback does to them. */
    public Runnable savepoint() {
        Map<PeriodCloseId, CloseRow> closesNow = new LinkedHashMap<>(closes);
        Map<PeriodCloseId, List<CheckRow>> checksNow = new LinkedHashMap<>();
        checks.forEach((id, rows) -> checksNow.put(id, new ArrayList<>(rows)));
        Map<PeriodReopenId, ReopenRow> reopensNow = new LinkedHashMap<>(reopens);
        return () -> {
            closes.clear();
            closes.putAll(closesNow);
            checks.clear();
            checks.putAll(checksNow);
            reopens.clear();
            reopens.putAll(reopensNow);
        };
    }

    @Override
    public void insert(Connection unitOfWork, NewClose close) {
        if (closes.values().stream().anyMatch(row -> row.period().equals(close.period()) && row.status() == PeriodCloseStatus.PROPOSED)) {
            throw new IllegalStateException("period_close_one_pending");
        }
        AccountingPeriodStore.PeriodRow period = periods.find(unitOfWork, close.period()).orElseThrow();
        YearMonth month = period.month();
        closes.put(close.id(), new CloseRow(close.id(), close.period(), month, close.revision(), close.lockedAt(), close.run(), close.pinned(), close.rateSet(),
                close.acknowledged(), close.resolved(), PeriodCloseStatus.PROPOSED, close.proposedBy().id(), now(), close.reason(), Optional.empty(), Optional.empty(),
                close.correlation()));
        checks.put(close.id(), new ArrayList<>());
    }

    @Override
    public void insertCheck(Connection unitOfWork, CheckRow check) {
        List<CheckRow> rows = checks.get(check.close());
        if (rows == null || closes.get(check.close()).status() != PeriodCloseStatus.PROPOSED) {
            throw new IllegalStateException("period_close_check_is_the_attempt: recorded with its proposal");
        }
        if (rows.stream().anyMatch(row -> row.check() == check.check())) {
            throw new IllegalStateException("period_close_check_pk");
        }
        rows.add(check);
    }

    @Override
    public Optional<CloseRow> find(Connection unitOfWork, PeriodCloseId id) {
        return Optional.ofNullable(closes.get(id));
    }

    @Override
    public Optional<CloseRow> lock(Connection unitOfWork, PeriodCloseId id) {
        return find(unitOfWork, id);
    }

    @Override
    public Optional<CloseRow> latest(Connection unitOfWork, AccountingPeriodId period) {
        return closes.values().stream().filter(row -> row.period().equals(period)).reduce((earlier, later) -> later);
    }

    @Override
    public List<CheckRow> checks(Connection unitOfWork, PeriodCloseId id) {
        return checks.getOrDefault(id, List.of()).stream().sorted(Comparator.comparing(CheckRow::check)).toList();
    }

    @Override
    public Map<CloseCheckCode, FrozenAttempt> frozenAttempts(Connection unitOfWork, PeriodCloseId id) {
        CloseRow close = closes.get(id);
        Map<CloseCheckCode, FrozenAttempt> frozen = new EnumMap<>(CloseCheckCode.class);
        if (close == null) {
            return frozen;
        }
        List<CloseRunStore.AttemptRow> attempts = runs.attempts(unitOfWork, close.run());
        for (CheckRow check : checks.getOrDefault(id, List.of())) {
            attempts.stream().filter(attempt -> attempt.id().equals(check.attemptId())).findFirst()
                    .ifPresent(attempt -> frozen.put(check.check(), new FrozenAttempt(attempt.evidence().orElseThrow(), attempt.concludedAt().orElseThrow())));
        }
        return frozen;
    }

    @Override
    public boolean decide(Connection unitOfWork, PeriodCloseId id, PeriodCloseStatus decision, Actor actor, String reason) {
        CloseRow row = closes.get(id);
        if (row == null || row.status() != PeriodCloseStatus.PROPOSED) {
            return false;
        }
        if (decision == PeriodCloseStatus.APPROVED && row.proposedBy().equals(actor.id())) {
            throw new IllegalStateException("period_close_four_eyes");
        }
        if (decision == PeriodCloseStatus.APPROVED && closes.values().stream().anyMatch(other -> other.period().equals(row.period())
                && other.revision() == row.revision() && other.status() == PeriodCloseStatus.APPROVED)) {
            throw new IllegalStateException("period_close_one_approved");
        }
        closes.put(id, new CloseRow(row.id(), row.period(), row.month(), row.revision(), row.lockedAt(), row.run(), row.pinned(), row.rateSet(), row.acknowledged(),
                row.resolved(), decision, row.proposedBy(), row.proposedAt(), row.proposalReason(), Optional.of(actor.id()), Optional.of(now()), row.correlation()));
        return true;
    }

    @Override
    public void insertReopen(Connection unitOfWork, NewReopen reopen) {
        if (reopens.values().stream().anyMatch(row -> row.period().equals(reopen.period()) && row.status() == PeriodReopenStatus.PROPOSED)) {
            throw new IllegalStateException("period_reopen_one_pending");
        }
        AccountingPeriodStore.PeriodRow period = periods.find(unitOfWork, reopen.period()).orElseThrow();
        if (period.status() != com.finapp.accounting.PeriodStatus.CLOSED || period.revision() != reopen.revision()) {
            throw new IllegalStateException("period_reopen_permits_only_machine_edges: a CLOSED period at its present revision");
        }
        reopens.put(reopen.id(), new ReopenRow(reopen.id(), reopen.period(), reopen.revision(), PeriodReopenStatus.PROPOSED, reopen.proposedBy().id(), now(), reopen.reason(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Override
    public Optional<ReopenRow> pendingReopen(Connection unitOfWork, AccountingPeriodId period) {
        return reopens.values().stream().filter(row -> row.period().equals(period) && row.status() == PeriodReopenStatus.PROPOSED).findFirst();
    }

    @Override
    public Optional<ReopenRow> lockPendingReopen(Connection unitOfWork, AccountingPeriodId period) {
        return pendingReopen(unitOfWork, period);
    }

    @Override
    public Optional<ReopenRow> approvedReopenAt(Connection unitOfWork, AccountingPeriodId period, int revision) {
        return reopens.values().stream().filter(row -> row.period().equals(period) && row.revision() == revision && row.status() == PeriodReopenStatus.APPROVED).findFirst();
    }

    @Override
    public Optional<ReopenRow> endedReopen(Connection unitOfWork, AccountingPeriodId period, PeriodReopenId id) {
        return Optional.ofNullable(reopens.get(id)).filter(row -> row.period().equals(period) && row.endedBy().isPresent());
    }

    @Override
    public boolean approveReopen(Connection unitOfWork, PeriodReopenId id, Actor actor, String reason) {
        ReopenRow row = reopens.get(id);
        if (row == null || row.status() != PeriodReopenStatus.PROPOSED) {
            return false;
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new IllegalStateException("period_reopen_four_eyes");
        }
        if (reopens.values().stream().anyMatch(other -> other.period().equals(row.period()) && other.revision() == row.revision() && other.status() == PeriodReopenStatus.APPROVED)) {
            throw new IllegalStateException("period_reopen_one_approved");
        }
        reopens.put(id, new ReopenRow(row.id(), row.period(), row.revision(), PeriodReopenStatus.APPROVED, row.proposedBy(), row.proposedAt(), row.proposalReason(),
                Optional.of(actor.id()), Optional.of(now()), Optional.empty(), Optional.empty()));
        return true;
    }

    @Override
    public boolean endReopen(Connection unitOfWork, PeriodReopenId id, PeriodRequestEnd end, Actor actor, String reason) {
        ReopenRow row = reopens.get(id);
        if (row == null || row.status() != PeriodReopenStatus.PROPOSED) {
            return false;
        }
        if ((end == PeriodRequestEnd.REJECTED) == row.proposedBy().equals(actor.id())) {
            throw new IllegalStateException("period_reopen_end_four_eyes");
        }
        AccountingPeriodStore.PeriodRow period = periods.find(unitOfWork, row.period()).orElseThrow();
        if (period.status() != com.finapp.accounting.PeriodStatus.CLOSED || period.revision() != row.revision()) {
            throw new IllegalStateException("period_reopen_permits_only_machine_edges: ended while its period is CLOSED at its revision");
        }
        reopens.put(id, new ReopenRow(row.id(), row.period(), row.revision(), end.reopenStatus(), row.proposedBy(), row.proposedAt(), row.proposalReason(), Optional.empty(), Optional.empty(),
                Optional.of(actor.id()), Optional.of(now())));
        return true;
    }

    private Instant now() {
        return Instant.EPOCH.plusSeconds(clock.incrementAndGet());
    }
}
