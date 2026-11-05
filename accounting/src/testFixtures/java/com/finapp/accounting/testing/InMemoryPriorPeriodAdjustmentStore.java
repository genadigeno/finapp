package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PriorPeriodAdjustmentId;
import com.finapp.accounting.PriorPeriodAdjustmentStatus;
import com.finapp.accounting.PriorPeriodAdjustmentStore;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link PriorPeriodAdjustmentStore} in memory, its one row per adjustment and the conditions of {@code accounting V009} it can hold: one proposal, one entry, four eyes, a conditional edge. Locks are counted, not held. */
public final class InMemoryPriorPeriodAdjustmentStore implements PriorPeriodAdjustmentStore {

    private final AccountingPeriodStore periods;
    private final Map<PriorPeriodAdjustmentId, Row> rows = new LinkedHashMap<>();
    private long stamps;

    public InMemoryPriorPeriodAdjustmentStore(AccountingPeriodStore periods) {
        this.periods = periods;
    }

    /** Every row, in birth order. */
    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    /** The store as it stands, restored by the runnable - a rolled-back transaction. */
    public Runnable savepoint() {
        Map<PriorPeriodAdjustmentId, Row> now = new LinkedHashMap<>(rows);
        return () -> {
            rows.clear();
            rows.putAll(now);
        };
    }

    @Override
    public void insert(Connection unitOfWork, NewAdjustment adjustment) {
        if (rows.values().stream().anyMatch(row -> row.proposal().equals(adjustment.proposal()))) {
            throw new IllegalStateException("prior_period_adjustment_one_proposal");
        }
        AccountingPeriodStore.PeriodRow corrected = periods.find(unitOfWork, adjustment.corrected()).orElseThrow();
        AccountingPeriodStore.PeriodRow target = periods.find(unitOfWork, adjustment.target()).orElseThrow();
        rows.put(adjustment.id(), new Row(adjustment.id(), adjustment.corrected(), corrected.month(), adjustment.correctedRevision(), adjustment.target(), target.month(), adjustment.targetRevision(), adjustment.postingDate(),
                adjustment.proposal(), Optional.empty(), PriorPeriodAdjustmentStatus.PROPOSED, adjustment.proposedBy().id(), Instant.ofEpochSecond(++stamps), adjustment.reason(), Optional.empty(),
                Optional.empty(), adjustment.correlation()));
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, PriorPeriodAdjustmentId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, PriorPeriodAdjustmentId id) {
        return find(unitOfWork, id);
    }

    @Override
    public List<Row> lockCorrectionsOf(Connection unitOfWork, AccountingPeriodId corrected) {
        return ofCorrected(unitOfWork, corrected).stream().filter(row -> row.status() != PriorPeriodAdjustmentStatus.REJECTED).toList();
    }

    @Override
    public List<Row> ofCorrected(Connection unitOfWork, AccountingPeriodId corrected) {
        List<Row> found = new ArrayList<>();
        rows.values().stream().filter(row -> row.correctedPeriod().equals(corrected)).forEach(found::add);
        return found;
    }

    @Override
    public long standingPast(Connection unitOfWork, java.time.Duration bound) {
        Instant at = Instant.ofEpochSecond(stamps);
        return rows.values().stream().filter(row -> row.status() == PriorPeriodAdjustmentStatus.PROPOSED && !row.proposedAt().plus(bound).isAfter(at)).count();
    }

    /** Moves what stands for the database's clock - a suite's way past a bound (`P14-TSK-020`). */
    public void advance(java.time.Duration by) {
        stamps += by.toSeconds();
    }

    @Override
    public List<Row> pending(Connection unitOfWork, int limit) {
        return rows.values().stream().filter(row -> row.status() == PriorPeriodAdjustmentStatus.PROPOSED).limit(limit).toList();
    }

    @Override
    public boolean markPosted(Connection unitOfWork, PriorPeriodAdjustmentId id, UUID entry, Actor actor, String reason) {
        Row row = rows.get(id);
        if (row == null || row.status() != PriorPeriodAdjustmentStatus.PROPOSED) {
            return false;
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new IllegalStateException("prior_period_adjustment_four_eyes");
        }
        if (rows.values().stream().anyMatch(other -> other.entry().filter(entry::equals).isPresent())) {
            throw new IllegalStateException("prior_period_adjustment_one_entry");
        }
        rows.put(id, decided(row, PriorPeriodAdjustmentStatus.POSTED, Optional.of(entry), actor));
        return true;
    }

    @Override
    public boolean markRejected(Connection unitOfWork, PriorPeriodAdjustmentId id, Actor actor, String reason) {
        Row row = rows.get(id);
        if (row == null || row.status() != PriorPeriodAdjustmentStatus.PROPOSED) {
            return false;
        }
        rows.put(id, decided(row, PriorPeriodAdjustmentStatus.REJECTED, Optional.empty(), actor));
        return true;
    }

    private Row decided(Row row, PriorPeriodAdjustmentStatus status, Optional<UUID> entry, Actor actor) {
        return new Row(row.id(), row.correctedPeriod(), row.correctedMonth(), row.correctedRevision(), row.targetPeriod(), row.targetMonth(), row.targetRevision(), row.postingDate(), row.proposal(), entry, status, row.proposedBy(),
                row.proposedAt(), row.proposalReason(), Optional.of(actor.id()), Optional.of(Instant.ofEpochSecond(++stamps)), row.correlation());
    }
}
