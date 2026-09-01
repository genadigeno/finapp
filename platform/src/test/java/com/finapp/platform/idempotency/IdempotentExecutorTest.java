package com.finapp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The idempotent execution wrapper against a real PostgreSQL (P0-TSK-016, ADR-0004).
 *
 * <p><strong>Why a real database.</strong> Every claim this class makes is a claim about
 * concurrency arbitration and transaction boundaries. A fake store would demonstrate that the
 * code calls the methods it calls, which is not the property under test — ADR-0004 rejected a
 * cache precisely because only the database can arbitrate.
 *
 * <p><strong>The "one effect" assertion is a side-effect table.</strong> Each command inserts a
 * row into a scratch table, so "exactly one financial effect" is counted rather than inferred
 * from the wrapper's own return value. A wrapper that returned the right thing while running
 * the command twice would pass a weaker test and lose money.
 */
@Tag("database")
// -Xlint:try flags a try-with-resources whose variable is never read. A correlation Scope is
// used for nothing else: entering it is the effect. See CorrelationContextTest.
@SuppressWarnings("try")
class IdempotentExecutorTest {

    private static final String EFFECTS = "idempotency_effect_probe";
    private static final Instant FIXED = Instant.parse("2026-09-01T12:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static Connection connection;
    private static final AtomicInteger SUFFIX = new AtomicInteger();

    private final IdempotencyRecordStore<Connection> store = new JdbcIdempotencyRecordStore();

    @BeforeAll
    static void connect() throws SQLException {
        connection = openConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            // An ordinary table, not a TEMPORARY one. A PostgreSQL temporary table is
            // session-local, so the racing connections below could not see it — a concurrency
            // test whose shared state is invisible across connections proves nothing. It is
            // still created and dropped by the test rather than by a migration: a throwaway
            // probe has no business joining the schema history.
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + EFFECTS
                            + " (id BIGSERIAL PRIMARY KEY, tag TEXT NOT NULL)");
        }
        connection.commit();
    }

    @AfterAll
    static void dropProbeAndDisconnect() throws SQLException {
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + EFFECTS);
            }
            connection.commit();
            connection.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.idempotency_record WHERE scope LIKE 'exec:%'");
            statement.executeUpdate("DELETE FROM " + EFFECTS);
        }
        connection.commit();
    }

    @AfterEach
    void leaveNoScope() {
        assertThat(CorrelationContext.current()).as("a correlation scope leaked").isEmpty();
    }

    // -----------------------------------------------------------------
    // Acceptance 1 — one effect, identical responses
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a retry replays the stored response and does not run the command again")
    void retryReplaysWithoutReExecuting() throws SQLException {
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("transfer:100".getBytes(StandardCharsets.UTF_8));
        AtomicInteger executions = new AtomicInteger();

        IdempotentExecutor.ExecutionOutcome first = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "first")));
        connection.commit();

        IdempotentExecutor.ExecutionOutcome retry = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "second")));
        connection.commit();

        assertThat(executions.get()).as("the command must have run exactly once").isEqualTo(1);
        assertThat(effectCount()).as("exactly one financial effect").isEqualTo(1);

        assertThat(first.executed()).isTrue();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body()).contains("first".getBytes(StandardCharsets.UTF_8));
        assertThat(retry.response()).as("byte-for-byte the original outcome").isEqualTo(first.response());
    }

    @Test
    @DisplayName("concurrent identical requests produce one effect and two identical responses")
    void concurrentDuplicatesProduceOneEffectAndTwoIdenticalResponses() throws Exception {
        // The acceptance criterion, run as a genuine race on separate connections. One request
        // wins the unique index; the other blocks until the winner commits, then replays.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("transfer:250".getBytes(StandardCharsets.UTF_8));
        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        List<Callable<IdempotentExecutor.ExecutionOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            tasks.add(
                    () -> {
                        try (Connection own = openConnection()) {
                            own.setAutoCommit(false);
                            start.await();
                            IdempotentExecutor.ExecutionOutcome outcome =
                                    inScope(() -> executor().execute(
                                            own, key, fingerprint,
                                            unitOfWork -> recordEffect(unitOfWork, executions, "once")));
                            own.commit();
                            return outcome;
                        }
                    });
        }

        List<IdempotentExecutor.ExecutionOutcome> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Future<IdempotentExecutor.ExecutionOutcome>> futures =
                    tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<IdempotentExecutor.ExecutionOutcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(executions.get()).as("the command must have run exactly once").isEqualTo(1);
        assertThat(effectCount()).as("exactly one financial effect").isEqualTo(1);
        assertThat(outcomes).hasSize(racers);
        assertThat(outcomes).filteredOn(IdempotentExecutor.ExecutionOutcome::executed).hasSize(1);
        assertThat(outcomes)
                .as("every caller receives the identical response")
                .extracting(IdempotentExecutor.ExecutionOutcome::response)
                .containsOnly(outcomes.getFirst().response());
    }

    @Test
    @DisplayName("a definitive failure is recorded and replayed, not re-attempted")
    void failureIsReplayedRatherThanRetried() throws SQLException {
        // A rejected transfer has a real outcome. Re-running it on retry would attempt a
        // command that was already definitively refused.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("declined".getBytes(StandardCharsets.UTF_8));
        AtomicInteger executions = new AtomicInteger();

        IdempotentExecutor.ExecutionOutcome first = inScope(() -> executor().execute(
                connection, key, fingerprint,
                unitOfWork -> {
                    executions.incrementAndGet();
                    return CommandResult.failed(
                            StoredResponse.of("insufficient funds".getBytes(StandardCharsets.UTF_8), "text/plain"));
                }));
        connection.commit();

        IdempotentExecutor.ExecutionOutcome retry = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> CommandResult.succeeded(StoredResponse.empty())));
        connection.commit();

        assertThat(executions.get()).isEqualTo(1);
        assertThat(first.state()).isEqualTo(IdempotencyState.FAILED);
        assertThat(retry.state()).isEqualTo(IdempotencyState.FAILED);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.response()).isEqualTo(first.response());
    }

    @Test
    @DisplayName("a rolled-back command leaves no claim, so the key stays usable")
    void rollbackReleasesTheClaim() throws SQLException {
        // The reason claim and effect share a transaction. If the claim survived a rollback,
        // the key would be blocked for work that never happened.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("rolled-back".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(
                        () ->
                                inScope(() -> executor().execute(
                                        connection, key, fingerprint,
                                        unitOfWork -> {
                                            throw new IllegalStateException("command blew up");
                                        })))
                .isInstanceOf(IllegalStateException.class);
        connection.rollback();

        AtomicInteger executions = new AtomicInteger();
        IdempotentExecutor.ExecutionOutcome afterRollback = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "retry")));
        connection.commit();

        assertThat(afterRollback.executed()).as("the key must be free after a rollback").isTrue();
        assertThat(executions.get()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Acceptance 2 — a differing fingerprint is a distinct conflict
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reusing a key with a different request is a distinct conflict, not a replay")
    void differingFingerprintIsRejected() throws SQLException {
        IdempotencyKey key = uniqueKey();
        AtomicInteger executions = new AtomicInteger();

        inScope(() -> executor().execute(
                connection, key,
                RequestFingerprint.sha256("transfer:100".getBytes(StandardCharsets.UTF_8)),
                unitOfWork -> recordEffect(unitOfWork, executions, "original")));
        connection.commit();

        assertThatExceptionOfType(IdempotencyConflictException.class)
                .as("returning the first response here would hide a client defect, or a fraud attempt")
                .isThrownBy(
                        () ->
                                inScope(() -> executor().execute(
                                        connection, key,
                                        RequestFingerprint.sha256("transfer:999".getBytes(StandardCharsets.UTF_8)),
                                        unitOfWork -> recordEffect(unitOfWork, executions, "different"))))
                .satisfies(conflict -> assertThat(conflict.key()).isEqualTo(key));
        connection.rollback();

        assertThat(executions.get()).as("and it must not have run the different command").isEqualTo(1);
        assertThat(effectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a conflict is refused even while the original claim is still in progress")
    void differingFingerprintIsRejectedBeforeStalenessIsConsidered() throws Exception {
        // Order matters: a different request must never inherit a key, so the fingerprint is
        // compared before any question of reclaiming an abandoned claim.
        IdempotencyKey key = uniqueKey();
        insertExpiredLeaseClaim(key, RequestFingerprint.sha256("original".getBytes(StandardCharsets.UTF_8)));

        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(
                        () ->
                                inScope(() -> executor().execute(
                                        connection, key,
                                        RequestFingerprint.sha256("different".getBytes(StandardCharsets.UTF_8)),
                                        unitOfWork -> CommandResult.succeeded(StoredResponse.empty()))));
        connection.rollback();
    }

    // -----------------------------------------------------------------
    // Acceptance 3 — an in-progress claim is deterministic, not a deadlock
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a fresh in-progress claim yields a deterministic outcome rather than waiting")
    void inProgressClaimIsReportedNotAwaited() throws Exception {
        // Committed by another connection, so it is genuinely visible and genuinely someone
        // else's. Waiting would tie up this connection for as long as that command runs;
        // re-executing would assume it failed.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("held".getBytes(StandardCharsets.UTF_8));
        try (Connection other = openConnection()) {
            other.setAutoCommit(false);
            store.claim(other, key, fingerprint, CorrelationId.of("other-flow"), FIXED, FIXED.plus(RETENTION), LEASE);
            other.commit();
        }

        AtomicInteger executions = new AtomicInteger();
        assertThatExceptionOfType(IdempotencyInProgressException.class)
                .isThrownBy(
                        () ->
                                inScope(() -> executor().execute(
                                        connection, key, fingerprint,
                                        unitOfWork -> recordEffect(unitOfWork, executions, "should not run"))))
                .satisfies(inProgress -> assertThat(inProgress.key()).isEqualTo(key));
        connection.rollback();

        assertThat(executions.get()).as("it must not have assumed the other command failed").isZero();
    }

    @Test
    @DisplayName("a claim abandoned by a crashed process is taken over, not blocked until expiry")
    void staleClaimIsReclaimed() throws Exception {
        // Without this a crashed process blocks its key until the record expires - up to a day
        // during which the customer's payment simply cannot be retried.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("abandoned".getBytes(StandardCharsets.UTF_8));
        insertExpiredLeaseClaim(key, fingerprint);

        AtomicInteger executions = new AtomicInteger();
        IdempotentExecutor.ExecutionOutcome outcome = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "reclaimed")));
        connection.commit();

        assertThat(outcome.executed()).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(stateOf(key)).isEqualTo(IdempotencyState.COMPLETED);
    }

    @Test
    @DisplayName("only one of two racing reclaims of the same abandoned key wins")
    void reclaimIsNotRacy() throws Exception {
        // The staleness test lives in the database's WHERE clause; if it lived in the caller,
        // both processes would read the same row and both would believe they had won.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("contended".getBytes(StandardCharsets.UTF_8));
        insertExpiredLeaseClaim(key, fingerprint);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger reclaimed = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(
                    () -> {
                        try (Connection own = openConnection()) {
                            own.setAutoCommit(false);
                            start.await();
                            try {
                                inScope(() -> executor().execute(
                                        own, key, fingerprint,
                                        unitOfWork -> recordEffect(unitOfWork, executions, "reclaimed")));
                                reclaimed.incrementAndGet();
                                own.commit();
                            } catch (IdempotencyInProgressException | IdempotencyConflictException expected) {
                                own.rollback();
                            }
                            return null;
                        }
                    });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(reclaimed.get()).as("exactly one reclaim may succeed").isEqualTo(1);
        assertThat(effectCount()).as("and therefore exactly one effect").isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate gives up on a held key within the bounded wait, rather than parking")
    void contendedClaimGivesUpWithinTheBoundedWait() throws Exception {
        // Found in this task's own review: the wrapper's javadoc claimed the wait was bounded
        // while nothing bounded it. Without a lock_timeout a duplicate blocks for as long as the
        // first command takes, and on a hot key with a retrying client that is how a connection
        // pool is exhausted - the failure this design refuses to risk by waiting on IN_PROGRESS.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("held-open".getBytes(StandardCharsets.UTF_8));
        Duration shortWait = Duration.ofMillis(300);

        try (Connection holder = openConnection()) {
            holder.setAutoCommit(false);
            // Claimed but NOT committed: the holder is mid-command.
            new JdbcIdempotencyRecordStore(shortWait)
                    .claim(holder, key, fingerprint, CorrelationId.of("holder-flow"), FIXED,
                            FIXED.plus(RETENTION), LEASE);

            IdempotentExecutor bounded =
                    new IdempotentExecutor(
                            new JdbcIdempotencyRecordStore(shortWait),
                            Clock.fixed(FIXED, ZoneOffset.UTC), RETENTION, LEASE);

            long startedAt = java.lang.System.nanoTime();
            assertThatExceptionOfType(IdempotencyInProgressException.class)
                    .as("an unknown outcome, because the holder may still commit or roll back")
                    .isThrownBy(
                            () ->
                                    inScope(() -> bounded.execute(
                                            connection, key, fingerprint,
                                            unitOfWork -> CommandResult.succeeded(StoredResponse.empty()))));
            Duration waited = Duration.ofNanos(java.lang.System.nanoTime() - startedAt);
            connection.rollback();

            // Both ends matter. An upper bound alone would pass if the claim failed instantly
            // for some unrelated reason; a lower bound alone would pass if it never gave up.
            assertThat(waited)
                    .as("it must actually wait, and then actually give up, near the bound")
                    .isBetween(shortWait.dividedBy(2), Duration.ofSeconds(3));

            holder.rollback();
        }
    }

    @Test
    @DisplayName("recording an outcome twice reports the second as a no-op, not an exception")
    void completingATerminalClaimIsReportedNotThrown() throws SQLException {
        // The store's WHERE state = 'IN_PROGRESS' guard. V003's trigger would reject the second
        // update anyway, so this is defence in depth - but the difference between the two is
        // the difference between a handled race and a stack trace, and a mutation sweep showed
        // nothing exercised it.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("once".getBytes(StandardCharsets.UTF_8));
        store.claim(connection, key, fingerprint, CorrelationId.of("flow"), FIXED, FIXED.plus(RETENTION), LEASE);

        assertThat(store.complete(connection, key, IdempotencyState.COMPLETED, StoredResponse.empty(), FIXED))
                .isTrue();
        assertThat(store.complete(connection, key, IdempotencyState.COMPLETED, StoredResponse.empty(), FIXED))
                .as("already terminal: reported, not thrown")
                .isFalse();
        connection.commit();
    }

    @Test
    @DisplayName("an instance whose clock runs fast cannot steal a live claim from another instance")
    void clockSkewCannotStealALiveClaim() throws Exception {
        // THE DEFECT THIS EXISTS FOR. The lease used to be judged by comparing one instance's
        // created_at against another instance's idea of "long enough ago". With N instances that
        // is a race against clock skew: an instance running six minutes fast considered every
        // claim just made by its neighbour abandoned, took it over, and ran the command while
        // the neighbour was still running it - two financial effects for one request.
        //
        // Every earlier test passed because they all ran in one JVM with one clock. This one
        // gives the second instance a clock an hour ahead, which is far more skew than any real
        // deployment, and the lease must still hold.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("live".getBytes(StandardCharsets.UTF_8));

        // Instance A claims the key and is still working.
        try (Connection instanceA = openConnection()) {
            instanceA.setAutoCommit(false);
            new JdbcIdempotencyRecordStore()
                    .claim(instanceA, key, fingerprint, CorrelationId.of("instance-a"), FIXED,
                            FIXED.plus(RETENTION), LEASE);
            instanceA.commit();
        }

        // Instance B's clock is an hour ahead of A's.
        IdempotentExecutor skewed =
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        Clock.fixed(FIXED.plus(Duration.ofHours(1)), ZoneOffset.UTC),
                        RETENTION,
                        LEASE);

        AtomicInteger executions = new AtomicInteger();
        assertThatExceptionOfType(IdempotencyInProgressException.class)
                .as("the lease is the database's, so B's fast clock buys it nothing")
                .isThrownBy(
                        () ->
                                inScope(() -> skewed.execute(
                                        connection, key, fingerprint,
                                        unitOfWork -> recordEffect(unitOfWork, executions, "stolen"))));
        connection.rollback();

        assertThat(executions.get()).as("the live command must not have been run a second time").isZero();
        assertThat(effectCount()).isZero();
    }

    // -----------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a command outside a correlation scope is refused rather than recorded untraceably")
    void correlationIsRequired() {
        // The NOT NULL could be satisfied with a placeholder. That would be worse: the record
        // would point at no flow at all, so the duplicate it describes could never be traced.
        assertThatThrownBy(
                        () ->
                                executor().execute(
                                        connection, uniqueKey(),
                                        RequestFingerprint.sha256("x".getBytes(StandardCharsets.UTF_8)),
                                        unitOfWork -> CommandResult.succeeded(StoredResponse.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("correlation scope");
    }

    // -----------------------------------------------------------------

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(),
                Clock.fixed(FIXED, ZoneOffset.UTC),
                RETENTION,
                LEASE);
    }

    /** Runs inside a correlation scope, as every money-moving command must. */
    private static <T> T inScope(java.util.function.Supplier<T> work) {
        try (var scope =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("exec-flow-" + SUFFIX.get())))) {
            return work.get();
        }
    }

    private static CommandResult recordEffect(Connection unitOfWork, AtomicInteger executions, String tag) {
        executions.incrementAndGet();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement("INSERT INTO " + EFFECTS + " (tag) VALUES (?)")) {
            insert.setString(1, tag);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the probe effect", e);
        }
        return CommandResult.succeeded(StoredResponse.of(tag.getBytes(StandardCharsets.UTF_8), "text/plain"));
    }

    private static int effectCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + EFFECTS)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static IdempotencyState stateOf(IdempotencyKey key) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT state FROM platform.idempotency_record WHERE scope = ? AND idempotency_key = ?")) {
            select.setString(1, key.scope());
            select.setString(2, key.key());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return IdempotencyState.valueOf(rows.getString(1));
            }
        }
    }

    /**
     * A claim whose lease has already expired, committed by another instance and never finished.
     *
     * <p>The lease is taken with a negative duration so the database sets {@code lease_expires_at}
     * in its own past. That is deliberate: a test that aged the claim by supplying an old
     * client-side {@code created_at} would be exercising the very client-clock staleness this
     * task removed, and would keep passing if the lease were reintroduced on a caller's clock.
     */
    private static void insertExpiredLeaseClaim(IdempotencyKey key, RequestFingerprint fingerprint)
            throws SQLException {
        try (Connection other = openConnection()) {
            other.setAutoCommit(false);
            new JdbcIdempotencyRecordStore()
                    .claim(other, key, fingerprint, CorrelationId.of("crashed-flow"), FIXED,
                            FIXED.plus(RETENTION), Duration.ofSeconds(-1));
            other.commit();
        }
    }

    private static IdempotencyKey uniqueKey() {
        return new IdempotencyKey("exec:transfer-" + SUFFIX.incrementAndGet(), "client-key");
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
