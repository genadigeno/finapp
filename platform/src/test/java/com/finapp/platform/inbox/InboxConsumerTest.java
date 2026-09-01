package com.finapp.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
 * The inbox against a real PostgreSQL ({@code INV-IDEM-04}, ADR-0005).
 *
 * <p><strong>Effects are counted, never inferred.</strong> Every "exactly once" assertion here
 * reads a row count from a side-effect table rather than trusting the wrapper's own return
 * value. A wrapper that reported {@code SKIPPED_DUPLICATE} while having run the handler would
 * pass any test that believed it, and that is the precise defect the class exists to prevent.
 *
 * <p><strong>Why a real database.</strong> The arbitration between two instances handed the same
 * redelivery is performed by a primary-key conflict, and the bounded wait is
 * {@code lock_timeout}. Neither is a property of this code.
 */
@Tag("database")
@SuppressWarnings("try") // A correlation Scope is used for its close side effect.
class InboxConsumerTest {

    private static final String TABLE = "platform.inbox_message";
    private static final String EFFECTS = "inbox_effect_probe";
    private static final Instant PROCESSED = Instant.parse("2026-09-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(PROCESSED, ZoneOffset.UTC);
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final CorrelationId FLOW = CorrelationId.of("inbox-flow");

    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        connection = open();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            // An ordinary table: a TEMPORARY one is session-local and so invisible to the
            // racing connections in the concurrency test, which is a trap this project has
            // already fallen into once (P0-TSK-016).
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + EFFECTS
                            + " (id BIGSERIAL PRIMARY KEY, dedupe_key TEXT NOT NULL)");
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
            statement.executeUpdate("DELETE FROM " + TABLE);
            statement.executeUpdate("DELETE FROM " + EFFECTS);
        }
        connection.commit();
    }

    // -----------------------------------------------------------------
    // INV-IDEM-04 — the acceptance criterion
    // -----------------------------------------------------------------

    @Test
    @DisplayName("redelivering the same message produces no second effect")
    void redeliveryProducesNoSecondEffect() throws SQLException {
        InboxKey key = key("message-1");

        InboxConsumer.Outcome first = consume(connection, key, recordEffect(key));
        connection.commit();
        InboxConsumer.Outcome second = consume(connection, key, recordEffect(key));
        connection.commit();
        InboxConsumer.Outcome third = consume(connection, key, recordEffect(key));
        connection.commit();

        assertThat(first).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(second).isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        assertThat(third).isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        assertThat(effectCount(key)).as("counted, not inferred from the outcome").isEqualTo(1);
    }

    @Test
    @DisplayName("the dedupe record and the side effect commit together")
    void theRecordAndTheEffectCommitTogether() throws SQLException {
        InboxKey key = key("message-atomic");

        consume(connection, key, recordEffect(key));
        connection.commit();

        assertThat(recordExists(key)).isTrue();
        assertThat(effectCount(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back handler takes its dedupe record with it, so the message is redelivered")
    void aRolledBackHandlerLeavesNothingBehind() throws SQLException {
        // The failure that matters most. If the record could survive a rolled-back handler the
        // message would be recorded as processed and its effect would never happen - a message
        // lost silently, which no later redelivery can repair because the inbox now refuses it.
        InboxKey key = key("message-rollback");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () ->
                                consume(
                                        connection,
                                        key,
                                        unitOfWork -> {
                                            recordEffect(key).handle(unitOfWork);
                                            throw new IllegalStateException("the handler failed");
                                        }));
        connection.rollback();

        assertThat(recordExists(key)).as("no dedupe record").isFalse();
        assertThat(effectCount(key)).as("and no effect").isZero();

        // And the redelivery is handled rather than skipped.
        assertThat(consume(connection, key, recordEffect(key)))
                .isEqualTo(InboxConsumer.Outcome.PROCESSED);
        connection.commit();
        assertThat(effectCount(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("a crash before commit leaves nothing, so the message is redelivered and handled once")
    void aCrashBeforeCommitIsRecoveredByRedelivery() throws SQLException {
        // "The service crashes" from CLAUDE.md §Failure Engineering, at the worst moment: after
        // the handler has done its work and before anything is durable.
        InboxKey key = key("message-crash");

        consume(connection, key, recordEffect(key));
        connection.rollback(); // the instance dies here

        assertThat(recordExists(key)).isFalse();
        assertThat(effectCount(key)).isZero();

        consume(connection, key, recordEffect(key));
        connection.commit();

        assertThat(effectCount(key)).as("exactly one effect across the crash").isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The consumer is part of the key
    // -----------------------------------------------------------------

    @Test
    @DisplayName("each consumer processes the same message once, independently of the others")
    void everyConsumerHandlesTheMessage() throws SQLException {
        // The defect this catches - deduplicating on the message alone - does not look like a
        // defect. Everything succeeds, one consumer runs, and the others silently never see the
        // message. It surfaces months later as "the notification never arrived", with the event
        // visibly published and no error recorded anywhere.
        String dedupeKey = "shared-message";
        InboxKey ledger = new InboxKey("ledger-projector", dedupeKey);
        InboxKey notifier = new InboxKey("notification-sender", dedupeKey);

        InboxConsumer.Outcome first = consume(connection, ledger, recordEffect(ledger));
        InboxConsumer.Outcome second = consume(connection, notifier, recordEffect(notifier));
        connection.commit();

        assertThat(first).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(second).as("a second consumer is not a duplicate").isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(effectCount(ledger)).isEqualTo(1);
        assertThat(effectCount(notifier)).isEqualTo(1);

        // And each is still deduplicated on its own.
        assertThat(consume(connection, ledger, recordEffect(ledger)))
                .isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        connection.commit();
        assertThat(effectCount(ledger)).isEqualTo(1);
    }

    @Test
    @DisplayName("different messages for one consumer are each handled")
    void differentMessagesAreNotConfused() throws SQLException {
        // A positive control. Without it, a store that reported every message as a duplicate
        // would pass every assertion above.
        InboxKey first = key("message-a");
        InboxKey second = key("message-b");

        assertThat(consume(connection, first, recordEffect(first)))
                .isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(consume(connection, second, recordEffect(second)))
                .isEqualTo(InboxConsumer.Outcome.PROCESSED);
        connection.commit();

        assertThat(effectCount(first)).isEqualTo(1);
        assertThat(effectCount(second)).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Multiple instances (ADR-0014)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("eight instances handed the same redelivery produce exactly one effect")
    void concurrentDuplicatesProduceOneEffect() throws Exception {
        InboxKey key = key("message-concurrent");
        int instances = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<InboxConsumer.Outcome>> deliveries = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            deliveries.add(
                    () -> {
                        // A connection of its own: a test sharing one connection serialises
                        // itself and would report the constraint working whether or not it did.
                        try (Connection own = open()) {
                            own.setAutoCommit(false);
                            start.await();
                            InboxConsumer.Outcome outcome = consume(own, key, recordEffect(key));
                            own.commit();
                            return outcome;
                        }
                    });
        }

        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<InboxConsumer.Outcome> outcomes = new ArrayList<>();
        try {
            List<Future<InboxConsumer.Outcome>> running = new ArrayList<>();
            for (Callable<InboxConsumer.Outcome> delivery : deliveries) {
                running.add(pool.submit(delivery));
            }
            start.countDown();
            for (Future<InboxConsumer.Outcome> future : running) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(effectCount(key)).as("one effect, whatever the instances believed").isEqualTo(1);
        assertThat(outcomes).filteredOn(InboxConsumer.Outcome.PROCESSED::equals).hasSize(1);
        assertThat(outcomes)
                .as("every loser is told something true: handled already, or handled by someone now")
                .allMatch(
                        outcome ->
                                outcome == InboxConsumer.Outcome.PROCESSED
                                        || outcome == InboxConsumer.Outcome.SKIPPED_DUPLICATE
                                        || outcome == InboxConsumer.Outcome.CONTENDED);
    }

    @Test
    @DisplayName("a delivery contended by an open transaction is reported, not waited on forever")
    void contentionIsBoundedAndReported() throws Exception {
        // Unbounded waiting is how a consumer pool is exhausted during exactly the traffic spike
        // that produced the duplicates. Giving up costs one redelivery, which the broker was
        // going to perform anyway.
        InboxKey key = key("message-contended");
        Duration wait = Duration.ofMillis(250);

        try (Connection holder = open()) {
            holder.setAutoCommit(false);
            // The holder claims the key and does not commit, standing in for another instance
            // still inside its handler.
            consume(holder, key, unitOfWork -> {});

            // Counted in memory, not in the database. The transaction is rolled back a moment
            // later, so a handler that HAD run would leave no row either - an effect-count
            // assertion here cannot tell "did not run" from "ran and was undone", and a mutation
            // sweep confirmed it caught neither. An in-memory counter survives the rollback.
            AtomicInteger handlerRuns = new AtomicInteger();

            Instant before = Instant.now();
            InboxConsumer.Outcome outcome;
            try (CorrelationContext.Scope ignored =
                    CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                outcome =
                        new InboxConsumer<Connection>(new JdbcInboxRecordStore(wait), CLOCK, RETENTION)
                                .consume(
                                        connection,
                                        key,
                                        "probe.Message",
                                        unitOfWork -> {
                                            handlerRuns.incrementAndGet();
                                            recordEffect(key).handle(unitOfWork);
                                        });
            }
            Duration waited = Duration.between(before, Instant.now());
            connection.rollback();

            assertThat(outcome).isEqualTo(InboxConsumer.Outcome.CONTENDED);
            assertThat(handlerRuns).hasValue(0);
            assertThat(effectCount(key)).as("and no effect reached the database").isZero();
            assertThat(waited)
                    .as("bounded above: it gave up rather than blocking on the other transaction")
                    .isLessThan(Duration.ofSeconds(10));
            // Bounded below, so a failure for some unrelated reason cannot masquerade as a
            // timeout - but at 80% of the configured wait rather than at it. PostgreSQL's
            // lock_timeout fires on its own polling granularity and the measurement starts a
            // moment after the statement does, so an observed 247ms against a 250ms timeout is
            // the mechanism working. Asserting the exact value would be measuring the timer's
            // precision, which is not this test's subject.
            assertThat(waited)
                    .as("it waited, rather than failing instantly for some unrelated reason")
                    .isGreaterThanOrEqualTo(wait.multipliedBy(4).dividedBy(5));

            holder.rollback();
        }
    }

    // -----------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------

    @Test
    @DisplayName("consuming without a correlation in scope is refused rather than invented")
    void correlationIsRequired() {
        // Defaulting to a fresh identifier would write a row claiming a flow that never existed
        // and quietly break the chain from the producing command to this effect.
        InboxKey key = key("message-uncorrelated");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () ->
                                new InboxConsumer<Connection>(
                                                new JdbcInboxRecordStore(), CLOCK, RETENTION)
                                        .consume(connection, key, "probe.Message", u -> {}))
                .withMessageContaining("No correlation is in scope");
    }

    @Test
    @DisplayName("an auto-commit connection is refused, because the record would commit alone")
    void anAutoCommitConnectionIsRefused() throws SQLException {
        InboxKey key = key("message-autocommit");

        try (Connection autoCommit = open()) {
            assertThatExceptionOfType(InboxStorageException.class)
                    .isThrownBy(() -> consume(autoCommit, key, recordEffect(key)))
                    .withMessageContaining("auto-commit");
        }
    }

    @Test
    @DisplayName("a retention that expires on arrival is refused")
    void retentionMustOutliveProcessing() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () -> new InboxConsumer<Connection>(new JdbcInboxRecordStore(), CLOCK, Duration.ZERO));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new InboxConsumer<Connection>(
                                        new JdbcInboxRecordStore(), CLOCK, Duration.ofSeconds(-1)));
    }

    // -----------------------------------------------------------------
    // What is written
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the record carries the flow, the message type, and an expiry set by the server")
    void theRecordIsDiagnosable() throws SQLException {
        InboxKey key = key("message-recorded");

        consume(connection, key, u -> {});
        connection.commit();

        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT message_type, correlation_id, processed_at, expires_at, "
                                + "expires_at > now() FROM " + TABLE
                                + " WHERE consumer = ? AND dedupe_key = ?")) {
            select.setString(1, key.consumer());
            select.setString(2, key.dedupeKey());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("message_type")).isEqualTo("probe.Message");
                assertThat(rows.getString("correlation_id")).isEqualTo(FLOW.value());
                assertThat(rows.getTimestamp("processed_at").toInstant())
                        .as("the application clock, from one injected Clock")
                        .isEqualTo(PROCESSED);
                assertThat(rows.getBoolean(5))
                        .as(
                                "the expiry is the server's now() plus the retention, not the "
                                        + "application clock's - which here is years in the past")
                        .isTrue();
            }
        }
        connection.commit();
    }

    // -----------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------

    private static InboxKey key(String dedupeKey) {
        return new InboxKey("probe-consumer", dedupeKey);
    }

    /** Runs the wrapper inside a correlation scope, as a real consumer does from the envelope. */
    @SuppressWarnings("try") // A correlation Scope is used for its close side effect.
    private static InboxConsumer.Outcome consume(
            Connection unitOfWork, InboxKey key, InboxConsumer.Handler<Connection> handler) {
        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(FLOW))) {
            return new InboxConsumer<Connection>(new JdbcInboxRecordStore(), CLOCK, RETENTION)
                    .consume(unitOfWork, key, "probe.Message", handler);
        }
    }

    private static InboxConsumer.Handler<Connection> recordEffect(InboxKey key) {
        return unitOfWork -> {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO " + EFFECTS + " (dedupe_key) VALUES (?)")) {
                insert.setString(1, key.toString());
                insert.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("the probe handler could not write its effect", e);
            }
        };
    }

    private static int effectCount(InboxKey key) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT count(*) FROM " + EFFECTS + " WHERE dedupe_key = ?")) {
            select.setString(1, key.toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } finally {
            connection.commit();
        }
    }

    private static boolean recordExists(InboxKey key) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT 1 FROM " + TABLE + " WHERE consumer = ? AND dedupe_key = ?")) {
            select.setString(1, key.consumer());
            select.setString(2, key.dedupeKey());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        } finally {
            connection.commit();
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                required("finapp.db.url"), required("finapp.db.user"), required("finapp.db.password"));
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
