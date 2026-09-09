package com.finapp.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * The broker adapter (`P2-TSK-001`): one outbox event becomes one Kafka record.
 *
 * <p>The one class in the platform permitted to touch a broker client —
 * {@code NoDirectBrokerPublicationRulesTest} holds that boundary for everything else, and this
 * class holds no logic worth taking a shortcut for: it maps a {@link PendingEvent} onto a record
 * and waits, bounded, for the broker's acknowledgement.
 *
 * <h2>The wire format, settled here as `EVENT_ARCHITECTURE.md` deferred it to be</h2>
 *
 * <ul>
 *   <li><strong>Value</strong> — the payload bytes, verbatim. The port forbids re-encoding:
 *       consumers must receive what the producing transaction committed.
 *   <li><strong>Key</strong> — {@link PendingEvent#partitionKey()} as a UTF-8 string. Kafka's
 *       partitioner then keeps one aggregate on one partition, which is what turns the relay's
 *       per-aggregate ordering into an ordering consumers actually observe.
 *   <li><strong>Headers</strong> — the ten envelope fields plus the payload media type, each
 *       {@code finapp.}-prefixed, as UTF-8 strings. Metadata rides outside the payload so a
 *       consumer can route and deduplicate an event it cannot parse — the envelope's own design
 *       principle ({@code P0-TSK-018}), extended to the wire. {@code finapp.eventId} is the
 *       consumer's dedupe key ({@code INV-IDEM-04}).
 *   <li><strong>Topic</strong> — {@code finapp.<producer>}: one topic per producing module, a
 *       small stable set; {@code finapp.eventType} in the headers is what a consumer filters on.
 *       Revisit trigger, recorded: a topic whose consumers' interests diverge materially.
 * </ul>
 *
 * <h2>Bounded, because the relay is holding a lock</h2>
 *
 * <p>The relay holds a database transaction and the aggregate's advisory lock across this call,
 * so an unbounded wait would stall that aggregate for as long as the broker takes to notice it is
 * unwell. {@code send(...).get(ackTimeout)} bounds the wait here; the producer the application
 * wires bounds its own internals ({@code max.block.ms}, {@code delivery.timeout.ms}) so the
 * future cannot dawdle past this bound by much.
 *
 * <h2>What a thrown exception means, and what it never contains</h2>
 *
 * <p>Any exception is a failed attempt the relay records and retries. A timeout is
 * indistinguishable from a failure and is treated as one — the broker may have the record anyway,
 * which is at-least-once delivery doing what ADR-0005 says it does; the duplicate carries the
 * same {@code finapp.eventId} and dies in the consumer's inbox. Messages name the event and the
 * failure class, never payload content ({@code INV-AUD-02}) — the relay writes them into
 * {@code last_error}, which operators read.
 */
public final class KafkaEventPublisher implements EventPublisher, AutoCloseable {

    static final String TOPIC_PREFIX = "finapp.";
    static final String HEADER_PREFIX = "finapp.";

    private final Producer<String, byte[]> producer;
    private final Duration ackTimeout;

    /**
     * Builds the adapter over a real producer, with the acknowledgement configuration ADR-0005
     * deferred to the first adapter — settled here, in the one package allowed to touch the
     * client, so the composition root passes strings and never sees a Kafka type
     * ({@code NoDirectBrokerPublicationRulesTest}'s boundary).
     *
     * <ul>
     *   <li>{@code acks=all} and {@code enable.idempotence=true}: a broker-side retry can
     *       neither drop nor reorder within a producer session, which together with the relay's
     *       per-aggregate lock is what keeps one aggregate's records in order on the wire.
     *   <li>{@code max.block.ms} and {@code delivery.timeout.ms} bounded near the ack timeout:
     *       the relay is holding a transaction and an advisory lock across every send, and an
     *       unbounded producer would stall that aggregate for as long as the broker is unwell.
     * </ul>
     */
    public static KafkaEventPublisher connect(
            String bootstrapServers, String securityProtocol, Duration ackTimeout) {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(securityProtocol, "securityProtocol must not be null");
        Objects.requireNonNull(ackTimeout, "ackTimeout must not be null");
        java.util.Properties config = new java.util.Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        config.put(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(ackTimeout.toMillis()));
        config.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Integer.toString(Math.toIntExact(ackTimeout.toMillis())));
        // Twice the ack timeout, not equal to it: the client refuses a delivery timeout smaller
        // than linger + request timeout (found by constructing one, not by reading - linger's
        // default is no longer zero in the 4.x client). The RELAY's bound stays the ack timeout,
        // enforced at the future; this only keeps the producer's internal retry window legal.
        config.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Integer.toString(Math.toIntExact(ackTimeout.toMillis() * 2)));
        config.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        config.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        return new KafkaEventPublisher(new KafkaProducer<>(config), ackTimeout);
    }

    public KafkaEventPublisher(Producer<String, byte[]> producer, Duration ackTimeout) {
        this.producer = Objects.requireNonNull(producer, "producer must not be null");
        this.ackTimeout = Objects.requireNonNull(ackTimeout, "ackTimeout must not be null");
        if (ackTimeout.isNegative() || ackTimeout.isZero()) {
            throw new IllegalArgumentException("ackTimeout must be positive: " + ackTimeout);
        }
    }

    @Override
    public void publish(PendingEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(
                        TOPIC_PREFIX + event.producer(),
                        event.partitionKey().toString(),
                        event.payload());
        header(record, "eventId", event.eventId().value().toString());
        header(record, "eventType", event.eventType());
        header(record, "eventVersion", Integer.toString(event.eventVersion()));
        header(record, "schemaVersion", Integer.toString(event.schemaVersion()));
        header(record, "aggregateId", event.aggregateId().toString());
        header(record, "aggregateType", event.aggregateType());
        header(record, "occurredAt", event.occurredAt().toString());
        header(record, "producer", event.producer());
        header(record, "correlationId", event.correlationId().value());
        header(record, "causationId", event.causationId().value());
        header(record, "payloadMediaType", event.payloadMediaType());

        try {
            producer.send(record).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BrokerPublicationException(event, "interrupted awaiting acknowledgement");
        } catch (ExecutionException failed) {
            // The cause's CLASS, never its message: a broker exception can carry hosts and
            // record fragments, and this text lands in last_error (INV-AUD-02).
            throw new BrokerPublicationException(
                    event,
                    failed.getCause() == null
                            ? "publication failed"
                            : failed.getCause().getClass().getSimpleName());
        } catch (TimeoutException late) {
            throw new BrokerPublicationException(
                    event,
                    "no acknowledgement within " + ackTimeout.toMillis() + " ms; the broker may"
                            + " hold the record anyway - the retry republishes with the same"
                            + " eventId and the consumer inbox deduplicates");
        }
    }

    /** Closes the underlying producer, flushing bounded by the same ack timeout. */
    @Override
    public void close() {
        producer.close(ackTimeout);
    }

    private static void header(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().add(HEADER_PREFIX + name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Carries which event failed and the failure's class — never payload content. */
    public static final class BrokerPublicationException extends RuntimeException {
        // The project's third meeting with this requirement (CurrencyCode, IdempotencyKey):
        // an exception is serializable, so the pin is mandatory under -Werror.
        private static final long serialVersionUID = 1L;

        BrokerPublicationException(PendingEvent event, String why) {
            super("event " + event.eventId().value() + " (" + event.eventType() + "): " + why);
        }
    }
}
