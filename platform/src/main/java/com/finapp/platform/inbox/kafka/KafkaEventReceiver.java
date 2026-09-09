package com.finapp.platform.inbox.kafka;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConnectionSource;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The consumer shell (`P2-TSK-002`): Kafka records in, inbox-deduplicated effects out.
 *
 * <p>The consuming counterpart of {@code KafkaEventPublisher}, and the second class in the
 * platform permitted to touch a broker client — {@code NoDirectBrokerPublicationRulesTest} holds
 * that boundary, and this package is its second exemption for the symmetric reason the outbox
 * package was its first: consuming directly past the inbox produces effects with no dedupe,
 * which is {@code INV-IDEM-04}'s violation arriving through the receiving door.
 *
 * <h2>The load-bearing ordering: commit the effect, then acknowledge the record</h2>
 *
 * <p>Per record, per interested handler, one database transaction commits the handler's effect
 * together with its dedupe record ({@link InboxConsumer}'s guarantee). Only after every record's
 * database work is settled does {@link #pollOnce()} commit offsets to the broker — and
 * {@code enable.auto.commit} is {@code false} precisely because auto-commit acknowledges on the
 * <em>next poll</em> regardless of what happened, which is the effect-then-maybe-lost ordering
 * this class exists to forbid. The two commits cannot be atomic, and no pretence is made that
 * they are: every failure between them — a crash, a rebalance, a lost connection — resolves as a
 * redelivery into the dedupe, which is the safe direction. The reverse ordering would lose
 * records, silently.
 *
 * <h2>A record that cannot be handled stalls its partition, loudly</h2>
 *
 * <p>A failing or contended record is <em>seeked back to</em>, not skipped: acknowledging past
 * it would be an undetectable gap in the stream — the outbox relay's block-don't-skip reasoning
 * ({@code P0-TSK-020}), on the consuming side. Other partitions in the same poll continue; the
 * stalled one retries on the next poll, and the loop above this backs off so a poison record
 * costs a bounded retry rate rather than a hot loop. Dead-letter tooling remains the recorded
 * Phase 15 debt. Failure logs carry the failure's <em>class</em> and the event's identifiers,
 * never payload or header content ({@code INV-AUD-02}).
 *
 * <h2>Running as N instances (ADR-0014)</h2>
 *
 * <p>The group protocol assigns partitions disjointly in the steady state, and nothing here
 * depends on that being airtight: during a rebalance two instances can hold the same in-flight
 * record, and the arbiter is the inbox primary key in PostgreSQL — one instance records, the
 * other is told {@code SKIPPED_DUPLICATE} or {@code CONTENDED}, and a contended record is never
 * acknowledged, because the other transaction may yet roll back. Broker-held offsets are
 * transport bookkeeping, not truth: losing them entirely replays the topic into the dedupe.
 */
public final class KafkaEventReceiver implements AutoCloseable {

    static final String HEADER_PREFIX = "finapp.";

    private static final Logger log = LoggerFactory.getLogger(KafkaEventReceiver.class);

    private final Consumer<String, byte[]> consumer;
    private final InboxConnectionSource connections;
    private final InboxConsumer<Connection> inbox;
    private final List<InboxEventHandler> handlers;
    private final Duration pollTimeout;

    /**
     * Builds the shell over a real consumer, subscribed to the registered handlers' topics —
     * with the client configuration settled here, in the one package allowed to touch the
     * client, so the composition root passes strings and never sees a Kafka type.
     *
     * <ul>
     *   <li>{@code enable.auto.commit=false}: offsets move only when {@link #pollOnce()} commits
     *       them, after the database did.
     *   <li>{@code auto.offset.reset=earliest}: a consumer group that has never committed an
     *       offset starts at the beginning, because a downstream module must see facts published
     *       before it first started — {@code latest} would silently skip history, and the inbox
     *       makes a replay merely redundant rather than harmful.
     * </ul>
     */
    public static KafkaEventReceiver connect(
            String bootstrapServers,
            String securityProtocol,
            String groupId,
            InboxConnectionSource connections,
            InboxConsumer<Connection> inbox,
            List<InboxEventHandler> handlers,
            Duration pollTimeout) {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(securityProtocol, "securityProtocol must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        config.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        KafkaConsumer<String, byte[]> kafka = new KafkaConsumer<>(config);
        KafkaEventReceiver receiver =
                new KafkaEventReceiver(kafka, connections, inbox, handlers, pollTimeout);
        kafka.subscribe(handlers.stream().map(InboxEventHandler::topic).distinct().toList());
        return receiver;
    }

    /**
     * Over any consumer, unsubscribed — the test seam ({@code MockConsumer}, a decorated real
     * consumer). Production goes through {@link #connect}, which is where subscription happens.
     */
    public KafkaEventReceiver(
            Consumer<String, byte[]> consumer,
            InboxConnectionSource connections,
            InboxConsumer<Connection> inbox,
            List<InboxEventHandler> handlers,
            Duration pollTimeout) {
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers must not be null"));
        if (this.handlers.isEmpty()) {
            // A receiver with no handlers would consume records to no purpose and commit
            // offsets past facts nobody reacted to. Refused rather than tolerated: the
            // composition root creates receivers per module that HAS handlers.
            throw new IllegalArgumentException("a receiver needs at least one handler");
        }
        this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout must not be null");
        if (pollTimeout.isNegative() || pollTimeout.isZero()) {
            throw new IllegalArgumentException("pollTimeout must be positive: " + pollTimeout);
        }
    }

    /**
     * Polls once and settles every record: effects committed, then offsets — see the class
     * javadoc for why that order is the whole design.
     */
    public ReceiverPollResult pollOnce() {
        try {
            return settle(consumer.poll(pollTimeout));
        } catch (WakeupException shuttingDown) {
            // wakeup() interrupted a blocking call - the loop above is stopping. Nothing was
            // acknowledged past a committed effect, so whatever was in flight redelivers into
            // the dedupe. Translated to an empty result here so no caller needs a Kafka type.
            return ReceiverPollResult.NOTHING;
        }
    }

    private ReceiverPollResult settle(ConsumerRecords<String, byte[]> records) {
        int processed = 0;
        int duplicates = 0;
        int contended = 0;
        int failed = 0;
        Map<TopicPartition, OffsetAndMetadata> acknowledged = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
            for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
                Delivery outcome = deliver(record);
                switch (outcome) {
                    case PROCESSED -> processed++;
                    case DUPLICATE -> duplicates++;
                    case UNHANDLED -> {
                        // A topic carries every event type its module produces; a record no
                        // registered handler wants is the normal case, acknowledged silently.
                    }
                    case CONTENDED -> contended++;
                    case FAILED -> failed++;
                }
                if (outcome == Delivery.CONTENDED || outcome == Delivery.FAILED) {
                    // Seek back and stall THIS partition; the ones already settled in this
                    // batch keep their acknowledgement, and other partitions keep going.
                    consumer.seek(partition, record.offset());
                    break;
                }
                acknowledged.put(partition, new OffsetAndMetadata(record.offset() + 1));
            }
        }
        if (!acknowledged.isEmpty()) {
            consumer.commitSync(acknowledged);
        }
        return new ReceiverPollResult(processed, duplicates, contended, failed);
    }

    private Delivery deliver(ConsumerRecord<String, byte[]> record) {
        ReceivedEvent event;
        try {
            event = parse(record);
        } catch (RuntimeException malformed) {
            // We produce every one of these headers (P2-TSK-001), so a malformed record is a
            // producer defect - and skipping it would be a silent gap in the stream, which is
            // the failure the seek-back exists to prevent. The partition stalls loudly instead.
            log.error(
                    "Refusing a malformed record on {} at offset {}: {}",
                    record.topic(),
                    record.offset(),
                    malformed.getClass().getSimpleName());
            return Delivery.FAILED;
        }

        List<InboxEventHandler> interested =
                handlers.stream()
                        .filter(handler -> handler.topic().equals(record.topic()))
                        .filter(handler -> handler.eventType().equals(event.eventType()))
                        .toList();
        if (interested.isEmpty()) {
            return Delivery.UNHANDLED;
        }

        // The effect's correlation is the producing flow's; its cause is this event. The
        // event's own causationId is what caused the EVENT and would flatten the causal tree
        // if inherited here - Correlation.causing's documented trap, met at the seam where a
        // wire message becomes local work.
        Correlation correlation =
                new Correlation(
                        event.correlationId(),
                        CausationId.of(event.eventId().value().toString()));

        boolean processedAny = false;
        for (InboxEventHandler handler : interested) {
            Delivery one = handleOne(handler, event, correlation);
            if (one == Delivery.CONTENDED || one == Delivery.FAILED) {
                // One consumer's failure must not let the others' acknowledgement skip the
                // record: the redelivery retries only the unfinished handlers, because the
                // finished ones' dedupe records are already committed.
                return one;
            }
            processedAny |= one == Delivery.PROCESSED;
        }
        return processedAny ? Delivery.PROCESSED : Delivery.DUPLICATE;
    }

    @SuppressWarnings("try") // the correlation Scope is used for its close side effect
    private Delivery handleOne(
            InboxEventHandler handler, ReceivedEvent event, Correlation correlation) {
        InboxKey key = new InboxKey(handler.consumerName(), event.eventId().value().toString());
        try (Connection unitOfWork = connections.open()) {
            unitOfWork.setAutoCommit(false);
            try (CorrelationContext.Scope scope = CorrelationContext.enter(correlation)) {
                InboxConsumer.Outcome outcome =
                        inbox.consume(
                                unitOfWork,
                                key,
                                event.eventType(),
                                connection -> handler.handle(connection, event));
                switch (outcome) {
                    case PROCESSED -> {
                        unitOfWork.commit();
                        return Delivery.PROCESSED;
                    }
                    case SKIPPED_DUPLICATE -> {
                        unitOfWork.commit();
                        return Delivery.DUPLICATE;
                    }
                    case CONTENDED -> {
                        unitOfWork.rollback();
                        return Delivery.CONTENDED;
                    }
                }
                throw new IllegalStateException("unreachable: " + outcome);
            } catch (RuntimeException handlerFailed) {
                unitOfWork.rollback();
                log.warn(
                        "Handler {} failed for {} ({}): {} - rolled back, dedupe record"
                                + " included, so the redelivery retries",
                        handler.consumerName(),
                        event.eventType(),
                        event.eventId().value(),
                        handlerFailed.getClass().getSimpleName());
                return Delivery.FAILED;
            }
        } catch (SQLException storage) {
            // Could not open, commit or roll back a unit of work. The class only: a JDBC
            // message can carry hosts, statements and row content (the P1-TSK-008 finding).
            log.warn(
                    "No unit of work for handler {} ({}): {}",
                    handler.consumerName(),
                    event.eventId().value(),
                    storage.getClass().getSimpleName());
            return Delivery.FAILED;
        }
    }

    private static ReceivedEvent parse(ConsumerRecord<String, byte[]> record) {
        return new ReceivedEvent(
                EventId.of(header(record, "eventId")),
                header(record, "eventType"),
                Integer.parseInt(header(record, "eventVersion")),
                Integer.parseInt(header(record, "schemaVersion")),
                UUID.fromString(header(record, "aggregateId")),
                header(record, "aggregateType"),
                Instant.parse(header(record, "occurredAt")),
                header(record, "producer"),
                CorrelationId.of(header(record, "correlationId")),
                CausationId.of(header(record, "causationId")),
                header(record, "payloadMediaType"),
                record.value() == null ? new byte[0] : record.value());
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(HEADER_PREFIX + name);
        if (header == null || header.value() == null) {
            throw new IllegalStateException("missing header " + HEADER_PREFIX + name);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Interrupts a blocking poll from another thread — the loop's shutdown signal. */
    public void wakeup() {
        consumer.wakeup();
    }

    @Override
    public void close() {
        try {
            consumer.close();
        } catch (WakeupException alreadyStopping) {
            // A wakeup that landed between the last poll and this close. Closing is what the
            // wakeup was for, so there is nothing left to interrupt.
        }
    }

    private enum Delivery {
        PROCESSED,
        DUPLICATE,
        UNHANDLED,
        CONTENDED,
        FAILED
    }
}
