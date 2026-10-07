package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The credit profile against a real database (`P10-TSK-004`, {@code INV-CRD-09}, {@code INV-CRD-04}).
 *
 * <p>Ten connections stand for ten instances (the {@code P0-TST-009} convention): one party ensured
 * by all of them leaves one row and one id, and a second locker is observed WAITING on the first -
 * read from {@code pg_blocking_pids}, never inferred from a sleep - until the first commits.
 */
@Tag("database")
@DisplayName("the credit profile (P10-TSK-004)")
class CreditProfileDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RAISE_EXCEPTION = "P0001";
    private static final int INSTANCES = 10;

    private final CreditProfiles<Connection> profiles = new JdbcCreditProfiles(IDS);

    @Test
    @DisplayName("ten instances ensuring one party leave one profile and return one id")
    void tenEnsurersLeaveOneProfile() throws Exception {
        UUID party = IDS.next();
        CyclicBarrier start = new CyclicBarrier(INSTANCES);
        ExecutorService instances = Executors.newFixedThreadPool(INSTANCES);
        try {
            List<Future<CreditProfileId>> ensured = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                ensured.add(instances.submit(() -> {
                    try (Connection connection = DatabaseRoles.application()) {
                        connection.setAutoCommit(false);
                        start.await(10, TimeUnit.SECONDS);
                        CreditProfile profile = profiles.ensure(connection, party);
                        connection.commit();
                        return profile.id();
                    }
                }));
            }
            Set<UUID> ids = new HashSet<>();
            for (Future<CreditProfileId> each : ensured) {
                ids.add(each.get(60, TimeUnit.SECONDS).value());
            }
            assertThat(ids).as("every ensurer, winner or loser, returns the one profile's id").hasSize(1);
        } finally {
            instances.shutdownNow();
        }
        assertThat(profileCount(party)).as("one row per party, however many instances race").isEqualTo(1);
    }

    @Test
    @DisplayName("ensuring again - a retry after a lost response - returns the same profile")
    void aRetryReadsTheSameProfile() throws SQLException {
        UUID party = IDS.next();
        try (Connection connection = DatabaseRoles.application()) {
            CreditProfile first = profiles.ensure(connection, party);
            CreditProfile again = profiles.ensure(connection, party);
            assertThat(again).isEqualTo(first);
            assertThat(again.partyId()).isEqualTo(party);
        }
        assertThat(profileCount(party)).isEqualTo(1);
    }

    @Test
    @DisplayName("a second locker waits until the first commits - the profile serialises deciding transactions")
    void aSecondLockerWaits() throws Exception {
        UUID party = IDS.next();
        try (Connection setup = DatabaseRoles.application()) {
            profiles.ensure(setup, party);
        }
        ExecutorService instanceB = Executors.newSingleThreadExecutor();
        try (Connection first = DatabaseRoles.application(); Connection second = DatabaseRoles.application()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            int secondPid = backendPid(second);

            assertThat(profiles.lockForDecision(first, party)).isPresent();
            Future<Optional<CreditProfile>> locking = instanceB.submit(() -> profiles.lockForDecision(second, party));

            assertThat(waitsOnALock(secondPid, Duration.ofSeconds(20)))
                    .as("the second locker is blocked on the first's row lock").isTrue();
            assertThat(locking).as("and has not returned while the first holds the row").isNotDone();

            first.commit();

            assertThat(locking.get(20, TimeUnit.SECONDS)).as("the first's commit releases the second").isPresent();
            second.commit();
        } finally {
            instanceB.shutdownNow();
        }
    }

    @Test
    @DisplayName("locking a party with no profile finds nothing")
    void anUnknownPartyHasNoProfileToLock() throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            assertThat(profiles.lockForDecision(connection, IDS.next())).isEmpty();
        }
    }

    @Test
    @DisplayName("the application cannot update, delete or truncate a profile - the trigger and the grants")
    void theApplicationCannotChangeAProfile() throws SQLException {
        UUID party = IDS.next();
        try (Connection connection = DatabaseRoles.application()) {
            profiles.ensure(connection, party);
            connection.setAutoCommit(false);
            // UPDATE (party_id) is granted ONLY so FOR UPDATE is takeable: the trigger refuses it.
            assertRefused(connection, "UPDATE credit.credit_profile SET party_id = party_id WHERE party_id = '"
                    + party + "'", RAISE_EXCEPTION);
            assertRefused(connection, "UPDATE credit.credit_profile SET created_at = now() WHERE party_id = '"
                    + party + "'", INSUFFICIENT_PRIVILEGE);
            assertRefused(connection, "DELETE FROM credit.credit_profile WHERE party_id = '" + party + "'",
                    INSUFFICIENT_PRIVILEGE);
            assertRefused(connection, "TRUNCATE credit.credit_profile", INSUFFICIENT_PRIVILEGE);
        }
    }

    @Test
    @DisplayName("the owner cannot update, delete or truncate a profile either - refused by the trigger")
    void theOwnerCannotChangeAProfile() throws SQLException {
        UUID party = IDS.next();
        try (Connection connection = DatabaseRoles.application()) {
            profiles.ensure(connection, party);
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            assertRefused(migrator, "UPDATE credit.credit_profile SET created_at = now() WHERE party_id = '"
                    + party + "'", RAISE_EXCEPTION);
            assertRefused(migrator, "DELETE FROM credit.credit_profile WHERE party_id = '" + party + "'",
                    RAISE_EXCEPTION);
            assertRefused(migrator, "TRUNCATE credit.credit_profile", RAISE_EXCEPTION);
        }
        assertThat(profileCount(party)).isEqualTo(1);
    }

    @Test
    @DisplayName("created_at is the database's, whatever a writer supplies")
    void theDatabaseDatesTheProfile() throws SQLException {
        UUID party = IDS.next();
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            Instant transactionStart;
            try (Statement statement = connection.createStatement();
                    ResultSet now = statement.executeQuery("SELECT transaction_timestamp()")) {
                now.next();
                transactionStart = now.getTimestamp(1).toInstant();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO credit.credit_profile (id, party_id, created_at)"
                            + " VALUES (?, ?, TIMESTAMPTZ '2000-01-01 00:00:00+00')")) {
                insert.setObject(1, IDS.next());
                insert.setObject(2, party);
                insert.executeUpdate();
            }
            CreditProfile profile = profiles.lockForDecision(connection, party).orElseThrow();
            assertThat(profile.createdAt()).as("the supplied date is overwritten by the database's clock")
                    .isAfterOrEqualTo(transactionStart);
            connection.rollback();
        }
    }

    @Test
    @DisplayName("the profile has no figure column - exactly id, party and creation time (INV-CRD-04)")
    void theProfileHasNoFigureColumn() throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> numeric = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT column_name, data_type FROM information_schema.columns"
                                + " WHERE table_schema = 'credit' AND table_name = 'credit_profile'")) {
            while (rows.next()) {
                columns.add(rows.getString(1));
                if (rows.getString(2).matches("numeric|integer|bigint|smallint|real|double precision|money|decimal")) {
                    numeric.add(rows.getString(1));
                }
            }
        }
        assertThat(columns).as("a figure on the profile would be a second answer to the records")
                .containsExactlyInAnyOrder("id", "party_id", "created_at");
        assertThat(numeric).as("no numeric column, whatever its name").isEmpty();
    }

    // -----------------------------------------------------------------

    private static void assertRefused(Connection connection, String sql, String sqlState) throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .as(sql)
                .isThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .matches(e -> sqlState.equals(e.getSQLState()), "SQLState " + sqlState);
        connection.rollback();
    }

    private static int profileCount(UUID party) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement count = migrator.prepareStatement(
                        "SELECT count(*) FROM credit.credit_profile WHERE party_id = ?")) {
            count.setObject(1, party);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /**
     * Whether the backend is observed blocked by another before the deadline - {@code pg_blocking_pids}, because
     * {@code pg_stat_activity} hides another role's wait event from a non-superuser observer.
     */
    private static boolean waitsOnALock(int pid, Duration deadline) throws Exception {
        long until = System.nanoTime() + deadline.toNanos();
        try (Connection observer = DatabaseRoles.migrator();
                PreparedStatement waiting = observer.prepareStatement(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0")) {
            waiting.setInt(1, pid);
            while (System.nanoTime() < until) {
                try (ResultSet row = waiting.executeQuery()) {
                    if (row.next() && row.getBoolean(1)) {
                        return true;
                    }
                }
                Thread.sleep(20);
            }
        }
        return false;
    }
}
