package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The outbox relay against a real PostgreSQL (P0-TSK-020, ADR-0005, ADR-0014).
 *
 * <p><strong>Why a real database, and why more than one connection.</strong> Every property this
 * class asserts is a property of the database: the advisory lock that stops two instances
 * publishing one aggregate, the {@code now()} comparison that decides whether a row is due, and
 * the transaction boundary that makes a crash lose the mark rather than the event. A fake would
 * assert that the relay calls the methods it calls.
 *
 * <p>Each simulated instance opens its own connection, because a test sharing one connection
 * serialises itself and would report the lock working whether or not it existed
 * ({@code DISTRIBUTED_EXECUTION.md} §5).
 */
@Tag("database")
class OutboxRelayTest {

    private static final String TABLE = "platform.outbox_event";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new Random(31L));

    /** Fails fast rather than doubling for tens of seconds inside a test. */
    private static final RetryPolicy IMPATIENT =
            new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 3);

    private static Connection writeConnection;

    /** Stands in for an aggregate identifier owned by a business module in a later phase. */
    static final class ProbeAggregateId extends EntityId {
        ProbeAggregateId(UUID value) {
            super(value);
        }
    }

    /** A crash, not a publication failure: the relay must not treat it as a failed attempt. */
    static final class SimulatedCrash extends Error {
        @Serial private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("the relay process died between publishing and recording publication");
        }
    }

    @BeforeAll
    static void connect() throws SQLException {
        writeConnection = open();
        writeConnection.setAutoCommit(false);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (writeConnection != null) {
            writeConnection.close();
        }
    }

    @BeforeEach
    void emptyTheOutbox() throws SQLException {
        // The whole table, not this class's rows. The relay's candidate query is deliberately
        // global - it publishes whatever is pending, wherever it came from - so a leftover row
        // from another test class would be picked up here and counted.
        try (Statement statement = writeConnection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE);
        }
        writeConnection.commit();
    }

    // -----------------------------------------------------------------
    // Publication
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every committed pending row is published and marked, and never published twice")
    void publishesEveryPendingRowExactlyOncePerSuccess() throws SQLException {
        UUID first = IDS.next();
        UUID second = IDS.next();
        EventId a = writeEvent(first);
        EventId b = writeEvent(second);
        RecordingPublisher publisher = new RecordingPublisher();

        RelayPollResult result = relay(publisher).pollOnce();

        assertThat(result.published()).isEqualTo(2);
        assertThat(publisher.delivered()).containsExactlyInAnyOrder(a, b);
        assertThat(publishedAt(a)).isNotNull();
        assertThat(publishedAt(b)).isNotNull();
        assertThat(attempts(a)).isEqualTo(1);

        // A second cycle finds nothing: a published row is not a pending row.
        RelayPollResult again = relay(publisher).pollOnce();

        assertThat(again.didWork()).isFalse();
        assertThat(publisher.delivered()).hasSize(2);
    }

    @Test
    @DisplayName("an empty outbox is an idle cycle, not an error and not a failed one")
    void anEmptyOutboxIsIdle() {
        RelayPollResult result = relay(new RecordingPublisher()).pollOnce();

        assertThat(result).isEqualTo(RelayPollResult.idle());
        assertThat(result.didWork()).isFalse();
    }

    @Test
    @DisplayName("the publisher receives the envelope, the correlation and the exact payload bytes")
    void whatTheProducerWroteIsWhatThePublisherSees() {
        UUID aggregateId = IDS.next();
        // Hostile to any re-encoding: leading and trailing whitespace, a NUL, and a byte that is
        // not valid UTF-8. Consumers must receive what the producing transaction committed.
        byte[] payload = new byte[] {' ', 0x00, (byte) 0xFF, '{', '}', '\n', ' '};
        EventId eventId = writeEvent(aggregateId, payload, "application/octet-stream");
        RecordingPublisher publisher = new RecordingPublisher();

        relay(publisher).pollOnce();

        PendingEvent seen = publisher.events().get(0);
        assertThat(seen.eventId()).isEqualTo(eventId);
        assertThat(seen.aggregateId()).isEqualTo(aggregateId);
        assertThat(seen.partitionKey()).as("ordering key is the aggregate").isEqualTo(aggregateId);
        assertThat(seen.payload()).isEqualTo(payload);
        assertThat(seen.payloadMediaType()).isEqualTo("application/octet-stream");
        assertThat(seen.correlationId()).isEqualTo(CorrelationId.of("relay-flow"));
        assertThat(seen.causationId()).isEqualTo(CausationId.of("relay-cause"));
        assertThat(seen.eventType()).isEqualTo("transfers.TransferCompleted");
        assertThat(seen.occurredAt()).isEqualTo(OCCURRED);
        assertThat(seen.attempts()).as("not yet attempted").isZero();
    }

    // -----------------------------------------------------------------
    // Ordering
    // -----------------------------------------------------------------

    @Test
    @DisplayName("one aggregate's events are published in the order they were written")
    void orderingIsPreservedWithinAnAggregate() {
        UUID aggregateId = IDS.next();
        List<EventId> written = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            written.add(writeEvent(aggregateId));
        }
        RecordingPublisher publisher = new RecordingPublisher();

        relay(publisher).pollOnce();

        assertThat(publisher.delivered()).containsExactlyElementsOf(written);
    }

    @Test
    @DisplayName("nothing behind a failed event is published, so ordering survives failure too")
    void aFailedEventBlocksTheOnesBehindIt() throws SQLException {
        // Ordering that only holds while everything succeeds is not ordering. This is the case
        // that distinguishes the two.
        UUID aggregateId = IDS.next();
        EventId first = writeEvent(aggregateId);
        EventId second = writeEvent(aggregateId);
        EventId third = writeEvent(aggregateId);
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failOn(second);

        RelayPollResult result = relay(publisher).pollOnce();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(publisher.delivered()).containsExactly(first, second);
        assertThat(publishedAt(third)).as("the event behind the failure stays pending").isNull();
        assertThat(attempts(third)).as("and is not even attempted").isZero();
    }

    // -----------------------------------------------------------------
    // Broker unavailability
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a broker outage costs retries, never rows")
    void brokerUnavailabilityRetriesAndLosesNothing() throws SQLException {
        UUID aggregateId = IDS.next();
        EventId eventId = writeEvent(aggregateId);
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failEverything();
        // A generous attempt limit on purpose: this test is about an outage that is survived,
        // not about one that exhausts the row. Abandonment is a different property with its own
        // test, and letting it happen here would hide the retry it is meant to demonstrate.
        OutboxRelay relay =
                relay(publisher, new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 10));

        for (int cycle = 0; cycle < 3; cycle++) {
            waitUntilDue(eventId);
            assertThat(relay.pollOnce().failed()).isEqualTo(1);
        }

        assertThat(attempts(eventId)).isEqualTo(3);
        assertThat(publishedAt(eventId)).as("still pending, not lost").isNull();
        assertThat(lastError(eventId)).contains("broker is unavailable");

        // And when the broker comes back, the row that was never lost is published.
        publisher.recover();
        waitUntilDue(eventId);

        assertThat(relay.pollOnce().published()).isEqualTo(1);
        assertThat(publishedAt(eventId)).isNotNull();
        assertThat(lastError(eventId)).as("cleared on success").isNull();
    }

    @Test
    @DisplayName("a failed row is not retried until its backoff has elapsed")
    void backoffDelaysTheNextAttempt() throws SQLException {
        // Without this, an unavailable broker is retried on every cycle by every instance: the
        // retry storm outlives the fault that caused it.
        UUID aggregateId = IDS.next();
        EventId eventId = writeEvent(aggregateId);
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failEverything();
        RetryPolicy patient =
                new RetryPolicy(Duration.ofMinutes(5), Duration.ofMinutes(10), 10);
        OutboxRelay relay = relay(publisher, patient);

        assertThat(relay.pollOnce().failed()).isEqualTo(1);
        RelayPollResult immediately = relay.pollOnce();

        assertThat(immediately.didWork()).as("nothing is due yet").isFalse();
        assertThat(publisher.deliveryCount()).isEqualTo(1);
        assertThat(attempts(eventId)).isEqualTo(1);
        assertThat(nextAttemptAt(eventId)).isAfter(Instant.now().plusSeconds(60));
    }

    // -----------------------------------------------------------------
    // Poison messages
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an event that cannot be published is abandoned and blocks its aggregate")
    void anExhaustedEventIsAbandonedAndBlocksWhatFollows() throws SQLException {
        // The alternative - skipping the poisoned event and carrying on - is quiet: consumers
        // receive events 1 and 3 with no way to know 2 existed. A stall somebody has to look at
        // is strictly better than an undetectable gap in a financial event stream (V006).
        UUID aggregateId = IDS.next();
        EventId poisoned = writeEvent(aggregateId);
        EventId behind = writeEvent(aggregateId);
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failOn(poisoned);
        OutboxRelay relay = relay(publisher);

        RelayPollResult last = null;
        for (int cycle = 0; cycle < IMPATIENT.maxAttempts(); cycle++) {
            waitUntilDue(poisoned);
            last = relay.pollOnce();
        }

        assertThat(last).isNotNull();
        assertThat(last.deadLettered()).isEqualTo(1);
        assertThat(deadLetteredAt(poisoned)).isNotNull();
        assertThat(attempts(poisoned)).isEqualTo(IMPATIENT.maxAttempts());

        // The aggregate is now stalled. Even with a publisher that would accept anything, the
        // event behind the abandoned one does not go out.
        publisher.recover();
        RelayPollResult afterRecovery = relay.pollOnce();

        assertThat(afterRecovery.didWork()).isFalse();
        assertThat(publishedAt(behind)).isNull();
        assertThat(attempts(behind)).isZero();
    }

    @Test
    @DisplayName("abandoning one aggregate does not stall the others")
    void aBlockedAggregateDoesNotBlockThePlatform() throws SQLException {
        UUID blocked = IDS.next();
        UUID healthy = IDS.next();
        EventId poisoned = writeEvent(blocked);
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failOn(poisoned);
        OutboxRelay relay = relay(publisher);
        for (int cycle = 0; cycle < IMPATIENT.maxAttempts(); cycle++) {
            waitUntilDue(poisoned);
            relay.pollOnce();
        }
        EventId unrelated = writeEvent(healthy);

        RelayPollResult result = relay.pollOnce();

        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt(unrelated)).isNotNull();
        assertThat(deadLetteredAt(poisoned)).isNotNull();
    }

    @Test
    @DisplayName("an abandoned event mid-stream stops the drain, not just an abandoned head")
    void anAbandonedEventBehindAPublishableOneStillBlocks() throws SQLException {
        // Two guards enforce this, and a mutation sweep found each one hiding the other: the
        // candidate query never offers an aggregate whose HEAD is abandoned, so a test seeding
        // the poison first never reaches the relay's own stop condition, and removing that
        // condition changed nothing anybody could see.
        //
        // It is not decorative. The candidate list is read outside the aggregate's lock, so
        // between choosing an aggregate and draining it another instance may have failed or
        // abandoned an event further down. This inner check is what stops the relay publishing
        // past it, and this is the shape that reaches it.
        UUID aggregateId = IDS.next();
        EventId first = writeEvent(aggregateId);
        EventId poisoned = writeEvent(aggregateId);
        EventId behind = writeEvent(aggregateId);
        abandon(poisoned);
        RecordingPublisher publisher = new RecordingPublisher();

        RelayPollResult result = relay(publisher).pollOnce();

        assertThat(publisher.delivered()).containsExactly(first);
        assertThat(publishedAt(first)).isNotNull();
        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt(behind)).as("blocked behind the abandoned event").isNull();
        assertThat(attempts(behind)).isZero();
    }

    @Test
    @DisplayName("an event still backing off mid-stream stops the drain")
    void anEventNotYetDueBehindAPublishableOneStillBlocks() throws SQLException {
        // Same masking as above, for the other stop condition: the candidate query filters on
        // the head's next_attempt_at, so only a backing-off event further down reaches the
        // relay's own due check. Publishing past it would reorder the aggregate purely because
        // one of its events had failed once.
        UUID aggregateId = IDS.next();
        EventId first = writeEvent(aggregateId);
        EventId backingOff = writeEvent(aggregateId);
        EventId behind = writeEvent(aggregateId);
        delayNextAttempt(backingOff, Duration.ofHours(1));
        RecordingPublisher publisher = new RecordingPublisher();

        RelayPollResult result = relay(publisher).pollOnce();

        assertThat(publisher.delivered()).containsExactly(first);
        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt(behind)).isNull();
        assertThat(attempts(behind)).isZero();
    }

    // -----------------------------------------------------------------
    // Crash recovery — the reason the outbox exists
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a crash between publishing and recording it republishes rather than losing")
    void aCrashAfterPublishingRepublishesOnRestart() throws SQLException {
        // "The database commits but the response is lost", from the broker's side. The relay
        // publishes and then records publication, and there is no transaction spanning the two.
        // A crash in between must republish - at-least-once - because the alternative ordering
        // loses the event, and losing is worse than repeating (ADR-0005).
        UUID aggregateId = IDS.next();
        EventId eventId = writeEvent(aggregateId);
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.crashAfterDelivering();

        assertThatExceptionOfType(SimulatedCrash.class).isThrownBy(() -> relay(publisher).pollOnce());

        assertThat(publisher.deliveryCount()).as("the broker did receive it").isEqualTo(1);
        assertThat(publishedAt(eventId)).as("but nothing recorded that").isNull();
        assertThat(attempts(eventId)).as("and the rolled-back attempt left no trace").isZero();

        // Restart.
        publisher.recover();
        RelayPollResult afterRestart = relay(publisher).pollOnce();

        assertThat(afterRestart.published()).isEqualTo(1);
        assertThat(publisher.deliveryCount()).as("delivered twice: at least once, not exactly once").isEqualTo(2);
        assertThat(publishedAt(eventId)).isNotNull();
    }

    // -----------------------------------------------------------------
    // Multiple instances (ADR-0014)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("two instances never publish the same aggregate at the same time")
    void oneAggregateIsDrainedByOneInstanceAtATime() throws Exception {
        UUID aggregateId = IDS.next();
        writeEvent(aggregateId);
        writeEvent(aggregateId);
        BlockingPublisher held = new BlockingPublisher();
        RecordingPublisher other = new RecordingPublisher();

        ExecutorService instanceA = Executors.newSingleThreadExecutor();
        try {
            Future<RelayPollResult> a = instanceA.submit(() -> relay(held).pollOnce());
            // Instance A is now inside publish(), holding the aggregate's advisory lock and its
            // transaction. This is a real overlap, not a hoped-for one: B polls while A is
            // provably mid-publication.
            awaitEntryOrExplain(held, a);

            RelayPollResult b = relay(other).pollOnce();

            assertThat(b.aggregatesDrained()).as("the aggregate is taken").isZero();
            assertThat(other.deliveryCount()).as("no duplicate publication").isZero();

            held.release();
            assertThat(a.get(30, TimeUnit.SECONDS).published()).isEqualTo(2);
        } finally {
            held.release();
            instanceA.shutdownNow();
        }
    }

    @Test
    @DisplayName("the lock is per aggregate: a second instance works on a different one meanwhile")
    void differentAggregatesProceedConcurrently() throws Exception {
        // Without this, a relay that took one global lock would pass the test above while
        // serialising the entire platform behind whichever instance polled first.
        UUID held = IDS.next();
        UUID free = IDS.next();
        writeEvent(held);
        EventId independent = writeEvent(free);
        BlockingPublisher blocking = new BlockingPublisher();
        RecordingPublisher other = new RecordingPublisher();

        ExecutorService instanceA = Executors.newSingleThreadExecutor();
        try {
            Future<RelayPollResult> a = instanceA.submit(() -> relay(blocking).pollOnce());
            awaitEntryOrExplain(blocking, a);

            RelayPollResult b = relay(other).pollOnce();

            assertThat(b.published()).isEqualTo(1);
            assertThat(other.delivered()).containsExactly(independent);

            blocking.release();
            a.get(30, TimeUnit.SECONDS);
        } finally {
            blocking.release();
            instanceA.shutdownNow();
        }
    }

    @Test
    @DisplayName("eight instances polling one backlog publish every event exactly once between them")
    void concurrentInstancesPublishEachEventOnce() throws Exception {
        int aggregates = 12;
        int perAggregate = 3;
        List<EventId> written = new ArrayList<>();
        for (int a = 0; a < aggregates; a++) {
            UUID aggregateId = IDS.next();
            for (int e = 0; e < perAggregate; e++) {
                written.add(writeEvent(aggregateId));
            }
        }
        RecordingPublisher shared = new RecordingPublisher();
        int instances = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                running.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    OutboxRelay relay = relay(shared);
                                    // Until the BACKLOG is empty, not until this instance stops
                                    // finding work. An instance refused every lock in a cycle
                                    // did no work and is not finished - it lost a race. Stopping
                                    // on its own idleness let all eight quit with events still
                                    // pending, which is how this test first failed: intermittently,
                                    // and looking exactly like a relay that loses events.
                                    Instant deadline = Instant.now().plusSeconds(60);
                                    while (pendingCountOnOwnConnection() > 0) {
                                        if (Instant.now().isAfter(deadline)) {
                                            throw new IllegalStateException(
                                                    "the cluster did not drain the backlog in time");
                                        }
                                        relay.pollOnce();
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : running) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(shared.delivered())
                .as("no event published twice, and none missed")
                .containsExactlyInAnyOrderElementsOf(written);
        assertThat(pendingCount()).isZero();
    }

    // -----------------------------------------------------------------
    // Publishers
    // -----------------------------------------------------------------

    /** Records what it was given, and fails on demand. */
    private static final class RecordingPublisher implements EventPublisher {
        private final List<PendingEvent> events = Collections.synchronizedList(new ArrayList<>());
        private volatile EventId failing;
        private volatile boolean failEverything;
        private volatile boolean crash;

        @Override
        public void publish(PendingEvent event) {
            events.add(event);
            if (crash) {
                throw new SimulatedCrash();
            }
            if (failEverything || event.eventId().equals(failing)) {
                throw new IllegalStateException("the broker is unavailable");
            }
        }

        void failOn(EventId eventId) {
            this.failing = eventId;
        }

        void failEverything() {
            this.failEverything = true;
        }

        void crashAfterDelivering() {
            this.crash = true;
        }

        void recover() {
            this.failing = null;
            this.failEverything = false;
            this.crash = false;
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

    /**
     * Waits for the held instance to reach the publisher, and explains itself when it does not.
     *
     * <p>Without this the latch simply times out, and the message says only that the instance
     * never arrived — which is true of a relay that threw, a relay that found nothing pending,
     * and a relay blocked on a lock alike. The instance's own outcome is the evidence, and a
     * test that discards it makes every failure look like the same failure.
     */
    private static void awaitEntryOrExplain(BlockingPublisher held, Future<RelayPollResult> instance)
            throws Exception {
        if (held.awaitEntry()) {
            return;
        }
        held.release();
        Object outcome;
        try {
            outcome = instance.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            outcome = e;
        }
        throw new AssertionError(
                "the held instance never reached the publisher; its poll returned/threw: " + outcome
                        + ", pending rows: " + pendingCount());
    }

    /** Blocks inside {@code publish} so a second instance can be observed while the first holds. */
    private static final class BlockingPublisher implements EventPublisher {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger delivered = new AtomicInteger();

        @Override
        public void publish(PendingEvent event) {
            if (delivered.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    // Far longer than it can need. Bounding this tightly would make the test
                    // fail for a reason unrelated to what it asserts on a slow machine.
                    if (!released.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the publisher");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }

        boolean awaitEntry() throws InterruptedException {
            return entered.await(30, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }

    // -----------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------

    private static OutboxRelay relay(EventPublisher publisher) {
        return relay(publisher, IMPATIENT);
    }

    private static OutboxRelay relay(EventPublisher publisher, RetryPolicy policy) {
        // A fresh connection per call, so simulated instances share nothing.
        return new OutboxRelay(OutboxRelayTest::open, publisher, policy, 8, 100);
    }

    private static EventId writeEvent(UUID aggregateId) {
        return writeEvent(aggregateId, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static EventId writeEvent(UUID aggregateId, byte[] payload, String mediaType) {
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "transfers.TransferCompleted",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeAggregateId(aggregateId),
                        "Transfer",
                        OCCURRED,
                        "relay-probe",
                        CorrelationId.of("relay-flow"),
                        CausationId.of("relay-cause"));
        try {
            new JdbcOutboxWriter().write(writeConnection, envelope, payload, mediaType);
            writeConnection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed an outbox row", e);
        }
        return envelope.eventId();
    }

    /**
     * Waits until the server considers the row eligible again.
     *
     * <p>Polls the database rather than sleeping for the backoff: the row's eligibility is
     * decided by the server's clock, so the test must ask the server rather than assume its own
     * clock agrees.
     */
    private static void waitUntilDue(EventId eventId) throws SQLException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            try (PreparedStatement select =
                    writeConnection.prepareStatement(
                            "SELECT next_attempt_at <= now() FROM " + TABLE + " WHERE event_id = ?")) {
                select.setObject(1, eventId.value());
                try (ResultSet rows = select.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        writeConnection.commit();
                        return;
                    }
                }
            }
            // Ends the read transaction so the next iteration sees a fresh now().
            writeConnection.commit();
            Thread.onSpinWait();
        }
        throw new IllegalStateException("event " + eventId.value() + " never became due");
    }

    /**
     * Puts a row into the state a previous relay cycle would have left it in.
     *
     * <p>Written directly rather than driven through the relay because the relay can only
     * abandon an aggregate's <em>head</em>, and the state under test is an abandoned event with
     * a publishable one in front of it — reachable in production when another instance fails
     * that event between this one reading the candidate list and taking the lock.
     */
    private static void abandon(EventId eventId) throws SQLException {
        try (PreparedStatement update =
                writeConnection.prepareStatement(
                        "UPDATE " + TABLE + " SET attempts = 3, dead_lettered_at = now(), "
                                + "last_error = 'probe' WHERE event_id = ?")) {
            update.setObject(1, eventId.value());
            update.executeUpdate();
        }
        writeConnection.commit();
    }

    private static void delayNextAttempt(EventId eventId, Duration delay) throws SQLException {
        try (PreparedStatement update =
                writeConnection.prepareStatement(
                        "UPDATE " + TABLE + " SET attempts = 1, "
                                + "next_attempt_at = now() + (? * INTERVAL '1 millisecond') "
                                + "WHERE event_id = ?")) {
            update.setLong(1, delay.toMillis());
            update.setObject(2, eventId.value());
            update.executeUpdate();
        }
        writeConnection.commit();
    }

    private static Instant publishedAt(EventId eventId) throws SQLException {
        return instantColumn(eventId, "published_at");
    }

    private static Instant deadLetteredAt(EventId eventId) throws SQLException {
        return instantColumn(eventId, "dead_lettered_at");
    }

    private static Instant nextAttemptAt(EventId eventId) throws SQLException {
        return instantColumn(eventId, "next_attempt_at");
    }

    private static Instant instantColumn(EventId eventId, String column) throws SQLException {
        Timestamp value = readColumn(eventId, column, Timestamp.class);
        return value == null ? null : value.toInstant();
    }

    private static int attempts(EventId eventId) throws SQLException {
        return readColumn(eventId, "attempts", Integer.class);
    }

    private static String lastError(EventId eventId) throws SQLException {
        return readColumn(eventId, "last_error", String.class);
    }

    private static <T> T readColumn(EventId eventId, String column, Class<T> type)
            throws SQLException {
        try (PreparedStatement select =
                writeConnection.prepareStatement(
                        "SELECT " + column + " FROM " + TABLE + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                T value = rows.getObject(1, type);
                return value;
            }
        } finally {
            // The reader runs in its own transaction; ending it keeps later reads from seeing a
            // snapshot taken before the relay committed.
            writeConnection.commit();
        }
    }

    /**
     * Pending rows, read on a connection of this thread's own.
     *
     * <p>The shared {@code writeConnection} is the main thread's; a simulated instance reading
     * through it would serialise against the very concurrency the test exists to create.
     */
    private static int pendingCountOnOwnConnection() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM " + TABLE + " WHERE published_at IS NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static int pendingCount() throws SQLException {
        try (Statement statement = writeConnection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM " + TABLE + " WHERE published_at IS NULL")) {
            rows.next();
            return rows.getInt(1);
        } finally {
            writeConnection.commit();
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
