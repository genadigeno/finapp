package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The outbox reaches a real broker (`P2-TSK-001`) — the first test of the platform's first
 * broker adapter, in the first tier that needs one.
 *
 * <h2>What "exactly once" honestly means here, and the acceptance this corrects</h2>
 *
 * <p>The backlog's acceptance line promised <em>"observable on the broker exactly once per
 * fact"</em>, and the design corrected it: the relay publishes and then records publication, so
 * a crash between the two republishes — at-least-once, by ADR-0005's explicit choice, with
 * {@code finapp.eventId} as the consumer's dedupe key. {@link #aCrashBetweenAckAndMarkRedelivers}
 * demonstrates the duplicate rather than hiding it, because an adapter that claimed
 * exactly-once would invite consumers to skip their inbox.
 */
@Tag("kafka")
@DisplayName("outbox events reach Kafka (P2-TSK-001)")
class KafkaOutboxDeliveryKafkaTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String OUTBOX = "platform.outbox_event";

    /** Each test writes under its own producer name, so topics and consumers cannot collide. */
    private final String producerName = "probe" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("a committed event is delivered once, envelope and bytes intact")
    void aCommittedEventIsDelivered() throws Exception {
        EventEnvelope envelope = writeEvent("aggregate-fact");

        RelayPollResult result = relay(realPublisher()).pollOnce();
        assertThat(result.published()).isEqualTo(1);

        List<ConsumerRecord<String, byte[]>> records = consumeAtLeast(1);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, byte[]> record = records.get(0);

        assertThat(record.value())
                .as("the bytes the producing transaction committed (INV-EVT-01's other half)")
                .isEqualTo("aggregate-fact".getBytes(StandardCharsets.UTF_8));
        assertThat(record.key()).isEqualTo(envelope.aggregateId().value().toString());
        assertThat(header(record, "finapp.eventId"))
                .isEqualTo(envelope.eventId().value().toString());
        assertThat(header(record, "finapp.eventType")).isEqualTo("probe.SomethingHappened");
        assertThat(header(record, "finapp.correlationId"))
                .isEqualTo(envelope.correlationId().value());
        assertThat(header(record, "finapp.payloadMediaType")).isEqualTo("text/plain");

        assertThat(publishedAt(envelope.eventId()))
                .as("and the outbox row records the publication")
                .isNotNull();
    }

    @Test
    @DisplayName("two relays, one aggregate: the broker sees the events in order")
    void orderSurvivesConcurrentRelays() throws Exception {
        UUID aggregate = IDS.next(); // v7: EntityId refuses a v4 as malformed (ADR-0013)
        EventEnvelope first = writeEvent("first", aggregate);
        EventEnvelope second = writeEvent("second", aggregate);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            List<Callable<RelayPollResult>> relays =
                    List.of(racer(start), racer(start));
            int published = 0;
            for (Future<RelayPollResult> outcome : pool.invokeAll(relays)) {
                published += outcome.get().published();
            }
            assertThat(published)
                    .as("two events published exactly once between the two instances - the"
                            + " advisory lock serialises the aggregate, whoever wins it")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        List<ConsumerRecord<String, byte[]>> records = consumeAtLeast(2);
        assertThat(records).hasSize(2);
        assertThat(header(records.get(0), "finapp.eventId"))
                .as("per-aggregate order survives concurrency and partitioning")
                .isEqualTo(first.eventId().value().toString());
        assertThat(header(records.get(1), "finapp.eventId"))
                .isEqualTo(second.eventId().value().toString());
        assertThat(records.get(0).partition())
                .as("one aggregate, one partition - the mechanism, asserted so a partitioner"
                        + " change cannot silently void the ordering claim")
                .isEqualTo(records.get(1).partition());
    }

    @Test
    @DisplayName("a crash between broker ack and the mark redelivers - same eventId, dedupable")
    void aCrashBetweenAckAndMarkRedelivers() throws Exception {
        EventEnvelope envelope = writeEvent("survives-the-crash");

        try (KafkaEventPublisher real = realPublisher()) {
            // Publishes for real, then dies before the relay can record it - the crash window
            // ADR-0005 names. The relay sees an ordinary failed attempt; the broker has the
            // record.
            EventPublisher publishThenCrash =
                    event -> {
                        real.publish(event);
                        throw new IllegalStateException("crashed after the acknowledgement");
                    };
            assertThat(relay(publishThenCrash).pollOnce().failed()).isEqualTo(1);
        }

        assertThat(publishedAt(envelope.eventId()))
                .as("the fact is still owed: nothing marked it published")
                .isNull();

        backDate(envelope.eventId());
        assertThat(relay(realPublisher()).pollOnce().published()).isEqualTo(1);

        List<ConsumerRecord<String, byte[]>> records = consumeAtLeast(2);
        assertThat(records)
                .as("at-least-once, demonstrated rather than hidden: the duplicate is real")
                .hasSize(2);
        assertThat(header(records.get(0), "finapp.eventId"))
                .as("and both copies carry the same dedupe key, which is what makes the"
                        + " consumer inbox able to absorb this (INV-IDEM-04)")
                .isEqualTo(header(records.get(1), "finapp.eventId"));
    }

    @Test
    @DisplayName("an unreachable broker fails within the bound and blocks the aggregate, loudly")
    void anUnreachableBrokerIsBoundedAndRecorded() throws Exception {
        EventEnvelope envelope = writeEvent("waiting-out-an-outage");

        Instant before = Instant.now();
        try (KafkaEventPublisher unreachable =
                KafkaEventPublisher.connect("localhost:1", "PLAINTEXT", Duration.ofSeconds(2))) {
            assertThat(relay(unreachable).pollOnce().failed()).isEqualTo(1);
        }
        assertThat(Duration.between(before, Instant.now()))
                .as("bounded: the relay was holding this aggregate's lock the whole time")
                .isLessThan(Duration.ofSeconds(30));

        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT attempts, last_error FROM " + OUTBOX
                                        + " WHERE event_id = ?")) {
            select.setObject(1, envelope.eventId().value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("attempts")).isEqualTo(1);
                assertThat(row.getString("last_error"))
                        .as("recorded for the operator, naming the failure and not the payload")
                        .isNotBlank()
                        .doesNotContain("waiting-out-an-outage");
            }
        }
    }

    // The schedule-drives-the-poll case lives in app (OutboxRelayScheduleKafkaTest): the
    // schedule is composition-root machinery and platform must not reach upward for it.

    // -----------------------------------------------------------------

    static final class ProbeId extends EntityId {
        ProbeId(UUID value) {
            super(value);
        }
    }

    private Callable<RelayPollResult> racer(CyclicBarrier start) {
        return () -> {
            try (KafkaEventPublisher publisher = realPublisher()) {
                start.await();
                return relay(publisher).pollOnce();
            }
        };
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(DatabaseRoles::application, publisher);
    }

    private static KafkaEventPublisher realPublisher() {
        return KafkaEventPublisher.connect(bootstrap(), "PLAINTEXT", Duration.ofSeconds(10));
    }

    private static String bootstrap() {
        String bootstrap = System.getProperty("finapp.kafka.bootstrap");
        assertThat(bootstrap)
                .as("KafkaUnderTest publishes this for the kafka tier; its absence means the"
                        + " task did not supply the image")
                .isNotNull();
        return bootstrap;
    }

    private EventEnvelope writeEvent(String payload) throws SQLException {
        return writeEvent(payload, IDS.next());
    }

    private EventEnvelope writeEvent(String payload, UUID aggregate) throws SQLException {
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "probe.SomethingHappened",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeId(aggregate),
                        "Probe",
                        Instant.now(),
                        producerName,
                        CorrelationId.of(UUID.randomUUID().toString()),
                        CausationId.of("command-1"));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcOutboxWriter()
                    .write(app, envelope, payload.getBytes(StandardCharsets.UTF_8), "text/plain");
            app.commit();
        }
        backDate(envelope.eventId());
        return envelope;
    }

    /** See {@code OutboxRelayTest.backDate}: the local server clock steps backwards. */
    private static void backDate(EventId eventId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE " + OUTBOX
                                        + " SET next_attempt_at = now() - INTERVAL '1 minute'"
                                        + " WHERE event_id = ?")) {
            update.setObject(1, eventId.value());
            update.executeUpdate();
        }
    }

    private static Instant publishedAt(EventId eventId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT published_at FROM " + OUTBOX + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                var stamp = row.getTimestamp(1);
                return stamp == null ? null : stamp.toInstant();
            }
        }
    }

    private List<ConsumerRecord<String, byte[]>> consumeAtLeast(int expected) {
        Properties config = new Properties();
        config.put("bootstrap.servers", bootstrap());
        config.put("group.id", "probe-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest");
        config.put(
                "key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer");
        config.put(
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of("finapp." + producerName));
            Instant deadline = Instant.now().plusSeconds(30);
            while (records.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
            // One more poll, so "exactly N" assertions can see an unexpected extra record
            // rather than stopping at the count they hoped for.
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        return records;
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as("header %s must be present", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
