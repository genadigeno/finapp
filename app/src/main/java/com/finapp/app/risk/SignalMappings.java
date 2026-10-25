package com.finapp.app.risk;

import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.SignalAttributes;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Each consumed event's envelope and payload, as the signal it records (`P13-TSK-005`) - the one place that knows
 * both a producer's payload field names and risk's attribute names. The subject is the envelope's aggregate, or -
 * for a recovery, whose aggregate is the recovery request - the identity the payload names; every attribute is a
 * payload value copied under its risk name, validated by {@link SignalAttributes} against the kind's schema before a
 * {@link RiskSignal} exists; {@code observedAt} is the envelope's {@code occurredAt}, never this handler's clock.
 *
 * <p>Nothing is defaulted and nothing is truncated: a missing field, a value outside its bound or a field this
 * mapping does not expect is an {@link IllegalArgumentException} the handler lets through - the producer's contract
 * changed under this build, and the shell stalls loudly rather than recording a guess. The one field refused by name
 * whatever the kind is {@code device}: a device description never enters a signal - the source hashes it and sends
 * the hash (OQ-4; {@code DeviceHash}) - and {@code EventPayload}'s grammar could not carry a description anyway, so
 * the refusal is the rule stated where a reader looks for it.
 *
 * <p><strong>The attributed kinds</strong> (`P13-TSK-014`): payments' {@code RefundCompleted} and
 * {@code ChargebackReceived} name the refund or the dispute and the intent - never the payer or the merchant - so their
 * signal's subject (the payer's {@code PARTY}) and its {@code merchant_subject_ref} come from risk's own sealed assessment of
 * that intent ({@link RiskAssessmentStore#judgedPayment}, the handler's read), the intent from the payload:
 * {@link #intentOf} reads and checks the event's own contract (a refund {@code COMPLETED}, a chargeback at
 * {@code CHARGED_BACK}, the dispute the envelope's), {@link #attributed} builds the signal. {@link #map} refuses them -
 * a refund's aggregate is no party.
 */
final class SignalMappings {

    /** The kinds whose subject is attributed from risk's own record of the payment, not read from the event. */
    static final Set<SignalKind> ATTRIBUTED = EnumSet.of(SignalKind.REFUND_COMPLETED, SignalKind.CHARGEBACK_RECEIVED);

    private SignalMappings() {
    }

    /**
     * The intent an attributed event names, its contract checked: a {@code RefundCompleted} reads {@code COMPLETED}; a
     * {@code ChargebackReceived} reads {@code CHARGED_BACK} and names the envelope's dispute.
     */
    static UUID intentOf(SignalKind kind, ReceivedEvent event) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(event, "event");
        Map<String, String> payload = readable(event);
        switch (kind) {
            case REFUND_COMPLETED -> expect(payload, "status", "COMPLETED", kind);
            case CHARGEBACK_RECEIVED -> {
                expect(payload, "stage", "CHARGED_BACK", kind);
                if (!ref(payload, "disputeId").equals(event.aggregateId())) {
                    throw new IllegalArgumentException("a " + kind.sourceEventType()
                            + " event names one dispute in its envelope and another in its payload");
                }
            }
            default -> throw new IllegalArgumentException(kind + " is not attributed from a payment");
        }
        return ref(payload, "intentId");
    }

    /** The attributed event as its signal: about the payer, naming the merchant and the intent by reference. */
    static RiskSignal attributed(SignalKind kind, ReceivedEvent event, IdGenerator ids,
            RiskAssessmentStore.JudgedPayment payment) {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(payment, "payment");
        UUID intent = intentOf(kind, event);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("merchant_subject_ref", payment.merchant().toString());
        attributes.put("intent_ref", intent.toString());
        return signal(kind, event, ids, SignalSubject.of(SubjectKind.PARTY, payment.party()), attributes);
    }

    static RiskSignal map(SignalKind kind, ReceivedEvent event, IdGenerator ids) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(ids, "ids");
        if (ATTRIBUTED.contains(kind)) {
            throw new IllegalArgumentException(kind + " is attributed from risk's record of its payment, never mapped"
                    + " from the event alone");
        }
        Map<String, String> payload = readable(event);
        return signal(kind, event, ids, subjectOf(kind, event, payload), attributesOf(kind, event, payload));
    }

    /** The event's payload, read by the writer's own inverse; a device description refused by name whatever the kind. */
    private static Map<String, String> readable(ReceivedEvent event) {
        Map<String, String> payload = EventPayload.fields(event.payload());
        if (payload.containsKey("device")) {
            throw new IllegalArgumentException(
                    "a device description never enters a risk signal: the source sends identity's hash (OQ-4)");
        }
        return payload;
    }

    private static RiskSignal signal(SignalKind kind, ReceivedEvent event, IdGenerator ids, SignalSubject subject,
            Map<String, String> attributes) {
        return new RiskSignal(
                RiskSignalId.next(ids),
                kind,
                subject,
                new RiskSignal.SourceEvent(
                        event.eventId(), event.eventType(), event.eventVersion(), event.producer(),
                        event.correlationId(), event.causationId()),
                event.occurredAt(),
                SignalAttributes.of(kind, attributes));
    }

    private static SignalSubject subjectOf(SignalKind kind, ReceivedEvent event, Map<String, String> payload) {
        return switch (kind) {
            // The recovery request is the aggregate; the identity recovered is the subject.
            case RECOVERY_INITIATED, RECOVERY_COMPLETED ->
                    SignalSubject.of(SubjectKind.IDENTITY, ref(payload, "identityId"));
            // The loan and the obligation ride both as the aggregate and in the payload: they must agree.
            case LOAN_DELINQUENCY_CHANGED, LOAN_DEFAULTED -> agreeing(kind, event, ref(payload, "loanId"));
            case BNPL_DELINQUENCY_CHANGED, BNPL_DEFAULTED -> agreeing(kind, event, ref(payload, "obligationId"));
            // P13-TSK-014: the application is the aggregate (it must agree with the payload); the applicant is the
            // subject - bnpl's event names its party itself.
            case BNPL_APPLICATION_SUBMITTED -> {
                if (!ref(payload, "applicationId").equals(event.aggregateId())) {
                    throw new IllegalArgumentException("a " + kind.sourceEventType()
                            + " event names one application in its envelope and another in its payload");
                }
                yield SignalSubject.of(SubjectKind.PARTY, ref(payload, "partyId"));
            }
            default -> SignalSubject.of(kind.subject(), event.aggregateId());
        };
    }

    private static SignalSubject agreeing(SignalKind kind, ReceivedEvent event, UUID named) {
        if (!named.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "a " + kind.sourceEventType() + " event names one agreement in its envelope and another in its payload");
        }
        return SignalSubject.of(kind.subject(), named);
    }

    /** The payload's fields under risk's attribute names - exactly the kind's set, checked by {@link SignalAttributes}. */
    private static Map<String, String> attributesOf(SignalKind kind, ReceivedEvent event, Map<String, String> payload) {
        Map<String, String> attributes = new LinkedHashMap<>();
        switch (kind) {
            case CREDENTIAL_CHANGED -> { }
            case RECOVERY_INITIATED, RECOVERY_COMPLETED -> {
                // The request rides both as the aggregate and in the payload: they must agree, as the agreements do.
                if (!ref(payload, "recoveryRequestId").equals(event.aggregateId())) {
                    throw new IllegalArgumentException(
                            "a " + kind.sourceEventType() + " event names one request in its envelope and another in its payload");
                }
                attributes.put("request_ref", event.aggregateId().toString());
                attributes.put("channel_ref", required(payload, "channelId"));
            }
            case IDENTITY_SUSPENDED -> attributes.put("status", required(payload, "status"));
            case ACCOUNT_OPENED -> attributes.put("product", required(payload, "productType"));
            case BENEFICIARY_REGISTERED -> {
                attributes.put("country", required(payload, "country"));
                attributes.put("currency", required(payload, "currency"));
            }
            case BENEFICIARY_ACTIVATED, BENEFICIARY_BLOCKED -> {
                attributes.put("country", required(payload, "country"));
                attributes.put("currency", required(payload, "currency"));
                attributes.put("previous_status", required(payload, "from"));
            }
            case MERCHANT_ONBOARDED -> attributes.put("currency", required(payload, "settlementCurrency"));
            case LOAN_DELINQUENCY_CHANGED, BNPL_DELINQUENCY_CHANGED -> {
                condition(attributes, payload);
                attributes.put("previous_bucket", required(payload, "previousBucket"));
            }
            case LOAN_DEFAULTED, BNPL_DEFAULTED -> condition(attributes, payload);
            case BNPL_APPLICATION_SUBMITTED -> attributes.put("product", required(payload, "product"));
            case REFUND_COMPLETED, CHARGEBACK_RECEIVED -> throw new IllegalArgumentException(kind + " is attributed");
        }
        return attributes;
    }

    /** lending's and bnpl's condition events share one payload shape (`P11-TSK-020`, `P12-TSK-020`). */
    private static void condition(Map<String, String> attributes, Map<String, String> payload) {
        attributes.put("product", required(payload, "product"));
        attributes.put("business_date", required(payload, "businessDate"));
        attributes.put("days_past_due", required(payload, "daysPastDue"));
        attributes.put("bucket", required(payload, "bucket"));
        attributes.put("defaulted", required(payload, "defaulted"));
        attributes.put("agreement_version", required(payload, "agreementVersion"));
    }

    private static void expect(Map<String, String> payload, String field, String value, SignalKind kind) {
        if (!value.equals(required(payload, field))) {
            // Never the value read: it is whatever the producer put there.
            throw new IllegalArgumentException("a " + kind.sourceEventType() + " event's " + field + " is not " + value);
        }
    }

    private static String required(Map<String, String> payload, String field) {
        String value = payload.get(field);
        if (value == null) {
            throw new IllegalArgumentException("the event's payload carries no " + field);
        }
        return value;
    }

    private static UUID ref(Map<String, String> payload, String field) {
        try {
            return UUID.fromString(required(payload, field));
        } catch (IllegalArgumentException notAUuid) {
            // Never the value: it is whatever the producer put there.
            throw new IllegalArgumentException("the event's " + field + " is not an identifier");
        }
    }
}
