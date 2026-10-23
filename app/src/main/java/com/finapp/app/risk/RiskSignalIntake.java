package com.finapp.app.risk;

import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.SignalKind;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The platform's events as risk signals and as monitoring hints (`P13-TSK-005`; {@code PHASE_13_PLAN.md} sections
 * 7.1 T-sig, 10, 12.5; ADR-0107 decision 8; {@code RISK_LIFECYCLES.md} section 4.4) - one handler instance per
 * consumed event type, every one under the consumer name {@code risk.signals} (so one dedupe scope and one consumer
 * group, {@code finapp.risk}, whose consumption progresses independently of every other module's). The bnpl and
 * lending origination hints are the shape; the difference is what a signal is.
 *
 * <p><strong>The payload IS read here</strong> - the first consumer in the platform to do so. A hint reacts to the
 * envelope's aggregate and asks the owning module through a port (the metadata-only stance, `P2-TSK-007`); a signal
 * records what the event <em>said</em>, with the attributes it carried and nothing read from the producer's tables
 * (section 12.5), so a delayed or reordered event records exactly its own fact with its own instant
 * ({@code INV-EVT-04}). The bytes are read by {@code EventPayload}'s own inverse, which admits exactly the writer's
 * grammar - identifiers and enumerated names - and nothing else; the mapping per kind is {@link SignalMappings}.
 *
 * <p><strong>A version this build does not know, or a payload it cannot read, is refused - thrown</strong>, so the
 * shell rolls the transaction back (the dedupe record with it), seeks back and stalls the partition loudly
 * ({@code finapp.inbox.consumption{outcome=failed}} rises; the loop's backoff bounds the retry rate). A hint may be
 * acknowledged and lost, because the sweep converges without it; a signal is evidence, and a signal silently dropped
 * is an undetectable gap in it - the block-don't-skip rule on the consuming side ({@code EVENT_ARCHITECTURE.md}
 * section Consumption). The backlog's "parking" is not a mechanism the platform has; the stall is. Nothing is ever
 * truncated to fit.
 *
 * <p><strong>The completions record no signal.</strong> {@code transfers}', {@code payments}' and {@code crossborder}'s
 * completions are hints to the monitoring sweep: each is handed to {@link MonitoringHints}, which `-019` implements
 * over its watermark (the permit pull, in this same transaction); a signal per completed movement would be a second,
 * event-derived copy of the journal's born-once {@code monitored_transaction} ({@code INV-RSK-09}) - Kafka is not
 * truth. Until `-019`, {@link MonitoringHints#NONE} lets the hint fall: the sweep's own permit converges.
 *
 * <p>No actor is entered: a signal names no actor, and the inbox's own record is the trail (plan section 11: audit -
 * none, machine acts). The span is {@link RiskSpans#SIGNAL_RECORD}, with no attribute.
 */
@Slf4j
public final class RiskSignalIntake implements InboxEventHandler {

    /** One dedupe scope and one consumer group for every source: {@code (risk.signals, eventId)} is the inbox key. */
    public static final String CONSUMER = "risk.signals";

    /**
     * Each consumed event: its topic ({@code EVENT_ARCHITECTURE.md}: one topic per producing module), its type, and
     * either the {@link SignalKind} it records or - for a completion - no kind, a hint. Every one at event version 1,
     * the newest every producer writes today; a higher version is refused until this list learns it.
     */
    public enum Source {
        CREDENTIAL_CHANGED("finapp.identity", SignalKind.CREDENTIAL_CHANGED),
        RECOVERY_INITIATED("finapp.identity", SignalKind.RECOVERY_INITIATED),
        RECOVERY_COMPLETED("finapp.identity", SignalKind.RECOVERY_COMPLETED),
        IDENTITY_SUSPENDED("finapp.identity", SignalKind.IDENTITY_SUSPENDED),
        ACCOUNT_OPENED("finapp.accounts", SignalKind.ACCOUNT_OPENED),
        BENEFICIARY_REGISTERED("finapp.crossborder", SignalKind.BENEFICIARY_REGISTERED),
        BENEFICIARY_ACTIVATED("finapp.crossborder", SignalKind.BENEFICIARY_ACTIVATED),
        BENEFICIARY_BLOCKED("finapp.crossborder", SignalKind.BENEFICIARY_BLOCKED),
        MERCHANT_ONBOARDED("finapp.merchant", SignalKind.MERCHANT_ONBOARDED),
        LOAN_DELINQUENCY_CHANGED("finapp.lending", SignalKind.LOAN_DELINQUENCY_CHANGED),
        LOAN_DEFAULTED("finapp.lending", SignalKind.LOAN_DEFAULTED),
        BNPL_DELINQUENCY_CHANGED("finapp.bnpl", SignalKind.BNPL_DELINQUENCY_CHANGED),
        BNPL_DEFAULTED("finapp.bnpl", SignalKind.BNPL_DEFAULTED),
        /** {@code transfers.TransferCompleted} - a hint. */
        TRANSFER_COMPLETED("finapp.transfers", "transfers.TransferCompleted"),
        /** {@code payments.PaymentCaptured} - a card capture, a hint. */
        PAYMENT_CAPTURED("finapp.payments", "payments.PaymentCaptured"),
        /** {@code payments.PaymentExecuted} - a wallet or book execution, a hint. */
        PAYMENT_EXECUTED("finapp.payments", "payments.PaymentExecuted"),
        /** {@code payments.WithdrawalCompleted} - a hint. */
        WITHDRAWAL_COMPLETED("finapp.payments", "payments.WithdrawalCompleted"),
        /** {@code crossborder.CrossBorderPaymentDelivered} - a hint. */
        CROSS_BORDER_DELIVERED("finapp.crossborder", "crossborder.CrossBorderPaymentDelivered");

        private static final int NEWEST_VERSION = 1;

        private final String topic;
        private final String eventType;
        private final SignalKind kind;

        Source(String topic, SignalKind kind) {
            this(topic, kind.sourceEventType(), kind);
        }

        Source(String topic, String eventType) {
            this(topic, eventType, null);
        }

        Source(String topic, String eventType, SignalKind kind) {
            this.topic = topic;
            this.eventType = eventType;
            this.kind = kind;
        }

        public String topic() {
            return topic;
        }

        public String eventType() {
            return eventType;
        }

        /** The kind this source records; empty for a completion, which is a hint and records nothing. */
        public Optional<SignalKind> kind() {
            return Optional.ofNullable(kind);
        }

        /** Whether this source is a completion hint rather than a signal source. */
        public boolean isHint() {
            return kind == null;
        }
    }

    private final Source source;
    private final RiskSignals signals;
    private final MonitoringHints hints;
    private final IdGenerator ids;
    private final RiskFlowScope scope;

    public RiskSignalIntake(
            Source source, RiskSignals signals, MonitoringHints hints, IdGenerator ids, RiskFlowScope scope) {
        this.source = Objects.requireNonNull(source, "source");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.hints = Objects.requireNonNull(hints, "hints");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public Source source() {
        return source;
    }

    @Override
    public String consumerName() {
        return CONSUMER;
    }

    @Override
    public String topic() {
        return source.topic;
    }

    @Override
    public String eventType() {
        return source.eventType;
    }

    @Override
    public void handle(Connection unitOfWork, ReceivedEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.eventVersion() < 1 || event.eventVersion() > Source.NEWEST_VERSION) {
            // Refused, not acknowledged: a signal of a contract this build cannot read is evidence it would otherwise
            // drop without a trace. The shell rolls back and stalls the partition loudly.
            throw new IllegalStateException(
                    "A " + source.eventType + " event of version " + event.eventVersion()
                            + " cannot be read as a risk signal by this build (newest known: "
                            + Source.NEWEST_VERSION + "); refused for redelivery after a deploy that knows it");
        }
        if (!source.eventType.equals(event.eventType())) {
            throw new IllegalStateException(
                    "A " + source.eventType + " handler was given a " + event.eventType() + " event");
        }
        scope.step(RiskSpans.SIGNAL_RECORD, () -> {
            if (source.isHint()) {
                hints.movementCompleted(unitOfWork, new MonitoringHints.CompletedMovement(
                        event.producer(), event.eventType(), event.aggregateId(), event.occurredAt()));
                return;
            }
            RiskSignal signal = SignalMappings.map(source.kind, event, ids);
            RiskSignals.Outcome outcome = signals.record(unitOfWork, signal);
            // Debug: an ALREADY is the arbiter working beneath the dedupe, not a fault. Ids and codes only.
            log.debug("Risk signal {} from {} {}: {}", signal.kind(), event.eventType(), event.eventId().value(), outcome);
        });
    }
}
