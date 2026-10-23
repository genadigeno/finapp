package com.finapp.risk;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * T-sig: a signal recorded, born once, in the inbox handler's transaction, and announced when born (`P13-TSK-005`;
 * {@code PHASE_13_PLAN.md} sections 7.1, 10; {@code RISK_LIFECYCLES.md} section 4.4).
 *
 * <p>The unit of work is the caller's - the inbox shell's, so the inbox's dedupe row, this row and the outbox row
 * commit together or not at all ({@code INV-IDEM-04}, {@code INV-EVT-01}). The store's insert is the arbiter
 * ({@code UNIQUE (source_event_id)}, {@code INV-RSK-06}): when it says the source event was already recorded,
 * nothing is announced - a second {@code RiskSignalRecorded} would tell consumers a second signal exists.
 *
 * <p>{@code risk.RiskSignalRecorded} carries the signal's id, its kind, and its subject's kind and reference -
 * identifiers and closed codes, never an attribute, a description or a reason ({@code INV-RSK-08}; ADR-0107 decision
 * 8). It inherits the consumed event's correlation and is caused by that event, so the causal chain reads producer →
 * signal rather than flattening into siblings ({@code EventEnvelope#correlationForEmittedEvent}'s rule).
 */
public final class RiskSignals {

    public static final String EVENT_TYPE = "risk.RiskSignalRecorded";
    public static final String PRODUCER = "risk";
    public static final int EVENT_VERSION = 1;
    public static final String AGGREGATE_TYPE = "risk_signal";

    /** What recording did. */
    public enum Outcome {
        /** The signal was born by this call and announced. */
        BORN,
        /** Its source event had already been recorded; nothing was written and nothing announced. */
        ALREADY
    }

    private final RiskSignalStore store;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public RiskSignals(RiskSignalStore store, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records {@code signal} on the caller's unit of work; announces it only when born. */
    public Outcome record(Connection unitOfWork, RiskSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (!store.insert(unitOfWork, signal)) {
            return Outcome.ALREADY;
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        signal.id(),
                        AGGREGATE_TYPE,
                        Instant.now(clock),
                        PRODUCER,
                        signal.source().correlation(),
                        CausationId.of(signal.source().id().value().toString())),
                EventPayload.of()
                        .with("signalId", signal.id().value().toString())
                        .with("kind", signal.kind().name())
                        .with("subjectKind", signal.subject().kind().name())
                        .with("subjectRef", signal.subject().ref())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return Outcome.BORN;
    }
}
