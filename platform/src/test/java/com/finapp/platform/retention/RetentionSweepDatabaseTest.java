package com.finapp.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The two sweep functions against PostgreSQL, as the application role (`P15-TSK-029`). Owed to the exit's database pass (C14).
 *
 * <p>Each probe row is stamped by the migrator, whose {@code UPDATE} and {@code INSERT} reach every column, and identified by a
 * producer or consumer this class alone uses, so a sweep's rows can be counted in a database other suites share.
 */
@Tag("database")
@DisplayName("the retention sweeps on PostgreSQL (P15-TSK-029)")
class RetentionSweepDatabaseTest {

    private static final String PROBE = "retention-probe";

    private static final Duration WINDOW = Duration.ofDays(14);

    /** A batch larger than the probe rows, except where a test wants to see it stop. */
    private static final int LARGE = 1000;

    @BeforeEach
    @AfterEach
    void removeProbes() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.outbox_event WHERE producer = '" + PROBE + "'");
            statement.executeUpdate("DELETE FROM platform.inbox_message WHERE consumer = '" + PROBE + "'");
            statement.executeUpdate("DELETE FROM platform.inbox_parked_record WHERE consumer = '" + PROBE + "'");
        }
    }

    @Test
    @DisplayName("a non-terminal row is never selected: pending and abandoned rows stay at any age; a young published row stays too")
    void aNonTerminalRowIsNeverSelected() throws SQLException {
        outbox("pending", "NULL", "NULL");
        outbox("abandoned", "NULL", "now() - INTERVAL '400 days'");
        outbox("young", "now() - INTERVAL '1 day'", "NULL");
        outbox("old", "now() - INTERVAL '40 days'", "NULL");

        drainOutbox(Duration.ofDays(30));

        assertThat(remainingOutbox()).containsExactlyInAnyOrder("pending", "abandoned", "young");
    }

    @Test
    @DisplayName("a published row a standing parked record names stays, however old, and goes once that record has settled")
    void anOutboxRowAStandingParkedRecordNamesIsKept() throws SQLException {
        UUID named = outbox("named", "now() - INTERVAL '40 days'", "NULL");
        park(named, "PARKED");

        drainOutbox(Duration.ofDays(30));
        assertThat(remainingOutbox()).as("a release still needs this row").containsExactly("named");

        settle(named, "HANDLED");

        drainOutbox(Duration.ofDays(30));
        assertThat(remainingOutbox()).isEmpty();
    }

    @Test
    @DisplayName("an inbox record is kept until both its written expiry and the retention in force have passed")
    void anInboxRecordYoungerThanItsBoundIsNeverSelected() throws SQLException {
        inbox("written-longer", "now() - INTERVAL '20 days'", "now() + INTERVAL '1 hour'");
        inbox("retention-raised", "now() - INTERVAL '5 days'", "now() - INTERVAL '1 hour'");
        inbox("gone", "now() - INTERVAL '20 days'", "now() - INTERVAL '1 hour'");

        drainInbox(WINDOW);

        assertThat(remainingInbox()).containsExactlyInAnyOrder("written-longer", "retention-raised");
    }

    @Test
    @DisplayName("ten sweepers delete disjoint batches: every due row exactly once, and the counts sum to the rows removed")
    void tenSweepersDeleteDisjointBatches() throws Exception {
        plantDueOutboxRows(1000);
        // Counted from the table rather than assumed: the database is shared, and any other suite's row this old is due too.
        long due = due();
        assertThat(due).isGreaterThanOrEqualTo(1000);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> sweepers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                sweepers.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    RetentionSweep sweep = RetentionSweep.ofPublishedOutboxEvents(DatabaseRoles::application, Duration.ofDays(30), WINDOW, 37);
                    int total = 0;
                    for (int deleted = sweep.sweepBatch(); deleted > 0; deleted = sweep.sweepBatch()) {
                        total += deleted;
                    }
                    return total;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> sweeper : sweepers) {
                total += sweeper.get();
            }
            assertThat((long) total).as("every due row deleted once across the ten: the returned counts sum to it").isEqualTo(due);
        } finally {
            pool.shutdownNow();
        }
        assertThat(remainingOutbox()).isEmpty();
    }

    @Test
    @DisplayName("a row another transaction holds is passed over at once, not waited for")
    void aLockedRowIsSkipped() throws SQLException {
        UUID held = outbox("held", "now() - INTERVAL '40 days'", "NULL");
        outbox("free", "now() - INTERVAL '40 days'", "NULL");

        try (Connection holder = DatabaseRoles.migrator()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM platform.outbox_event WHERE event_id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery().close();
            }
            int deleted = RetentionSweep.ofPublishedOutboxEvents(DatabaseRoles::application, Duration.ofDays(30), WINDOW, LARGE).sweepBatch();
            assertThat(deleted).as("the free row, and no wait on the held one").isEqualTo(1);
            holder.rollback();
        }
        assertThat(remainingOutbox()).containsExactly("held");
    }

    @Test
    @DisplayName("an interrupted sweep resumes: a batch rolled back deletes nothing, and the next one takes the same rows")
    void anInterruptedSweepResumes() throws SQLException {
        outbox("first", "now() - INTERVAL '40 days'", "NULL");

        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            try (PreparedStatement call = application.prepareStatement("SELECT platform.sweep_published_outbox_events(?, ?)")) {
                call.setLong(1, Duration.ofDays(30).toMillis());
                call.setInt(2, LARGE);
                call.executeQuery().close();
            }
            application.rollback();
        }
        assertThat(remainingOutbox()).containsExactly("first");

        drainOutbox(Duration.ofDays(30));
        assertThat(remainingOutbox()).isEmpty();
    }

    @Test
    @DisplayName("the application role holds no DELETE on either table, and the sweep's functions are its one path")
    void deletionIsTheFunctionsAlone() throws SQLException {
        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
            DatabaseRoles.assertCannotBypassPrivileges(application);
            for (String table : List.of("platform.outbox_event", "platform.inbox_message")) {
                assertThatExceptionOfType(SQLException.class)
                        .isThrownBy(() -> statement.executeUpdate("DELETE FROM " + table + " WHERE false"))
                        .matches(e -> "42501".equals(e.getSQLState()), "the privilege is refused");
            }
        }
    }

    @Test
    @DisplayName("each function refuses a bound at the broker's redelivery horizon, so a chosen expiry cannot reach a young dedupe record")
    void aBoundInsideTheBrokersHorizonIsRefused() throws SQLException {
        inbox("expiry-moved-early", "now() - INTERVAL '2 days'", "now() - INTERVAL '1 day'");
        long horizon = RetentionSweep.BROKER_REDELIVERY_HORIZON.toMillis();

        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
            for (String function : List.of("platform.sweep_expired_inbox_messages", "platform.sweep_published_outbox_events")) {
                assertThatExceptionOfType(SQLException.class)
                        .isThrownBy(() -> statement.executeQuery("SELECT " + function + "(" + horizon + ", 10)"))
                        .matches(e -> "22023".equals(e.getSQLState()), "invalid_parameter_value");
            }
        }
        assertThat(remainingInbox()).containsExactly("expiry-moved-early");

        drainInbox(RetentionSweep.BROKER_REDELIVERY_HORIZON.plusMillis(1));
        assertThat(remainingInbox()).as("processed two days ago: inside the floor, whatever its expiry says").containsExactly("expiry-moved-early");
    }

    @Test
    @DisplayName("an unreviewed column stops the inbox's sweep: it raises and deletes nothing")
    void anUnknownColumnStopsTheSweep() throws SQLException {
        inbox("kept", "now() - INTERVAL '20 days'", "now() - INTERVAL '1 hour'");

        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try (Statement statement = migrator.createStatement()) {
                statement.execute("ALTER TABLE platform.inbox_message ADD COLUMN probe_state TEXT");
                assertThatExceptionOfType(SQLException.class)
                        .isThrownBy(() -> statement.executeQuery("SELECT platform.sweep_expired_inbox_messages(" + WINDOW.toMillis() + ", 10)"))
                        .matches(e -> "55000".equals(e.getSQLState()), "object_not_in_prerequisite_state");
            }
            migrator.rollback();
        }
        assertThat(remainingInbox()).containsExactly("kept");
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static void drainOutbox(Duration bound) {
        RetentionSweep sweep = RetentionSweep.ofPublishedOutboxEvents(DatabaseRoles::application, bound, WINDOW, LARGE);
        while (sweep.sweepBatch() == LARGE) {
            // Sweeps the shared database's other old rows too; only this class's probes are asserted.
        }
    }

    private static void drainInbox(Duration bound) {
        RetentionSweep sweep = RetentionSweep.ofExpiredInboxMessages(DatabaseRoles::application, bound, LARGE);
        while (sweep.sweepBatch() == LARGE) {
            // As above.
        }
    }

    private static UUID outbox(String name, String publishedAt, String deadLetteredAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO platform.outbox_event (event_id, event_type, event_version, schema_version, aggregate_id,"
                                + " aggregate_type, occurred_at, producer, correlation_id, causation_id, payload, payload_media_type,"
                                + " published_at, attempts, dead_lettered_at)"
                                + " VALUES (?, ?, 1, 1, ?, 'probe', now(), '" + PROBE + "', 'c', 'c', '\\x00'::bytea, 'text/plain', "
                                + publishedAt + ", 1, " + deadLetteredAt + ")")) {
            insert.setObject(1, id);
            insert.setString(2, name);
            insert.setObject(3, UUID.randomUUID());
            insert.executeUpdate();
        }
        return id;
    }

    private static void park(UUID event, String state) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO platform.inbox_parked_record (park_id, consumer, dedupe_key, consumer_group, message_type,"
                                + " correlation_id, topic, partition_number, record_offset, state, attempts, last_failure,"
                                + " first_failed_at, last_failed_at, parked_at)"
                                + " VALUES (gen_random_uuid(), '" + PROBE + "', ?, 'g', 'probe', 'c', 't', 0, 0, ?, 1, 'Probe',"
                                + " now(), now(), now())")) {
            insert.setString(1, event.toString());
            insert.setString(2, state);
            insert.executeUpdate();
        }
    }

    private static void settle(UUID event, String state) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement update = migrator.prepareStatement(
                        "UPDATE platform.inbox_parked_record SET state = ?, settled_at = now() WHERE consumer = '" + PROBE
                                + "' AND dedupe_key = ?")) {
            update.setString(1, state);
            update.setString(2, event.toString());
            update.executeUpdate();
        }
    }

    private static void plantDueOutboxRows(int rows) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO platform.outbox_event (event_id, event_type, event_version, schema_version, aggregate_id,"
                                + " aggregate_type, occurred_at, producer, correlation_id, causation_id, payload, payload_media_type,"
                                + " published_at, attempts)"
                                + " SELECT gen_random_uuid(), 'due-' || g, 1, 1, gen_random_uuid(), 'probe', now(), '" + PROBE
                                + "', 'c', 'c', '\\x00'::bytea, 'text/plain', now() - INTERVAL '40 days', 1"
                                + " FROM generate_series(1, ?) g")) {
            insert.setInt(1, rows);
            insert.executeUpdate();
        }
    }

    private static long due() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement();
                ResultSet count = statement.executeQuery(
                        "SELECT count(*) FROM platform.outbox_event WHERE published_at < now() - INTERVAL '30 days'")) {
            count.next();
            return count.getLong(1);
        }
    }

    private static void inbox(String key, String processedAt, String expiresAt) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO platform.inbox_message (consumer, dedupe_key, message_type, correlation_id, processed_at,"
                                + " expires_at) VALUES ('" + PROBE + "', ?, 'probe', 'c', " + processedAt + ", " + expiresAt + ")")) {
            insert.setString(1, key);
            insert.executeUpdate();
        }
    }

    private static List<String> remainingOutbox() throws SQLException {
        return names("SELECT event_type FROM platform.outbox_event WHERE producer = '" + PROBE + "'");
    }

    private static List<String> remainingInbox() throws SQLException {
        return names("SELECT dedupe_key FROM platform.inbox_message WHERE consumer = '" + PROBE + "'");
    }

    private static List<String> names(String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }
}
