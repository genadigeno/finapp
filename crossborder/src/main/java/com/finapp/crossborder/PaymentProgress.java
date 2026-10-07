package com.finapp.crossborder;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
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
 * The cross-border payment's edges after its authorization (`P9-TSK-020`, the lifecycle document 3.5): taken in
 * its outbound credit's outcome applier's transaction, on the payment locked there, one history row and one
 * event per edge - {@code SUBMITTED -> IN_TRANSIT} at the corridor provider's acceptance, {@code IN_TRANSIT ->
 * DELIVERED} at its delivery, {@code SUBMITTED -> FAILED} when the instruction will never execute. Several facts
 * from one answer are taken in order inside the one transaction. The payment's every-writer trigger holds the
 * edges beneath this.
 */
@RequiredArgsConstructor
public final class PaymentProgress {

    public static final String IN_TRANSIT_EVENT = "crossborder.CrossBorderPaymentInTransit";
    public static final String DELIVERED_EVENT = "crossborder.CrossBorderPaymentDelivered";
    public static final String FAILED_EVENT = "crossborder.CrossBorderPaymentFailed";
    public static final String RETURNED_EVENT = "crossborder.CrossBorderPaymentReturned";

    @NonNull private final PaymentStore payments;
    @NonNull private final OfferStore offers;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    /** The payment and its frozen offer, the payment locked - the completion's inputs. */
    public record Locked(PaymentStore.Row payment, OfferStore.OfferRow offer) {}

    /** Locks {@code paymentId} - its outbound credit's frozen subject - and reads its offer. */
    public Locked lock(Connection unitOfWork, UUID paymentId) {
        PaymentStore.Row payment = payments.lock(unitOfWork, paymentId)
                .orElseThrow(() -> new IllegalStateException("an outbound credit's subject is always a payment"));
        OfferStore.OfferRow offer = offers.offerOwned(unitOfWork, payment.quote(), payment.owner())
                .orElseThrow(() -> new IllegalStateException("a payment's offer always reads back"));
        return new Locked(payment, offer);
    }

    /** {@code SUBMITTED -> IN_TRANSIT}: the corridor provider took irrevocable responsibility. */
    public void inTransit(Connection unitOfWork, UUID paymentId, String tradeReference, Instant at, CorrelationId correlation) {
        take(unitOfWork, paymentId, PaymentStore.Status.SUBMITTED, PaymentStore.Status.IN_TRANSIT, Optional.empty(),
                "ACCEPTED_BY_PROVIDER", IN_TRANSIT_EVENT, EventPayload.of().with("trade", tradeReference), at, correlation);
    }

    /** {@code IN_TRANSIT -> DELIVERED}: the provider confirmed the beneficiary's institution credited them. */
    public void delivered(Connection unitOfWork, UUID paymentId, Instant at, CorrelationId correlation) {
        PaymentStore.Row payment = payments.lock(unitOfWork, paymentId)
                .orElseThrow(() -> new IllegalStateException("an outbound credit's subject is always a payment"));
        if (payment.status() == PaymentStore.Status.RETURNED) {
            // Delivered, then returned - the provider reported both: the delivery is the credit's history only (its
            // delivered_at), and the payment stays RETURNED. Refusing it rolled the whole inquiry back on every pass and
            // kept the credit due for ever (the Phase 9 -> 10 transition).
            return;
        }
        take(unitOfWork, paymentId, PaymentStore.Status.IN_TRANSIT, PaymentStore.Status.DELIVERED, Optional.empty(),
                "DELIVERED", DELIVERED_EVENT, EventPayload.of(), at, correlation);
    }

    /** {@code SUBMITTED -> FAILED(reason)}: nothing was posted; the hold is released by its owner. */
    public void failed(Connection unitOfWork, UUID paymentId, String reason, Instant at, CorrelationId correlation) {
        Objects.requireNonNull(reason, "reason must not be null");
        take(unitOfWork, paymentId, PaymentStore.Status.SUBMITTED, PaymentStore.Status.FAILED, Optional.of(reason),
                "FAILED_" + reason, FAILED_EVENT, EventPayload.of().with("failureReason", reason), at, correlation);
    }

    /**
     * {@code IN_TRANSIT | DELIVERED -> RETURNED} (`P9-TSK-023`): the credit came back - applied automatically
     * ({@code APPLIED}) or by a person's resolution ({@code RESOLVED}), the basis on the event. A return is the
     * receiving side's act, admitted whether or not delivery was confirmed.
     */
    public void returned(Connection unitOfWork, UUID paymentId, String basis, Instant at, CorrelationId correlation) {
        Objects.requireNonNull(basis, "basis must not be null");
        PaymentStore.Row payment = payments.lock(unitOfWork, paymentId)
                .orElseThrow(() -> new IllegalStateException("an outbound credit's subject is always a payment"));
        if (payment.status() != PaymentStore.Status.IN_TRANSIT && payment.status() != PaymentStore.Status.DELIVERED) {
            throw new IllegalStateException("payment " + paymentId + " is " + payment.status() + ": only a payment in transit"
                    + " or delivered returns");
        }
        take(unitOfWork, paymentId, payment.status(), PaymentStore.Status.RETURNED, Optional.empty(), "RETURNED_" + basis,
                RETURNED_EVENT, EventPayload.of().with("basis", basis), at, correlation);
    }

    private void take(
            Connection unitOfWork,
            UUID paymentId,
            PaymentStore.Status from,
            PaymentStore.Status to,
            Optional<String> failureReason,
            String cause,
            String eventType,
            EventPayload payload,
            Instant at,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!payments.move(unitOfWork, paymentId, from, to, failureReason)) {
            // The applier holds the credit's lock and acted once: the payment cannot be elsewhere unless another
            // writer moved it - refused loudly, rolling the outcome back whole.
            throw new IllegalStateException("payment " + paymentId + " was not " + from + " for its edge to " + to);
        }
        payments.appendEvent(unitOfWork, ids.next(), paymentId, Optional.of(from), to, cause, at);
        // Identifiers and enumerated names only - never an amount, a name or a reference (INV-AUD-02).
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), eventType, CorridorPolicyAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, new PaymentEventId(paymentId), PaymentAuthorization.TARGET_TYPE,
                        at, CorridorPolicyAdministration.PRODUCER, correlation, CausationId.of(correlation.value())),
                payload.with("payment", paymentId.toString()).with("status", to.name()).toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** The payment's identity on the envelope. */
    private static final class PaymentEventId extends EntityId {
        PaymentEventId(UUID value) {
            super(value);
        }
    }
}
