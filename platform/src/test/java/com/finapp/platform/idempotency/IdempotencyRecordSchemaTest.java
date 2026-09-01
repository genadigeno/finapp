package com.finapp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The idempotency table against a real PostgreSQL ({@code INV-IDEM-01}, ADR-0004).
 *
 * <p><strong>Why this cannot be a unit test.</strong> The claim is that the <em>database</em>
 * arbitrates between simultaneous duplicate claims. ADR-0004 rejected a cache and an HTTP
 * filter precisely because they cannot make that guarantee, so testing this against anything
 * other than the real engine would be testing the substitute rather than the mechanism.
 *
 * <p>Tagged {@code database}: it runs under {@code ./gradlew :platform:databaseTest}, not in
 * the hermetic build. Requires {@code docker compose up -d postgres}.
 */
@Tag("database")
class IdempotencyRecordSchemaTest {

    private static final String TABLE = "platform.idempotency_record";

    /** PostgreSQL SQLStates; locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static Connection connection;

    private static final AtomicInteger UNIQUE_SUFFIX = new AtomicInteger();

    @BeforeAll
    static void connect() throws SQLException {
        connection =
                DriverManager.getConnection(
                        requiredProperty("finapp.db.url"),
                        requiredProperty("finapp.db.user"),
                        requiredProperty("finapp.db.password"));
        connection.setAutoCommit(true);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void removeThisTestsRows() throws SQLException {
        // Scoped deletion rather than TRUNCATE: the table is shared with whatever else has run
        // against this database, and a test that wipes a developer's local data to make its own
        // assertions easier is a test that will eventually be run against something it should
        // not have wiped.
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE scope LIKE 'test:%'");
        }
    }

    // -----------------------------------------------------------------
    // INV-IDEM-01 — the guarantee
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a second claim on the same key is refused by the database")
    void duplicateKeyIsRejected() throws SQLException {
        String scope = uniqueScope();

        claim(scope, "key-1");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> claim(scope, "key-1"))
                .matches(e -> UNIQUE_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("scope separates keys, so two commands may reuse one client key")
    void scopeSeparatesKeys() throws SQLException {
        // A client generating one key per business action would otherwise find its second
        // command rejected because an unrelated command already used that key.
        String scopeA = uniqueScope();
        String scopeB = uniqueScope();

        claim(scopeA, "shared-key");
        claim(scopeB, "shared-key");

        assertThat(countIn(scopeA) + countIn(scopeB)).isEqualTo(2);
    }

    @Test
    @DisplayName("under genuine concurrency exactly one claim wins")
    void exactlyOneClaimWinsUnderContention() throws Exception {
        // The reason this mechanism is in the database at all. Sixteen threads race for one
        // key from a standing start; anything other than exactly one winner is a duplicate
        // financial effect or a lost request.
        int threads = 16;
        String scope = uniqueScope();
        CountDownLatch start = new CountDownLatch(1);
        Set<String> outcomes = ConcurrentHashMap.newKeySet();
        AtomicInteger winners = new AtomicInteger();

        List<Callable<String>> racers =
                java.util.stream.IntStream.range(0, threads)
                        .<Callable<String>>mapToObj(
                                i ->
                                        () -> {
                                            // Each racer needs its own connection: sharing one
                                            // would serialise them and the race would not happen.
                                            try (Connection own = openConnection()) {
                                                start.await();
                                                try {
                                                    claimOn(own, scope, "contended");
                                                    winners.incrementAndGet();
                                                    return "won";
                                                } catch (SQLException e) {
                                                    return e.getSQLState();
                                                }
                                            }
                                        })
                        .collect(Collectors.toList());

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = racers.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<String> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(winners.get()).as("exactly one claim may succeed").isEqualTo(1);
        assertThat(outcomes)
                .as("every loser must fail as a unique violation, not as something unexplained")
                .containsExactlyInAnyOrder("won", UNIQUE_VIOLATION);
        assertThat(countIn(scope)).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The state machine, as far as a row can express it
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an unknown state is refused, so a typo cannot invent one")
    void unknownStateIsRejected() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(uniqueScope(), "k", "PROCESSING", null, null, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("an in-progress claim cannot already carry an outcome")
    void inProgressCannotCarryAnOutcome() {
        // A row claiming to be unfinished while holding a response would let a reader treat an
        // unfinished command as finished — the one reading that produces a second effect.
        Instant now = Instant.now();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () -> insert(uniqueScope(), "k", "IN_PROGRESS", now, "body".getBytes(), "text/plain"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a terminal state must record when it finished")
    void terminalStateNeedsACompletionTime() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(uniqueScope(), "k", "COMPLETED", null, null, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(uniqueScope(), "k", "FAILED", null, null, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a completed claim may legitimately have no body")
    void completedMayHaveNoBody() throws SQLException {
        // A command can succeed with no content. Requiring a body would force the wrapper to
        // invent one, and an invented response is not the original outcome.
        String scope = uniqueScope();

        insert(scope, "k", "COMPLETED", Instant.now(), null, null);

        assertThat(countIn(scope)).isEqualTo(1);
    }

    @Test
    @DisplayName("a response body without a media type cannot be stored")
    void bodyAndMediaTypeTravelTogether() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () -> insert(uniqueScope(), "k", "COMPLETED", Instant.now(), "body".getBytes(), null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    // -----------------------------------------------------------------
    // Bounds and required data
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a record that expires before it was created is refused")
    void expiryMustFollowCreation() {
        // Such a row would be swept away before it could ever answer a retry, which is a
        // silent loss of the guarantee rather than a visible failure.
        Instant created = Instant.now();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertWithExpiry(uniqueScope(), "k", created, created.minusSeconds(1)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a truncated fingerprint is refused, because it would weaken INV-IDEM-03")
    void fingerprintLengthIsConstrained() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertWithFingerprint(uniqueScope(), "k", new byte[16]))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertWithFingerprint(uniqueScope(), "k", new byte[65]))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("an over-long scope or key is refused rather than silently stored")
    void boundsAreEnforced() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> claim("test:" + "x".repeat(200), "k"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> claim(uniqueScope(), "k".repeat(201)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("a claim without a correlation cannot be written")
    void correlationIsRequired() {
        // A duplicate submission nobody can trace back to the request that made it is a
        // support case with no thread to pull.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertWithCorrelation(uniqueScope(), "k", null))
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
    }

    // -----------------------------------------------------------------
    // The enum and the constraint are one definition
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every state the enum declares is accepted by the schema, and only those")
    void enumAndCheckConstraintAgree() throws SQLException {
        // Proven by round-trip rather than by reading the DDL: adding a constant to
        // IdempotencyState without a migration fails here, and widening the constraint without
        // adding the constant fails too.
        for (IdempotencyState state : IdempotencyState.values()) {
            String scope = uniqueScope();
            insert(scope, "k", state.name(), state.isTerminal() ? Instant.now() : null, null, null);
            assertThat(countIn(scope)).as("%s must be storable", state).isEqualTo(1);
        }

        assertThatExceptionOfType(SQLException.class)
                .as("a state the enum does not declare must be refused")
                .isThrownBy(() -> insert(uniqueScope(), "k", "ROLLED_BACK", Instant.now(), null, null))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    // -----------------------------------------------------------------

    private static String uniqueScope() {
        return "test:scope-" + UNIQUE_SUFFIX.incrementAndGet();
    }

    private static void claim(String scope, String key) throws SQLException {
        claimOn(connection, scope, key);
    }

    private static void claimOn(Connection target, String scope, String key) throws SQLException {
        insertOn(target, scope, key, "IN_PROGRESS", null, null, null, sha256(scope + key), "req-1",
                Instant.now(), Instant.now().plusSeconds(86_400));
    }

    private static void insert(
            String scope, String key, String state, Instant completedAt, byte[] body, String mediaType)
            throws SQLException {
        insertOn(connection, scope, key, state, completedAt, body, mediaType, sha256(key), "req-1",
                Instant.now(), Instant.now().plusSeconds(86_400));
    }

    private static void insertWithExpiry(String scope, String key, Instant created, Instant expires)
            throws SQLException {
        insertOn(connection, scope, key, "IN_PROGRESS", null, null, null, sha256(key), "req-1",
                created, expires);
    }

    private static void insertWithFingerprint(String scope, String key, byte[] fingerprint)
            throws SQLException {
        insertOn(connection, scope, key, "IN_PROGRESS", null, null, null, fingerprint, "req-1",
                Instant.now(), Instant.now().plusSeconds(86_400));
    }

    private static void insertWithCorrelation(String scope, String key, String correlationId)
            throws SQLException {
        insertOn(connection, scope, key, "IN_PROGRESS", null, null, null, sha256(key), correlationId,
                Instant.now(), Instant.now().plusSeconds(86_400));
    }

    private static void insertOn(
            Connection target,
            String scope,
            String key,
            String state,
            Instant completedAt,
            byte[] body,
            String mediaType,
            byte[] fingerprint,
            String correlationId,
            Instant createdAt,
            Instant expiresAt)
            throws SQLException {
        String sql =
                "INSERT INTO " + TABLE + " (scope, idempotency_key, request_fingerprint, "
                        + "fingerprint_algorithm, state, response_body, response_media_type, "
                        + "correlation_id, created_at, completed_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = target.prepareStatement(sql)) {
            insert.setString(1, scope);
            insert.setString(2, key);
            insert.setBytes(3, fingerprint);
            insert.setString(4, "SHA-256");
            insert.setString(5, state);
            insert.setBytes(6, body);
            insert.setString(7, mediaType);
            insert.setString(8, correlationId);
            insert.setTimestamp(9, Timestamp.from(createdAt));
            insert.setTimestamp(10, completedAt == null ? null : Timestamp.from(completedAt));
            insert.setTimestamp(11, Timestamp.from(expiresAt));
            insert.executeUpdate();
        }
    }

    private static int countIn(String scope) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT count(*) FROM " + TABLE + " WHERE scope = ?")) {
            select.setString(1, scope);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                requiredProperty("finapp.db.url"),
                requiredProperty("finapp.db.user"),
                requiredProperty("finapp.db.password"));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property " + name + " is not set. Run this through "
                            + "'./gradlew :platform:databaseTest', which supplies it.");
        }
        return value;
    }
}
