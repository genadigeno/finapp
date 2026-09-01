package com.finapp.sharedkernel.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code INV-EVT-03}: every event carries the full envelope, enforced at construction.
 *
 * <p>The interesting tests here are the two that cannot be written by listing what the envelope
 * currently holds: one derives the field set from the record itself, so a field added without a
 * null check fails; the other pins the canonical form exactly, so the serialisation contract
 * cannot drift silently.
 */
class EventEnvelopeTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new Random(11L));

    /** Stands in for an aggregate identifier owned by a business module in a later phase. */
    static final class ProbeTransferId extends EntityId {
        ProbeTransferId(UUID value) {
            super(value);
        }
    }

    // -----------------------------------------------------------------
    // INV-EVT-03 — all ten, mandatory
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the envelope has exactly the ten fields the invariant names")
    void carriesExactlyTheMandatedFields() {
        // Derived from the record rather than listed, so adding an eleventh field without
        // deciding whether it is mandatory fails here rather than passing unnoticed.
        List<String> fields =
                java.util.Arrays.stream(EventEnvelope.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(fields)
                .as("CLAUDE.md §Events and EVENT_ARCHITECTURE.md §Event Metadata")
                .containsExactlyInAnyOrder(
                        "eventId", "eventType", "eventVersion", "schemaVersion",
                        "aggregateId", "aggregateType", "occurredAt", "producer",
                        "correlationId", "causationId");
    }

    @Test
    @DisplayName("every reference field rejects null, so an untraceable event cannot be built")
    void everyReferenceFieldIsMandatory() {
        // Reflection over the record's own components: a field added later without a null check
        // fails this test, where a hand-written list would simply not mention it.
        for (RecordComponent component : EventEnvelope.class.getRecordComponents()) {
            if (component.getType().isPrimitive()) {
                continue;
            }
            assertThatThrownBy(() -> envelopeWithNull(component.getName()))
                    .as("%s must be rejected when null (INV-EVT-03)", component.getName())
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("a version of zero is refused, because that is what an unset int looks like")
    void versionsMustBePositive() {
        assertThatThrownBy(() -> envelopeWith(0, EventEnvelope.CURRENT_SCHEMA_VERSION, "transfers.T", "transfers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
        assertThatThrownBy(() -> envelopeWith(1, 0, "transfers.T", "transfers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @DisplayName("blank and over-long names are refused")
    void namesAreValidated() {
        assertThatThrownBy(() -> envelopeWith(1, 1, "  ", "transfers"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> envelopeWith(1, 1, "transfers.T", "p".repeat(EventEnvelope.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    // -----------------------------------------------------------------
    // Serialisation stability
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the canonical form is pinned exactly, so the contract cannot drift silently")
    void canonicalFormIsStable() {
        // The acceptance criterion's "serialisation is stable and versioned". Renaming a field,
        // reordering one, or adding one changes this string and fails the build - which is the
        // only way "stable" is a property rather than an intention.
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.of("0199b3c1-0000-7000-8000-000000000001"),
                        "transfers.TransferCompleted",
                        2,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeTransferId(UUID.fromString("0199b3c1-0000-7000-8000-000000000002")),
                        "Transfer",
                        OCCURRED,
                        "transfers",
                        CorrelationId.of("flow-1"),
                        CausationId.of("command-1"));

        assertThat(envelope.toCanonicalForm())
                .isEqualTo(
                        "EventEnvelope{schemaVersion=1,"
                                + "eventId=0199b3c1-0000-7000-8000-000000000001,"
                                + "eventType=transfers.TransferCompleted,"
                                + "eventVersion=2,"
                                + "aggregateType=Transfer,"
                                + "aggregateId=0199b3c1-0000-7000-8000-000000000002,"
                                + "occurredAt=2026-09-01T12:00:00Z,"
                                + "producer=transfers,"
                                + "correlationId=flow-1,"
                                + "causationId=command-1}");
    }

    @Test
    @DisplayName("the schema version leads, so an old consumer can tell how to read the rest")
    void schemaVersionComesFirst() {
        // A consumer that cannot parse the envelope cannot read the field telling it which
        // layout to expect - unless that field is first and never moves.
        assertThat(envelope().toCanonicalForm()).startsWith("EventEnvelope{schemaVersion=");
    }

    @Test
    @DisplayName("the canonical form carries no payload, because the envelope has none")
    void canonicalFormCannotLeakEventContents() {
        // Metadata only, by construction. A log line containing an envelope can never spill an
        // amount, a beneficiary or an account number (INV-AUD-02).
        assertThat(EventEnvelope.class.getRecordComponents())
                .as("no payload component may be added")
                .noneMatch(component -> component.getName().toLowerCase(java.util.Locale.ROOT).contains("payload")
                        || component.getName().toLowerCase(java.util.Locale.ROOT).contains("body")
                        || component.getName().toLowerCase(java.util.Locale.ROOT).contains("data"));
    }

    // -----------------------------------------------------------------
    // Causation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an event emitted from this one is caused by it, and keeps the flow")
    void emittedEventInheritsFlowAndIsCausedByThisEvent() {
        EventEnvelope cause = envelope();

        Correlation next = cause.correlationForEmittedEvent();

        assertThat(next.correlationId()).as("the flow is what stays the same").isEqualTo(cause.correlationId());
        assertThat(next.cause())
                .as("the next event's cause is this event, not this event's cause")
                .contains(CausationId.of(cause.eventId().value().toString()));
        assertThat(next.cause()).as("and definitely not the parent cause").isNotEqualTo(java.util.Optional.of(cause.causationId()));
    }

    @Test
    @DisplayName("two envelopes with the same values are equal, and differ when any field does")
    void valueSemantics() {
        // Fixed identifiers, because envelope() mints a fresh EventId per call and two events
        // are genuinely not the same event.
        assertThat(envelopeWith(1, 1, "transfers.TransferCompleted", "transfers"))
                .isEqualTo(envelopeWith(1, 1, "transfers.TransferCompleted", "transfers"));
        assertThat(envelopeWith(1, 1, "transfers.TransferCompleted", "transfers"))
                .isNotEqualTo(envelopeWith(9, 1, "transfers.TransferCompleted", "transfers"));
    }

    // -----------------------------------------------------------------

    private static EventEnvelope envelope() {
        return new EventEnvelope(
                EventId.next(IDS),
                "transfers.TransferCompleted",
                1,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                new ProbeTransferId(IDS.next()),
                "Transfer",
                OCCURRED,
                "transfers",
                CorrelationId.of("flow-1"),
                CausationId.of("command-1"));
    }

    /** An envelope with the versions and names varied, everything else fixed. */
    private static EventEnvelope envelopeWith(
            int eventVersion, int schemaVersion, String eventType, String producer) {
        return new EventEnvelope(
                EventId.of("0199b3c1-0000-7000-8000-00000000000a"),
                eventType,
                eventVersion,
                schemaVersion,
                new ProbeTransferId(UUID.fromString("0199b3c1-0000-7000-8000-00000000000b")),
                "Transfer",
                OCCURRED,
                producer,
                CorrelationId.of("flow-1"),
                CausationId.of("command-1"));
    }

    /** Builds an envelope with one named component set to null. */
    private static EventEnvelope envelopeWithNull(String nullField) {
        return new EventEnvelope(
                "eventId".equals(nullField) ? null : EventId.next(IDS),
                "eventType".equals(nullField) ? null : "transfers.TransferCompleted",
                1,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                "aggregateId".equals(nullField) ? null : new ProbeTransferId(IDS.next()),
                "aggregateType".equals(nullField) ? null : "Transfer",
                "occurredAt".equals(nullField) ? null : OCCURRED,
                "producer".equals(nullField) ? null : "transfers",
                "correlationId".equals(nullField) ? null : CorrelationId.of("flow-1"),
                "causationId".equals(nullField) ? null : CausationId.of("command-1"));
    }
}
