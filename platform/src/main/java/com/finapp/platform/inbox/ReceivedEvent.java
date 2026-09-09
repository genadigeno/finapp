package com.finapp.platform.inbox;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One event as it arrives off the wire, typed and transport-free (`P2-TSK-002`).
 *
 * <p>The consuming mirror of {@link com.finapp.platform.outbox.PendingEvent}, and deliberately
 * shaped the same way for the same reasons: the aggregate identifier is a raw {@code UUID}
 * because a domain-blind shell cannot know which module's typed identifier it is (ADR-0013), and
 * the payload is the bytes the producing transaction committed — {@code P2-TSK-001}'s adapter
 * forbids re-encoding on the way out, and this type carries them verbatim on the way in, cloned
 * at the boundary in both directions so no handler can mutate what a sibling handler receives.
 *
 * <p><strong>Why this is not a Kafka type.</strong> A handler's signature is the seam a business
 * module programs against ({@link InboxEventHandler}), and a broker type in it would spread the
 * client dependency to every consuming module — the exact widening
 * {@code NoDirectBrokerPublicationRulesTest} exists to prevent. The shell parses the wire
 * headers into this record inside the one package allowed to touch the client; everything past
 * that boundary is plain platform vocabulary.
 */
public record ReceivedEvent(
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
        String payloadMediaType,
        byte[] payload) {

    public ReceivedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(producer, "producer must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(causationId, "causationId must not be null");
        Objects.requireNonNull(payloadMediaType, "payloadMediaType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        payload = payload.clone();
    }

    /** The bytes the producing transaction committed. Never a re-encoding of them. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
