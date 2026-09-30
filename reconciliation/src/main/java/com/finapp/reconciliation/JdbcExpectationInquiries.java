package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link ExpectationInquiries} over JDBC (ADR-0033) — lock-free, stored references only. */
public final class JdbcExpectationInquiries implements ExpectationInquiries {

    private static final String EXPECTATION_COLUMNS =
            "SELECT id, kind, operation_ref, posting_key, source_id, position_purpose,"
                    + " ledger_account_id, direction, amount_minor, currency, scale,"
                    + " journal_entry_id, posting_date, settlement_cycle, expected_by,"
                    + " rule_set_id, status, allocated_minor, resolved_minor, overdue_since,"
                    + " opened_at, status_changed_at FROM reconciliation.expectation";

    private static ExpectationRow expectationRow(ResultSet row) throws SQLException {
        return new ExpectationRow(
                row.getObject("id", UUID.class),
                ExpectationKind.valueOf(row.getString("kind")),
                row.getString("operation_ref"),
                row.getString("posting_key"),
                row.getObject("source_id", UUID.class),
                row.getString("position_purpose"),
                row.getObject("ledger_account_id", UUID.class),
                ExpectationDirection.valueOf(row.getString("direction")),
                row.getLong("amount_minor"),
                row.getString("currency").trim(),
                row.getInt("scale"),
                Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)),
                row.getObject("posting_date", java.time.LocalDate.class),
                Optional.ofNullable(row.getString("settlement_cycle")),
                row.getObject("expected_by", java.time.LocalDate.class),
                row.getObject("rule_set_id", UUID.class),
                ExpectationStatus.valueOf(row.getString("status")),
                row.getLong("allocated_minor"),
                row.getLong("resolved_minor"),
                Optional.ofNullable(row.getObject("overdue_since", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant),
                JdbcBreakInquiries.instant(row, "opened_at"),
                JdbcBreakInquiries.instant(row, "status_changed_at"));
    }

    @Override
    public List<ExpectationRow> expectations(
            Connection unitOfWork, ExpectationFilter filter, int limit) {
        StringBuilder sql = new StringBuilder(EXPECTATION_COLUMNS + " WHERE TRUE");
        List<Object> args = new ArrayList<>();
        filter.status().ifPresent(status -> {
            sql.append(" AND status = ?");
            args.add(status.name());
        });
        filter.overdue().ifPresent(overdue ->
                sql.append(overdue ? " AND overdue_since IS NOT NULL" : " AND overdue_since IS NULL"));
        filter.sourceId().ifPresent(source -> {
            sql.append(" AND source_id = ?");
            args.add(source);
        });
        sql.append(" ORDER BY opened_at DESC, id DESC LIMIT ?");
        args.add(limit);
        try (PreparedStatement read = unitOfWork.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                read.setObject(i + 1, args.get(i));
            }
            try (ResultSet rows = read.executeQuery()) {
                List<ExpectationRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(expectationRow(rows));
                }
                return List.copyOf(found);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not list the expectations", failure);
        }
    }

    @Override
    public Optional<ExpectationRow> expectationById(Connection unitOfWork, UUID expectationId) {
        return JdbcBreakInquiries.one(
                unitOfWork, EXPECTATION_COLUMNS + " WHERE id = ?", expectationId,
                JdbcExpectationInquiries::expectationRow);
    }

    @Override
    public Optional<ExpectationRow> expectationByOperation(
            Connection unitOfWork, ExpectationKind kind, String operationRef) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        EXPECTATION_COLUMNS + " WHERE kind = ? AND operation_ref = ?")) {
            read.setString(1, kind.name());
            read.setString(2, operationRef);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(expectationRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the operation's expectation", failure);
        }
    }

    @Override
    public List<KeyRow> keys(Connection unitOfWork, UUID expectationId) {
        return JdbcBreakInquiries.list(
                unitOfWork,
                "SELECT key_kind, key_value FROM reconciliation.expectation_key"
                        + " WHERE expectation_id = ? ORDER BY key_kind, key_value",
                expectationId,
                row -> new KeyRow(row.getString("key_kind"), row.getString("key_value")));
    }

    @Override
    public List<EventRow> events(Connection unitOfWork, UUID expectationId) {
        return JdbcBreakInquiries.list(
                unitOfWork,
                "SELECT seq, event_type, detail, actor, actor_type, occurred_at"
                        + " FROM reconciliation.expectation_event WHERE expectation_id = ?"
                        + " ORDER BY seq",
                expectationId,
                row ->
                        new EventRow(
                                row.getLong("seq"),
                                row.getString("event_type"),
                                Optional.ofNullable(row.getString("detail")),
                                row.getString("actor"),
                                row.getString("actor_type"),
                                JdbcBreakInquiries.instant(row, "occurred_at")));
    }

    @Override
    public List<AllocationRow> allocations(
            Connection unitOfWork, UUID expectationId, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, decision_id, external_item_id, amount_minor,"
                                + " reverses_allocation_id, created_at"
                                + " FROM reconciliation.allocation WHERE expectation_id = ?"
                                + " ORDER BY id LIMIT ?")) {
            read.setObject(1, expectationId);
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<AllocationRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(
                            new AllocationRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("decision_id", UUID.class),
                                    rows.getObject("external_item_id", UUID.class),
                                    rows.getLong("amount_minor"),
                                    Optional.ofNullable(
                                            rows.getObject("reverses_allocation_id", UUID.class)),
                                    JdbcBreakInquiries.instant(rows, "created_at")));
                }
                return List.copyOf(found);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation's allocations", failure);
        }
    }

    @Override
    public List<BreakRef> breaksOn(Connection unitOfWork, UUID expectationId) {
        return JdbcBreakInquiries.list(
                unitOfWork,
                "SELECT id, type, status, severity FROM reconciliation.break"
                        + " WHERE expectation_id = ? ORDER BY raised_at, id",
                expectationId,
                row ->
                        new BreakRef(
                                row.getObject("id", UUID.class),
                                row.getString("type"),
                                row.getString("status"),
                                row.getString("severity")));
    }

    @Override
    public Optional<ItemRun> itemRun(Connection unitOfWork, UUID itemId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT i.id, i.run_id, r.batch_id FROM reconciliation.external_item i"
                        + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                        + " WHERE i.id = ?",
                itemId,
                row ->
                        new ItemRun(
                                row.getObject("id", UUID.class),
                                row.getObject("run_id", UUID.class),
                                Optional.ofNullable(row.getObject("batch_id", UUID.class))));
    }

    @Override
    public Optional<ExpectationRow> remittanceOfBatch(Connection unitOfWork, UUID batchId) {
        return expectationByOperation(unitOfWork, ExpectationKind.REMITTANCE, batchId.toString());
    }
}
