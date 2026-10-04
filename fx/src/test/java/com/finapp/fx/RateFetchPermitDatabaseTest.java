package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reference fetch's pacing permit against a live PostgreSQL (`P9-TSK-005`): database-stamped,
 * strictly forward, never deleted, and one claim per window across ten racing instances
 * (counted). Pacing only - what is stored is the snapshot's to decide.
 */
@Tag("database")
@DisplayName("the rate fetch permit: database-stamped, forward-only, one claim per window"
        + " (P9-TSK-005)")
class RateFetchPermitDatabaseTest {

    private static final String RAISED = "P0001";

    private final RateFetchPermitStore<Connection> permits = new JdbcRateFetchPermitStore();

    @Test
    @DisplayName("the first claim takes it, a second within the window does not, and a zero window"
            + " advances it strictly - stamped by the database")
    void claimsAreWindowedAndDatabaseStamped() throws SQLException {
        String source = freshSource();
        try (Connection app = application()) {
            assertThat(permits.claim(app, source, Duration.ofHours(1))).isTrue();
            app.commit();
            Instant first = lastAttempt(app, source);
            Instant databaseNow = databaseNow(app);
            assertThat(first)
                    .as("the attempt is the database's statement_timestamp(), not a JVM instant")
                    .isBetween(databaseNow.minusSeconds(60), databaseNow);
            app.commit();

            assertThat(permits.claim(app, source, Duration.ofHours(1))).isFalse();
            app.commit();
            assertThat(attempts(app, source)).isEqualTo(1);
            app.commit();

            assertThat(permits.claim(app, source, Duration.ZERO)).isTrue();
            app.commit();
            assertThat(lastAttempt(app, source)).isAfter(first);
            assertThat(attempts(app, source)).isEqualTo(2);
            app.commit();
        }
    }

    @Test
    @DisplayName("the permit only moves forward and is never deleted, for every writer")
    void thePermitOnlyMovesForward() throws SQLException {
        String source = freshSource();
        try (Connection app = application(); Connection migrator = DatabaseRoles.migrator()) {
            permits.claim(app, source, Duration.ZERO);
            app.commit();
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE fx.rate_fetch_permit SET last_attempt_at ="
                                                    + " last_attempt_at - interval '1 hour',"
                                                    + " attempts = attempts + 1"
                                                    + " WHERE source = '" + source + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "DELETE FROM fx.rate_fetch_permit WHERE source = '"
                                                    + source + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThat(attempts(app, source)).isEqualTo(1);
            app.commit();
        }
    }

    @Test
    @DisplayName("ten instances claiming one source's window at once: exactly one takes it"
            + " (counted)")
    void tenClaimantsOneClaim() throws Exception {
        String source = freshSource();
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        int claimed = 0;
        try {
            List<Callable<Boolean>> claimants = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                claimants.add(
                        () -> {
                            try (Connection own = application()) {
                                start.await();
                                boolean mine = permits.claim(own, source, Duration.ofHours(1));
                                own.commit();
                                return mine;
                            }
                        });
            }
            for (Future<Boolean> outcome : pool.invokeAll(claimants)) {
                if (outcome.get()) {
                    claimed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(claimed).isEqualTo(1);
        try (Connection app = application()) {
            assertThat(attempts(app, source)).as("counted in the row").isEqualTo(1);
            app.commit();
        }
    }

    // -----------------------------------------------------------------

    private static Connection application() throws SQLException {
        Connection app = DatabaseRoles.application();
        app.setAutoCommit(false);
        return app;
    }

    private static String freshSource() {
        return "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Instant lastAttempt(Connection connection, String source) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery(
                                "SELECT last_attempt_at FROM fx.rate_fetch_permit WHERE source = '"
                                        + source + "'")) {
            assertThat(row.next()).isTrue();
            return row.getTimestamp(1).toInstant();
        }
    }

    private static int attempts(Connection connection, String source) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery(
                                "SELECT attempts FROM fx.rate_fetch_permit WHERE source = '"
                                        + source + "'")) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT statement_timestamp()")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
