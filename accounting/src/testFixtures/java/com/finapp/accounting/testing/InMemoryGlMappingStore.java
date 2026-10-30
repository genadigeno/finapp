package com.finapp.accounting.testing;

import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link GlMappingStore} in memory (`P14-TSK-003`): the hermetic suites' store, holding the database's ranks the
 * administration relies on - the four-eyes {@code CHECK}, one {@code ACTIVE}, content born once, edges only from their
 * states - so a defect in the administration that the database would refuse is refused here too, as an
 * {@link IllegalStateException} naming the rank. The real ranks are {@code accounting V002}'s, proven by
 * {@code GlMappingDatabaseTest} (owed, C5).
 */
public final class InMemoryGlMappingStore implements GlMappingStore {

    private final Map<GlMappingVersionId, VersionRow> rows = new LinkedHashMap<>();
    private final Map<GlMappingVersionId, GlMappingVersion> contents = new LinkedHashMap<>();
    private final List<HistoryRow> history = new ArrayList<>();
    private int locks;

    /** How many times A2 was taken. */
    public int locks() {
        return locks;
    }

    /** The history, in order. */
    public List<HistoryRow> history() {
        return List.copyOf(history);
    }

    /** One row as stored. */
    public VersionRow row(GlMappingVersionId id) {
        return rows.get(id);
    }

    @Override
    public void lockMapping(Connection unitOfWork) {
        locks++;
    }

    @Override
    public int maxVersion(Connection unitOfWork) {
        return rows.values().stream().mapToInt(VersionRow::version).max().orElse(0);
    }

    @Override
    public void insertProposal(Connection unitOfWork, NewProposal proposal) {
        if (rows.values().stream().anyMatch(row -> row.version() == proposal.version())) {
            throw new IllegalStateException("gl_mapping_version_number_unique");
        }
        rows.put(proposal.id(), new VersionRow(proposal.id(), proposal.version(), GlMappingVersionStatus.PROPOSED,
                proposal.content().effectiveFrom(), Optional.empty(), proposal.actor().id(), Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty()));
        contents.put(proposal.id(), proposal.content());
        appendEvent(unitOfWork, new HistoryRow(proposal.historyId(), proposal.id(), Optional.empty(),
                GlMappingVersionStatus.PROPOSED, proposal.actor(), proposal.reason(), proposal.correlation()));
    }

    @Override
    public Optional<VersionRow> find(Connection unitOfWork, GlMappingVersionId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, GlMappingVersionId id) {
        return find(unitOfWork, id);
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork) {
        return active(unitOfWork);
    }

    @Override
    public Optional<VersionRow> active(Connection unitOfWork) {
        return rows.values().stream().filter(row -> row.status() == GlMappingVersionStatus.ACTIVE).findFirst();
    }

    @Override
    public Optional<YearMonth> latestEffectiveFrom(Connection unitOfWork) {
        return rows.values().stream()
                .filter(row -> row.status() == GlMappingVersionStatus.ACTIVE || row.status() == GlMappingVersionStatus.RETIRED)
                .map(VersionRow::effectiveFrom).max(Comparator.naturalOrder());
    }

    @Override
    public GlMappingVersion content(Connection unitOfWork, GlMappingVersionId id) {
        return contents.get(id);
    }

    @Override
    public boolean retire(Connection unitOfWork, GlMappingVersionId id, YearMonth endsAt) {
        VersionRow row = rows.get(id);
        if (row == null || row.status() != GlMappingVersionStatus.ACTIVE) {
            return false;
        }
        if (endsAt.isBefore(row.effectiveFrom())) {
            throw new IllegalStateException("gl_mapping_version_effective_coherent");
        }
        rows.put(id, new VersionRow(id, row.version(), GlMappingVersionStatus.RETIRED, row.effectiveFrom(),
                Optional.of(endsAt), row.proposedBy(), row.proposedAt(), row.decidedBy(), row.decidedAt(),
                Optional.of(Instant.EPOCH)));
        return true;
    }

    @Override
    public boolean decide(
            Connection unitOfWork, GlMappingVersionId id, GlMappingVersionStatus to, Actor actor, String reason) {
        VersionRow row = rows.get(id);
        if (row == null || row.status() != GlMappingVersionStatus.PROPOSED) {
            return false;
        }
        if (to == GlMappingVersionStatus.ACTIVE && actor.id().equals(row.proposedBy())) {
            throw new IllegalStateException("gl_mapping_version_four_eyes");
        }
        if (to == GlMappingVersionStatus.ACTIVE && active(unitOfWork).isPresent()) {
            throw new IllegalStateException("gl_mapping_one_active");
        }
        rows.put(id, new VersionRow(id, row.version(), to, row.effectiveFrom(), Optional.empty(), row.proposedBy(),
                row.proposedAt(), Optional.of(actor.id()), Optional.of(Instant.EPOCH), Optional.empty()));
        return true;
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        history.add(row);
    }
}
