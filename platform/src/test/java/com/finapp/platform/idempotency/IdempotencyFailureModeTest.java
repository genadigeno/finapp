package com.finapp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.correlation.Correlation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.correlation.CorrelationId;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The failure modes from {@code CLAUDE.md} §Failure Engineering, applied to idempotency
 * (P0-TST-004).
 *
 * <p><strong>No test here relies on timing luck.</strong> That is an explicit acceptance
 * criterion and it is harder than it sounds: starting eight threads from a latch makes them
 * *begin* together, but the winner can finish before any loser reaches the claim, so the test
 * would pass without ever exercising contention — and would keep passing if contention were
 * broken. Where a test needs the losers to be genuinely blocked, it waits until <em>PostgreSQL
 * itself reports them waiting on a lock</em>, then proceeds. That is an observation, not a
 * sleep.
 *
 * <p><strong>What this adds over {@link IdempotentExecutorTest}.</strong> That class proves the
 * mechanism's contract. This one drives the specific failures the platform is required to
 * survive: a lost response, an expired key, a crashed instance mid-command, and contention
 * whose outcome depends on how long the holder takes.
 */
@Tag("database")
@SuppressWarnings("try") // A correlation Scope is used for its close side effect.
class IdempotencyFailureModeTest {

    private static final String EFFECTS = "failure_mode_effect_probe";
    private static final Instant FIXED = Instant.parse("2026-09-01T12:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static Connection connection;
    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @BeforeAll
    static void connect() throws SQLException {
        connection = openConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
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
            statement.executeUpdate("DELETE FROM platform.idempotency_record WHERE scope LIKE 'fail:%'");
            statement.executeUpdate("DELETE FROM " + EFFECTS);
        }
        connection.commit();
    }

    // -----------------------------------------------------------------
    // Two threads, one key — with contention actually observed
    // -----------------------------------------------------------------

    @Test
    @DisplayName("losers blocked on the key replay the winner's response once it commits")
    void observedContentionResolvesToOneEffectAndIdenticalResponses() throws Exception {
        // Deterministic: the winner does not commit until the database reports the losers
        // waiting on a lock. Without that the winner could finish first and the losers would
        // never contend, so the test would pass while proving nothing about contention.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("transfer".getBytes(StandardCharsets.UTF_8));
        int losers = 4;
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch winnerHasClaimed = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(losers + 1)) {
            Future<IdempotentExecutor.ExecutionOutcome> winner =
                    pool.submit(
                            () -> {
                                try (Connection own = openConnection()) {
                                    own.setAutoCommit(false);
                                    IdempotentExecutor.ExecutionOutcome outcome =
                                            inScope(() -> executor().execute(
                                                    own, key, fingerprint,
                                                    unitOfWork -> {
                                                        // The claim is committed-pending here: the
                                                        // insert has happened, the transaction has not.
                                                        winnerHasClaimed.countDown();
                                                        awaitLockWaiters(losers);
                                                        return recordEffect(unitOfWork, executions, "winner");
                                                    }));
                                    own.commit();
                                    return outcome;
                                }
                            });

            assertThat(winnerHasClaimed.await(30, TimeUnit.SECONDS)).as("winner claimed").isTrue();

            List<Callable<IdempotentExecutor.ExecutionOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < losers; i++) {
                tasks.add(
                        () -> {
                            try (Connection own = openConnection()) {
                                own.setAutoCommit(false);
                                IdempotentExecutor.ExecutionOutcome outcome =
                                        inScope(() -> executor().execute(
                                                own, key, fingerprint,
                                                unitOfWork -> recordEffect(unitOfWork, executions, "loser")));
                                own.commit();
                                return outcome;
                            }
                        });
            }
            List<Future<IdempotentExecutor.ExecutionOutcome>> losing =
                    tasks.stream().map(pool::submit).toList();

            IdempotentExecutor.ExecutionOutcome winning = winner.get(60, TimeUnit.SECONDS);
            assertThat(winning.executed()).isTrue();

            for (Future<IdempotentExecutor.ExecutionOutcome> future : losing) {
                IdempotentExecutor.ExecutionOutcome outcome = future.get(60, TimeUnit.SECONDS);
                assertThat(outcome.replayed()).as("a blocked loser must replay, not re-run").isTrue();
                assertThat(outcome.response()).isEqualTo(winning.response());
            }
        }

        assertThat(executions.get()).as("exactly one command execution").isEqualTo(1);
        assertThat(effectCount()).as("exactly one financial effect").isEqualTo(1);
    }

    @Test
    @DisplayName("a loser whose bounded wait expires is told the outcome is unknown, not left hanging")
    void contentionLongerThanTheBoundedWaitIsDeterministic() throws Exception {
        // The other half of contention, and the one that must not hang. The holder keeps its
        // claim uncommitted for longer than the loser is willing to wait; the loser must get a
        // deterministic answer rather than parking on the index.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("slow".getBytes(StandardCharsets.UTF_8));
        Duration shortWait = Duration.ofMillis(250);
        CountDownLatch holderHasClaimed = new CountDownLatch(1);
        CountDownLatch loserHasAnswered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(1);
                Connection holder = openConnection()) {
            holder.setAutoCommit(false);
            Future<?> held =
                    pool.submit(
                            () -> {
                                new JdbcIdempotencyRecordStore(shortWait)
                                        .claim(holder, key, fingerprint, CorrelationId.of("holder"),
                                                FIXED, FIXED.plus(RETENTION), LEASE);
                                holderHasClaimed.countDown();
                                // Held until the loser has been answered - so the loser's
                                // outcome is decided while the claim is genuinely uncommitted.
                                loserHasAnswered.await(30, TimeUnit.SECONDS);
                                holder.rollback();
                                return null;
                            });

            assertThat(holderHasClaimed.await(30, TimeUnit.SECONDS)).isTrue();

            IdempotentExecutor impatient =
                    new IdempotentExecutor(
                            new JdbcIdempotencyRecordStore(shortWait),
                            Clock.fixed(FIXED, ZoneOffset.UTC), RETENTION, LEASE);

            assertThatExceptionOfType(IdempotencyInProgressException.class)
                    .isThrownBy(
                            () ->
                                    inScope(() -> impatient.execute(
                                            connection, key, fingerprint,
                                            unitOfWork -> recordEffect(unitOfWork, executions, "loser"))));
            connection.rollback();
            loserHasAnswered.countDown();
            held.get(30, TimeUnit.SECONDS);
        }

        assertThat(executions.get()).as("it must not have run the command").isZero();
    }

    // -----------------------------------------------------------------
    // The database commits but the response is lost
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a retry after the response was lost replays it, and does not re-run the command")
    void responseLostAfterCommitIsReplayed() throws Exception {
        // CLAUDE.md §Failure Engineering: "the database commits but the response is lost".
        // From the client's side this is indistinguishable from a timeout, so it retries - and
        // the effect has already happened.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("pay:500".getBytes(StandardCharsets.UTF_8));
        AtomicInteger executions = new AtomicInteger();

        IdempotentExecutor.ExecutionOutcome committed;
        try (Connection first = openConnection()) {
            first.setAutoCommit(false);
            committed = inScope(() -> executor().execute(
                    first, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "paid")));
            first.commit();
        }
        // The response never reaches the client. Nothing about the committed state records that.

        IdempotentExecutor.ExecutionOutcome afterRetry = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "paid-again")));
        connection.commit();

        assertThat(executions.get()).as("the payment must have happened once").isEqualTo(1);
        assertThat(effectCount()).isEqualTo(1);
        assertThat(afterRetry.replayed()).isTrue();
        assertThat(afterRetry.response()).isEqualTo(committed.response());
    }

    // -----------------------------------------------------------------
    // Expiry
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an expired but unswept record still replays, because replaying is always safe")
    void expiredRecordStillReplays() throws SQLException {
        // The retry path does not consult expires_at, deliberately. Refusing to replay a record
        // merely because retention could have removed it would turn a safe answer into a
        // re-execution, which is the one outcome that costs money.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("old".getBytes(StandardCharsets.UTF_8));
        AtomicInteger executions = new AtomicInteger();

        // Created with a one-second retention, so the record is already past its expiry rather
        // than aged afterwards. Ageing it afterwards is impossible by design: V003 freezes a
        // terminal claim entirely, expires_at included, and the trigger refused the attempt
        // while this test was being written. That is the guard working - and it means retention
        // can never be *extended* on a completed record, only decided when it is written.
        // A clock two days in the past with a one-second retention, so expires_at is
        // unambiguously behind real time rather than behind this test's fixed instant - which
        // is itself in the future on some days and would make the premise silently false.
        Instant longAgo = Instant.now().minus(Duration.ofDays(2));
        IdempotentExecutor shortRetention =
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        Clock.fixed(longAgo, ZoneOffset.UTC),
                        Duration.ofSeconds(1),
                        LEASE);

        IdempotentExecutor.ExecutionOutcome first = inScope(() -> shortRetention.execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "original")));
        connection.commit();
        assertThat(expiryOf(key)).as("the record is past its retention").isBefore(Instant.now());

        IdempotentExecutor.ExecutionOutcome afterExpiry = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "again")));
        connection.commit();

        assertThat(executions.get()).isEqualTo(1);
        assertThat(afterExpiry.replayed()).isTrue();
        assertThat(afterExpiry.response()).isEqualTo(first.response());
    }

    @Test
    @DisplayName("once retention has swept the record, the same key runs again — the documented cost of short expiry")
    void sweptRecordIsReExecuted() throws SQLException {
        // Not a defect; the consequence DATA_MIGRATIONS.md §8 warns about. It is asserted so the
        // behaviour is known rather than discovered, and so that anyone shortening the retention
        // window can see what they are buying: a retry arriving after the sweep produces a
        // second financial effect, because nothing is left to say the first one happened.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("swept".getBytes(StandardCharsets.UTF_8));
        AtomicInteger executions = new AtomicInteger();

        inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "first")));
        connection.commit();
        sweep(key);

        IdempotentExecutor.ExecutionOutcome afterSweep = inScope(() -> executor().execute(
                connection, key, fingerprint, unitOfWork -> recordEffect(unitOfWork, executions, "second")));
        connection.commit();

        assertThat(afterSweep.executed()).isTrue();
        assertThat(executions.get()).as("two effects — which is why retention must exceed the retry window").isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // An instance crashes while IN_PROGRESS
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a crash after the claim commits leaves the key recoverable exactly once")
    void crashedInstanceClaimIsRecoveredOnceOnly() throws Exception {
        // The instance committed its claim and then died - no rollback, no outcome. Two
        // surviving instances then retry. The lease must let exactly one take over.
        IdempotencyKey key = uniqueKey();
        RequestFingerprint fingerprint = RequestFingerprint.sha256("crashed".getBytes(StandardCharsets.UTF_8));
        try (Connection dying = openConnection()) {
            dying.setAutoCommit(false);
            new JdbcIdempotencyRecordStore()
                    .claim(dying, key, fingerprint, CorrelationId.of("dead-instance"), FIXED,
                            FIXED.plus(RETENTION), Duration.ofSeconds(-1)); // lease already expired
            dying.commit();
        }

        AtomicInteger executions = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<Void>> survivors = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            survivors.add(
                    () -> {
                        try (Connection own = openConnection()) {
                            own.setAutoCommit(false);
                            start.await();
                            try {
                                inScope(() -> executor().execute(
                                        own, key, fingerprint,
                                        unitOfWork -> recordEffect(unitOfWork, executions, "recovered")));
                                recoveries.incrementAndGet();
                                own.commit();
                            } catch (IdempotencyInProgressException expected) {
                                own.rollback();
                            }
                            return null;
                        }
                    });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Void>> futures = survivors.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(recoveries.get()).as("exactly one instance may recover the key").isEqualTo(1);
        assertThat(effectCount()).as("and therefore exactly one effect").isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Blocks until PostgreSQL reports {@code expected} sessions waiting on a lock.
     *
     * <p>This is what makes the contention test deterministic. Sleeping for "long enough" would
     * be the timing luck the acceptance criterion forbids: it would usually work, would
     * occasionally not, and would keep passing if contention stopped happening at all.
     */
    private static void awaitLockWaiters(int expected) {
        Instant deadline = Instant.now().plusSeconds(30);
        try (Connection observer = openConnection()) {
            while (Instant.now().isBefore(deadline)) {
                try (PreparedStatement select =
                                observer.prepareStatement(
                                        "SELECT count(*) FROM pg_stat_activity "
                                                + "WHERE wait_event_type = 'Lock' "
                                                + "AND query LIKE '%idempotency_record%'");
                        ResultSet rows = select.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= expected) {
                        return;
                    }
                }
            }
            throw new IllegalStateException(
                    "Timed out waiting for " + expected + " sessions to block on the key; "
                            + "without observed contention this test proves nothing");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not observe lock waiters", e);
        }
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), Clock.fixed(FIXED, ZoneOffset.UTC), RETENTION, LEASE);
    }

    private static <T> T inScope(java.util.function.Supplier<T> work) {
        try (var scope =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("fail-flow-" + SUFFIX.get())))) {
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

    /** When a record's retention window ends. */
    private static Instant expiryOf(IdempotencyKey key) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT expires_at FROM platform.idempotency_record "
                                + "WHERE scope = ? AND idempotency_key = ?")) {
            select.setString(1, key.scope());
            select.setString(2, key.key());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    /** What the retention job will do. */
    private static void sweep(IdempotencyKey key) throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement(
                        "DELETE FROM platform.idempotency_record WHERE scope = ? AND idempotency_key = ?")) {
            delete.setString(1, key.scope());
            delete.setString(2, key.key());
            delete.executeUpdate();
        }
        connection.commit();
    }

    private static int effectCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + EFFECTS)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static IdempotencyKey uniqueKey() {
        return new IdempotencyKey("fail:command-" + SUFFIX.incrementAndGet(), "client-key");
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
