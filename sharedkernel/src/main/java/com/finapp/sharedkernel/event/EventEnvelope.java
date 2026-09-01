package com.finapp.sharedkernel.event;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.EntityId;
import java.time.Instant;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The metadata every published event carries ({@code INV-EVT-03}, {@code CLAUDE.md} §Events,
 * {@code EVENT_ARCHITECTURE.md} §Event Metadata).
 *
 * <p><strong>Why all ten fields are mandatory.</strong> An envelope with optional fields is an
 * envelope whose fields are absent exactly when they are needed: during an incident, on the one
 * event nobody can explain. Every field is required at construction, so an event that cannot be
 * traced cannot be built — which is what makes {@code INV-EVT-03} a property of the type rather
 * than a convention consumers hope was followed.
 *
 * <p><strong>What the envelope is not.</strong> It carries no payload. The payload is the event's
 * own type, and keeping it out means the envelope can be read, stored, indexed and routed
 * without deserialising anything domain-specific — which is what lets an outbox relay,
 * a dead-letter tool and a consumer's deduplication all work on events they do not understand.
 *
 * <h2>The two versions, which are not the same thing</h2>
 *
 * <p>{@code EVENT_ARCHITECTURE.md} lists both and defines neither, so they are defined here:
 *
 * <ul>
 *   <li>{@link #eventVersion()} — the version of <em>this event type's contract</em>. It changes
 *       when {@code TransferCompleted} starts meaning something different, and it is what lets a
 *       consumer refuse an event whose meaning it does not understand.
 *   <li>{@link #schemaVersion()} — the version of <em>the envelope's own structure</em>. It
 *       changes when the metadata layout changes, and it is what lets a consumer parse an
 *       envelope written by an older producer at all.
 * </ul>
 *
 * <p>Collapsing them looks harmless until the first envelope migration, at which point there is
 * no way to say "the metadata moved but the event means the same thing" — and every consumer has
 * to be redeployed in step with every producer.
 *
 * <h2>Time</h2>
 *
 * <p>{@link #occurredAt()} is when the fact happened, supplied by the caller from an injected
 * {@code Clock} (P0-TSK-013). It is deliberately not "when this envelope was built" and not
 * "when it was published": an event delayed in an outbox for an hour still occurred when it
 * occurred, and {@code INV-EVT-04} requires consumers to tolerate exactly that.
 *
 * @param eventId identifies this event; the deduplication key an inbox uses
 *     ({@code INV-IDEM-04})
 * @param eventType the event's name, for example {@code transfers.TransferCompleted}
 * @param eventVersion the version of that event type's contract
 * @param schemaVersion the version of the envelope structure itself
 * @param aggregateId the aggregate this event is about
 * @param aggregateType the aggregate's kind — the envelope holds an {@link EntityId} it cannot
 *     name, and this is what tells a reader which kind it is
 * @param occurredAt when the fact happened
 * @param producer which module or service emitted it
 * @param correlationId the flow this event belongs to
 * @param causationId what directly caused it
 */
public record EventEnvelope(
        EventId eventId,
        String eventType,
        int eventVersion,
        int schemaVersion,
        EntityId aggregateId,
        String aggregateType,
        Instant occurredAt,
        String producer,
        CorrelationId correlationId,
        CausationId causationId) {

    /** The envelope structure this code writes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Bounded because these reach log lines, table columns and broker headers. */
    public static final int MAX_NAME_LENGTH = 200;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null (INV-EVT-03)");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null (INV-EVT-03)");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null (INV-EVT-03)");
        Objects.requireNonNull(correlationId, "correlationId must not be null (INV-EVT-03)");
        // Causation is required here, unlike in a Correlation: an event always has a cause,
        // because something made it happen. A flow may start uncaused; an event may not.
        Objects.requireNonNull(causationId, "causationId must not be null (INV-EVT-03)");

        eventType = name(eventType, "eventType");
        aggregateType = name(aggregateType, "aggregateType");
        producer = name(producer, "producer");

        eventVersion = positive(eventVersion, "eventVersion");
        schemaVersion = positive(schemaVersion, "schemaVersion");
    }

    private static String name(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null (INV-EVT-03)");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    field + " must be at most " + MAX_NAME_LENGTH + " characters but was "
                            + value.length());
        }
        return value;
    }

    private static int positive(int value, String field) {
        if (value < 1) {
            // Zero is the value an uninitialised int has, so accepting it would let a caller
            // that forgot to set a version produce an envelope that looks versioned.
            throw new IllegalArgumentException(field + " must be at least 1 but was " + value);
        }
        return value;
    }

    /**
     * A deterministic textual form of the metadata, for hashing, comparison and diagnosis.
     *
     * <p><strong>What "stable" means here and what it does not.</strong> The field set and their
     * order are a contract: {@code EventEnvelopeTest} pins the exact output, so renaming a
     * field, reordering one or adding one fails the build rather than silently changing what
     * downstream systems see. What this is *not* is the wire format — that belongs to the
     * transport, and the outbox and relay (P0-EPIC-06) choose it. Defining a wire format here
     * would commit the shared kernel to a serialisation library it must not depend on.
     *
     * <p>The payload is absent by construction, so this form can never leak event contents into
     * a log line ({@code INV-AUD-02}).
     */
    public String toCanonicalForm() {
        StringJoiner fields = new StringJoiner(",", "EventEnvelope{", "}");
        fields.add("schemaVersion=" + schemaVersion);
        fields.add("eventId=" + eventId.value());
        fields.add("eventType=" + eventType);
        fields.add("eventVersion=" + eventVersion);
        fields.add("aggregateType=" + aggregateType);
        fields.add("aggregateId=" + aggregateId.value());
        fields.add("occurredAt=" + occurredAt);
        fields.add("producer=" + producer);
        fields.add("correlationId=" + correlationId.value());
        fields.add("causationId=" + causationId.value());
        return fields.toString();
    }

    /**
     * The correlation an event emitted from this one should carry.
     *
     * <p>The flow is inherited; the cause becomes <em>this</em> event, because from the next
     * event's point of view this one is what caused it. Getting that backwards flattens the
     * causal tree into a list of siblings, and it reads as working because correlation still
     * ties them together.
     */
    public Correlation correlationForEmittedEvent() {
        return new Correlation(
                correlationId, CausationId.of(eventId.value().toString()));
    }

    @Override
    public String toString() {
        return toCanonicalForm();
    }
}
