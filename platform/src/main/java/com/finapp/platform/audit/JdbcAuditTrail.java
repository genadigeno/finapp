package com.finapp.platform.audit;

import com.finapp.platform.persistence.DatabaseFailure;
import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AuditTrail} in explicit SQL (`P17-TSK-017`). A window walks {@code V017}'s {@code (occurred_at, audit_id)} index backwards
 * from the cursor; a target's history its {@code (target_type, target_id, occurred_at, audit_id)} index; an actor rides
 * {@code V009}'s {@code (actor_id, audit_id)}. The other filters narrow what the window's range already bounds.
 */
public final class JdbcAuditTrail implements AuditTrail<Connection> {

    private static final String COLUMNS = "SELECT audit_id, occurred_at, actor_id, actor_type, operation, target_type, target_id,"
            + " reason, outcome, correlation_id, change_summary FROM platform.audit_record";

    @Override
    public List<Row> page(Connection unitOfWork, Filter filter, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one record, not " + limit);
        }
        StringBuilder sql = new StringBuilder(COLUMNS).append(" WHERE occurred_at >= ? AND occurred_at < ?");
        List<Object> arguments = new ArrayList<>(List.of(Timestamp.from(filter.from()), Timestamp.from(filter.to())));
        equal(sql, arguments, "actor_id", filter.actorId());
        equal(sql, arguments, "operation", filter.operation());
        equal(sql, arguments, "target_type", filter.targetType());
        equal(sql, arguments, "target_id", filter.targetId());
        equal(sql, arguments, "correlation_id", filter.correlationId());
        equal(sql, arguments, "outcome", filter.outcome().map(AuditOutcome::name));
        if (after.isPresent()) {
            sql.append(" AND (occurred_at, audit_id) < (?, ?)");
            arguments.add(Timestamp.from(after.get().occurredAt()));
            arguments.add(after.get().auditId());
        }
        sql.append(" ORDER BY occurred_at DESC, audit_id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            for (int i = 0; i < arguments.size(); i++) {
                select.setObject(i + 1, arguments.get(i));
            }
            try (ResultSet rows = select.executeQuery()) {
                List<Row> page = new ArrayList<>();
                while (rows.next()) {
                    page.add(row(rows));
                }
                return page;
            }
        } catch (SQLException e) {
            throw new AuditReadException(DatabaseFailure.describe("Could not read the audit trail", e), e);
        }
    }

    @Override
    public Optional<Row> byId(Connection unitOfWork, UUID auditId) {
        Objects.requireNonNull(auditId, "auditId must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(COLUMNS + " WHERE audit_id = ?")) {
            select.setObject(1, auditId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AuditReadException(DatabaseFailure.describe("Could not read an audit record", e), e);
        }
    }

    private static void equal(StringBuilder sql, List<Object> arguments, String column, Optional<String> value) {
        if (value.isPresent()) {
            sql.append(" AND ").append(column).append(" = ?");
            arguments.add(value.get());
        }
    }

    private static Row row(ResultSet rows) throws SQLException {
        return new Row(
                rows.getObject("audit_id", UUID.class),
                rows.getTimestamp("occurred_at").toInstant(),
                rows.getString("actor_id"),
                rows.getString("actor_type"),
                rows.getString("operation"),
                rows.getString("target_type"),
                rows.getString("target_id"),
                Optional.ofNullable(rows.getString("reason")),
                AuditOutcome.valueOf(rows.getString("outcome")),
                rows.getString("correlation_id"),
                Optional.ofNullable(rows.getString("change_summary")));
    }

    /** The trail could not be read: the database's failure, described without a statement's values. */
    public static final class AuditReadException extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        AuditReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
