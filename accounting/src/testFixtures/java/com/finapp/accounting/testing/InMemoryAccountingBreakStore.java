package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingBreakId;
import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.BreakStatus;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AccountingBreakStore} in memory (`P14-TSK-010`): the hermetic suites' store, holding the database's ranks the
 * machine relies on - born once per {@code (period, check, subject, generation)} (the unique key's insert-if-absent), a later generation
 * only over a decided one reading differently, born
 * {@code OPEN}, the conditional edges, the four-eyes {@code CHECK}, the case file append-only - so a defect the database
 * would refuse is refused here too, as an {@link IllegalStateException} naming the rank. The real ranks are
 * {@code accounting V006}'s, proven by {@code AccountingBreakDatabaseTest} (owed, C5).
 */
public final class InMemoryAccountingBreakStore implements AccountingBreakStore {

    private final Map<AccountingBreakId, BreakRow> rows = new LinkedHashMap<>();
    private final List<EventRow> history = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private int rowLocks;
    private boolean uniqueKeyDisarmed;

    /** The case file, in order. */
    public List<EventRow> history() {
        return List.copyOf(history);
    }

    /** Every break, in birth order. */
    public List<BreakRow> rows() {
        return List.copyOf(rows.values());
    }

    /** How many row locks were taken. */
    public int rowLocks() {
        return rowLocks;
    }

    /** A probe's handle: the born-once key no longer arbitrates, as a migration without it would not. */
    public void disarmUniqueKeyForProbe() {
        uniqueKeyDisarmed = true;
    }

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewBreak born) {
        if (born.generation() > 1 && rows.values().stream().noneMatch(row -> row.period().equals(born.period())
                && row.check() == born.finding().check() && row.subject().equals(born.finding().subject())
                && row.generation() == born.generation() - 1 && row.status() != BreakStatus.OPEN
                && !row.evidence().equals(born.finding().evidence()))) {
            throw new IllegalStateException("accounting_break_permits_only_machine_edges: a later generation follows a decided one"
                    + " and reads differently");
        }
        boolean held = rows.values().stream().anyMatch(row -> row.period().equals(born.period())
                && row.check() == born.finding().check() && row.subject().equals(born.finding().subject())
                && row.generation() == born.generation());
        if (held && !uniqueKeyDisarmed) {
            return false;
        }
        if (rows.containsKey(born.id())) {
            throw new IllegalStateException("accounting_break_pk: a break id is born once");
        }
        rows.put(born.id(), new BreakRow(born.id(), born.period(), born.finding().check(), born.finding().type(),
                born.finding().subject(), born.generation(), born.finding().evidence(), born.finding().judgedBy(), BreakStatus.OPEN, now(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        return true;
    }

    @Override
    public Optional<BreakRow> find(Connection unitOfWork, AccountingBreakId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<BreakRow> lock(Connection unitOfWork, AccountingBreakId id) {
        rowLocks++;
        return find(unitOfWork, id);
    }

    @Override
    public List<BreakRow> list(Connection unitOfWork, YearMonth period, Optional<BreakStatus> status,
            Optional<AccountingBreakId> after, int limit) {
        // PostgreSQL's uuid order - unsigned, big-endian - which the ids' birth order follows (UUIDv7).
        Comparator<BreakRow> byId = (left, right) -> compare(left.id().value(), right.id().value());
        return rows.values().stream()
                .filter(row -> row.period().equals(period))
                .filter(row -> status.map(wanted -> row.status() == wanted).orElse(true))
                .filter(row -> after.map(cursor -> compare(row.id().value(), cursor.value()) > 0).orElse(true))
                .sorted(byId)
                .limit(limit)
                .toList();
    }

    @Override
    public Map<String, Held> held(Connection unitOfWork, YearMonth period, CloseCheckCode check) {
        Map<String, Held> held = new TreeMap<>();
        rows.values().stream().filter(row -> row.period().equals(period) && row.check() == check)
                .filter(row -> !held.containsKey(row.subject()) || held.get(row.subject()).generation() < row.generation())
                .forEach(row -> held.merge(row.subject(), new Held(row.status(), row.evidence(), row.generation()),
                        (kept, later) -> later.generation() > kept.generation() ? later : kept));
        return held;
    }

    private static int compare(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    @Override
    public Map<BreakType, Long> openByType(Connection unitOfWork) {
        Map<BreakType, Long> open = new EnumMap<>(BreakType.class);
        rows.values().stream().filter(row -> row.status() != BreakStatus.RESOLVED)
                .forEach(row -> open.merge(row.type(), 1L, Long::sum));
        return open;
    }

    @Override
    public Map<BreakStatus, Long> countByStatus(Connection unitOfWork, YearMonth period) {
        Map<BreakStatus, Long> counts = new EnumMap<>(BreakStatus.class);
        rows.values().stream().filter(row -> row.period().equals(period))
                .forEach(row -> counts.merge(row.status(), 1L, Long::sum));
        return counts;
    }

    @Override
    public boolean proposeAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String disclosure) {
        BreakRow row = rows.get(id);
        if (row == null || row.status() != BreakStatus.OPEN || row.ackProposedBy().isPresent()) {
            return false;
        }
        rows.put(id, new BreakRow(row.id(), row.period(), row.check(), row.type(), row.subject(), row.generation(), row.evidence(),
                row.judgedBy(), row.status(), row.raisedAt(), Optional.of(actor.id()), Optional.of(now()),
                Optional.of(disclosure), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()));
        return true;
    }

    @Override
    public boolean approveAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String reason) {
        BreakRow row = rows.get(id);
        if (row == null || row.status() != BreakStatus.OPEN || row.ackProposedBy().isEmpty()
                || row.ackApprovedBy().isPresent()) {
            return false;
        }
        if (row.ackProposedBy().get().equals(actor.id())) {
            throw new IllegalStateException("accounting_break_acknowledgement_four_eyes: the approver proposed it");
        }
        rows.put(id, new BreakRow(row.id(), row.period(), row.check(), row.type(), row.subject(), row.generation(), row.evidence(),
                row.judgedBy(), BreakStatus.ACKNOWLEDGED, row.raisedAt(), row.ackProposedBy(), row.ackProposedAt(),
                row.disclosure(), Optional.of(actor.id()), Optional.of(now()), Optional.empty(), Optional.empty(),
                Optional.empty()));
        return true;
    }

    @Override
    public boolean resolve(Connection unitOfWork, AccountingBreakId id, BreakStatus from, Actor actor,
            UUID correctingEntry, String reason) {
        BreakRow row = rows.get(id);
        if (row == null || row.status() != from || from == BreakStatus.RESOLVED) {
            return false;
        }
        rows.put(id, new BreakRow(row.id(), row.period(), row.check(), row.type(), row.subject(), row.generation(), row.evidence(),
                row.judgedBy(), BreakStatus.RESOLVED, row.raisedAt(), row.ackProposedBy(), row.ackProposedAt(),
                row.disclosure(), row.ackApprovedBy(), row.ackApprovedAt(), Optional.of(actor.id()), Optional.of(now()),
                Optional.of(correctingEntry)));
        return true;
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow row) {
        if (!rows.containsKey(row.breakId())) {
            throw new IllegalStateException("break_event_break_fk: the case file names a break");
        }
        if (row.proposedBy().isPresent() && row.proposedBy().get().equals(row.actor().id())) {
            throw new IllegalStateException("break_event_four_eyes: the acknowledgement names its proposer, never the"
                    + " approver");
        }
        history.add(row);
    }

    private Instant now() {
        return Instant.EPOCH.plusSeconds(clock.incrementAndGet());
    }
}
