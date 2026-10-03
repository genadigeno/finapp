package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The pull permit against the real schema (`P8-TSK-021`, settlement `V008`): the windowed claim
 * takes it once per window, the operator's renewal always takes it, every renewal strictly
 * advances the instant — and the trigger refuses an EQUAL instant as a step back for every
 * writer, raw SQL included; ten racing claimers of one key take it once.
 *
 * <p>Runs as {@code finapp_app}; the shared connection rolls back, the race commits.
 */
@Tag("database")
@DisplayName("the pull permit: windowed, strictly forward, one taker per window (P8-TSK-021)")
class PullPermitDatabaseTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Instant T0 = Instant.parse("2026-09-30T08:00:00Z");

    private static Connection application;
    private static UUID source;
    private static final JdbcPullPermitStore PERMITS = new JdbcPullPermitStore();

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT id FROM settlement.source WHERE code = 'simulated-psp.settlement'");
                ResultSet row = read.executeQuery()) {
            row.next();
            source = row.getObject(1, UUID.class);
        }
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static String key() {
        return "K" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    @DisplayName("the windowed claim takes the permit first, never again inside the window, and"
            + " again once the window has passed - each renewal strictly advancing the instant")
    void theWindowPacesTheHerd() throws SQLException {
        String key = key();
        assertThat(PERMITS.claim(application, source, key, T0, WINDOW)).isTrue();
        assertThat(PERMITS.claim(application, source, key, T0.plus(Duration.ofMinutes(5)),
                        WINDOW))
                .as("inside the window: another attempt stands")
                .isFalse();
        assertThat(PERMITS.claim(application, source, key, T0.plus(WINDOW), WINDOW))
                .as("the window has passed")
                .isTrue();
        assertThat(attempts(key)).isEqualTo(2);
        assertThat(lastAttempt(key)).isEqualTo(T0.plus(WINDOW));

        // The operator's renewal is never refused - and advances strictly even with a stale
        // clock (one microsecond past the stored instant, never back to it).
        PERMITS.renew(application, source, key, T0);
        assertThat(attempts(key)).isEqualTo(3);
        assertThat(lastAttempt(key)).isEqualTo(T0.plus(WINDOW).plusNanos(1_000));
    }

    @Test
    @DisplayName("every writer meets the strict advance: an equal or earlier instant, attempts that"
            + " do not grow, a moved identity and a delete are all refused")
    void everyWriterMovesItStrictlyForward() throws SQLException {
        String key = key();
        PERMITS.renew(application, source, key, T0);
        String equal = "UPDATE settlement.pull_permit SET last_attempt_at = ?,"
                + " attempts = attempts + 1 WHERE source_id = ? AND business_key = ?";
        assertThat(refused(equal, Timestamp.from(T0), source, key))
                .as("an EQUAL instant is a step back")
                .isEqualTo("P0001");
        assertThat(refused(equal, Timestamp.from(T0.minusSeconds(1)), source, key))
                .isEqualTo("P0001");
        assertThat(refused("UPDATE settlement.pull_permit SET last_attempt_at = ?"
                        + " WHERE source_id = ? AND business_key = ?",
                        Timestamp.from(T0.plusSeconds(60)), source, key))
                .as("attempts only grow")
                .isEqualTo("P0001");
        assertThat(refused("DELETE FROM settlement.pull_permit WHERE source_id = ?"
                        + " AND business_key = ?", source, key))
                .as("the application holds no DELETE")
                .isEqualTo("42501");
        assertThat(refused("INSERT INTO settlement.pull_permit (source_id, business_key,"
                        + " last_attempt_at, attempts, created_at) VALUES (?, 'bad key', now(), 1,"
                        + " now())", source))
                .as("the business key's shape")
                .isEqualTo("23514");
    }

    @Test
    @DisplayName("ten claimers of one key at one instant: exactly one takes the permit")
    void tenClaimersTakeItOnce() throws Exception {
        String key = key();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> racers = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                racers.add(pool.submit(() -> {
                    try (Connection racer = DatabaseRoles.application()) {
                        racer.setAutoCommit(false);
                        start.await();
                        boolean mine = PERMITS.claim(racer, source, key, T0, WINDOW);
                        racer.commit();
                        return mine;
                    }
                }));
            }
            start.countDown();
            int taken = 0;
            for (Future<Boolean> racer : racers) {
                if (racer.get(1, TimeUnit.MINUTES)) {
                    taken++;
                }
            }
            assertThat(taken).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        try (Connection reader = DatabaseRoles.application();
                PreparedStatement read =
                        reader.prepareStatement(
                                "SELECT attempts FROM settlement.pull_permit WHERE source_id = ?"
                                        + " AND business_key = ?")) {
            read.setObject(1, source);
            read.setString(2, key);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                assertThat(row.getInt(1)).as("one attempt recorded").isEqualTo(1);
            }
        }
    }

    // ----------------------------------------------------------------- SQL

    private static int attempts(String key) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT attempts FROM settlement.pull_permit WHERE source_id = ?"
                                + " AND business_key = ?")) {
            read.setObject(1, source);
            read.setString(2, key);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static Instant lastAttempt(String key) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT last_attempt_at FROM settlement.pull_permit WHERE source_id = ?"
                                + " AND business_key = ?")) {
            read.setObject(1, source);
            read.setString(2, key);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        }
    }

    private static String refused(String sql, Object... args) throws SQLException {
        Savepoint before = application.setSavepoint();
        Throwable thrown =
                catchThrowable(() -> {
                    try (PreparedStatement statement = application.prepareStatement(sql)) {
                        for (int i = 0; i < args.length; i++) {
                            statement.setObject(i + 1, args[i]);
                        }
                        statement.executeUpdate();
                    }
                });
        application.rollback(before);
        return thrown instanceof SQLException sql1 ? sql1.getSQLState() : null;
    }
}
