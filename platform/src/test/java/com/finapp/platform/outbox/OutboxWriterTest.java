package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The outbox writer against a real PostgreSQL ({@code INV-EVT-01}, ADR-0005).
 *
 * <p><strong>Why a real database.</strong> The claim under test is about a transaction boundary:
 * that a rolled-back fact takes its publication record with it. A fake would demonstrate that
 * the writer calls the methods it calls, which is not the property that matters — the property
 * is that the database ties the two together.
 */
@Tag("database")
class OutboxWriterTest {

    private static final String TABLE = "platform.outbox_event";
    private static final String FACTS = "outbox_fact_probe";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new Random(19L));

    private static Connection connection;
    private final OutboxWriter<Connection> outbox = new JdbcOutboxWriter();

    /** Stands in for an aggregate identifier owned by a business module in a later phase. */
    static final class ProbeTransferId extends EntityId {
        ProbeTransferId(UUID value) {
            super(value);
        }
    }

    @BeforeAll
    static void connect() throws SQLException {
        connection = DatabaseRoles.bootstrap();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            // An ordinary table, so the rollback assertion is about a real committed state
            // rather than about session-local scratch space.
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + FACTS + " (id BIGSERIAL PRIMARY KEY, tag TEXT NOT NULL)");
        }
        connection.commit();
    }

    @AfterAll
    static void dropProbeAndDisconnect() throws SQLException {
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + FACTS);
            }
            connection.commit();
            connection.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE producer = 'probe'");
            statement.executeUpdate("DELETE FROM " + FACTS);
        }
        connection.commit();
    }

    // -----------------------------------------------------------------
    // INV-EVT-01 — the acceptance criterion
    // -----------------------------------------------------------------

    @Test
    @DisplayName("rolling back the business transaction rolls back the outbox row")
    void rollbackTakesTheOutboxRowWithIt() throws SQLException {
        // The whole reason the outbox exists. If the publication record could survive a
        // rolled-back fact, the platform would announce something that never happened; if the
        // fact could survive without the record, it would happen and nobody would ever hear.
        EventEnvelope envelope = envelope();

        recordFact("transfer-attempted");
        outbox.write(connection, envelope, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        connection.rollback();

        assertThat(factCount()).as("the fact rolled back").isZero();
        assertThat(exists(envelope.eventId())).as("and so did its publication record").isFalse();
    }

    @Test
    @DisplayName("committing the business transaction commits the outbox row with it")
    void commitKeepsThemTogether() throws SQLException {
        // The other half. A test that only checked the rollback would pass against a writer
        // that never wrote anything at all.
        EventEnvelope envelope = envelope();

        recordFact("transfer-completed");
        outbox.write(connection, envelope, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        connection.commit();

        assertThat(factCount()).isEqualTo(1);
        assertThat(exists(envelope.eventId())).isTrue();
    }

    @Test
    @DisplayName("a failed outbox write fails the caller's transaction rather than being swallowed")
    void aFailedWriteMustNotLeaveTheFactCommittable() throws SQLException {
        // Written twice under the same event id. The point is not the duplicate itself but that
        // the writer raises rather than logging and returning: a caller that committed anyway
        // would have produced the lost event INV-EVT-01 forbids.
        EventEnvelope envelope = envelope();
        outbox.write(connection, envelope, "{}".getBytes(StandardCharsets.UTF_8), "application/json");

        assertThatExceptionOfType(OutboxWriteException.class)
                .isThrownBy(
                        () -> outbox.write(
                                connection, envelope, "{}".getBytes(StandardCharsets.UTF_8), "application/json"))
                .withMessageContaining("must not commit");
        connection.rollback();

        assertThat(exists(envelope.eventId())).isFalse();
    }

    // -----------------------------------------------------------------
    // What gets written
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every envelope field reaches the row, so a queued event is as traceable as a built one")
    void theWholeEnvelopeIsPersisted() throws SQLException {
        EventEnvelope envelope = envelope();
        // Deliberately hostile to any re-encoding on the way through: leading and trailing
        // whitespace, a NUL, and a byte that is not valid UTF-8. A mutation sweep during this
        // task's review showed the earlier payload survived a trim-and-re-encode unchanged, so
        // the test asserted byte-exactness while being unable to detect its loss. A relay must
        // publish what the producer wrote, not a round-trip of it.
        byte[] payload = new byte[] {' ', 0x00, (byte) 0xFF, '{', '}', '\n', ' '};

        outbox.write(connection, envelope, payload, "application/octet-stream");
        connection.commit();

        try (PreparedStatement select =
                        connection.prepareStatement("SELECT * FROM " + TABLE + " WHERE event_id = ?")) {
            select.setObject(1, envelope.eventId().value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("event_type")).isEqualTo(envelope.eventType());
                assertThat(row.getInt("event_version")).isEqualTo(envelope.eventVersion());
                assertThat(row.getInt("schema_version")).isEqualTo(envelope.schemaVersion());
                assertThat(row.getObject("aggregate_id")).isEqualTo(envelope.aggregateId().value());
                assertThat(row.getString("aggregate_type")).isEqualTo(envelope.aggregateType());
                assertThat(row.getTimestamp("occurred_at").toInstant()).isEqualTo(envelope.occurredAt());
                assertThat(row.getString("producer")).isEqualTo(envelope.producer());
                assertThat(row.getString("correlation_id")).isEqualTo(envelope.correlationId().value());
                assertThat(row.getString("causation_id")).isEqualTo(envelope.causationId().value());
                // Byte-exact: a relay must publish what the producer wrote, not a
                // re-serialisation of it.
                assertThat(row.getBytes("payload")).isEqualTo(payload);
                assertThat(row.getString("payload_media_type")).isEqualTo("application/octet-stream");
            }
        }
    }

    @Test
    @DisplayName("a newly written event is pending, which is the only thing the relay will look for")
    void aNewRowIsPending() throws SQLException {
        EventEnvelope envelope = envelope();

        outbox.write(connection, envelope, new byte[] {1, 2, 3}, "application/octet-stream");
        connection.commit();

        try (PreparedStatement select =
                        connection.prepareStatement(
                                "SELECT published_at, attempts FROM " + TABLE + " WHERE event_id = ?")) {
            select.setObject(1, envelope.eventId().value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getTimestamp("published_at")).as("not yet published").isNull();
                assertThat(row.getInt("attempts")).isZero();
            }
        }
    }

    @Test
    @DisplayName("an empty payload is written as written, not turned into null")
    void anEmptyPayloadIsPreserved() throws SQLException {
        // An event whose whole content is its envelope is legitimate. Coercing empty to null
        // would give the relay something it cannot publish.
        EventEnvelope envelope = envelope();

        outbox.write(connection, envelope, new byte[0], "application/json");
        connection.commit();

        assertThat(payloadOf(envelope.eventId())).isEmpty();
    }

    // -----------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a payload with no media type is refused, because a relay could not publish it")
    void mediaTypeIsRequired() {
        assertThatThrownBy(() -> outbox.write(connection, envelope(), new byte[0], "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.write(connection, envelope(), new byte[0], null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null arguments are refused rather than written as absent fields")
    void nullsAreRefused() {
        assertThatThrownBy(() -> outbox.write(null, envelope(), new byte[0], "application/json"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.write(connection, null, new byte[0], "application/json"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.write(connection, envelope(), null, "application/json"))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------

    private static EventEnvelope envelope() {
        return new EventEnvelope(
                EventId.next(IDS),
                "transfers.TransferCompleted",
                1,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                new ProbeTransferId(IDS.next()),
                "Transfer",
                OCCURRED,
                "probe",
                CorrelationId.of("flow-1"),
                CausationId.of("command-1"));
    }

    private static void recordFact(String tag) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO " + FACTS + " (tag) VALUES (?)")) {
            insert.setString(1, tag);
            insert.executeUpdate();
        }
    }

    private static int factCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + FACTS)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean exists(EventId eventId) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT 1 FROM " + TABLE + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static byte[] payloadOf(EventId eventId) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT payload FROM " + TABLE + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getBytes(1);
            }
        }
    }


}
