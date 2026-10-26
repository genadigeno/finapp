package com.finapp.risk.testing;

import com.finapp.risk.RescreeningPermit;
import com.finapp.risk.RescreeningPermitId;
import com.finapp.risk.RescreeningPermitStore;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersionStatus;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link RescreeningPermitStore} in memory for the hermetic suites (`P13-TSK-022`) - {@code risk V018}'s arbiters as the
 * statements apply them: one permit per (party, version) ({@code rescreening_permit_once} - a second is no row), born only
 * for a version in force, the claim only of a permit that has come on the database clock {@link #now} and that no other
 * claimer holds ({@link #held}, the {@code SKIP LOCKED} a suite's fake transaction ends with {@link #release}), moved a
 * lease ahead with one attempt counted, every conclusion conditional on {@code PENDING} and the claimed attempt. Reads join
 * the versions of {@code lists}. {@link #snapshot}/{@link #restore} let a suite's fake transaction roll a failed act back.
 */
public final class InMemoryRescreeningPermitStore implements RescreeningPermitStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-26T09:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    /** The permits a claim or a lock holds until its fake transaction ends. */
    public final Set<RescreeningPermitId> held = new HashSet<>();

    private final InMemoryScreeningListStore lists;

    private Map<RescreeningPermitId, Row> rows = new LinkedHashMap<>();

    private record Row(RescreeningPermitId id, java.util.UUID party, com.finapp.risk.ScreeningListVersionId version,
            RescreeningPermit.Status status, Optional<RescreeningPermit.Outcome> outcome,
            Optional<RescreeningPermit.Cause> cause, Optional<RiskSignalId> signal, Optional<Instant> next, int attempts,
            String correlationId) {}

    public InMemoryRescreeningPermitStore(InMemoryScreeningListStore lists) {
        this.lists = Objects.requireNonNull(lists, "lists");
    }

    /** Every permit, in enqueue order. */
    public List<RescreeningPermit> all() {
        return rows.values().stream().map(this::permit).toList();
    }

    /** A copy of the rows - a fake transaction's begin; opaque to the suite. */
    public Object snapshot() {
        return new LinkedHashMap<>(rows);
    }

    /** The rows put back as {@code snapshot} held them - a fake transaction's rollback. */
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        rows = new LinkedHashMap<>((Map<RescreeningPermitId, Row>) snapshot);
    }

    /** A fake transaction's end: every claim's row lock released. */
    public void release() {
        held.clear();
    }

    @Override
    public boolean enqueue(Connection unitOfWork, NewPermit permit) {
        ScreeningListVersion version = lists.get(permit.listVersionId());
        if (version == null || version.status() != ScreeningListVersionStatus.ACTIVE) {
            throw new IllegalStateException("a rescreening permit is enqueued for a screening list version in force");
        }
        if (rows.values().stream().anyMatch(row -> row.party().equals(permit.partyId())
                && row.version().equals(permit.listVersionId()))) {
            log.add("enqueue conflict");
            return false;
        }
        if (rows.containsKey(permit.id())) {
            throw new IllegalStateException("a second permit under one id: the primary key refuses it");
        }
        rows.put(permit.id(), new Row(permit.id(), permit.partyId(), permit.listVersionId(),
                RescreeningPermit.Status.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(now),
                0, permit.correlationId()));
        log.add("enqueue");
        return true;
    }

    @Override
    public Optional<RescreeningPermit> claimNextDue(Connection unitOfWork, Duration lease) {
        Optional<Row> due = rows.values().stream()
                .filter(row -> row.status() == RescreeningPermit.Status.PENDING)
                .filter(row -> !row.next().orElseThrow().isAfter(now))
                .filter(row -> !held.contains(row.id()))
                .min(Comparator.comparing((Row row) -> row.next().orElseThrow()).thenComparing(row -> row.id().value()));
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Row row = due.get();
        Row claimed = new Row(row.id(), row.party(), row.version(), row.status(), row.outcome(), row.cause(),
                row.signal(), Optional.of(now.plus(lease)), row.attempts() + 1, row.correlationId());
        rows.put(row.id(), claimed);
        held.add(row.id());
        log.add("claim " + claimed.attempts());
        return Optional.of(permit(claimed));
    }

    @Override
    public Optional<RescreeningPermit> lock(Connection unitOfWork, RescreeningPermitId id) {
        log.add("lock");
        held.add(id);
        return find(unitOfWork, id);
    }

    @Override
    public Optional<RescreeningPermit> find(Connection unitOfWork, RescreeningPermitId id) {
        return Optional.ofNullable(rows.get(id)).map(this::permit);
    }

    @Override
    public boolean conclude(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Outcome outcome,
            Optional<RiskSignalId> signal) {
        Row row = pendingAt(id, attempt);
        if (row == null) {
            return false;
        }
        rows.put(id, new Row(row.id(), row.party(), row.version(), RescreeningPermit.Status.DONE, Optional.of(outcome),
                Optional.empty(), signal, Optional.empty(), row.attempts(), row.correlationId()));
        log.add("conclude " + outcome);
        return true;
    }

    @Override
    public boolean fail(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Cause cause) {
        Row row = pendingAt(id, attempt);
        if (row == null) {
            return false;
        }
        rows.put(id, new Row(row.id(), row.party(), row.version(), RescreeningPermit.Status.FAILED, Optional.empty(),
                Optional.of(cause), Optional.empty(), Optional.empty(), row.attempts(), row.correlationId()));
        log.add("fail " + cause);
        return true;
    }

    @Override
    public boolean defer(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Cause cause,
            Duration backoff) {
        Row row = pendingAt(id, attempt);
        if (row == null) {
            return false;
        }
        rows.put(id, new Row(row.id(), row.party(), row.version(), row.status(), Optional.empty(), Optional.of(cause),
                Optional.empty(), Optional.of(now.plus(backoff)), row.attempts(), row.correlationId()));
        log.add("defer " + cause + " " + backoff);
        return true;
    }

    @Override
    public long pending(Connection unitOfWork, long cap) {
        return Math.min(cap, rows.values().stream().filter(row -> row.status() == RescreeningPermit.Status.PENDING)
                .count());
    }

    private Row pendingAt(RescreeningPermitId id, int attempt) {
        Row row = rows.get(id);
        return row != null && row.status() == RescreeningPermit.Status.PENDING && row.attempts() == attempt ? row : null;
    }

    private RescreeningPermit permit(Row row) {
        ScreeningListVersion version = lists.get(row.version());
        return new RescreeningPermit(row.id(), row.party(), row.version(), version.list(),
                version.activatedAt().orElseThrow(), row.status(), row.outcome(), row.cause(), row.signal(), row.next(),
                row.attempts(), row.correlationId());
    }
}
