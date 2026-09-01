package com.finapp.sharedkernel.event;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one event.
 *
 * <p>This is the value an inbox deduplicates on ({@code INV-IDEM-04}): at-least-once delivery is
 * the norm, so the same event will arrive more than once and the consumer must recognise it.
 * That makes the identifier part of the delivery contract rather than a diagnostic — a
 * regenerated identifier on a redelivered event defeats deduplication entirely, which is why it
 * belongs to the event and is fixed when the event is created.
 *
 * <p>An {@link EntityId} like any other, so it is time-ordered (ADR-0013) and cannot be passed
 * where a different kind of identifier is required. Unlike aggregate identifiers it lives in the
 * shared kernel, because an event is not a business noun owned by one module — every module
 * emits them and the envelope is defined here.
 */
public final class EventId extends EntityId {

    private EventId(UUID value) {
        super(value);
    }

    /** Mints an identifier for an event being created now. */
    public static EventId next(IdGenerator generator) {
        Objects.requireNonNull(generator, "generator must not be null");
        return new EventId(generator.next());
    }

    /** Rebuilds an identifier read back from storage or from a received message. */
    public static EventId of(UUID value) {
        return new EventId(value);
    }

    /** Rebuilds from text, as it arrives on a message header. */
    public static EventId of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new EventId(UUID.fromString(value));
    }
}
