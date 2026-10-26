package com.finapp.risk.testing;

import com.finapp.platform.security.Actor;
import com.finapp.risk.RiskCase;
import com.finapp.risk.ScreeningList;
import com.finapp.risk.ScreeningListStore;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersionId;
import com.finapp.risk.ScreeningListVersionStatus;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link ScreeningListStore} in memory for the hermetic suites (`P13-TSK-022`) - {@code risk V017}'s arbiters as the
 * statements apply them: the label's unique (a second recording of a label is no row), every edge conditional on the
 * state it leaves, the stamps the database clock {@link #now} (which the suite moves), the enqueue's claim only of a
 * version whose permit has come and that no other claimer holds - {@link #held}, the {@code SKIP LOCKED} a suite's fake
 * transaction ends with {@link #release}. <strong>Not modelled, deliberately</strong>: the four-eyes and the persons'
 * {@code CHECK}s and the trigger's ordinal rule - the domain's own refusals are what the hermetic suites prove, each alone;
 * the database's are {@code RescreeningDatabaseTest}'s (written and owed, C5). {@link #log} records each operation.
 */
public final class InMemoryScreeningListStore implements ScreeningListStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-26T09:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    /** The versions a claim holds until its fake transaction ends - what {@code SKIP LOCKED} skips. */
    public final Set<ScreeningListVersionId> held = new HashSet<>();

    private Map<ScreeningListVersionId, ScreeningListVersion> rows = new LinkedHashMap<>();

    /** Every version stored, in insert order. */
    public List<ScreeningListVersion> all() {
        return List.copyOf(rows.values());
    }

    /** The version {@code id}, as it now stands. */
    public ScreeningListVersion get(ScreeningListVersionId id) {
        return rows.get(id);
    }

    /** A copy of the rows - a fake transaction's begin. */
    public Map<ScreeningListVersionId, ScreeningListVersion> snapshot() {
        return new LinkedHashMap<>(rows);
    }

    /** The rows put back as {@code snapshot} held them - a fake transaction's rollback. */
    public void restore(Map<ScreeningListVersionId, ScreeningListVersion> snapshot) {
        rows = new LinkedHashMap<>(snapshot);
    }

    /** A fake transaction's end: every claim's row lock released. */
    public void release() {
        held.clear();
    }

    @Override
    public void lockList(Connection unitOfWork, ScreeningList list) {
        log.add("lock list " + list);
    }

    @Override
    public int maxOrdinal(Connection unitOfWork, ScreeningList list) {
        return rows.values().stream().filter(row -> row.list() == list).mapToInt(ScreeningListVersion::ordinal).max()
                .orElse(0);
    }

    @Override
    public Optional<ScreeningListVersion> insert(Connection unitOfWork, NewVersion version) {
        log.add("insert " + version.list() + " " + version.version());
        if (findByLabel(unitOfWork, version.list(), version.version()).isPresent()) {
            return Optional.empty();
        }
        if (rows.containsKey(version.id())) {
            throw new IllegalStateException("a second version under one id: the primary key refuses it");
        }
        ScreeningListVersion born = new ScreeningListVersion(version.id(), version.list(), version.version(),
                version.ordinal(), ScreeningListVersionStatus.RECORDED, RiskCase.Person.of(version.recorder()), now,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), version.correlationId());
        rows.put(born.id(), born);
        return Optional.of(born);
    }

    @Override
    public Optional<ScreeningListVersion> find(Connection unitOfWork, ScreeningListVersionId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<ScreeningListVersion> findByLabel(Connection unitOfWork, ScreeningList list, String version) {
        return rows.values().stream().filter(row -> row.list() == list && row.version().equals(version)).findFirst();
    }

    @Override
    public Optional<ScreeningListVersion> lock(Connection unitOfWork, ScreeningListVersionId id) {
        log.add("lock " + id.value());
        return find(unitOfWork, id);
    }

    @Override
    public Optional<ScreeningListVersion> lockActive(Connection unitOfWork, ScreeningList list) {
        log.add("lock active " + list);
        return rows.values().stream().filter(row -> row.list() == list
                && row.status() == ScreeningListVersionStatus.ACTIVE).findFirst();
    }

    @Override
    public boolean retire(Connection unitOfWork, ScreeningListVersionId id) {
        log.add("retire " + id.value());
        ScreeningListVersion row = rows.get(id);
        if (row == null || row.status() != ScreeningListVersionStatus.ACTIVE) {
            return false;
        }
        rows.put(id, new ScreeningListVersion(row.id(), row.list(), row.version(), row.ordinal(),
                ScreeningListVersionStatus.RETIRED, row.recordedBy(), row.recordedAt(), row.activatedBy(),
                row.activatedAt(), Optional.of(now), Optional.empty(), row.enqueueCursor(), row.enqueued(),
                row.enqueueCompletedAt(), row.correlationId()));
        return true;
    }

    @Override
    public Optional<ScreeningListVersion> activate(Connection unitOfWork, ScreeningListVersionId id, Actor activator,
            String correlationId) {
        log.add("activate " + id.value());
        ScreeningListVersion row = rows.get(id);
        if (row == null || row.status() != ScreeningListVersionStatus.RECORDED) {
            return Optional.empty();
        }
        if (rows.values().stream().anyMatch(other -> other.list() == row.list()
                && other.status() == ScreeningListVersionStatus.ACTIVE)) {
            throw new IllegalStateException("a second ACTIVE version of " + row.list()
                    + ": screening_list_version_one_active refuses it");
        }
        ScreeningListVersion activated = new ScreeningListVersion(row.id(), row.list(), row.version(), row.ordinal(),
                ScreeningListVersionStatus.ACTIVE, row.recordedBy(), row.recordedAt(),
                Optional.of(RiskCase.Person.of(activator)), Optional.of(now), Optional.empty(), Optional.of(now),
                Optional.empty(), 0, Optional.empty(), correlationId);
        rows.put(id, activated);
        return Optional.of(activated);
    }

    @Override
    public Optional<ScreeningListVersion> claimEnqueueDue(Connection unitOfWork) {
        Optional<ScreeningListVersion> due = rows.values().stream()
                .filter(row -> row.status() == ScreeningListVersionStatus.ACTIVE)
                .filter(row -> row.enqueueNextAt().filter(next -> !next.isAfter(now)).isPresent())
                .filter(row -> !held.contains(row.id()))
                .min(Comparator.comparing((ScreeningListVersion row) -> row.enqueueNextAt().orElseThrow())
                        .thenComparing(row -> row.id().value()));
        due.ifPresent(row -> {
            held.add(row.id());
            log.add("claim enqueue " + row.id().value());
        });
        return due;
    }

    @Override
    public ScreeningListVersion advanceEnqueue(Connection unitOfWork, ScreeningListVersionId id, Optional<UUID> cursor,
            int born, boolean complete) {
        log.add("advance " + id.value() + " +" + born + (complete ? " complete" : ""));
        ScreeningListVersion row = rows.get(id);
        if (row == null || !row.enqueuing()) {
            throw new IllegalStateException("the claimed list version's enqueue moved under its own row lock");
        }
        if (row.enqueueCursor().isPresent() && (cursor.isEmpty() || cursor.get().compareTo(row.enqueueCursor().get()) < 0)) {
            throw new IllegalStateException("a list version's enqueue only moves forward");
        }
        ScreeningListVersion advanced = new ScreeningListVersion(row.id(), row.list(), row.version(), row.ordinal(),
                row.status(), row.recordedBy(), row.recordedAt(), row.activatedBy(), row.activatedAt(), row.retiredAt(),
                complete ? Optional.empty() : Optional.of(now), cursor, row.enqueued() + born,
                complete ? Optional.of(now) : Optional.empty(), row.correlationId());
        rows.put(id, advanced);
        return advanced;
    }
}
