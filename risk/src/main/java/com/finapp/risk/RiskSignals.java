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
 *
 * <p><strong>The permit pull</strong> (`P13-TSK-007`): a signal born is handed to {@link ProfileHints} in the same
 * transaction, which pulls the subject's party's customer risk profile permit to now - enrolling the party if it has
 * none. A signal already recorded hints nothing: its first delivery did.
 *
 * <p><strong>The fact's alert</strong> (`P13-TSK-025`, {@link SignalAlerts}): a signal of a kind that raises an alert
 * of its own - a default ({@link CollectionsCases}) - has it raised in the same transaction, after the permit pull,
 * <strong>on every delivery that reaches the handler</strong>: the born signal, or, when the insert answered
 * {@link Outcome#ALREADY}, the standing row read back by its source event ({@link RiskSignalStore#recordedFrom}). The
 * alert's own unique is what a duplicate meets, and a default recorded before the alert existed converges on its
 * replay. The raise folds into a case under the acting principal, so the handler enters the platform's for such a kind
 * ({@link #raises}).
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
    private final ProfileHints profileHints;
    private final SignalAlerts alerts;
    private final SignalObserver observer;

    /** T-sig with no permit pull ({@link ProfileHints#NONE}) - the suites' shape. */
    public RiskSignals(RiskSignalStore store, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock) {
        this(store, outbox, ids, clock, ProfileHints.NONE);
    }

    /** T-sig with the permit pull and no fact's alert ({@link SignalAlerts#NONE}). */
    public RiskSignals(RiskSignalStore store, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock,
            ProfileHints profileHints) {
        this(store, outbox, ids, clock, profileHints, SignalAlerts.NONE);
    }

    /** T-sig with the permit pull and the fact's alert, counting nothing ({@link SignalObserver#NONE}). */
    public RiskSignals(RiskSignalStore store, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock,
            ProfileHints profileHints, SignalAlerts alerts) {
        this(store, outbox, ids, clock, profileHints, alerts, SignalObserver.NONE);
    }

    /** T-sig as composed: every recording told to {@code observer} (`P13-TSK-024`, {@code finapp.risk.signal}). */
    public RiskSignals(RiskSignalStore store, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock,
            ProfileHints profileHints, SignalAlerts alerts, SignalObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.profileHints = Objects.requireNonNull(profileHints, "profileHints");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * True when recording a signal of {@code kind} raises an alert - and so opens or joins a case - in the same
     * transaction (`P13-TSK-025`): the handler records it under the platform's actor.
     */
    public boolean raises(SignalKind kind) {
        return alerts.raises(Objects.requireNonNull(kind, "kind"));
    }

    /**
     * Records {@code signal} on the caller's unit of work; announces it only when born; and for a kind that
     * {@link #raises}, raises its alert over the standing signal whether born now or already.
     */
    public Outcome record(Connection unitOfWork, RiskSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (!store.insert(unitOfWork, signal)) {
            if (alerts.raises(signal.kind())) {
                RiskSignal standing = store.recordedFrom(unitOfWork, signal.source().id())
                        .orElseThrow(() -> new IllegalStateException(
                                "a signal's insert conflicted and no signal stands for its source event"));
                if (standing.kind() != signal.kind()) {
                    throw new IllegalStateException("a source event stands recorded as another kind of signal");
                }
                alerts.raise(unitOfWork, standing);
            }
            observer.recorded(signal.kind(), Outcome.ALREADY);
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
        profileHints.signalRecorded(unitOfWork, signal);
        if (alerts.raises(signal.kind())) {
            alerts.raise(unitOfWork, signal);
        }
        observer.recorded(signal.kind(), Outcome.BORN);
        return Outcome.BORN;
    }
}
