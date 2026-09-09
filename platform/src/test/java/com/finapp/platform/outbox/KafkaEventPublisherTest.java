package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire mapping and the failure discipline of the broker adapter (`P2-TSK-001`).
 *
 * <p>Hermetic on purpose: {@code MockProducer} connects to nothing, so this is the unit tier —
 * which is exactly why {@code TestTier}'s kafka signature names the concrete
 * {@code KafkaProducer} rather than the client package wholesale. What needs a broker — the
 * relay-to-consumer path, ordering, crash redelivery — is {@code KafkaOutboxDeliveryKafkaTest}'s
 * subject in the kafka tier.
 */
@DisplayName("the Kafka event publisher (P2-TSK-001)")
class KafkaEventPublisherTest {

    private static final IdGenerator IDS =
            new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("one event becomes one record: topic, key, verbatim bytes, all eleven headers")
    void theWireMappingIsComplete() {
        MockProducer<String, byte[]> producer =
                new MockProducer<>(true, new FirstPartition(), new StringSerializer(), new ByteArraySerializer());
        PendingEvent event = anEvent("payload-bytes-∂-unchanged");

        new KafkaEventPublisher(producer, Duration.ofSeconds(5)).publish(event);

        assertThat(producer.history()).hasSize(1);
        ProducerRecord<String, byte[]> record = producer.history().get(0);
        assertThat(record.topic())
                .as("one topic per producing module")
                .isEqualTo("finapp.kafka-probe");
        assertThat(record.key())
                .as("the partition key is the aggregate, which is what makes ordering real")
                .isEqualTo(event.partitionKey().toString());
        assertThat(record.value())
                .as("the bytes the producing transaction committed, never a re-encoding")
                .isEqualTo("payload-bytes-∂-unchanged".getBytes(StandardCharsets.UTF_8));

        // The envelope rides as headers so a consumer can route and deduplicate an event it
        // cannot parse. finapp.eventId is the inbox dedupe key (INV-IDEM-04), so its absence
        // would break every consumer's only defence against at-least-once delivery.
        assertThat(header(record, "finapp.eventId"))
                .isEqualTo(event.eventId().value().toString());
        assertThat(header(record, "finapp.eventType")).isEqualTo("probe.SomethingHappened");
        assertThat(header(record, "finapp.eventVersion")).isEqualTo("1");
        assertThat(header(record, "finapp.schemaVersion")).isEqualTo("1");
        assertThat(header(record, "finapp.aggregateId"))
                .isEqualTo(event.aggregateId().toString());
        assertThat(header(record, "finapp.aggregateType")).isEqualTo("Probe");
        assertThat(header(record, "finapp.occurredAt")).isEqualTo(event.occurredAt().toString());
        assertThat(header(record, "finapp.producer")).isEqualTo("kafka-probe");
        assertThat(header(record, "finapp.correlationId"))
                .isEqualTo(event.correlationId().value());
        assertThat(header(record, "finapp.causationId")).isEqualTo(event.causationId().value());
        assertThat(header(record, "finapp.payloadMediaType")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("no acknowledgement within the bound is an exception, not a wait")
    void theWaitIsBounded() {
        // autoComplete=false: the send's future never completes, which is a broker that accepted
        // the connection and went quiet - the exact case that would otherwise hold the relay's
        // transaction and advisory lock for as long as the broker cared to.
        MockProducer<String, byte[]> silent =
                new MockProducer<>(false, new FirstPartition(), new StringSerializer(), new ByteArraySerializer());

        long before = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                new KafkaEventPublisher(silent, Duration.ofMillis(200))
                                        .publish(anEvent("x")))
                .isInstanceOf(KafkaEventPublisher.BrokerPublicationException.class)
                .hasMessageContaining("no acknowledgement within");
        assertThat(Duration.ofNanos(System.nanoTime() - before))
                .as("the bound is the bound - generous slack for a loaded machine, but bounded")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a broker failure surfaces as its class, never its message")
    void aFailureCarriesTheClassAndNotTheMessage() {
        MockProducer<String, byte[]> failing =
                new MockProducer<>(false, new FirstPartition(), new StringSerializer(), new ByteArraySerializer());
        PendingEvent event = anEvent("secret-adjacent-payload");

        Thread completer =
                new Thread(
                        () -> {
                            // The broker message deliberately carries the kind of content
                            // INV-AUD-02 keeps out of last_error - the assertion is that none
                            // of it survives into the exception the relay records. errorNext
                            // fails the OLDEST QUEUED send and returns false while none is
                            // queued, so it spins until the publish under test has queued one -
                            // waiting on the condition, never a duration.
                            var brokerError =
                                    new org.apache.kafka.common.KafkaException(
                                            "host broker-7.internal rejected"
                                                    + " secret-adjacent-payload");
                            while (!failing.errorNext(brokerError)) {
                                Thread.onSpinWait();
                            }
                        });
        completer.start();

        assertThatThrownBy(
                        () ->
                                new KafkaEventPublisher(failing, Duration.ofSeconds(5))
                                        .publish(event))
                .isInstanceOf(KafkaEventPublisher.BrokerPublicationException.class)
                .hasMessageContaining("KafkaException")
                .hasMessageContaining(event.eventId().value().toString())
                .satisfies(
                        thrown -> {
                            assertThat(thrown.getMessage()).doesNotContain("broker-7.internal");
                            assertThat(thrown.getMessage())
                                    .doesNotContain("secret-adjacent-payload");
                        });
    }

    // -----------------------------------------------------------------

    /** kafka-clients 4.x MockProducer wants an explicit partitioner; the tests care only that
     * one exists. */
    static final class FirstPartition implements org.apache.kafka.clients.producer.Partitioner {
        @Override
        public int partition(
                String topic,
                Object key,
                byte[] keyBytes,
                Object value,
                byte[] valueBytes,
                org.apache.kafka.common.Cluster cluster) {
            return 0;
        }

        @Override
        public void close() {}

        @Override
        public void configure(java.util.Map<String, ?> configs) {}
    }

    private static String header(ProducerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as("header %s must be present", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static PendingEvent anEvent(String payload) {
        return new PendingEvent(
                EventId.next(IDS),
                "probe.SomethingHappened",
                1,
                1,
                UUID.randomUUID(),
                "Probe",
                Instant.parse("2026-09-09T12:00:00Z"),
                "kafka-probe",
                CorrelationId.of(UUID.randomUUID().toString()),
                CausationId.of("command-1"),
                payload.getBytes(StandardCharsets.UTF_8),
                "application/json",
                0);
    }
}
