package com.finapp.crossborder;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's cancellation of an authorized cross-border payment (`P9-TSK-024`, ADR-0079 point 5, D22): a recall
 * REQUEST, in one transaction - the outbound credit marked for recall (so no re-send ever follows), the born-once
 * request recorded, the event and the audit record written. The recall itself is asked of the provider by payments'
 * resolution, holding no connection, and only its definitive {@code RECALLED} cancels: a recall is never assumed.
 *
 * <p>Lock order is the outcome applier's - the credit {@code FOR UPDATE} first, then the payment - so a cancellation
 * racing the provider's acceptance serialises behind it instead of deadlocking against it: whichever commits first
 * decides, and the other sees its result on the locked rows.
 */
@RequiredArgsConstructor
public final class PaymentCancellation {

    public static final String REQUESTED_EVENT = "crossborder.CrossBorderCancellationRequested";

    @NonNull private final PaymentStore payments;
    @NonNull private final CancellationStore cancellations;
    @NonNull private final CrossBorderExecution execution;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    /** Whether this call recorded the request, or found it already standing. */
    public enum Outcome {
        REQUESTED,
        ALREADY_REQUESTED
    }

    /** The payment is past recall - in transit, delivered, returned or failed. Nothing written. */
    public static final class NotCancellable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NotCancellable(String detail) {
            super(detail);
        }
    }

    /** No payment of the owner's has this identifier. Nothing written. */
    public static final class NotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NotFound() {
            super("no such payment");
        }
    }

    /** Requests {@code owner}'s payment {@code paymentId}'s cancellation in the caller's unit of work. */
    public Outcome request(
            Connection unitOfWork, UUID paymentId, UUID owner, Actor actor, Instant at, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        PaymentStore.Row seen = payments.findOwned(unitOfWork, paymentId, owner).orElseThrow(NotFound::new);
        // The credit FOR UPDATE first (the applier's order), marked for recall once - conditional on a status the
        // provider may still recall; then the payment, re-read under its lock.
        CrossBorderExecution.RecallRequest recall = execution.requestRecall(unitOfWork, seen.outboundCredit());
        PaymentStore.Row payment = payments.lock(unitOfWork, paymentId)
                .orElseThrow(() -> new IllegalStateException("a found payment vanished"));
        if (payment.status() != PaymentStore.Status.SUBMITTED
                || recall == CrossBorderExecution.RecallRequest.NOT_RECALLABLE) {
            throw new NotCancellable("the payment is " + payment.status() + ": past recall");
        }
        if (!cancellations.insert(unitOfWork, ids.next(), paymentId, actor.id(), correlation.value())) {
            return Outcome.ALREADY_REQUESTED;
        }
        // Identifiers only - never an amount, a name or a reference (INV-AUD-02).
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), REQUESTED_EVENT, CorridorPolicyAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, new PaymentEventId(paymentId), PaymentAuthorization.TARGET_TYPE,
                        at, CorridorPolicyAdministration.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of().with("payment", paymentId.toString()).toBytes(), EventPayload.MEDIA_TYPE);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, at,
                CrossborderAuditAction.CROSSBORDER_CANCELLATION_REQUESTED, PaymentAuthorization.TARGET_TYPE,
                paymentId.toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("payment=" + paymentId + ", recall=" + recall)));
        return Outcome.REQUESTED;
    }

    /** The payment's identity on the envelope. */
    private static final class PaymentEventId extends EntityId {
        PaymentEventId(UUID value) {
            super(value);
        }
    }
}
