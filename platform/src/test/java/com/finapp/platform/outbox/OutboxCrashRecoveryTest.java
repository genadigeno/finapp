package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The canonical failure {@code CLAUDE.md} §Failure Engineering names: <em>the database commits
 * but the response is lost</em>, end to end.
 *
 * <p><strong>What this adds over the tests that already exist.</strong> {@code OutboxWriterTest}
 * proves the outbox row commits with the fact, and {@code OutboxRelayTest} proves a crash
 * <em>after</em> publishing republishes. Neither joins the whole chain — business fact, outbox
 * row, relay, publisher — across a process that dies before publication, which is the scenario
 * the outbox exists for and the one where a mistake is invisible: the fact is committed, nobody
 * is told, and nothing reports it.
 *
 * <p><strong>Run as the application role.</strong> The relay's connections come from
 * {@code finapp_app}, exactly as they will in production, so {@code V008}'s grants on
 * {@code outbox_event} are exercised rather than assumed — the other outbox suites connect as
 * the bootstrap superuser and would pass with no grants at all.
 *
 * <p><strong>The acceptance criterion is
 * {@link #aRolledBackFactLeavesTheRelayNothingToPublish()}.</strong> If the outbox write were
 * moved out of the business transaction, that test fails: the row would commit on its own and
 * the relay would announce a fact that never happened. Demonstrated by doing it — see the
 * task's completion notes.
 */
@Tag("database")
class OutboxCrashRecoveryTest {

    private static final String OUTBOX = "platform.outbox_event";
    private static final String FACTS = "platform.crash_fact_probe";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new Random(101L));
    private static final CorrelationId FLOW = CorrelationId.of("crash-recovery-flow");

    private static Connection business;

    /** Stands in for an aggregate identifier owned by a business module in a later phase. */
    static final class ProbeTransferId extends EntityId {
        ProbeTransferId(UUID value) {
            super(value);
        }
    }

    @BeforeAll
    static void connect() throws SQLException {
        // The probe table needs DDL, which the application role deliberately lacks. Created by
        // the migrator and used by the application - the same split production has, rather than
        // widening a grant to make a test convenient.
        try (Connection migrator = migrator();
                Statement statement = migrator.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + FACTS
                            + " (aggregate_id UUID PRIMARY KEY, description TEXT NOT NULL)");
            statement.execute("GRANT SELECT, INSERT, DELETE ON " + FACTS + " TO finapp_app");
        }
        business = application();
        business.setAutoCommit(false);
    }

    @AfterAll
    static void dropProbeAndDisconnect() throws SQLException {
        if (business != null) {
            business.close();
        }
        try (Connection migrator = migrator();
                Statement statement = migrator.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + FACTS);
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Statement statement = business.createStatement()) {
            statement.executeUpdate("DELETE FROM " + OUTBOX);
            statement.executeUpdate("DELETE FROM " + FACTS);
        }
        business.commit();
    }

    // -----------------------------------------------------------------
    // The scenario the task names
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a committed fact is published after the relay restarts, exactly once, with its correlation")
    void aCommittedFactSurvivesARelayThatNeverRan() throws SQLException {
        // The business transaction commits. The relay then dies before it publishes anything -
        // modelled here as never having run, which is indistinguishable from a process that was
        // killed between the commit and its first poll.
        UUID aggregateId = IDS.next();
        EventId eventId = commitFactAndEvent(aggregateId, "transfer-completed");

        assertThat(factExists(aggregateId)).as("the fact is durable").isTrue();
        assertThat(publishedAt(eventId)).as("and nobody has been told").isNull();

        // Restart: a new relay, new connections, no memory of anything.
        RecordingPublisher publisher = new RecordingPublisher();
        RelayPollResult afterRestart = relay(publisher).pollOnce();

        assertThat(afterRestart.published()).isEqualTo(1);
        assertThat(publisher.delivered()).containsExactly(eventId);
        assertThat(publisher.events().get(0).correlationId())
                .as("the flow that produced the fact is still attached to its announcement")
                .isEqualTo(FLOW);
        assertThat(publisher.events().get(0).aggregateId()).isEqualTo(aggregateId);
        assertThat(publishedAt(eventId)).isNotNull();

        // A further restart publishes nothing more. "Exactly once" here is a claim about this
        // scenario - crash BEFORE publication - not about the relay in general, which is
        // at-least-once by design and republishes when it dies after publishing instead.
        RelayPollResult again = relay(publisher).pollOnce();

        assertThat(again.didWork()).isFalse();
        assertThat(publisher.deliveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an instance killed mid-publication releases its aggregate, and another finishes the job")
    void aKilledInstanceDoesNotStrandItsAggregate() throws Exception {
        // A real kill, not a rollback: the instance's database backend is terminated while it
        // holds the aggregate's advisory lock and an open transaction.
        //
        // The property is that the aggregate is not stranded. The relay's lock is
        // transaction-scoped precisely so a crash cannot leak it - a session-scoped lock on a
        // pooled connection would outlive the code meant to release it, and that aggregate would
        // stop publishing forever with nothing to show why. This is what proves the choice.
        UUID aggregateId = IDS.next();
        EventId eventId = commitFactAndEvent(aggregateId, "transfer-completed");
        BlockingPublisher dying = new BlockingPublisher();

        ExecutorService instanceA = Executors.newSingleThreadExecutor();
        try {
            Future<?> a = instanceA.submit(() -> relay(dying).pollOnce());
            assertThat(dying.awaitEntry()).as("instance A reached the publisher").isTrue();

            int killed = terminateBackendsHoldingAnAggregateLock();
            assertThat(killed).as("a backend must have been holding the aggregate lock").isEqualTo(1);
            dying.release();

            // The instance dies rather than returning: its connection is gone mid-transaction.
            assertThatCode(() -> a.get(30, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
        } finally {
            dying.release();
            instanceA.shutdownNow();
            assertThat(instanceA.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(publishedAt(eventId)).as("the kill left the row pending, not published").isNull();

        // A surviving instance picks it up. If the lock had leaked, this poll would find the
        // aggregate permanently taken and publish nothing.
        RecordingPublisher survivor = new RecordingPublisher();
        RelayPollResult recovered = relay(survivor).pollOnce();

        assertThat(recovered.published()).isEqualTo(1);
        assertThat(survivor.delivered()).containsExactly(eventId);
        assertThat(survivor.events().get(0).correlationId()).isEqualTo(FLOW);
    }

    // -----------------------------------------------------------------
    // The acceptance criterion
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a rolled-back fact leaves the relay nothing to publish")
    void aRolledBackFactLeavesTheRelayNothingToPublish() throws SQLException {
        // THE criterion: "test fails if the outbox write is moved outside the business
        // transaction". Move it out - give the writer its own connection and let it commit - and
        // the row survives this rollback, the relay finds it, and an event is published for a
        // transfer that never happened.
        //
        // Asserted here at the RELAY rather than at the writer, where OutboxWriterTest already
        // checks the row is gone. The difference matters: a missing row is a fact about storage,
        // whereas an announcement nobody can retract is the consequence, and it is the
        // consequence that makes the atomicity worth having.
        UUID aggregateId = IDS.next();
        EventId eventId = writeFactAndEvent(aggregateId, "transfer-attempted");
        business.rollback();

        assertThat(factExists(aggregateId)).as("the fact rolled back").isFalse();

        RecordingPublisher publisher = new RecordingPublisher();
        RelayPollResult result = relay(publisher).pollOnce();

        assertThat(result.didWork()).as("there is nothing to announce").isFalse();
        assertThat(publisher.deliveryCount())
                .as("an event published here would describe something that never happened")
                .isZero();
        assertThat(exists(eventId)).as("and no row was left behind to publish later").isFalse();
    }

    @Test
    @DisplayName("a fact and its announcement cannot disagree, whichever way the transaction goes")
    void theFactAndItsAnnouncementAgree() throws SQLException {
        // The pair, run together so neither direction can be satisfied by a writer that simply
        // never writes: one transaction commits and one rolls back, and the relay's output is
        // exactly the committed one.
        UUID committed = IDS.next();
        EventId announced = commitFactAndEvent(committed, "transfer-completed");

        UUID abandoned = IDS.next();
        EventId neverHappened = writeFactAndEvent(abandoned, "transfer-abandoned");
        business.rollback();

        RecordingPublisher publisher = new RecordingPublisher();
        relay(publisher).pollOnce();

        // The row's absence is asserted as well as the publisher's silence, and deliberately.
        // Asserting only what the publisher saw makes this test depend on the rolled-back row
        // being DUE: under the mutation this criterion exists to catch, the surviving row's
        // eligibility turns on the server clock, which on this machine steps backwards - so the
        // test detected the fault in one run and not the next. A row that must not exist is a
        // stronger and clock-independent statement of the same property.
        assertThat(exists(neverHappened))
                .as("no row may survive a rolled-back fact, due or not")
                .isFalse();
        assertThat(publisher.delivered()).containsExactly(announced);
        assertThat(factExists(committed)).isTrue();
        assertThat(factExists(abandoned)).isFalse();
    }

    // -----------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------

    /** Writes the fact and its announcement in one transaction, and commits. */
    private static EventId commitFactAndEvent(UUID aggregateId, String description)
            throws SQLException {
        EventId eventId = writeFactAndEvent(aggregateId, description);
        business.commit();
        return eventId;
    }

    /**
     * Writes the fact and its announcement on one connection, uncommitted.
     *
     * <p>One connection, one transaction, both writes — which is the whole design. The writer is
     * handed this connection rather than opening its own, and that is what the acceptance
     * criterion is about.
     */
    private static EventId writeFactAndEvent(UUID aggregateId, String description)
            throws SQLException {
        try (PreparedStatement insert =
                business.prepareStatement(
                        "INSERT INTO " + FACTS + " (aggregate_id, description) VALUES (?, ?)")) {
            insert.setObject(1, aggregateId);
            insert.setString(2, description);
            insert.executeUpdate();
        }
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "transfers.TransferCompleted",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeTransferId(aggregateId),
                        "Transfer",
                        OCCURRED,
                        "crash-probe",
                        FLOW,
                        CausationId.of("command-1"));
        new JdbcOutboxWriter()
                .write(business, envelope, description.getBytes(StandardCharsets.UTF_8), "text/plain");
        backDate(envelope.eventId());
        return envelope.eventId();
    }

    /** See {@code OutboxRelayTest.backDate}: the local server clock steps backwards. */
    private static void backDate(EventId eventId) throws SQLException {
        try (PreparedStatement update =
                business.prepareStatement(
                        "UPDATE " + OUTBOX + " SET next_attempt_at = now() - INTERVAL '1 minute' "
                                + "WHERE event_id = ?")) {
            update.setObject(1, eventId.value());
            update.executeUpdate();
        }
    }

    /**
     * Terminates every backend holding an outbox aggregate lock.
     *
     * <p>{@code pg_terminate_backend} is as close to killing a process as a test can get without
     * one: the connection dies mid-transaction, with no chance to roll back or release anything
     * politely. Run as the superuser because terminating another role's backend requires it.
     */
    private static int terminateBackendsHoldingAnAggregateLock() throws SQLException {
        try (Connection admin = superuser();
                PreparedStatement select =
                        admin.prepareStatement(
                                "SELECT pg_terminate_backend(l.pid) FROM pg_locks l "
                                        + "WHERE l.locktype = 'advisory' AND l.classid = 1 "
                                        + "AND l.granted AND l.pid <> pg_backend_pid()")) {
            try (ResultSet rows = select.executeQuery()) {
                int terminated = 0;
                while (rows.next()) {
                    terminated++;
                }
                return terminated;
            }
        }
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(
                OutboxCrashRecoveryTest::application,
                publisher,
                new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 5),
                8,
                100);
    }

    private static boolean factExists(UUID aggregateId) throws SQLException {
        try (PreparedStatement select =
                business.prepareStatement("SELECT 1 FROM " + FACTS + " WHERE aggregate_id = ?")) {
            select.setObject(1, aggregateId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        } finally {
            business.commit();
        }
    }

    private static boolean exists(EventId eventId) throws SQLException {
        try (PreparedStatement select =
                business.prepareStatement("SELECT 1 FROM " + OUTBOX + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        } finally {
            business.commit();
        }
    }

    private static Instant publishedAt(EventId eventId) throws SQLException {
        try (PreparedStatement select =
                business.prepareStatement(
                        "SELECT published_at FROM " + OUTBOX + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                java.sql.Timestamp value = rows.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        } finally {
            business.commit();
        }
    }

    // -----------------------------------------------------------------

    private static final class RecordingPublisher implements EventPublisher {
        private final List<PendingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(PendingEvent event) {
            events.add(event);
        }

        List<PendingEvent> events() {
            return List.copyOf(events);
        }

        List<EventId> delivered() {
            return events().stream().map(PendingEvent::eventId).toList();
        }

        int deliveryCount() {
            return events.size();
        }
    }

    /** Blocks inside {@code publish} so the instance can be killed while it holds the lock. */
    private static final class BlockingPublisher implements EventPublisher {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void publish(PendingEvent event) {
            entered.countDown();
            try {
                if (!released.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the publisher");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        boolean awaitEntry() throws InterruptedException {
            return entered.await(30, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }

    private static Connection application() throws SQLException {
        return DriverManager.getConnection(
                required("finapp.db.url"),
                required("finapp.db.app.user"),
                required("finapp.db.app.password"));
    }

    private static Connection migrator() throws SQLException {
        return DriverManager.getConnection(
                required("finapp.db.url"),
                required("finapp.db.migrator.user"),
                required("finapp.db.migrator.password"));
    }

    private static Connection superuser() throws SQLException {
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
