package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.outbox.PendingEvent;
import com.finapp.platform.outbox.RetryPolicy;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The counted case of `P15-TSK-008`, against PostgreSQL: <strong>stop an instance during a claimed batch; the batch is
 * finished by its owner, or - interrupted past the drain's bound - taken by a second instance; one effect per key.</strong>
 * Two {@link OutboxRelaySchedule}s over one database stand for two instances, each with its own executor, its own
 * connections and its own publisher. The relay is the schedule every instance runs on every event the platform emits. The
 * effect counted is a publication the broker accepted: the publisher records an event only when it returns normally.
 *
 * <p><strong>The claim here is the relay's</strong>: a transaction-scoped advisory lock on the aggregate, released when the
 * transaction ends - at commit, at rollback, or when a killed process's connection drops. The timed lease's reclaim, the
 * other kind of claim the schedules take, is {@code CloseRunDatabaseTest#aDeadWorkersCheckIsReclaimed}'s.
 *
 * <p><strong>Written and owed</strong> (owner decision C14: no database tier per task; it runs at the phase's one pass,
 * {@code P15-TST-002}). Its own container: the relay's candidate query is global, and on the shared database it would
 * publish every other suite's rows. The hermetic half is {@code ScheduleDrainTest}'s two counted cases.
 */
@Tag("database")
@Tag("own-container") // the relay reads the whole outbox: a database of its own, as the javadoc says
@DisplayName("a relay stopped inside a claimed batch: finished by it, or taken by the next instance, one publication per event (P15-TSK-008)")
class ScheduleDrainDatabaseTest {

    private static final String TABLE = "platform.outbox_event";
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final RetryPolicy IMPATIENT = new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 10);
    private static final Duration POLL = Duration.ofMillis(50);

    private final Map<UUID, List<String>> accepted = new ConcurrentHashMap<>();

    /** Stands in for a business module's aggregate identifier. */
    static final class ProbeAggregateId extends EntityId {
        ProbeAggregateId(UUID value) {
            super(value);
        }
    }

    @BeforeEach
    void anEmptyOutbox() throws SQLException {
        try (Connection connection = DatabaseRoles.bootstrap(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE);
        }
        accepted.clear();
    }

    @Test
    @DisplayName("drained: A, stopped while its publisher holds a claimed aggregate, finishes it; every event published once")
    void aDrainedBatchIsFinishedByItsOwner() throws Exception {
        List<EventId> written = seed(3, 3);
        Holding holdingA = new Holding("A");
        OutboxRelaySchedule a = schedule(holdingA);
        a.start();
        assertThat(holdingA.entered.await(30, TimeUnit.SECONDS)).as("A holds a claimed aggregate").isTrue();
        UUID heldAggregate = holdingA.heldAggregate;

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(a::stop);
        for (int poll = 0; poll < 100 && a.isRunning(); poll++) {
            Thread.sleep(20);
        }
        assertThat(a.isRunning()).as("A stopped claiming").isFalse();
        Thread.sleep(300);
        assertThat(stopping).as("A's stop waits for its batch").isNotDone();
        holdingA.release.countDown();
        stopping.get(60, TimeUnit.SECONDS);

        OutboxRelaySchedule b = schedule(new Holding("B", false));
        b.start();
        awaitDrained();
        b.stop();

        assertThat(accepted.keySet()).as("every event published").containsExactlyInAnyOrderElementsOf(
                written.stream().map(EventId::value).toList());
        assertThat(accepted).as("one publication per event").allSatisfy((event, by) -> assertThat(by).hasSize(1));
        assertThat(eventsOf(heldAggregate)).as("the held aggregate's batch finished by A, its owner")
                .allSatisfy(event -> assertThat(accepted.get(event)).containsExactly("A"));
    }

    @Test
    @DisplayName("interrupted: A's publisher never returns, the bound passes, its claim is released, B publishes it; once each")
    void anInterruptedBatchIsTakenByTheNextInstance() throws Exception {
        List<EventId> written = seed(3, 3);
        Holding holdingA = new Holding("A"); // never released: interrupted by A's drain past its bound
        OutboxRelaySchedule a = schedule(holdingA);
        a.start();
        assertThat(holdingA.entered.await(30, TimeUnit.SECONDS)).isTrue();
        UUID heldEvent = holdingA.heldEvent;

        a.stop(); // returns past its bound: the interrupt failed the held publication, and its transaction ended
        assertThat(accepted).as("A's interrupted publication was never accepted").doesNotContainKey(heldEvent);

        OutboxRelaySchedule b = schedule(new Holding("B", false));
        b.start();
        awaitDrained();
        b.stop();

        assertThat(accepted.keySet()).as("every event published").containsExactlyInAnyOrderElementsOf(
                written.stream().map(EventId::value).toList());
        assertThat(accepted).as("one publication per event").allSatisfy((event, by) -> assertThat(by).hasSize(1));
        assertThat(accepted.get(heldEvent)).as("the event A held, taken by B once A's claim was released")
                .containsExactly("B");
    }

    // ------------------------------------------------------------------------------------------------------------------

    private OutboxRelaySchedule schedule(EventPublisher publisher) {
        return new OutboxRelaySchedule(new OutboxRelay(DatabaseRoles::application, publisher, IMPATIENT, 8, 100), POLL,
                new SimpleMeterRegistry());
    }

    /** A publisher that records what the broker accepted; its first publication holds until released, interruptibly. */
    private final class Holding implements EventPublisher {
        private final String instance;
        private final boolean holdsFirst;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean held;
        private volatile UUID heldEvent;
        private volatile UUID heldAggregate;

        private Holding(String instance) {
            this(instance, true);
        }

        private Holding(String instance, boolean holdsFirst) {
            this.instance = instance;
            this.holdsFirst = holdsFirst;
        }

        @Override
        public void publish(PendingEvent event) {
            if (holdsFirst && !held) {
                held = true;
                heldEvent = event.eventId().value();
                heldAggregate = event.aggregateId();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.MINUTES)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (InterruptedException interrupted) {
                    // As a real adapter waiting on the broker's acknowledgement: not accepted.
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted before the broker accepted", interrupted);
                }
            }
            accepted.computeIfAbsent(event.eventId().value(), ignored -> new CopyOnWriteArrayList<>()).add(instance);
        }
    }

    private static List<EventId> seed(int aggregates, int eventsEach) throws SQLException {
        List<EventId> written = new ArrayList<>();
        try (Connection connection = DatabaseRoles.bootstrap()) {
            connection.setAutoCommit(false);
            for (int aggregate = 0; aggregate < aggregates; aggregate++) {
                UUID aggregateId = IDS.next();
                for (int event = 0; event < eventsEach; event++) {
                    EventEnvelope envelope = new EventEnvelope(EventId.next(IDS), "transfers.TransferCompleted", 1,
                            EventEnvelope.CURRENT_SCHEMA_VERSION, new ProbeAggregateId(aggregateId), "Transfer",
                            Instant.parse("2026-11-08T12:00:00Z"), "drain-probe", CorrelationId.of("drain-flow"),
                            CausationId.of("drain-cause"));
                    new JdbcOutboxWriter().write(connection, envelope, "{}".getBytes(StandardCharsets.UTF_8),
                            "application/json");
                    written.add(envelope.eventId());
                }
            }
            // Due at once whatever the container's clock does between this commit and the relay's read.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + TABLE + " SET next_attempt_at = now() - INTERVAL '1 minute'");
            }
            connection.commit();
        }
        return written;
    }

    private static List<UUID> eventsOf(UUID aggregate) throws SQLException {
        List<UUID> events = new ArrayList<>();
        try (Connection connection = DatabaseRoles.bootstrap();
                PreparedStatement select = connection.prepareStatement(
                        "SELECT event_id FROM " + TABLE + " WHERE aggregate_id = ?")) {
            select.setObject(1, aggregate);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    events.add(rows.getObject(1, UUID.class));
                }
            }
        }
        assertThat(events).as("the held aggregate's events").hasSize(3);
        return events;
    }

    private static void awaitDrained() throws Exception {
        for (int poll = 0; poll < 600; poll++) {
            try (Connection connection = DatabaseRoles.bootstrap();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + TABLE + " WHERE published_at IS NULL")) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the outbox never drained");
    }
}
