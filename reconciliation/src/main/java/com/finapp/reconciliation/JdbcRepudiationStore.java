package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * {@link RepudiationStore} over explicit JDBC (`P8-TSK-023`). Every locking read orders its
 * rows by id, so two writers taking the same rows take them in one order; the writes are
 * conditional where a machine edge is at stake and loud when a row moved under its own lock.
 */
public final class JdbcRepudiationStore implements RepudiationStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String ITEM_COLUMNS =
            "i.id, i.run_id, i.source_id, i.status, i.line_type, i.amount_minor,"
                    + " i.allocated_minor, i.parked_minor, i.currency, i.scale";

    private static final String ALLOCATION_COLUMNS =
            "a.id, a.decision_id, a.external_item_id, a.expectation_id, a.amount_minor,"
                    + " a.currency, a.scale";

    /** Not a counter, and no counter names it. */
    private static final String STANDING =
            " a.reverses_allocation_id IS NULL AND NOT EXISTS (SELECT 1 FROM"
                    + " reconciliation.allocation c WHERE c.reverses_allocation_id = a.id)";

    @Override
    public Optional<RunFacts> runOf(Connection unitOfWork, UUID settlementBatchId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, source_id, rule_set_id, status"
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE batch_id = ? AND kind = 'BATCH'")) {
            read.setObject(1, settlementBatchId);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(
                                new RunFacts(
                                        row.getObject("id", UUID.class),
                                        row.getObject("source_id", UUID.class),
                                        row.getObject("rule_set_id", UUID.class),
                                        RunStatus.valueOf(row.getString("status"))))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the batch's run", failure);
        }
    }

    @Override
    public List<ItemRow> itemsOfRun(Connection unitOfWork, UUID runId, boolean lock) {
        return items(
                unitOfWork,
                "SELECT " + ITEM_COLUMNS + " FROM reconciliation.external_item i"
                        + " WHERE i.run_id = ? ORDER BY i.id" + (lock ? " FOR UPDATE" : ""),
                runId);
    }

    @Override
    public List<ItemRow> itemsById(Connection unitOfWork, Collection<UUID> itemIds, boolean lock) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return items(
                unitOfWork,
                "SELECT " + ITEM_COLUMNS + " FROM reconciliation.external_item i"
                        + " WHERE i.id = ANY (?) ORDER BY i.id" + (lock ? " FOR UPDATE" : ""),
                itemIds);
    }

    private static List<ItemRow> items(Connection unitOfWork, String sql, Object parameter) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            bind(unitOfWork, read, 1, parameter);
            List<ItemRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new ItemRow(
                                    row.getObject("id", UUID.class),
                                    row.getObject("run_id", UUID.class),
                                    row.getObject("source_id", UUID.class),
                                    ItemStatus.valueOf(row.getString("status")),
                                    row.getString("line_type"),
                                    row.getLong("amount_minor"),
                                    row.getLong("allocated_minor"),
                                    row.getLong("parked_minor"),
                                    row.getString("currency").trim(),
                                    row.getInt("scale")));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the items", failure);
        }
    }

    @Override
    public List<AllocationRow> standingAllocationsOfRun(Connection unitOfWork, UUID runId) {
        return allocations(
                unitOfWork,
                "SELECT " + ALLOCATION_COLUMNS + " FROM reconciliation.allocation a"
                        + " JOIN reconciliation.external_item i ON i.id = a.external_item_id"
                        + " WHERE i.run_id = ? AND" + STANDING + " ORDER BY a.id",
                runId,
                null);
    }

    @Override
    public List<AllocationRow> standingAllocationsTo(
            Connection unitOfWork, UUID expectationId, UUID excludingRunId) {
        return allocations(
                unitOfWork,
                "SELECT " + ALLOCATION_COLUMNS + " FROM reconciliation.allocation a"
                        + " JOIN reconciliation.external_item i ON i.id = a.external_item_id"
                        + " WHERE a.expectation_id = ? AND i.run_id <> ? AND" + STANDING
                        + " ORDER BY a.id",
                expectationId,
                excludingRunId);
    }

    private static List<AllocationRow> allocations(
            Connection unitOfWork, String sql, UUID first, UUID second) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, first);
            if (second != null) {
                read.setObject(2, second);
            }
            List<AllocationRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new AllocationRow(
                                    row.getObject("id", UUID.class),
                                    row.getObject("decision_id", UUID.class),
                                    row.getObject("external_item_id", UUID.class),
                                    row.getObject("expectation_id", UUID.class),
                                    row.getLong("amount_minor"),
                                    row.getString("currency").trim(),
                                    row.getInt("scale")));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the allocations", failure);
        }
    }

    @Override
    public Optional<UUID> remittanceOf(Connection unitOfWork, UUID settlementBatchId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.expectation"
                                + " WHERE kind = 'REMITTANCE' AND operation_ref = ?")) {
            read.setString(1, settlementBatchId.toString());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("id", UUID.class))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the batch's remittance", failure);
        }
    }

    @Override
    public List<ExpectationRow> expectationsById(
            Connection unitOfWork, Collection<UUID> expectationIds, boolean lock) {
        if (expectationIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, status, direction, amount_minor, allocated_minor,"
                                + " resolved_minor, source_id, rule_set_id,"
                                + " overdue_since IS NOT NULL AS overdue, currency, scale"
                                + " FROM reconciliation.expectation"
                                + " WHERE id = ANY (?) ORDER BY id"
                                + (lock ? " FOR UPDATE" : ""))) {
            bind(unitOfWork, read, 1, expectationIds);
            List<ExpectationRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new ExpectationRow(
                                    row.getObject("id", UUID.class),
                                    row.getString("kind"),
                                    ExpectationStatus.valueOf(row.getString("status")),
                                    ExpectationDirection.valueOf(row.getString("direction")),
                                    row.getLong("amount_minor"),
                                    row.getLong("allocated_minor"),
                                    row.getLong("resolved_minor"),
                                    row.getObject("source_id", UUID.class),
                                    row.getObject("rule_set_id", UUID.class),
                                    row.getBoolean("overdue"),
                                    row.getString("currency").trim(),
                                    row.getInt("scale")));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the expectations", failure);
        }
    }

    @Override
    public List<SuspenseRow> suspenseOfItems(
            Connection unitOfWork, Collection<UUID> itemIds, boolean lock) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT s.id, s.break_id, b.source_id AS break_source_id,"
                                + " s.external_item_id, s.origin, s.side, s.amount_minor,"
                                + " s.released_minor, s.currency, s.scale, s.position_account_id,"
                                // Released elsewhere and not yet answered: an earlier
                                // repudiation's REPUDIATION item names this row once.
                                + " CASE WHEN EXISTS (SELECT 1 FROM reconciliation.suspense_item a"
                                + " WHERE a.origin = 'REPUDIATION' AND a.origin_ref = s.id::text)"
                                + " THEN 0 ELSE COALESCE((SELECT SUM(r.amount_minor)"
                                + " FROM reconciliation.suspense_release r WHERE r.item_id = s.id"
                                + " AND r.cause IN ('RESOLUTION', 'OFFSET_SUSPENSE')), 0) END"
                                + " AS released_elsewhere_minor,"
                                + " COALESCE((SELECT SUM(r.amount_minor)"
                                + " FROM reconciliation.suspense_release r WHERE r.item_id = s.id"
                                + " AND r.cause = 'CORRECTION_OFFSET'), 0) AS corrected_minor"
                                + " FROM reconciliation.suspense_item s"
                                + " JOIN reconciliation.break b ON b.id = s.break_id"
                                + " WHERE s.external_item_id = ANY (?) ORDER BY s.id"
                                + (lock ? " FOR UPDATE OF s" : ""))) {
            bind(unitOfWork, read, 1, itemIds);
            List<SuspenseRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new SuspenseRow(
                                    row.getObject("id", UUID.class),
                                    row.getObject("break_id", UUID.class),
                                    row.getObject("break_source_id", UUID.class),
                                    Optional.ofNullable(
                                            row.getObject("external_item_id", UUID.class)),
                                    SuspenseOrigin.valueOf(row.getString("origin")),
                                    SuspenseSide.valueOf(row.getString("side")),
                                    row.getLong("amount_minor"),
                                    row.getLong("released_minor"),
                                    row.getLong("released_elsewhere_minor"),
                                    row.getLong("corrected_minor"),
                                    row.getString("currency").trim(),
                                    row.getInt("scale"),
                                    Optional.ofNullable(
                                            row.getObject("position_account_id", UUID.class))));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the items' suspense", failure);
        }
    }

    @Override
    public List<UUID> decisionsOfItems(Connection unitOfWork, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.match_decision"
                                + " WHERE external_item_id = ANY (?) ORDER BY id")) {
            bind(unitOfWork, read, 1, itemIds);
            List<UUID> ids = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    ids.add(row.getObject("id", UUID.class));
                }
            }
            return List.copyOf(ids);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the items' decisions", failure);
        }
    }

    @Override
    public List<BreakRef> openBreaks(
            Connection unitOfWork,
            Collection<UUID> itemIds,
            UUID runId,
            Optional<UUID> remittanceId,
            Collection<UUID> suspenseItemIds,
            Collection<UUID> ownerBreakIds,
            Collection<UUID> decisionIds) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, source_id, status FROM reconciliation.break"
                                + " WHERE status <> 'RESOLVED' AND (external_item_id = ANY (?)"
                                + " OR run_id = ? OR expectation_id = ? OR suspense_item_id ="
                                + " ANY (?) OR id = ANY (?) OR decision_id = ANY (?))"
                                + " ORDER BY id")) {
            bind(unitOfWork, read, 1, itemIds);
            read.setObject(2, runId);
            read.setObject(3, remittanceId.orElse(null));
            bind(unitOfWork, read, 4, suspenseItemIds);
            bind(unitOfWork, read, 5, ownerBreakIds);
            bind(unitOfWork, read, 6, decisionIds);
            List<BreakRef> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new BreakRef(
                                    row.getObject("id", UUID.class),
                                    row.getObject("source_id", UUID.class),
                                    BreakStatus.valueOf(row.getString("status"))));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the open breaks", failure);
        }
    }

    @Override
    public OptionalInt graceHours(Connection unitOfWork, UUID sourceId, String lineType) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT r.grace_hours FROM reconciliation.rule r"
                                + " JOIN reconciliation.rule_set s ON s.id = r.rule_set_id"
                                + " WHERE s.source_id = ? AND s.status = 'ACTIVE'"
                                + " AND r.line_type = ? ORDER BY r.priority LIMIT 1")) {
            read.setObject(1, sourceId);
            read.setString(2, lineType);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? OptionalInt.of(row.getInt("grace_hours")) : OptionalInt.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the grace", failure);
        }
    }

    @Override
    public Optional<UUID> lastResolvedBreakOn(
            Connection unitOfWork, UUID expectationId, BreakType type) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.break WHERE expectation_id = ?"
                                + " AND type = ? AND status = 'RESOLVED'"
                                + " ORDER BY resolved_at DESC, id DESC LIMIT 1")) {
            read.setObject(1, expectationId);
            read.setString(2, type.name());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("id", UUID.class))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation's closed break", failure);
        }
    }

    // ------------------------------------------------------------------ the resolution

    @Override
    public void insert(Connection unitOfWork, NewRepudiation repudiation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.resolution (id, break_id,"
                                + " settlement_batch_id, subject_digest, kind, status,"
                                + " reason_code, narrative, four_eyes, proposed_amount_minor,"
                                + " currency, scale, residual_version, rule_set_id,"
                                + " proposed_by, proposed_by_type, proposed_at, created_at,"
                                + " status_changed_at, correlation_id)"
                                + " VALUES (?, NULL, ?, ?, 'REPUDIATE_BATCH', 'PROPOSED',"
                                + " 'EVIDENCE_REPUDIATED', ?, true, ?, ?, ?, 0, ?, ?, ?, ?, ?,"
                                + " ?, ?)")) {
            insert.setObject(1, repudiation.id());
            insert.setObject(2, repudiation.settlementBatchId());
            insert.setBytes(3, repudiation.subjectDigest());
            insert.setString(4, repudiation.narrative());
            insert.setLong(5, repudiation.proposedAmount().minorUnits());
            insert.setString(6, repudiation.proposedAmount().currency().code());
            insert.setInt(7, repudiation.proposedAmount().scale());
            insert.setObject(8, repudiation.ruleSetId());
            insert.setString(9, repudiation.proposedBy().id());
            insert.setString(10, repudiation.proposedBy().type().name());
            insert.setTimestamp(11, Timestamp.from(repudiation.at()));
            insert.setTimestamp(12, Timestamp.from(repudiation.at()));
            insert.setTimestamp(13, Timestamp.from(repudiation.at()));
            insert.setString(14, repudiation.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new ResolutionStore.OneLiveProposal(failure);
            }
            // Never the driver's message: a CHECK violation may echo the narrative.
            throw new ReconciliationStorageException(
                    "could not store the repudiation (SQLState " + failure.getSQLState() + ")");
        }
    }

    @Override
    public Optional<RepudiationRow> byId(Connection unitOfWork, UUID resolutionId, boolean lock) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, settlement_batch_id, status, reason_code, proposed_by,"
                                + " decided_by, subject_digest, journal_entry_id, rule_set_id"
                                + " FROM reconciliation.resolution WHERE id = ?"
                                + " AND settlement_batch_id IS NOT NULL"
                                + (lock ? " FOR UPDATE" : ""))) {
            read.setObject(1, resolutionId);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(
                                new RepudiationRow(
                                        row.getObject("id", UUID.class),
                                        row.getObject("settlement_batch_id", UUID.class),
                                        ResolutionStatus.valueOf(row.getString("status")),
                                        ResolutionReasonCode.valueOf(
                                                row.getString("reason_code")),
                                        row.getString("proposed_by"),
                                        Optional.ofNullable(row.getString("decided_by")),
                                        row.getBytes("subject_digest"),
                                        Optional.ofNullable(
                                                row.getObject("journal_entry_id", UUID.class)),
                                        row.getObject("rule_set_id", UUID.class)))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the repudiation", failure);
        }
    }

    @Override
    public boolean isRepudiation(Connection unitOfWork, UUID resolutionId) {
        return byId(unitOfWork, resolutionId, false).isPresent();
    }

    // ------------------------------------------------------------------ the writes

    @Override
    public void insertCounter(
            Connection unitOfWork,
            AllocationRow original,
            UUID counterId,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.allocation (id, decision_id,"
                                + " external_item_id, expectation_id, amount_minor, currency,"
                                + " scale, reverses_allocation_id, created_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, counterId);
            insert.setObject(2, original.decisionId());
            insert.setObject(3, original.externalItemId());
            insert.setObject(4, original.expectationId());
            insert.setLong(5, original.amountMinor());
            insert.setString(6, original.currency());
            insert.setInt(7, original.scale());
            insert.setObject(8, original.id());
            insert.setTimestamp(9, Timestamp.from(at));
            insert.setString(10, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the counter-allocation", failure);
        }
    }

    @Override
    public void reopenExpectation(
            Connection unitOfWork,
            UUID expectationId,
            long amountMinor,
            String detail,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.expectation SET"
                                + " allocated_minor = allocated_minor - ?,"
                                + " status = CASE WHEN allocated_minor - ? = 0 THEN 'OPEN'"
                                + " ELSE 'PARTIALLY_SETTLED' END,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status IN ('SETTLED', 'PARTIALLY_SETTLED')"
                                + " AND resolved_minor = 0 AND allocated_minor >= ?")) {
            update.setLong(1, amountMinor);
            update.setLong(2, amountMinor);
            update.setTimestamp(3, Timestamp.from(at));
            update.setObject(4, expectationId);
            update.setLong(5, amountMinor);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "expectation " + expectationId + " moved under its lock: a counter"
                                + " restores a settled remainder");
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not reopen the expectation", failure);
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.expectation_event (expectation_id,"
                                + " event_type, detail, actor, actor_type, occurred_at,"
                                + " correlation_id) VALUES (?, 'REOPENED', ?, ?, ?, ?, ?)")) {
            insert.setObject(1, expectationId);
            insert.setString(2, detail);
            insert.setString(3, actor.id());
            insert.setString(4, actor.type().name());
            insert.setTimestamp(5, Timestamp.from(at));
            insert.setString(6, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the expectation's history", failure);
        }
    }

    @Override
    public void moveItem(
            Connection unitOfWork,
            UUID itemId,
            ItemStatus from,
            ItemStatus to,
            long counteredMinor,
            long unparkedMinor,
            OptionalInt graceHours,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET"
                                + " allocated_minor = allocated_minor - ?,"
                                + " parked_minor = parked_minor - ?, status = ?,"
                                + " grace_until = CASE WHEN ?::int IS NULL THEN grace_until"
                                + " ELSE statement_timestamp() + make_interval(hours => ?) END,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setLong(1, counteredMinor);
            update.setLong(2, unparkedMinor);
            update.setString(3, to.name());
            if (graceHours.isPresent()) {
                update.setInt(4, graceHours.getAsInt());
                update.setInt(5, graceHours.getAsInt());
            } else {
                update.setNull(4, java.sql.Types.INTEGER);
                update.setNull(5, java.sql.Types.INTEGER);
            }
            update.setTimestamp(6, Timestamp.from(at));
            update.setObject(7, itemId);
            update.setString(8, from.name());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "item " + itemId + " moved under its lock: expected " + from);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not move the item", failure);
        }
        if (from == to) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.external_item_event (item_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")) {
            insert.setObject(1, itemId);
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.setString(7, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the item's history", failure);
        }
    }

    @Override
    public void insertClosure(
            Connection unitOfWork,
            UUID breakId,
            UUID resolutionId,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.repudiation_closure (break_id,"
                                + " resolution_id, closed_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?)")) {
            insert.setObject(1, breakId);
            insert.setObject(2, resolutionId);
            insert.setTimestamp(3, Timestamp.from(at));
            insert.setString(4, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not record the closure", failure);
        }
    }

    @Override
    public int releaseKeys(Connection unitOfWork, UUID remittanceId, UUID resolutionId) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.expectation_key SET released_by_resolution_id = ?"
                                + " WHERE expectation_id = ? AND released_by_resolution_id IS NULL")) {
            update.setObject(1, resolutionId);
            update.setObject(2, remittanceId);
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not release the remittance's keys", failure);
        }
    }

    private static void bind(
            Connection unitOfWork, PreparedStatement statement, int index, Object parameter)
            throws SQLException {
        if (parameter instanceof Collection<?> values) {
            Array array = unitOfWork.createArrayOf("uuid", values.toArray());
            statement.setArray(index, array);
        } else {
            statement.setObject(index, parameter);
        }
    }
}
