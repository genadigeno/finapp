package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.retention.RetentionSweep;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * The window counter against PostgreSQL, as the application role (`P15-TSK-022`, {@code INV-CON-03}). Owed to the exit's database pass (C14).
 *
 * <p>Every statement here is held to platform {@code V014}'s DDL by hand: scopes are lower-case with dots and dashes, keys match the key
 * shape, a window ends after it starts, {@code admitted} is at least one. Probe rows carry the {@code test.} scope and are removed by the
 * migrator, the only role that can delete them, so a run leaves nothing a later suite counts.
 */
@Tag("database")
@DisplayName("the window counter on PostgreSQL (P15-TSK-022)")
class WindowCounterDatabaseTest {

    private static final String SCOPE = "test.window-counter";

    private static final WindowLimit HOURLY_TEN = new WindowLimit(10, Duration.ofHours(1));

    private final WindowCounter counter = new WindowCounter(DatabaseRoles::application, 64);

    @BeforeEach
    @AfterEach
    void removeProbes() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.window_counter WHERE scope LIKE 'test.%'");
        }
    }

    /** A test straddling a window's end would count in two windows: wait until the hour has at least thirty seconds left. */
    private static void awayFromAWindowEnd() throws Exception {
        while (true) {
            try (Connection connection = DatabaseRoles.application();
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT 3600 - (EXTRACT(EPOCH FROM now())::bigint % 3600)")) {
                result.next();
                if (result.getLong(1) >= 30) {
                    return;
                }
            }
            Thread.sleep(1000);
        }
    }

    @Test
    @DisplayName("three instances racing: exactly the limit admitted, and the row holds exactly the limit")
    void exactlyTheLimitIsAdmittedAcrossThreeInstances() throws Exception {
        awayFromAWindowEnd();
        List<WindowCounter> instances = List.of(
                new WindowCounter(DatabaseRoles::application, 64),
                new WindowCounter(DatabaseRoles::application, 64),
                new WindowCounter(DatabaseRoles::application, 64));
        int perInstance = 12;
        ExecutorService threads = Executors.newFixedThreadPool(instances.size() * perInstance);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Admission>> attempts = new ArrayList<>();
            for (WindowCounter instance : instances) {
                for (int i = 0; i < perInstance; i++) {
                    Callable<Admission> attempt = () -> {
                        start.await();
                        return instance.admit(SCOPE, "race", HOURLY_TEN);
                    };
                    attempts.add(threads.submit(attempt));
                }
            }
            start.countDown();
            int admitted = 0;
            for (Future<Admission> attempt : attempts) {
                Admission admission = attempt.get();
                if (admission.admitted()) {
                    admitted++;
                }
                assertThat(admission.retryAfterSeconds()).isBetween(1L, 3600L);
            }
            assertThat(admitted).as("exactly the limit, not the limit times the instance count").isEqualTo(10);
            assertThat(admittedInRow("race")).as("the row is the arbiter").isEqualTo(10);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("the window is the database's: its start is date_bin over the database's clock, and it ends a window later")
    void theWindowIsTheDatabases() throws Exception {
        awayFromAWindowEnd();

        counter.admit(SCOPE, "clock", HOURLY_TEN);

        try (Connection connection = DatabaseRoles.migrator();
                PreparedStatement read = connection.prepareStatement(
                        """
                        SELECT window_start = date_bin(INTERVAL '1 hour', now(), TIMESTAMPTZ 'epoch'),
                               window_ends_at = window_start + INTERVAL '1 hour'
                          FROM platform.window_counter WHERE scope = ? AND counter_key = 'clock'
                        """)) {
            read.setString(1, SCOPE);
            try (ResultSet result = read.executeQuery()) {
                assertThat(result.next()).as("the row exists").isTrue();
                assertThat(result.getBoolean(1)).as("the window start is the database's bin").isTrue();
                assertThat(result.getBoolean(2)).as("the window ends one stride later").isTrue();
            }
        }
    }

    @Test
    @DisplayName("a request past the limit writes no new row version: the cost of a refused flood is a lock, not a write")
    void aRefusedRequestWritesNothing() throws Exception {
        awayFromAWindowEnd();
        WindowLimit one = new WindowLimit(1, Duration.ofHours(1));
        counter.admit(SCOPE, "refused", one);
        String versionBefore = rowVersion("refused");

        Admission refused = counter.admit(SCOPE, "refused", one);

        assertThat(refused.admitted()).isFalse();
        assertThat(rowVersion("refused")).as("the row was not rewritten").isEqualTo(versionBefore);
        assertThat(admittedInRow("refused")).isEqualTo(1);
    }

    @Test
    @DisplayName("an expired window does not count against the next one")
    void aNewWindowStartsAtOne() throws Exception {
        awayFromAWindowEnd();
        insertAsMigrator("reset", "date_bin(INTERVAL '1 hour', now(), TIMESTAMPTZ 'epoch') - INTERVAL '1 hour'",
                "date_bin(INTERVAL '1 hour', now(), TIMESTAMPTZ 'epoch')", 10);

        assertThat(counter.admit(SCOPE, "reset", HOURLY_TEN).admitted()).isTrue();

        assertThat(admittedInRowWhereWindowIsCurrent("reset")).isEqualTo(1);
    }

    @Test
    @DisplayName("the application role can count and cannot delete or truncate")
    void theRoleCannotDeleteOrTruncate() throws Exception {
        counter.admit(SCOPE, "grants", HOURLY_TEN);
        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeUpdate("DELETE FROM platform.window_counter"))
                    .satisfies(denied -> assertThat(denied.getSQLState()).isEqualTo("42501"));
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeUpdate("TRUNCATE platform.window_counter"))
                    .satisfies(denied -> assertThat(denied.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    @DisplayName("the sweep deletes ended windows only, and passes over a row another sweeper holds")
    void theSweepDeletesEndedWindowsOnly() throws Exception {
        insertAsMigrator("ended", "now() - INTERVAL '3 hours'", "now() - INTERVAL '2 hours'", 1);
        insertAsMigrator("held", "now() - INTERVAL '3 hours'", "now() - INTERVAL '2 hours'", 1);
        insertAsMigrator("live", "now() - INTERVAL '10 minutes'", "now() + INTERVAL '50 minutes'", 1);

        try (Connection sweeper = DatabaseRoles.migrator()) {
            sweeper.setAutoCommit(false);
            try (Statement lock = sweeper.createStatement()) {
                lock.executeQuery("SELECT 1 FROM platform.window_counter WHERE scope = '" + SCOPE + "' AND counter_key = 'held' FOR UPDATE");
            }

            RetentionSweep.ofExpiredWindowCounters(DatabaseRoles::application, Duration.ofSeconds(1), 1000).sweepBatch();

            assertThat(admittedInRow("ended")).as("the ended window is gone").isZero();
            assertThat(admittedInRow("held")).as("a held row is passed over, not waited for").isEqualTo(1);
            assertThat(admittedInRow("live")).as("a live window stays, whatever the bound").isEqualTo(1);
            sweeper.rollback();
        }

        RetentionSweep.ofExpiredWindowCounters(DatabaseRoles::application, Duration.ofSeconds(1), 1000).sweepBatch();
        assertThat(admittedInRow("held")).as("taken by the next tick").isZero();
    }

    @Test
    @DisplayName("the function refuses a zero bound and an oversized batch")
    void theFunctionRefusesBadBounds() throws Exception {
        for (String call : List.of("SELECT platform.sweep_expired_window_counters(0, 10)", "SELECT platform.sweep_expired_window_counters(1000, 1001)")) {
            try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
                assertThatExceptionOfType(SQLException.class)
                        .as(call)
                        .isThrownBy(() -> statement.executeQuery(call))
                        .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("22023"));
            }
        }
    }

    private int admittedInRow(String key) throws SQLException {
        try (Connection connection = DatabaseRoles.migrator();
                PreparedStatement read = connection.prepareStatement(
                        "SELECT COALESCE(SUM(admitted), 0) FROM platform.window_counter WHERE scope = ? AND counter_key = ?")) {
            read.setString(1, SCOPE);
            read.setString(2, key);
            try (ResultSet result = read.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int admittedInRowWhereWindowIsCurrent(String key) throws SQLException {
        try (Connection connection = DatabaseRoles.migrator();
                PreparedStatement read = connection.prepareStatement(
                        """
                        SELECT admitted FROM platform.window_counter
                         WHERE scope = ? AND counter_key = ? AND window_start = date_bin(INTERVAL '1 hour', now(), TIMESTAMPTZ 'epoch')
                        """)) {
            read.setString(1, SCOPE);
            read.setString(2, key);
            try (ResultSet result = read.executeQuery()) {
                assertThat(result.next()).as("a new row in the current window").isTrue();
                return result.getInt(1);
            }
        }
    }

    private String rowVersion(String key) throws SQLException {
        try (Connection connection = DatabaseRoles.migrator();
                PreparedStatement read = connection.prepareStatement(
                        "SELECT ctid::text || '/' || xmin::text FROM platform.window_counter WHERE scope = ? AND counter_key = ?")) {
            read.setString(1, SCOPE);
            read.setString(2, key);
            try (ResultSet result = read.executeQuery()) {
                assertThat(result.next()).as("the row exists").isTrue();
                return result.getString(1);
            }
        }
    }

    private void insertAsMigrator(String key, String windowStart, String windowEnd, int admitted) throws SQLException {
        try (Connection connection = DatabaseRoles.migrator();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO platform.window_counter (scope, counter_key, window_start, window_ends_at, admitted) VALUES (?, ?, "
                                + windowStart + ", " + windowEnd + ", ?)")) {
            insert.setString(1, SCOPE);
            insert.setString(2, key);
            insert.setInt(3, admitted);
            insert.executeUpdate();
        }
    }
}
