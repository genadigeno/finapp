package com.finapp.accounting.testing;

import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryObligationStatus;
import com.finapp.accounting.RegulatoryObligationStore;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RegulatoryObligationStore} in memory (`P14-TSK-018`), holding the ranks of {@code accounting V010} the machine relies on: one version per form, one in force per form, four eyes, the edges. A defect the database would refuse is refused here too, as an
 * {@link IllegalStateException} naming the rank. The real ranks are V010's, proven by {@code RegulatorySubmissionDatabaseTest} (owed, C5).
 */
public final class InMemoryRegulatoryObligationStore implements RegulatoryObligationStore {

    private final Clock clock;
    private final ConcurrentHashMap<RegulatoryObligationId, Row> rows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegulatoryObligationId> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegulatoryObligationId> active = new ConcurrentHashMap<>();

    public InMemoryRegulatoryObligationStore(Clock clock) {
        this.clock = clock;
    }

    /** Every row. */
    public List<Row> all() {
        return rows.values().stream().sorted(Comparator.comparing(row -> row.id().value())).toList();
    }

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewObligation obligation) {
        if (versions.putIfAbsent(obligation.jurisdiction() + "|" + obligation.form() + "|" + obligation.formVersion(), obligation.id()) != null) {
            return false;
        }
        rows.put(obligation.id(), new Row(obligation.id(), obligation.jurisdiction(), obligation.form(), obligation.formVersion(), "MONTH", obligation.adapter(), "REFERENCE", obligation.configuration(), obligation.configurationSha256(),
                RegulatoryObligationStatus.PROPOSED, obligation.proposedBy().id(), clock.instant(), obligation.reason(), Optional.empty(), Optional.empty(), Optional.empty(), obligation.correlation()));
        return true;
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, RegulatoryObligationId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Row> lockShared(Connection unitOfWork, RegulatoryObligationId id) {
        return find(unitOfWork, id);
    }

    @Override
    public List<Row> lockForm(Connection unitOfWork, String jurisdiction, String form) {
        return rows.values().stream().filter(row -> row.jurisdiction().equals(jurisdiction) && row.form().equals(form)).sorted(Comparator.comparing(row -> row.id().value())).toList();
    }

    @Override
    public List<Row> recent(Connection unitOfWork, int limit) {
        return rows.values().stream().sorted(Comparator.comparing(Row::proposedAt).reversed()).limit(limit).toList();
    }

    @Override
    public List<Row> awaitingActivation(Connection unitOfWork, String reader, int limit) {
        return rows.values().stream().filter(row -> row.status() == RegulatoryObligationStatus.PROPOSED && !row.proposedBy().equals(reader)).sorted(Comparator.comparing(Row::proposedAt)).limit(limit).toList();
    }

    @Override
    public boolean markActive(Connection unitOfWork, RegulatoryObligationId id, Actor actor, String reason) {
        Row row = rows.get(id);
        if (row == null || row.status() != RegulatoryObligationStatus.PROPOSED) {
            return false;
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new IllegalStateException("regulatory_obligation_four_eyes");
        }
        if (active.putIfAbsent(row.jurisdiction() + "|" + row.form(), id) != null) {
            throw new IllegalStateException("regulatory_obligation_one_active");
        }
        rows.put(id, decided(row, RegulatoryObligationStatus.ACTIVE, actor));
        return true;
    }

    @Override
    public boolean markRejected(Connection unitOfWork, RegulatoryObligationId id, Actor actor, String reason) {
        Row row = rows.get(id);
        if (row == null || row.status() != RegulatoryObligationStatus.PROPOSED) {
            return false;
        }
        rows.put(id, decided(row, RegulatoryObligationStatus.REJECTED, actor));
        return true;
    }

    @Override
    public boolean markRetired(Connection unitOfWork, RegulatoryObligationId id) {
        Row row = rows.get(id);
        if (row == null || row.status() != RegulatoryObligationStatus.ACTIVE) {
            return false;
        }
        active.remove(row.jurisdiction() + "|" + row.form(), id);
        rows.put(id, new Row(row.id(), row.jurisdiction(), row.form(), row.formVersion(), row.periodType(), row.adapter(), row.standing(), row.configuration(), row.configurationSha256(), RegulatoryObligationStatus.RETIRED, row.proposedBy(),
                row.proposedAt(), row.proposalReason(), row.decidedBy(), row.decidedAt(), Optional.of(clock.instant()), row.correlation()));
        return true;
    }

    private Row decided(Row row, RegulatoryObligationStatus status, Actor actor) {
        Instant now = clock.instant();
        return new Row(row.id(), row.jurisdiction(), row.form(), row.formVersion(), row.periodType(), row.adapter(), row.standing(), row.configuration(), row.configurationSha256(), status, row.proposedBy(), row.proposedAt(),
                row.proposalReason(), Optional.of(actor.id()), Optional.of(now), Optional.empty(), row.correlation());
    }
}
