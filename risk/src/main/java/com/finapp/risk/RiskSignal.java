package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.time.Instant;
import java.util.Objects;

/**
 * A risk signal: an append-only fact about a subject, born once per source event (`P13-TSK-005`;
 * {@code PHASE_13_PLAN.md} section 3.1; {@code RISK_LIFECYCLES.md} section 4.4; {@code INV-RSK-06}) - its kind, its
 * subject, the event it was born from, when the fact was observed (the event's own instant, never the handler's
 * clock - {@code INV-EVT-04}) and the typed, bounded attributes the event carried. Not a machine: it has no status,
 * is never updated and is never deleted by any role.
 *
 * <p>The constructor holds the three agreements a mapping can get wrong: the subject's kind is the kind's declared
 * subject; the source event's type is the kind's declared source; the attributes are exactly the kind's schema. A
 * signal that cannot be built is a mapping defect in {@code app}'s handler, refused before any row exists.
 *
 * @param id the signal's identity
 * @param kind which fact
 * @param subject what it is about
 * @param source the event it was born from - its id is the born-once key
 * @param observedAt when the fact happened: the source event's {@code occurredAt}
 * @param attributes what the event carried, typed and bounded per kind
 */
public record RiskSignal(
        RiskSignalId id,
        SignalKind kind,
        SignalSubject subject,
        SourceEvent source,
        Instant observedAt,
        SignalAttributes attributes) {

    public RiskSignal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(attributes, "attributes");
        if (subject.kind() != kind.subject()) {
            throw new IllegalArgumentException(
                    "a " + kind + " signal is about a " + kind.subject() + ", not a " + subject.kind());
        }
        if (!source.type().equals(kind.sourceEventType())) {
            throw new IllegalArgumentException(
                    "a " + kind + " signal is born from " + kind.sourceEventType() + ", not " + source.type());
        }
        // Re-validated against the kind: an attributes object built for another kind does not fit this one.
        SignalAttributes.of(kind, attributes.asMap());
    }

    /**
     * The event a signal was born from - what the row keeps of the envelope so the signal is traceable to the outbox
     * row that produced it: the id (the born-once key), the type and version (which contract was read), the producer,
     * and the flow (the emitted {@code RiskSignalRecorded} inherits the correlation and is caused by this event).
     */
    public record SourceEvent(
            EventId id,
            String type,
            int version,
            String producer,
            CorrelationId correlation,
            CausationId causation) {

        public SourceEvent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(correlation, "correlation");
            Objects.requireNonNull(causation, "causation");
            if (type.isBlank() || type.length() > 200) {
                throw new IllegalArgumentException("an event type is 1..200 characters");
            }
            if (producer.isBlank() || producer.length() > 64) {
                throw new IllegalArgumentException("a producer is 1..64 characters");
            }
            if (version < 1) {
                throw new IllegalArgumentException("an event version is at least 1");
            }
        }
    }
}
