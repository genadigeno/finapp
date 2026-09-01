package com.finapp.platform.outbox;

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
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The outbox table's own constraints, exercised directly.
 *
 * <p><strong>Why separately from {@link OutboxWriterTest}.</strong> Most of these cannot be
 * reached through the writer at all: {@code EventEnvelope} validates bounds and versions before
 * SQL ever sees them, and nothing writes {@code attempts} or {@code published_at} until the
 * relay exists. That is precisely the argument for testing them here rather than treating them
 * as covered — a constraint application code cannot reach is one only the database will ever
 * enforce, against an operator, a migration, or a writer nobody has written yet.
 *
 * <p>A review of this task found all ten of {@code V005}'s constraints unexercised, which is the
 * same gap the {@code V002} review closed for the idempotency table.
 */
@Tag("database")
class OutboxEventSchemaTest {

    private static final String TABLE = "platform.outbox_event";

    /** PostgreSQL SQLStates; locale-independent, unlike the messages. */
    private static final String CHECK_VIOLATION = "23514";

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NOT_NULL_VIOLATION = "23502";

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
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE producer LIKE 'schema-probe%'");
        }
    }

    @Test
    @DisplayName("the same event cannot be queued twice, so a retry cannot double-publish")
    void eventIdIsUnique() throws SQLException {
        UUID eventId = UUID.randomUUID();
        insert(eventId, "schema-probe", 1, 1, 0, null);

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(eventId, "schema-probe", 1, 1, 0, null))
                .matches(e -> UNIQUE_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a version of zero is refused, because that is what an unset integer looks like")
    void versionsMustBePositive() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), "schema-probe", 0, 1, 0, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), "schema-probe", 1, 0, 0, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a negative attempt count is refused")
    void attemptsCannotBeNegative() {
        // Nothing writes attempts until the relay, which is exactly why it is asserted now: the
        // relay will be the first code to touch it and the constraint must already hold.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), "schema-probe", 1, 1, -1, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a row cannot claim to be published without ever having been attempted")
    void publishedImpliesAttempted() {
        // A published row with zero attempts says the relay succeeded without trying, which
        // would make the attempt count useless for diagnosing a backlog.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), "schema-probe", 1, 1, 0, Instant.now()))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a published row with attempts recorded is accepted, so the rule is not a blanket ban")
    void publishedWithAttemptsIsAccepted() throws SQLException {
        // The other side of the constraint. Without this, tightening it to forbid publication
        // outright would pass every rejection test above.
        insert(UUID.randomUUID(), "schema-probe", 1, 1, 1, Instant.now());

        assertThat(probeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an over-long producer or correlation identifier is refused rather than stored")
    void boundsAreEnforced() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), "schema-probe" + "x".repeat(200), 1, 1, 0, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertWithCorrelation(UUID.randomUUID(), "c".repeat(129)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a queued event without correlation cannot be written")
    void correlationIsRequired() {
        // INV-EVT-03 in the schema and not only in the envelope: an event nobody can trace must
        // be impossible to queue, whatever wrote it.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertWithCorrelation(UUID.randomUUID(), null))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
    }

    // -----------------------------------------------------------------
    // V006 — relay scheduling
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a row cannot be both published and abandoned")
    void publishedAndAbandonedAreMutuallyExclusive() {
        // If it could, "how many events did we fail to deliver" would have two contradictory
        // answers, and the one an operator happened to query would decide whether anybody
        // investigated.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> writeRelayState(UUID.randomUUID(), 1, Instant.now(), Instant.now(), "boom"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a row cannot be abandoned without ever having been attempted")
    void abandonmentImpliesAnAttempt() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> writeRelayState(UUID.randomUUID(), 0, null, Instant.now(), "boom"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("an abandoned row with attempts recorded is accepted, so the rule is not a blanket ban")
    void abandonmentIsOtherwiseAllowed() throws SQLException {
        // Without this, tightening the constraint to forbid abandonment outright would pass
        // every rejection test above.
        writeRelayState(UUID.randomUUID(), 1, null, Instant.now(), "the broker is unavailable");

        assertThat(probeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unbounded error message is refused rather than stored")
    void lastErrorIsBounded() {
        // The relay truncates, but the relay is not the only thing that will ever write here:
        // an operator tool or a later writer would otherwise put an unbounded provider response
        // into a column read in operational views.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> writeRelayState(UUID.randomUUID(), 1, null, null, "x".repeat(1001)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> writeRelayState(UUID.randomUUID(), 1, null, null, ""))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    // -----------------------------------------------------------------

    private static void writeRelayState(
            UUID eventId, int attempts, Instant publishedAt, Instant deadLetteredAt, String lastError)
            throws SQLException {
        String sql =
                "INSERT INTO " + TABLE + " (event_id, event_type, event_version, schema_version, "
                        + "aggregate_id, aggregate_type, occurred_at, producer, correlation_id, "
                        + "causation_id, payload, payload_media_type, published_at, attempts, "
                        + "dead_lettered_at, last_error) "
                        + "VALUES (?, 'probe.Event', 1, 1, ?, 'Probe', now(), 'schema-probe', "
                        + "'flow-1', 'cause-1', '\\x00', 'application/json', ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, eventId);
            insert.setObject(2, UUID.randomUUID());
            insert.setTimestamp(3, publishedAt == null ? null : Timestamp.from(publishedAt));
            insert.setInt(4, attempts);
            insert.setTimestamp(5, deadLetteredAt == null ? null : Timestamp.from(deadLetteredAt));
            insert.setString(6, lastError);
            insert.executeUpdate();
        }
    }

    private static void insert(
            UUID eventId,
            String producer,
            int eventVersion,
            int schemaVersion,
            int attempts,
            Instant publishedAt)
            throws SQLException {
        write(eventId, producer, eventVersion, schemaVersion, attempts, publishedAt, "flow-1");
    }

    private static void insertWithCorrelation(UUID eventId, String correlationId) throws SQLException {
        write(eventId, "schema-probe", 1, 1, 0, null, correlationId);
    }

    private static void write(
            UUID eventId,
            String producer,
            int eventVersion,
            int schemaVersion,
            int attempts,
            Instant publishedAt,
            String correlationId)
            throws SQLException {
        String sql =
                "INSERT INTO " + TABLE + " (event_id, event_type, event_version, schema_version, "
                        + "aggregate_id, aggregate_type, occurred_at, producer, correlation_id, "
                        + "causation_id, payload, payload_media_type, published_at, attempts) "
                        + "VALUES (?, 'probe.Event', ?, ?, ?, 'Probe', now(), ?, ?, 'cause-1', ?, "
                        + "'application/json', ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, eventId);
            insert.setInt(2, eventVersion);
            insert.setInt(3, schemaVersion);
            insert.setObject(4, UUID.randomUUID());
            insert.setString(5, producer);
            insert.setString(6, correlationId);
            insert.setBytes(7, new byte[] {0});
            insert.setTimestamp(8, publishedAt == null ? null : Timestamp.from(publishedAt));
            insert.setInt(9, attempts);
            insert.executeUpdate();
        }
    }

    private static int probeCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM " + TABLE + " WHERE producer LIKE 'schema-probe%'")) {
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
