package com.finapp.platform.inbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxConnectionSource;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The consumer shell's loop mechanics (`P2-TSK-002`), hermetically — the decisions a real broker
 * cannot drive deterministically: that offsets move only after the database commit, that a
 * failing or contended record is seeked back to rather than skipped, and that one handler's
 * failure does not let another's success acknowledge the record past it.
 *
 * <p>{@code MockConsumer} connects to nothing, which is exactly why {@code TestTier}'s kafka
 * signature names the concrete {@code KafkaConsumer} rather than the client package wholesale —
 * the {@code MockProducer} precedent. What needs a broker — redelivery after a crash, rebalance,
 * the real relay-to-inbox path — is {@code KafkaInboxDeliveryKafkaTest}'s subject.
 */
@DisplayName("the Kafka event receiver (P2-TSK-002)")
class KafkaEventReceiverTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String TOPIC = "finapp.probe";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final List<String> journal = new ArrayList<>();
    private final FakeStore store = new FakeStore();
    private MockConsumer<String, byte[]> consumer;

    @BeforeEach
    void freshConsumer() {
        consumer = new AcknowledgementJournalingConsumer(journal);
        consumer.assign(List.of(PARTITION));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
    }

    @Test
    @DisplayName("the offset is acknowledged only after the effect is committed")
    void offsetsMoveOnlyAfterTheEffect() {
        AtomicReference<ReceivedEvent> seen = new AtomicReference<>();
        KafkaEventReceiver receiver = receiver(capturing("probe.consumer", seen));
        EventId eventId = EventId.next(IDS);
        consumer.addRecord(record(0, eventId, "probe.SomethingHappened", "the-payload-∂"));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result).isEqualTo(new ReceiverPollResult(1, 0, 0, 0));
        assertThat(journal)
                .as("the load-bearing ordering: database commit first, broker acknowledgement"
                        + " second - the reverse loses records")
                .containsExactly("effect-committed", "offsets-acknowledged");
        assertThat(committedOffset())
                .as("acknowledged exactly past the settled record")
                .isEqualTo(1L);
        ReceivedEvent event = seen.get();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.payload())
                .as("the bytes the producing transaction committed, verbatim")
                .isEqualTo("the-payload-∂".getBytes(StandardCharsets.UTF_8));
        assertThat(event.correlationId().value()).isEqualTo("flow-1");
    }

    @Test
    @DisplayName("a failing handler rolls back, seeks back, and acknowledges nothing")
    void aFailingHandlerStallsThePartition() {
        KafkaEventReceiver receiver =
                receiver(
                        throwing(
                                "probe.consumer",
                                new IllegalStateException("the handler is unwell")));
        consumer.addRecord(record(0, EventId.next(IDS), "probe.SomethingHappened", "x"));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(journal)
                .as("rolled back - dedupe record included - and never acknowledged, so the"
                        + " redelivery retries rather than the stream gaining a silent gap")
                .containsExactly("rolled-back");
        assertThat(consumer.position(PARTITION)).as("seeked back to the record").isEqualTo(0L);
        assertThat(store.committed).isEmpty();
    }

    @Test
    @DisplayName("a contended record is never acknowledged, whichever way the other side goes")
    void contendedIsNeverAcknowledged() {
        AtomicInteger effects = new AtomicInteger();
        KafkaEventReceiver receiver = receiver(counting("probe.consumer", effects));
        store.scripted.add(InboxRecordStore.RecordOutcome.CONTENDED);
        consumer.addRecord(record(0, EventId.next(IDS), "probe.SomethingHappened", "x"));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.contended()).isEqualTo(1);
        assertThat(effects).as("the handler never ran").hasValue(0);
        assertThat(journal)
                .as("the other transaction may yet roll back, so this record is still owed")
                .containsExactly("rolled-back");
        assertThat(consumer.position(PARTITION)).isEqualTo(0L);
    }

    @Test
    @DisplayName("one handler's failure keeps the record owed; the other's dedupe absorbs the retry")
    void oneFailingHandlerDoesNotSkipTheRecord() {
        AtomicInteger firstEffects = new AtomicInteger();
        AtomicInteger secondAttempts = new AtomicInteger();
        InboxEventHandler flaky =
                new InboxEventHandler() {
                    @Override
                    public String consumerName() {
                        return "probe.second";
                    }

                    @Override
                    public String topic() {
                        return TOPIC;
                    }

                    @Override
                    public String eventType() {
                        return "probe.SomethingHappened";
                    }

                    @Override
                    public void handle(Connection unitOfWork, ReceivedEvent event) {
                        if (secondAttempts.incrementAndGet() == 1) {
                            throw new IllegalStateException("first attempt fails");
                        }
                    }
                };
        KafkaEventReceiver receiver = receiver(counting("probe.first", firstEffects), flaky);
        EventId eventId = EventId.next(IDS);
        ConsumerRecord<String, byte[]> delivery =
                record(0, eventId, "probe.SomethingHappened", "x");
        consumer.addRecord(delivery);

        ReceiverPollResult firstPoll = receiver.pollOnce();
        assertThat(firstPoll.failed()).isEqualTo(1);
        assertThat(firstEffects).as("the first consumer's effect committed").hasValue(1);
        assertThat(journal).doesNotContain("offsets-acknowledged");
        assertThat(consumer.position(PARTITION)).isEqualTo(0L);

        // The redelivery the seek-back asked for. MockConsumer does not replay on its own.
        consumer.addRecord(delivery);
        ReceiverPollResult secondPoll = receiver.pollOnce();

        assertThat(secondPoll.processed()).isEqualTo(1);
        assertThat(firstEffects)
                .as("protected by its own dedupe record on the retry (INV-IDEM-04)")
                .hasValue(1);
        assertThat(secondAttempts).hasValue(2);
        assertThat(committedOffset()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a malformed record stalls its partition loudly rather than being skipped")
    void aMalformedRecordStalls() {
        AtomicInteger effects = new AtomicInteger();
        KafkaEventReceiver receiver = receiver(counting("probe.consumer", effects));
        RecordHeaders missingEventId = new RecordHeaders();
        missingEventId.add("finapp.eventType", "probe.SomethingHappened".getBytes(StandardCharsets.UTF_8));
        consumer.addRecord(
                new ConsumerRecord<>(
                        TOPIC,
                        0,
                        0,
                        0L,
                        TimestampType.CREATE_TIME,
                        -1,
                        -1,
                        "key",
                        "x".getBytes(StandardCharsets.UTF_8),
                        missingEventId,
                        Optional.empty()));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result.failed())
                .as("we produce these headers, so this is a producer defect - a stall is loud"
                        + " and a skip is an undetectable gap")
                .isEqualTo(1);
        assertThat(effects).hasValue(0);
        assertThat(journal).isEmpty();
        assertThat(consumer.position(PARTITION)).isEqualTo(0L);
    }

    @Test
    @DisplayName("a record no registered handler wants is acknowledged and nothing else")
    void aRecordNobodyWantsIsAcknowledged() {
        AtomicInteger effects = new AtomicInteger();
        KafkaEventReceiver receiver = receiver(counting("probe.consumer", effects));
        consumer.addRecord(record(0, EventId.next(IDS), "probe.SomethingElseEntirely", "x"));

        ReceiverPollResult result = receiver.pollOnce();

        assertThat(result).isEqualTo(new ReceiverPollResult(0, 0, 0, 0));
        assertThat(effects).hasValue(0);
        assertThat(committedOffset())
                .as("a topic carries every type its module produces; passing one by is normal")
                .isEqualTo(1L);
    }

    // -----------------------------------------------------------------

    private KafkaEventReceiver receiver(InboxEventHandler... handlers) {
        InboxConsumer<Connection> inbox =
                new InboxConsumer<>(store, Clock.systemUTC(), Duration.ofDays(1));
        InboxConnectionSource connections = () -> fakeConnection(journal, store);
        return new KafkaEventReceiver(
                consumer, connections, inbox, List.of(handlers), Duration.ofMillis(50));
    }

    private long committedOffset() {
        Map<TopicPartition, OffsetAndMetadata> committed =
                consumer.committed(Set.of(PARTITION));
        OffsetAndMetadata offset = committed.get(PARTITION);
        return offset == null ? -1L : offset.offset();
    }

    private static ConsumerRecord<String, byte[]> record(
            long offset, EventId eventId, String eventType, String payload) {
        RecordHeaders headers = new RecordHeaders();
        header(headers, "eventId", eventId.value().toString());
        header(headers, "eventType", eventType);
        header(headers, "eventVersion", "1");
        header(headers, "schemaVersion", "1");
        header(headers, "aggregateId", IDS.next().toString());
        header(headers, "aggregateType", "Probe");
        header(headers, "occurredAt", Instant.parse("2026-09-09T12:00:00Z").toString());
        header(headers, "producer", "probe");
        header(headers, "correlationId", "flow-1");
        header(headers, "causationId", "command-1");
        header(headers, "payloadMediaType", "text/plain");
        return new ConsumerRecord<>(
                TOPIC,
                0,
                offset,
                0L,
                TimestampType.CREATE_TIME,
                -1,
                -1,
                "key",
                payload.getBytes(StandardCharsets.UTF_8),
                headers,
                Optional.empty());
    }

    private static void header(RecordHeaders headers, String name, String value) {
        headers.add("finapp." + name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static InboxEventHandler counting(String consumerName, AtomicInteger effects) {
        return handler(consumerName, (connection, event) -> effects.incrementAndGet());
    }

    private static InboxEventHandler capturing(
            String consumerName, AtomicReference<ReceivedEvent> seen) {
        return handler(consumerName, (connection, event) -> seen.set(event));
    }

    private static InboxEventHandler throwing(String consumerName, RuntimeException failure) {
        return handler(
                consumerName,
                (connection, event) -> {
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
                return "probe.SomethingHappened";
            }

            @Override
            public void handle(Connection unitOfWork, ReceivedEvent event) {
                reaction.react(unitOfWork, event);
            }
        };
    }

    /** Logs broker acknowledgements into the shared journal, so ordering is assertable. */
    private static final class AcknowledgementJournalingConsumer
            extends MockConsumer<String, byte[]> {
        private final List<String> journal;

        private AcknowledgementJournalingConsumer(List<String> journal) {
            super("earliest");
            this.journal = journal;
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            journal.add("offsets-acknowledged");
            super.commitSync(offsets);
        }
    }

    /**
     * An in-memory inbox record store whose "commit" is driven by the fake connection, so the
     * dedupe semantics across a rollback are real: a pending record that is rolled back never
     * becomes a duplicate marker.
     */
    private static final class FakeStore implements InboxRecordStore<Connection> {
        private final Set<InboxKey> committed = new HashSet<>();
        private final List<InboxKey> pending = new ArrayList<>();
        private final Deque<RecordOutcome> scripted = new ArrayDeque<>();

        @Override
        public RecordOutcome record(
                Connection unitOfWork,
                InboxKey key,
                String messageType,
                CorrelationId correlationId,
                Instant processedAt,
                Duration retention) {
            if (!scripted.isEmpty()) {
                return scripted.pop();
            }
            if (committed.contains(key)) {
                return RecordOutcome.ALREADY_PROCESSED;
            }
            pending.add(key);
            return RecordOutcome.RECORDED;
        }

        private void commitPending() {
            committed.addAll(pending);
            pending.clear();
        }

        private void rollbackPending() {
            pending.clear();
        }
    }

    /** The four calls the receiver makes, journalled; anything else fails loudly. */
    private static Connection fakeConnection(List<String> journal, FakeStore store) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "setAutoCommit", "close" -> null;
                                    case "commit" -> {
                                        journal.add("effect-committed");
                                        store.commitPending();
                                        yield null;
                                    }
                                    case "rollback" -> {
                                        journal.add("rolled-back");
                                        store.rollbackPending();
                                        yield null;
                                    }
                                    case "toString" -> "fake-connection";
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    "unexpected call: " + method.getName());
                                });
    }
}
