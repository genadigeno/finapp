package com.finapp.platform.outbox;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One outbox row, as the relay reads it and hands it to a publisher.
 *
 * <p><strong>Why this is not an {@code EventEnvelope}.</strong> The envelope's
 * {@code aggregateId} is an {@code EntityId} — a typed identifier whose concrete class belongs
 * to the module that owns the aggregate (ADR-0013). The relay reads a raw {@code UUID} out of a
 * column and has no way to know whether it is a {@code TransferId} or a {@code PaymentId}, and
 * inventing a generic subclass to fill the gap would fabricate an identity the domain never
 * issued.
 *
 * <p>That is not a shortcoming of the envelope; it is the boundary working. The relay is
 * deliberately domain-blind: it moves rows, and the whole reason the envelope carries no payload
 * is so that a relay can route and publish an event it cannot interpret.
 *
 * <p>The payload is copied in and out. It is the bytes the producer wrote, and a publisher must
 * transmit them unchanged — a re-serialisation would make what consumers receive differ from
 * what the producing transaction committed.
 */
public record PendingEvent(
        EventId eventId,
        String eventType,
        int eventVersion,
        int schemaVersion,
        UUID aggregateId,
        String aggregateType,
        Instant occurredAt,
        String producer,
        CorrelationId correlationId,
        CausationId causationId,
        byte[] payload,
        String payloadMediaType,
        int attempts) {

    public PendingEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(producer, "producer must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(causationId, "causationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(payloadMediaType, "payloadMediaType must not be null");
        payload = payload.clone();
    }

    /** The bytes the producing transaction wrote. Never a re-encoding of them. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * The value a broker adapter uses as its partition key.
     *
     * <p>ADR-0005 preserves ordering per aggregate, and a partition key is how a broker is told
     * which messages must stay in order relative to one another. Naming it here rather than
     * leaving each adapter to choose means the guarantee does not depend on an adapter author
     * remembering it.
     */
    public UUID partitionKey() {
        return aggregateId;
    }
}
