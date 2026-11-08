package com.finapp.platform.inbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.inbox.EventNotYetReadable;
import com.finapp.platform.inbox.InboxConnectionSource;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxParking;
import com.finapp.platform.inbox.InboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A poison record is set aside; everything else about the partition stands (`P15-TSK-028`).
 *
 * <p>Over a {@code MockConsumer} and a fake {@link InboxParking} whose count survives across polls, as the row does across
 * instances. The real statements and the race across connections are {@code DeadLetterDatabaseTest}'s.
 */
@DisplayName("a poison record is parked and its group continues (P15-TSK-028)")
class KafkaEventReceiverParkingTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String TOPIC = "finapp.probe";
    private static final String TYPE = "probe.SomethingHappened";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final List<String> journal = new ArrayList<>();
    private final FakeStore store = new FakeStore();
    private final FakeParking parking = new FakeParking(2);
    private MockConsumer<String, byte[]> consumer;
    private SQLException commitFailure;

    @BeforeEach
    void freshConsumer() {
        consumer = new MockConsumer<>("earliest") {
            @Override
            public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
                journal.add("offsets-acknowledged");
                super.commitSync(offsets);
            }
        };
        consumer.assign(List.of(PARTITION));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
    }

    @Test
    @DisplayName("a record failing for a reason of its own stalls until its budget is spent, then parks and is acknowledged")
    void aPoisonRecordParksAfterItsBudget() {
        KafkaEventReceiver receiver = receiver(failing(new IllegalArgumentException("unreadable payload")));
        EventId event = EventId.next(IDS);
        consumer.addRecord(record(0, event));

        ReceiverPollResult first = receiver.pollOnce();

        assertThat(first.failed()).as("inside its budget: stalled, not parked").isEqualTo(1);
        assertThat(first.parked()).isZero();
        assertThat(consumer.position(PARTITION)).isZero();
        assertThat(journal).doesNotContain("offsets-acknowledged");

        consumer.addRecord(record(0, event));
        ReceiverPollResult second = receiver.pollOnce();

        assertThat(second.parked()).isEqualTo(1);
        assertThat(second.failed()).isZero();
        assertThat(parking.standingOf("probe.consumer", event)).isEqualTo(InboxParking.Standing.PARKED);
        assertThat(committedOffset()).as("the group has moved on").isEqualTo(1L);
    }

    @Test
    @DisplayName("a parked record does not block the next record of its partition")
    void aParkedRecordDoesNotBlockItsPartition() {
        AtomicInteger later = new AtomicInteger();
        EventId poison = EventId.next(IDS);
        parking.failuresSoFar.put(new InboxKey("probe.consumer", poison.value().toString()), 1);
        KafkaEventReceiver receiver = receiver(handler("probe.consumer", (connection, event) -> {
            if (event.eventId().equals(poison)) {
                throw new IllegalArgumentException("poison");
            }
            later.incrementAndGet();
        }));
        consumer.addRecord(record(0, poison));
        consumer.addRecord(record(1, EventId.next(IDS)));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.parked()).isEqualTo(1);
        assertThat(result.processed()).as("the very next record, in the same poll").isEqualTo(1);
        assertThat(later).hasValue(1);
        assertThat(committedOffset()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a transient failure is never counted, however often it happens, and never parks a valid record")
    void aTransientFailureNeverParks() {
        KafkaEventReceiver receiver = receiver(failing(new RuntimeException(new SQLException("connection lost", "08006"))));
        EventId event = EventId.next(IDS);

        for (int attempt = 0; attempt < 20; attempt++) {
            consumer.addRecord(record(0, event));
            consumer.seek(PARTITION, 0L);
            assertThat(receiver.pollOnce().failed()).isEqualTo(1);
        }

        assertThat(parking.counted).as("nothing counted").isEmpty();
        assertThat(parking.standingOf("probe.consumer", event)).isEqualTo(InboxParking.Standing.NONE);
        assertThat(consumer.position(PARTITION)).isZero();
    }

    @Test
    @DisplayName("a build too old to read a version never parks the record")
    void anUnreadableVersionNeverParks() {
        KafkaEventReceiver receiver = receiver(failing(new EventNotYetReadable("newer than this build")));
        EventId event = EventId.next(IDS);

        for (int attempt = 0; attempt < 5; attempt++) {
            consumer.addRecord(record(0, event));
            consumer.seek(PARTITION, 0L);
            assertThat(receiver.pollOnce().failed()).isEqualTo(1);
        }

        assertThat(parking.counted).isEmpty();
    }

    @Test
    @DisplayName("a contended claim is never counted")
    void aContendedClaimNeverCounts() {
        store.contended = true;
        KafkaEventReceiver receiver = receiver(failing(new IllegalStateException("never runs")));
        consumer.addRecord(record(0, EventId.next(IDS)));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.contended()).isEqualTo(1);
        assertThat(parking.counted).isEmpty();
        assertThat(consumer.position(PARTITION)).isZero();
    }

    @Test
    @DisplayName("a refused broker credential leaves the poll as a refusal, and nothing is counted")
    void aRefusedCredentialNeverParks() {
        KafkaEventReceiver receiver = new KafkaEventReceiver(
                new MockConsumer<>("earliest") {
                    @Override
                    public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
                        throw new GroupAuthorizationException("not allowed");
                    }
                },
                "finapp.probe",
                connections(),
                inbox(),
                parking,
                List.of(failing(new IllegalStateException("never runs"))),
                Duration.ofMillis(50));

        assertThatThrownBy(receiver::pollOnce).isInstanceOf(BrokerAccessRefusedException.class);
        assertThat(parking.counted).isEmpty();
    }

    @Test
    @DisplayName("the offset does not move when the park could not be written")
    void theOffsetMovesOnlyAfterTheParkIsDurable() {
        EventId event = EventId.next(IDS);
        parking.failuresSoFar.put(new InboxKey("probe.consumer", event.value().toString()), 1);
        parking.failWith = new IllegalStateException("the database went away");
        KafkaEventReceiver receiver = receiver(failing(new IllegalArgumentException("poison")));
        consumer.addRecord(record(0, event));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.parked()).isZero();
        assertThat(consumer.position(PARTITION)).isZero();
        assertThat(journal).doesNotContain("offsets-acknowledged");
    }

    @Test
    @DisplayName("the offset does not move when the park's commit fails, and it moves only after the park's commit")
    void theParkCommitsBeforeTheOffset() {
        EventId event = EventId.next(IDS);
        parking.failuresSoFar.put(new InboxKey("probe.consumer", event.value().toString()), 1);
        commitFailure = new SQLException("commit failed", "08006");
        KafkaEventReceiver receiver = receiver(failing(new IllegalArgumentException("poison")));
        consumer.addRecord(record(0, event));

        assertThat(receiver.pollOnce().failed()).isEqualTo(1);
        assertThat(journal).doesNotContain("offsets-acknowledged");

        // The commit that failed took the count with it, which this fake, not being a transaction, must be told.
        parking.failuresSoFar.put(new InboxKey("probe.consumer", event.value().toString()), 1);
        parking.standing.clear();
        commitFailure = null;
        journal.clear();
        consumer.addRecord(record(0, event));
        consumer.seek(PARTITION, 0L);
        assertThat(receiver.pollOnce().parked()).isEqualTo(1);
        assertThat(journal).containsSubsequence("committed", "offsets-acknowledged");
    }

    @Test
    @DisplayName("a redelivery of a parked record is acknowledged without running the handler")
    void aRedeliveredParkedRecordIsSkipped() {
        AtomicInteger effects = new AtomicInteger();
        EventId event = EventId.next(IDS);
        parking.standing.put(new InboxKey("probe.consumer", event.value().toString()), InboxParking.Standing.PARKED);
        KafkaEventReceiver receiver = receiver(handler("probe.consumer", (connection, seen) -> effects.incrementAndGet()));
        consumer.addRecord(record(0, event));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(effects).hasValue(0);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(committedOffset()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a discarded record is never handled, whatever delivers it")
    void aDiscardedRecordIsNeverHandled() {
        AtomicInteger effects = new AtomicInteger();
        EventId event = EventId.next(IDS);
        parking.standing.put(new InboxKey("probe.consumer", event.value().toString()), InboxParking.Standing.DISCARDED);
        KafkaEventReceiver receiver = receiver(handler("probe.consumer", (connection, seen) -> effects.incrementAndGet()));
        consumer.addRecord(record(0, event));

        receiver.pollOnce();

        assertThat(effects).hasValue(0);
        assertThat(committedOffset()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a record released back to failing runs again, and its row is settled with the effect")
    void aReleasedRecordIsHandledOnceAndSettled() {
        AtomicInteger effects = new AtomicInteger();
        EventId event = EventId.next(IDS);
        InboxKey key = new InboxKey("probe.consumer", event.value().toString());
        parking.standing.put(key, InboxParking.Standing.FAILING);
        KafkaEventReceiver receiver = receiver(handler("probe.consumer", (connection, seen) -> effects.incrementAndGet()));
        consumer.addRecord(record(0, event));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.processed()).isEqualTo(1);
        assertThat(effects).hasValue(1);
        assertThat(parking.standing).containsEntry(key, InboxParking.Standing.HANDLED);
    }

    @Test
    @DisplayName("a malformed record is counted against its group, by where it sits, and parks")
    void aMalformedRecordParks() {
        KafkaEventReceiver receiver = receiver(failing(new IllegalStateException("never runs")));
        consumer.addRecord(malformed(0));
        assertThat(receiver.pollOnce().failed()).isEqualTo(1);

        consumer.addRecord(malformed(0));
        consumer.seek(PARTITION, 0L);
        ReceiverPollResult second = receiver.pollOnce();

        assertThat(second.parked()).isEqualTo(1);
        assertThat(parking.standing).containsKey(new InboxKey("finapp.probe", "malformed:finapp.probe:0:0"));
        assertThat(parking.failures.getLast().correlation()).as("no header could be read, so none is claimed").isEmpty();
        assertThat(committedOffset()).isEqualTo(1L);
    }

    @Test
    @DisplayName("one handler parked does not acknowledge a record another handler has yet to finish")
    void aParkedHandlerDoesNotCoverAFailingOne() {
        EventId event = EventId.next(IDS);
        parking.failuresSoFar.put(new InboxKey("probe.parking", event.value().toString()), 1);
        KafkaEventReceiver receiver = receiver(
                handler("probe.parking", (connection, seen) -> {
                    throw new IllegalArgumentException("poison for this one");
                }),
                failing(new RuntimeException(new SQLException("blip", "08006"))));
        consumer.addRecord(record(0, event));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(consumer.position(PARTITION)).isZero();
        assertThat(journal).doesNotContain("offsets-acknowledged");
    }

    // -----------------------------------------------------------------

    private KafkaEventReceiver receiver(InboxEventHandler... handlers) {
        return new KafkaEventReceiver(consumer, "finapp.probe", connections(), inbox(), parking, List.of(handlers), Duration.ofMillis(50));
    }

    private InboxConsumer<Connection> inbox() {
        return new InboxConsumer<>(store, Clock.systemUTC(), Duration.ofDays(14));
    }

    private InboxConnectionSource connections() {
        return () -> (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "rollback" -> {
                        journal.add("rolled-back");
                        store.pending.clear();
                        yield null;
                    }
                    case "commit" -> {
                        if (commitFailure != null) {
                            throw commitFailure;
                        }
                        journal.add("committed");
                        store.committed.addAll(store.pending);
                        store.pending.clear();
                        yield null;
                    }
                    case "toString" -> "fake-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("unexpected call: " + method.getName());
                });
    }

    private long committedOffset() {
        OffsetAndMetadata committed = consumer.committed(Set.of(PARTITION)).get(PARTITION);
        return committed == null ? -1L : committed.offset();
    }

    private static ConsumerRecord<String, byte[]> record(long offset, EventId eventId) {
        RecordHeaders headers = new RecordHeaders();
        header(headers, "eventId", eventId.value().toString());
        header(headers, "eventType", TYPE);
        header(headers, "eventVersion", "1");
        header(headers, "schemaVersion", "1");
        header(headers, "aggregateId", "01890f42-98c4-7cc3-9c5c-0a1b2c3d4e5f");
        header(headers, "aggregateType", "Probe");
        header(headers, "occurredAt", "2026-11-08T10:00:00Z");
        header(headers, "producer", "probe");
        header(headers, "correlationId", "probe-flow");
        header(headers, "causationId", "probe-cause");
        header(headers, "payloadMediaType", "application/json");
        return new ConsumerRecord<>(TOPIC, 0, offset, 0L, TimestampType.CREATE_TIME, -1, -1, "key", "x".getBytes(StandardCharsets.UTF_8), headers, Optional.empty());
    }

    private static ConsumerRecord<String, byte[]> malformed(long offset) {
        return new ConsumerRecord<>(TOPIC, 0, offset, 0L, TimestampType.CREATE_TIME, -1, -1, "key", "x".getBytes(StandardCharsets.UTF_8), new RecordHeaders(), Optional.empty());
    }

    private static void header(RecordHeaders headers, String name, String value) {
        headers.add("finapp." + name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static InboxEventHandler failing(RuntimeException failure) {
        return handler("probe.consumer", (connection, event) -> {
            throw failure;
        });
    }

    private interface Reaction {
        void react(Connection connection, ReceivedEvent event);
    }

    private static InboxEventHandler handler(String consumerName, Reaction reaction) {
        return new InboxEventHandler() {
            @Override
            public String consumerName() {
                return consumerName;
            }

            @Override
            public String topic() {
                return TOPIC;
            }

            @Override
            public String eventType() {
                return TYPE;
            }

            @Override
            public void handle(Connection unitOfWork, ReceivedEvent event) {
                reaction.react(unitOfWork, event);
            }
        };
    }

    /** The count survives across polls, as the row does; the budget is a count only here, the elapsed floor being the SQL's. */
    private static final class FakeParking implements InboxParking {
        private final int budget;
        private final Map<InboxKey, Integer> failuresSoFar = new HashMap<>();
        private final Map<InboxKey, Standing> standing = new HashMap<>();
        private final List<InboxKey> counted = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private RuntimeException failWith;

        private FakeParking(int budget) {
            this.budget = budget;
        }

        Standing standingOf(String consumer, EventId event) {
            return standing.getOrDefault(new InboxKey(consumer, event.value().toString()), Standing.NONE);
        }

        @Override
        public Standing standing(Connection unitOfWork, InboxKey key) {
            return standing.getOrDefault(key, Standing.NONE);
        }

        @Override
        public void handled(Connection unitOfWork, InboxKey key) {
            standing.put(key, Standing.HANDLED);
        }

        @Override
        public Verdict recordFailure(Connection unitOfWork, Failure failure, String failureClass) {
            if (failWith != null) {
                throw failWith;
            }
            counted.add(failure.key());
            failures.add(failure);
            int attempts = failuresSoFar.merge(failure.key(), 1, Integer::sum);
            if (attempts >= budget) {
                standing.put(failure.key(), Standing.PARKED);
                return Verdict.PARKED;
            }
            standing.put(failure.key(), Standing.FAILING);
            return Verdict.RETRY;
        }
    }

    /** Only what {@code consume} needs: a committed record is a duplicate, a pending one rolls back. */
    private static final class FakeStore implements InboxRecordStore<Connection> {
        private final Set<InboxKey> committed = new HashSet<>();
        private final List<InboxKey> pending = new ArrayList<>();
        private boolean contended;

        @Override
        public RecordOutcome record(
                Connection unitOfWork, InboxKey key, String messageType, CorrelationId correlationId, Instant processedAt, Duration retention) {
            if (contended) {
                return RecordOutcome.CONTENDED;
            }
            if (committed.contains(key)) {
                return RecordOutcome.ALREADY_PROCESSED;
            }
            pending.add(key);
            return RecordOutcome.RECORDED;
        }
    }

}
