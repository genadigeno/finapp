package com.finapp.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The inbox table's own constraints, exercised directly.
 *
 * <p><strong>Why separately from {@link InboxConsumerTest}.</strong> Most of these cannot be
 * reached through the wrapper: {@code InboxKey} validates bounds before SQL sees them, and the
 * store computes {@code expires_at} from the server clock so no caller can make it precede
 * {@code processed_at}. That is the argument for testing them here rather than treating them as
 * covered — a constraint application code cannot reach is one only the database will ever
 * enforce, against an operator, a migration, or a writer nobody has written yet.
 *
 * <p>The same gap was found by review on both {@code V002} and {@code V005}. Writing these with
 * the migration rather than after it is the correction.
 */
@Tag("database")
class InboxMessageSchemaTest {

    private static final String TABLE = "platform.inbox_message";

    /** PostgreSQL SQLStates; locale-independent, unlike the messages. */
    private static final String CHECK_VIOLATION = "23514";

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static final Instant PROCESSED = Instant.parse("2026-09-01T12:00:00Z");

    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        connection =
                DriverManager.getConnection(
                        required("finapp.db.url"), required("finapp.db.user"), required("finapp.db.password"));
        connection.setAutoCommit(true);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE consumer LIKE 'schema-probe%'");
        }
    }

    @Test
    @DisplayName("one consumer cannot record the same message twice — INV-IDEM-04 at the schema")
    void theKeyIsUniquePerConsumer() throws SQLException {
        insert("schema-probe", "message-1");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert("schema-probe", "message-1"))
                .matches(e -> UNIQUE_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("two consumers can each record the same message, or one would suppress the other")
    void theKeyIsScopedToTheConsumer() throws SQLException {
        // The positive half, and the one that matters: without it, tightening the key to the
        // message alone would pass every rejection test in this class while silently stopping
        // every consumer but the first from ever seeing a message.
        insert("schema-probe-ledger", "message-shared");
        insert("schema-probe-notifier", "message-shared");

        assertThat(probeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a record that expires before it was processed is refused")
    void expiryMustFollowProcessing() {
        // Such a row would be swept before it could deduplicate anything, so the duplicate it
        // exists to refuse would be admitted - quietly, and only under redelivery.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                write(
                                        "schema-probe",
                                        "message-expired",
                                        "probe.Event",
                                        "flow-1",
                                        PROCESSED,
                                        PROCESSED.minusSeconds(1)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        // Equal is refused too: a record expiring at the instant it is written is no record.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                write(
                                        "schema-probe",
                                        "message-expired",
                                        "probe.Event",
                                        "flow-1",
                                        PROCESSED,
                                        PROCESSED))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("an over-long consumer, key, type or correlation identifier is refused")
    void boundsAreEnforced() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert("schema-probe" + "x".repeat(200), "message-2"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert("schema-probe", "m".repeat(201)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                write(
                                        "schema-probe",
                                        "message-3",
                                        "t".repeat(201),
                                        "flow-1",
                                        PROCESSED,
                                        PROCESSED.plusSeconds(60)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                write(
                                        "schema-probe",
                                        "message-4",
                                        "probe.Event",
                                        "c".repeat(129),
                                        PROCESSED,
                                        PROCESSED.plusSeconds(60)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("values at exactly the maximum length are accepted, so the bounds are not off by one")
    void theBoundsAreInclusive() throws SQLException {
        // InboxKey.MAX_LENGTH is written in Java and again in the CHECK. If they disagreed by
        // one, a legal key would be rejected by the database after passing validation - which
        // presents as a constraint violation from three layers down on a perfectly good message.
        write(
                "s".repeat(InboxKey.MAX_LENGTH),
                "m".repeat(InboxKey.MAX_LENGTH),
                "t".repeat(200),
                "c".repeat(128),
                PROCESSED,
                PROCESSED.plusSeconds(60));

        assertThat(countWhereConsumerLength(InboxKey.MAX_LENGTH)).isEqualTo(1);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE length(consumer) = 200");
        }
    }

    @Test
    @DisplayName("a record without correlation cannot be written")
    void correlationIsRequired() {
        // The chain from the producing command to this consumer's effect is not optional: an
        // effect nobody can trace back to its cause is the thing correlation exists to prevent.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                write(
                                        "schema-probe",
                                        "message-5",
                                        "probe.Event",
                                        null,
                                        PROCESSED,
                                        PROCESSED.plusSeconds(60)))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
    }

    // -----------------------------------------------------------------

    private static void insert(String consumer, String dedupeKey) throws SQLException {
        write(consumer, dedupeKey, "probe.Event", "flow-1", PROCESSED, PROCESSED.plusSeconds(60));
    }

    private static void write(
            String consumer,
            String dedupeKey,
            String messageType,
            String correlationId,
            Instant processedAt,
            Instant expiresAt)
            throws SQLException {
        String sql =
                "INSERT INTO " + TABLE + " (consumer, dedupe_key, message_type, correlation_id, "
                        + "processed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, consumer);
            insert.setString(2, dedupeKey);
            insert.setString(3, messageType);
            insert.setString(4, correlationId);
            insert.setTimestamp(5, Timestamp.from(processedAt));
            insert.setTimestamp(6, Timestamp.from(expiresAt));
            insert.executeUpdate();
        }
    }

    private static int probeCount() throws SQLException {
        return count("SELECT count(*) FROM " + TABLE + " WHERE consumer LIKE 'schema-probe%'");
    }

    private static int countWhereConsumerLength(int length) throws SQLException {
        return count("SELECT count(*) FROM " + TABLE + " WHERE length(consumer) = " + length);
    }

    private static int count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property " + name + " is not set. Run this through "
                            + "'./gradlew :platform:databaseTest', which supplies it.");
        }
        return value;
    }
}
