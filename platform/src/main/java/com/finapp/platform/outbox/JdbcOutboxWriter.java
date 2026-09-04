package com.finapp.platform.outbox;

import com.finapp.sharedkernel.event.EventEnvelope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * Plain-JDBC outbox writer.
 *
 * <p>Explicit SQL, which ADR-0033 makes the platform-wide decision. The adapter is small, and
 * callers depend on {@link OutboxWriter} rather than on this class.
 *
 * <p>The insert happens on the connection it is handed and nothing else: no commit, no rollback,
 * no connection of its own. That is what makes {@code INV-EVT-01} true rather than intended.
 */
public final class JdbcOutboxWriter implements OutboxWriter<Connection> {

    private static final String TABLE = "platform.outbox_event";

    @Override
    public void write(
            Connection unitOfWork, EventEnvelope envelope, byte[] payload, String payloadMediaType) {

        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(payloadMediaType, "payloadMediaType must not be null");
        if (payloadMediaType.isBlank()) {
            // A payload nobody can interpret is not publishable, and the failure would surface
            // in the relay long after the transaction that produced it had gone.
            throw new IllegalArgumentException("payloadMediaType must not be blank");
        }

        String sql =
                "INSERT INTO " + TABLE + " (event_id, event_type, event_version, schema_version, "
                        + "aggregate_id, aggregate_type, occurred_at, producer, correlation_id, "
                        + "causation_id, payload, payload_media_type) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, envelope.eventId().value());
            insert.setString(2, envelope.eventType());
            insert.setInt(3, envelope.eventVersion());
            insert.setInt(4, envelope.schemaVersion());
            insert.setObject(5, envelope.aggregateId().value());
            insert.setString(6, envelope.aggregateType());
            insert.setTimestamp(7, Timestamp.from(envelope.occurredAt()));
            insert.setString(8, envelope.producer());
            insert.setString(9, envelope.correlationId().value());
            insert.setString(10, envelope.causationId().value());
            insert.setBytes(11, payload);
            insert.setString(12, payloadMediaType);
            insert.executeUpdate();
        } catch (SQLException e) {
            // Not swallowed, and deliberately not downgraded to a log line. The caller's
            // transaction must fail: a fact committed without its publication record is the
            // lost event INV-EVT-01 exists to prevent, and it is undetectable afterwards.
            throw new OutboxWriteException(
                    "Could not queue " + envelope.eventType() + " " + envelope.eventId().value()
                            + " for publication; the transaction that produced it must not commit",
                    e);
        }
    }
}
