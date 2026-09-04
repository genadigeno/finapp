package com.finapp.platform.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Objects;

/**
 * Plain-JDBC audit writer.
 *
 * <p>Explicit SQL, which ADR-0033 makes the platform-wide decision rather than a local one. It
 * mattered more here than anywhere, and this class is why: Hibernate's dirty checking emits {@code UPDATE}s, and the
 * application role holds no {@code UPDATE} on this table at all. An ORM mapping of an audit
 * record is a mapping of an entity that can never be updated, which most ORMs have no way to
 * express — so this adapter stays deliberately small, and callers depend on {@link AuditWriter}.
 *
 * <p>The insert happens on the connection it is handed and nothing else: no commit, no rollback,
 * no connection of its own. That is what makes "the audit record commits with the action"
 * (ADR-0010) true rather than intended.
 */
public final class JdbcAuditWriter implements AuditWriter<Connection> {

    private static final String TABLE = "platform.audit_record";

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE + " (audit_id, actor_id, actor_type, occurred_at, operation, "
                    + "target_type, target_id, reason, outcome, correlation_id, change_summary) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Override
    public void append(Connection unitOfWork, AuditRecord record) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(record, "record must not be null");
        requireTransaction(unitOfWork);

        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SQL)) {
            insert.setObject(1, record.auditId().value());
            insert.setString(2, record.actor().id());
            insert.setString(3, record.actor().type().name());
            insert.setTimestamp(4, Timestamp.from(record.occurredAt()));
            insert.setString(5, record.operation().code());
            insert.setString(6, record.targetType());
            insert.setString(7, record.targetId());
            setNullableText(insert, 8, record.reason().orElse(null));
            insert.setString(9, record.outcome().name());
            insert.setString(10, record.correlationId().value());
            setNullableText(insert, 11, record.changeSummary().orElse(null));
            insert.executeUpdate();
        } catch (SQLException e) {
            // Not swallowed and not downgraded to a log line. The caller's transaction must
            // fail: an action committed without its audit record leaves no trace that it
            // happened, and an absent audit record is indistinguishable from an action that
            // never occurred.
            throw new AuditWriteException(
                    "Could not append audit record for " + record.operation().code() + " on "
                            + record.targetType() + " " + record.targetId()
                            + "; the transaction that performed it must not commit",
                    e);
        }
    }

    /**
     * Refuses a connection in auto-commit mode.
     *
     * <p>The one guarantee this class provides is that the record commits with the action. On an
     * auto-commit connection it commits alone — so an action that then fails and rolls back
     * leaves an audit record asserting something that did not happen, and the trail becomes
     * evidence of the wrong thing.
     *
     * <p>Checked explicitly rather than left to fail somewhere less obvious, for the same reason
     * as {@code JdbcInboxRecordStore}: an error naming the mistake beats an error naming a
     * mechanism.
     */
    private static void requireTransaction(Connection connection) {
        try {
            if (connection.getAutoCommit()) {
                throw new AuditWriteException(
                        "The audit record must commit with the action it records, so it needs the "
                                + "caller's transaction: this connection is in auto-commit mode, "
                                + "which would commit the record even if the action rolled back "
                                + "(ADR-0010)",
                        null);
            }
        } catch (SQLException e) {
            throw new AuditWriteException("Could not determine the connection's commit mode", e);
        }
    }

    private static void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
