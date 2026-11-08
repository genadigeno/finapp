package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxConnectionSource;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.inbox.kafka.KafkaEventReceiver;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A stopping instance's consumer loops (`P15-TSK-008`): woken out of a poll at once, so none takes a new batch; a loop inside
 * a batch finishes it and acknowledges it; each closes its consumer, leaving its group; and the stop returns within its
 * bound even when a loop will not let go. Over the real {@code KafkaEventReceiver} on a {@code MockConsumer} that behaves as
 * the real one does where it matters here: a poll blocks until its timeout or a wakeup, and a wakeup pending when a commit
 * starts makes the commit throw.
 */
@DisplayName("a stopping instance's consumers take no new batch, acknowledge the one they hold and leave (P15-TSK-008)")
class InboxConsumersStopTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String TOPIC = "finapp.probe";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @Test
    @DisplayName("a loop blocked in a long poll is woken at once: the stop returns in moments, not at the poll's timeout")
    void aPollingLoopIsWokenAtOnce() throws Exception {
        LikeTheRealConsumer consumer = new LikeTheRealConsumer();
        InboxConsumers consumers = consumers(consumer, (connection, event) -> { }, Duration.ofMinutes(5));
        consumers.start();
        assertThat(consumer.polling.await(10, TimeUnit.SECONDS)).as("the loop is inside a five-minute poll").isTrue();

        long started = System.nanoTime();
        consumers.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as("woken, not waited out: below the settle bound").isLessThan(Duration.ofSeconds(3));
        assertThat(consumer.closed()).as("the consumer closed: the group sees it leave").isTrue();
        assertThat(consumers.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a loop inside a batch when the stop comes finishes the batch, acknowledges it, then closes")
    void aLoopInsideABatchAcknowledgesIt() throws Exception {
        LikeTheRealConsumer consumer = new LikeTheRealConsumer();
        consumer.addRecord(record(0));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean handled = new AtomicBoolean();
        InboxConsumers consumers = consumers(consumer, (connection, event) -> {
            inside.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            handled.set(true);
        }, Duration.ofMillis(100));
        consumers.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).as("the loop is handling a record").isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(consumers::stop);
        Thread.sleep(200);
        assertThat(stopping).as("the stop waits for the batch").isNotDone();
        release.countDown();
        stopping.get(10, TimeUnit.SECONDS);

        assertThat(handled).as("the record's effect committed").isTrue();
        assertThat(committedOffset(consumer)).as("acknowledged past the settled record, the wakeup notwithstanding").isEqualTo(1L);
        assertThat(consumer.closed()).isTrue();
        assertThat(consumer.polls).as("no poll after the batch: no new batch taken").isEqualTo(1);
    }

    @Test
    @DisplayName("a loop that will not let go is interrupted past the settle bound, and the stop returns within its stop bound")
    void aStopIsBoundedWhenALoopHolds() throws Exception {
        LikeTheRealConsumer consumer = new LikeTheRealConsumer();
        consumer.addRecord(record(0));
        CountDownLatch inside = new CountDownLatch(1);
        InboxConsumers consumers = consumers(consumer, (connection, event) -> {
            inside.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(5).toMillis()); // a handler stuck on its database
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }, Duration.ofMillis(100));
        consumers.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        consumers.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as("the settle bound was waited out").isGreaterThanOrEqualTo(InboxConsumers.SETTLE_BOUND);
        assertThat(took).as("and the stop returned within its stop bound").isLessThan(consumers.stopBound().plusSeconds(1));
        assertThat(committedOffset(consumer)).as("nothing acknowledged: the record redelivers into the dedupe").isEqualTo(-1L);
        assertThat(consumer.closed()).as("the interrupted loop still closed its consumer").isTrue();
    }

    // ------------------------------------------------------------------------------------------------------------------

    private interface Reaction {
        void react(Connection connection, ReceivedEvent event);
    }

    private static InboxConsumers consumers(LikeTheRealConsumer consumer, Reaction reaction, Duration pollTimeout) {
        FakeStore store = new FakeStore();
        InboxConsumer<Connection> inbox = new InboxConsumer<>(store, Clock.systemUTC(), Duration.ofDays(1));
        InboxConnectionSource connections = () -> fakeConnection(store);
        return new InboxConsumers(
                List.of(handler(reaction)),
                (group, handlers) -> new KafkaEventReceiver(consumer, group, connections, inbox, com.finapp.platform.testing.InboxParkingDoubles.NEVER_PARKS, handlers, pollTimeout),
                Duration.ofMillis(50),
                new SimpleMeterRegistry());
    }

    /** What the consumer acknowledged, read after it closed: a closed {@code MockConsumer} answers nothing. */
    private static long committedOffset(LikeTheRealConsumer consumer) {
        return consumer.acknowledged;
    }

    private static ConsumerRecord<String, byte[]> record(long offset) {
        RecordHeaders headers = new RecordHeaders();
        Map.of("eventId", EventId.next(IDS).value().toString(),
                        "eventType", "probe.SomethingHappened",
                        "eventVersion", "1",
                        "schemaVersion", "1",
                        "aggregateId", IDS.next().toString(),
                        "aggregateType", "Probe",
                        "occurredAt", Instant.parse("2026-11-08T12:00:00Z").toString(),
                        "producer", "probe",
                        "correlationId", "flow-1",
                        "causationId", "command-1")
                .forEach((name, value) -> headers.add("finapp." + name, value.getBytes(StandardCharsets.UTF_8)));
        headers.add("finapp.payloadMediaType", "text/plain".getBytes(StandardCharsets.UTF_8));
        return new ConsumerRecord<>(TOPIC, 0, offset, 0L, TimestampType.CREATE_TIME, -1, -1, "key",
                "payload".getBytes(StandardCharsets.UTF_8), headers, Optional.empty());
    }

    private static InboxEventHandler handler(Reaction reaction) {
        return new InboxEventHandler() {
            @Override
            public String consumerName() {
                return "probe.consumer";
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

    /**
     * A {@code MockConsumer} that behaves as {@code KafkaConsumer} does where a stop matters: a poll blocks until its timeout
     * or a wakeup, an interrupt in it surfaces as Kafka's {@code InterruptException}, and a wakeup pending when a commit
     * starts makes that commit throw - the case the receiver now retries.
     */
    private static final class LikeTheRealConsumer extends MockConsumer<String, byte[]> {
        private final CountDownLatch polling = new CountDownLatch(1);
        private final CountDownLatch woken = new CountDownLatch(1);
        private volatile boolean wakeupPending;
        private volatile int polls;

        private LikeTheRealConsumer() {
            super("earliest");
            assign(List.of(PARTITION));
            updateBeginningOffsets(Map.of(PARTITION, 0L));
        }

        @Override
        public ConsumerRecords<String, byte[]> poll(Duration timeout) {
            polls++;
            if (recordsWaiting) {
                recordsWaiting = false;
                return super.poll(Duration.ZERO);
            }
            polling.countDown();
            try {
                if (woken.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    wakeupPending = false;
                    throw new WakeupException();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptException(interrupted);
            }
            return super.poll(Duration.ZERO);
        }

        private volatile boolean recordsWaiting;

        @Override
        public synchronized void addRecord(ConsumerRecord<String, byte[]> record) {
            recordsWaiting = true;
            super.addRecord(record);
        }

        @Override
        public void wakeup() {
            wakeupPending = true;
            woken.countDown();
        }

        private volatile long acknowledged = -1L;

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (wakeupPending) {
                wakeupPending = false;
                throw new WakeupException();
            }
            super.commitSync(offsets);
            OffsetAndMetadata offset = offsets.get(PARTITION);
            if (offset != null) {
                acknowledged = offset.offset();
            }
        }
    }

    /** An in-memory inbox record store: a record is committed by the fake connection's commit. */
    private static final class FakeStore implements InboxRecordStore<Connection> {
        private final Set<InboxKey> committed = new HashSet<>();
        private final Set<InboxKey> pending = new HashSet<>();

        @Override
        public RecordOutcome record(Connection unitOfWork, InboxKey key, String messageType, CorrelationId correlationId,
                Instant processedAt, Duration retention) {
            if (committed.contains(key)) {
                return RecordOutcome.ALREADY_PROCESSED;
            }
            pending.add(key);
            return RecordOutcome.RECORDED;
        }
    }

    private static Connection fakeConnection(FakeStore store) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "commit" -> {
                        store.committed.addAll(store.pending);
                        store.pending.clear();
                        yield null;
                    }
                    case "rollback" -> {
                        store.pending.clear();
                        yield null;
                    }
                    case "toString" -> "fake-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("unexpected call: " + method.getName());
                });
    }
}
