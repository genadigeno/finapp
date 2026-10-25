package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalStore;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The intake's own decisions (`P13-TSK-005`), hermetic over a fake inbox delivery and a scripted store: every
 * consumed event mapped to its signal - the kind, the subject the event names, the attributes it carries, the event's
 * own instant - a completion handed to the hint port and recording nothing, and every refusal thrown rather than
 * acknowledged. The dedupe, the race and the unique are the shell's and the database suite's
 * ({@code SignalIntakeDatabaseTest}, written and owed).
 */
@DisplayName("the risk signal intake: every consumed event, mapped or refused (P13-TSK-005)")
class RiskSignalIntakeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant OBSERVED = Instant.parse("2026-10-22T09:30:00Z");

    private final RecordingStore store = new RecordingStore();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final RecordingHints hints = new RecordingHints();
    private final RiskSignals signals = new RiskSignals(store, outbox, IDS, CLOCK);

    @Test
    @DisplayName("every source is under the one consumer name, on its producer's topic, for its one event type")
    void everySourceIsNamedAndRouted() {
        for (RiskSignalIntake.Source source : RiskSignalIntake.Source.values()) {
            RiskSignalIntake intake = intake(source);
            assertThat(intake.consumerName()).isEqualTo("risk.signals");
            assertThat(intake.topic()).isEqualTo("finapp." + source.eventType().substring(0, source.eventType().indexOf('.')));
            assertThat(intake.eventType()).isEqualTo(source.eventType());
            source.kind().ifPresent(kind -> assertThat(kind.sourceEventType()).isEqualTo(source.eventType()));
        }
        assertThat(RiskSignalIntake.Source.values()).hasSize(21);
        assertThat(java.util.Arrays.stream(RiskSignalIntake.Source.values()).filter(RiskSignalIntake.Source::isAttributed))
                .as("P13-TSK-014: the refund and the chargeback, attributed from risk's assessments")
                .containsExactly(RiskSignalIntake.Source.REFUND_COMPLETED, RiskSignalIntake.Source.CHARGEBACK_RECEIVED);
        assertThat(java.util.Arrays.stream(RiskSignalIntake.Source.values()).filter(RiskSignalIntake.Source::isHint))
                .hasSize(5);
        assertThat(java.util.Arrays.stream(RiskSignalIntake.Source.values()).map(RiskSignalIntake.Source::kind)
                .flatMap(Optional::stream)).containsExactlyInAnyOrder(SignalKind.values());
    }

    @Test
    @DisplayName("identity.CredentialChanged: the identity is the aggregate; nothing else is carried")
    void credentialChanged() {
        UUID identity = UUID.randomUUID();
        handle(RiskSignalIntake.Source.CREDENTIAL_CHANGED, event("identity.CredentialChanged", identity, "identity",
                EventPayload.of().with("identityId", identity.toString())));
        RiskSignal signal = only();
        assertThat(signal.kind()).isEqualTo(SignalKind.CREDENTIAL_CHANGED);
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.IDENTITY, identity));
        assertThat(signal.attributes().isEmpty()).isTrue();
        assertThat(signal.observedAt()).as("the event's own instant, never the handler's clock").isEqualTo(OBSERVED);
        assertThat(signal.source().producer()).isEqualTo("identity");
        assertThat(signal.source().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("identity.RecoveryInitiated / RecoveryCompleted: the aggregate is the request, the subject the identity recovered")
    void recovery() {
        UUID request = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        EventPayload payload = EventPayload.of()
                .with("recoveryRequestId", request.toString())
                .with("identityId", identity.toString())
                .with("channelId", channel.toString());
        handle(RiskSignalIntake.Source.RECOVERY_INITIATED, event("identity.RecoveryInitiated", request, "identity", payload));
        handle(RiskSignalIntake.Source.RECOVERY_COMPLETED, event("identity.RecoveryCompleted", request, "identity", payload));
        assertThat(store.inserted).hasSize(2);
        for (RiskSignal signal : store.inserted) {
            assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.IDENTITY, identity));
            assertThat(signal.attributes().asMap()).containsExactly(
                    Map.entry("request_ref", request.toString()), Map.entry("channel_ref", channel.toString()));
        }
        assertThat(store.inserted.get(0).kind()).isEqualTo(SignalKind.RECOVERY_INITIATED);
        assertThat(store.inserted.get(1).kind()).isEqualTo(SignalKind.RECOVERY_COMPLETED);
    }

    @Test
    @DisplayName("identity.IdentitySuspended: the status it now reads")
    void identitySuspended() {
        UUID identity = UUID.randomUUID();
        handle(RiskSignalIntake.Source.IDENTITY_SUSPENDED, event("identity.IdentitySuspended", identity, "identity",
                EventPayload.of().with("identityId", identity.toString()).with("status", "SUSPENDED")));
        RiskSignal signal = only();
        assertThat(signal.kind()).isEqualTo(SignalKind.IDENTITY_SUSPENDED);
        assertThat(signal.subject().ref()).isEqualTo(identity.toString());
        assertThat(signal.attributes().get("status")).contains("SUSPENDED");
    }

    @Test
    @DisplayName("accounts.AccountOpened: the account and its product")
    void accountOpened() {
        UUID account = UUID.randomUUID();
        handle(RiskSignalIntake.Source.ACCOUNT_OPENED, event("accounts.AccountOpened", account, "accounts",
                EventPayload.of().with("productType", "WALLET")));
        RiskSignal signal = only();
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.ACCOUNT, account));
        assertThat(signal.attributes().asMap()).containsExactly(Map.entry("product", "WALLET"));
    }

    @Test
    @DisplayName("crossborder's beneficiary registered, activated and blocked: the beneficiary, its country and currency, and the status it came from")
    void beneficiary() {
        UUID beneficiary = UUID.randomUUID();
        handle(RiskSignalIntake.Source.BENEFICIARY_REGISTERED, event("crossborder.BeneficiaryRegistered", beneficiary,
                "crossborder", EventPayload.of().with("beneficiary", beneficiary.toString()).with("country", "DE")
                        .with("currency", "EUR")));
        handle(RiskSignalIntake.Source.BENEFICIARY_ACTIVATED, event("crossborder.BeneficiaryActivated", beneficiary,
                "crossborder", EventPayload.of().with("beneficiary", beneficiary.toString()).with("country", "DE")
                        .with("currency", "EUR").with("from", "PENDING_SCREENING")));
        handle(RiskSignalIntake.Source.BENEFICIARY_BLOCKED, event("crossborder.BeneficiaryBlocked", beneficiary,
                "crossborder", EventPayload.of().with("beneficiary", beneficiary.toString()).with("country", "DE")
                        .with("currency", "EUR").with("from", "IN_REVIEW")));
        assertThat(store.inserted).extracting(RiskSignal::kind).containsExactly(
                SignalKind.BENEFICIARY_REGISTERED, SignalKind.BENEFICIARY_ACTIVATED, SignalKind.BENEFICIARY_BLOCKED);
        assertThat(store.inserted).allSatisfy(signal ->
                assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.BENEFICIARY, beneficiary)));
        assertThat(store.inserted.get(0).attributes().asMap())
                .containsExactly(Map.entry("country", "DE"), Map.entry("currency", "EUR"));
        assertThat(store.inserted.get(2).attributes().asMap()).containsExactly(
                Map.entry("country", "DE"), Map.entry("currency", "EUR"), Map.entry("previous_status", "IN_REVIEW"));
    }

    @Test
    @DisplayName("merchant.MerchantOnboarded: the merchant and its settlement currency")
    void merchantOnboarded() {
        UUID merchant = UUID.randomUUID();
        handle(RiskSignalIntake.Source.MERCHANT_ONBOARDED, event("merchant.MerchantOnboarded", merchant, "merchant",
                EventPayload.of().with("settlementCurrency", "EUR")));
        RiskSignal signal = only();
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.MERCHANT, merchant));
        assertThat(signal.attributes().asMap()).containsExactly(Map.entry("currency", "EUR"));
    }

    @Test
    @DisplayName("lending's and bnpl's conditions: the agreement, the condition after the change and the bucket before it")
    void conditions() {
        UUID loan = UUID.randomUUID();
        handle(RiskSignalIntake.Source.LOAN_DELINQUENCY_CHANGED, event("lending.LoanDelinquencyChanged", loan, "lending",
                condition("loanId", loan).with("previousBucket", "CURRENT")));
        handle(RiskSignalIntake.Source.LOAN_DEFAULTED, event("lending.LoanDefaulted", loan, "lending",
                condition("loanId", loan)));
        UUID obligation = UUID.randomUUID();
        handle(RiskSignalIntake.Source.BNPL_DELINQUENCY_CHANGED, event("bnpl.BnplDelinquencyChanged", obligation, "bnpl",
                condition("obligationId", obligation).with("previousBucket", "B1_1_29")));
        handle(RiskSignalIntake.Source.BNPL_DEFAULTED, event("bnpl.BnplDefaulted", obligation, "bnpl",
                condition("obligationId", obligation)));

        assertThat(store.inserted).extracting(RiskSignal::kind).containsExactly(SignalKind.LOAN_DELINQUENCY_CHANGED,
                SignalKind.LOAN_DEFAULTED, SignalKind.BNPL_DELINQUENCY_CHANGED, SignalKind.BNPL_DEFAULTED);
        assertThat(store.inserted.get(0).subject()).isEqualTo(SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan));
        assertThat(store.inserted.get(3).subject()).isEqualTo(SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, obligation));
        assertThat(store.inserted.get(0).attributes().asMap()).containsExactly(
                Map.entry("product", "PERSONAL_LOAN"), Map.entry("business_date", "2026-10-22"),
                Map.entry("days_past_due", "91"), Map.entry("bucket", "B4_90_PLUS"),
                Map.entry("previous_bucket", "CURRENT"), Map.entry("defaulted", "true"),
                Map.entry("agreement_version", "2"));
        assertThat(store.inserted.get(1).attributes().asMap()).doesNotContainKey("previous_bucket").hasSize(6);
    }

    @Test
    @DisplayName("a completion is a hint: handed to the port with the envelope's facts, and no signal recorded")
    void aCompletionIsAHint() {
        UUID transfer = UUID.randomUUID();
        handle(RiskSignalIntake.Source.TRANSFER_COMPLETED, event("transfers.TransferCompleted", transfer, "transfers",
                EventPayload.of().with("status", "COMPLETED")));
        UUID withdrawal = UUID.randomUUID();
        handle(RiskSignalIntake.Source.WITHDRAWAL_COMPLETED, event("payments.WithdrawalCompleted", withdrawal, "payments",
                EventPayload.of().with("status", "COMPLETED").with("rail", "SEPA")));
        assertThat(store.inserted).isEmpty();
        assertThat(outbox.envelopes).isEmpty();
        assertThat(hints.movements).containsExactly(
                new MonitoringHints.CompletedMovement("transfers", "transfers.TransferCompleted", transfer, OBSERVED),
                new MonitoringHints.CompletedMovement("payments", "payments.WithdrawalCompleted", withdrawal, OBSERVED));
    }

    @Test
    @DisplayName("a signal born is announced; one already recorded is not - the store's answer, not the handler's")
    void bornAnnouncesAlreadyDoesNot() {
        UUID account = UUID.randomUUID();
        ReceivedEvent event = event("accounts.AccountOpened", account, "accounts", EventPayload.of().with("productType", "WALLET"));
        handle(RiskSignalIntake.Source.ACCOUNT_OPENED, event);
        assertThat(outbox.envelopes).hasSize(1);
        EventEnvelope announced = outbox.envelopes.get(0);
        assertThat(announced.eventType()).isEqualTo("risk.RiskSignalRecorded");
        assertThat(announced.causationId().value()).isEqualTo(event.eventId().value().toString());
        assertThat(announced.correlationId()).isEqualTo(event.correlationId());

        // The same event again, past the inbox's dedupe: the unique says already.
        handle(RiskSignalIntake.Source.ACCOUNT_OPENED, event);
        assertThat(store.inserted).hasSize(2);
        assertThat(outbox.envelopes).as("announced once").hasSize(1);
    }

    @Test
    @DisplayName("a version this build does not know is refused - thrown, so the shell stalls loudly - never acknowledged")
    void anUnknownVersionIsRefused() {
        UUID account = UUID.randomUUID();
        ReceivedEvent v2 = new ReceivedEvent(EventId.next(IDS), "accounts.AccountOpened", 2, 1, account, "customer_account",
                OBSERVED, "accounts", CorrelationId.of("flow"), CausationId.of("cause"), "application/json",
                EventPayload.of().with("productType", "WALLET").toBytes());
        assertThatThrownBy(() -> intake(RiskSignalIntake.Source.ACCOUNT_OPENED).handle(null, v2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 2");
        assertThat(store.inserted).isEmpty();
        assertThat(hints.movements).isEmpty();
    }

    @Test
    @DisplayName("a payload the writer could not have produced, a missing field or a value past its bound is refused - never truncated, never defaulted")
    void anUnreadablePayloadIsRefused() {
        UUID loan = UUID.randomUUID();
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.LOAN_DEFAULTED, raw("lending.LoanDefaulted", loan,
                "lending", "{\"loanId\":{\"nested\":\"x\"}}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.LOAN_DEFAULTED, event("lending.LoanDefaulted", loan,
                "lending", EventPayload.of().with("loanId", loan.toString()).with("product", "PERSONAL_LOAN"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("businessDate");
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.LOAN_DEFAULTED, event("lending.LoanDefaulted", loan,
                "lending", condition("loanId", loan, "99999"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days_past_due")
                .hasMessageNotContaining("99999");
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.LOAN_DEFAULTED, event("lending.LoanDefaulted",
                UUID.randomUUID(), "lending", condition("loanId", loan))))
                .as("the envelope and the payload name different agreements")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.RECOVERY_COMPLETED, event("identity.RecoveryCompleted",
                UUID.randomUUID(), "identity", EventPayload.of().with("recoveryRequestId", UUID.randomUUID().toString())
                        .with("identityId", UUID.randomUUID().toString()).with("channelId", UUID.randomUUID().toString()))))
                .as("the envelope and the payload name different recovery requests")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.ACCOUNT_OPENED, event("accounts.AccountClosed",
                UUID.randomUUID(), "accounts", EventPayload.of().with("productType", "WALLET"))))
                .as("a handler is given only its own event type")
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.inserted).isEmpty();
    }

    @Test
    @DisplayName("a device description never crosses: a `device` field is refused by name, whatever the kind (OQ-4)")
    void aDeviceDescriptionNeverCrosses() {
        UUID identity = UUID.randomUUID();
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.CREDENTIAL_CHANGED, raw("identity.CredentialChanged",
                identity, "identity", "{\"identityId\":\"" + identity + "\",\"device\":\"Chrome-on-Windows\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash")
                .hasMessageNotContaining("Chrome");
        assertThat(store.inserted).isEmpty();
    }

    // ------------------------------------------------------------------ P13-TSK-014: the abuse sources

    @Test
    @DisplayName("payments.RefundCompleted: about the payer risk judged, naming the merchant it judged and the intent - the"
            + " event's own instant; the announcement names the party, never the merchant, the intent or an amount")
    void refundCompleted() {
        UUID intent = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        judged.put(intent, new RiskAssessmentStore.JudgedPayment(party, merchant));
        UUID refund = UUID.randomUUID();
        handle(RiskSignalIntake.Source.REFUND_COMPLETED, event("payments.RefundCompleted", refund, "payments",
                refundPayload(intent, "COMPLETED")));
        RiskSignal signal = only();
        assertThat(signal.kind()).isEqualTo(SignalKind.REFUND_COMPLETED);
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(signal.attributes().get("merchant_subject_ref")).contains(merchant.toString());
        assertThat(signal.attributes().get("intent_ref")).contains(intent.toString());
        assertThat(signal.observedAt()).isEqualTo(OBSERVED);
        assertThat(attributionsAsked).containsExactly(intent);
        assertThat(outbox.envelopes).singleElement().extracting(EventEnvelope::eventType)
                .isEqualTo(RiskSignals.EVENT_TYPE);
        Map<String, String> announced = EventPayload.fields(outbox.payloads.get(0));
        assertThat(announced).containsOnlyKeys("signalId", "kind", "subjectKind", "subjectRef")
                .containsEntry("subjectRef", party.toString());
        assertThat(announced.values()).doesNotContain(merchant.toString(), intent.toString(), refund.toString());
    }

    @Test
    @DisplayName("payments.ChargebackReceived: the dispute's chargeback attributed as the refund is; the envelope's dispute"
            + " and the payload's must agree, and the stage must be CHARGED_BACK")
    void chargebackReceived() {
        UUID intent = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        judged.put(intent, new RiskAssessmentStore.JudgedPayment(party, merchant));
        UUID dispute = UUID.randomUUID();
        handle(RiskSignalIntake.Source.CHARGEBACK_RECEIVED, event("payments.ChargebackReceived", dispute, "payments",
                chargebackPayload(dispute, intent, "CHARGED_BACK")));
        RiskSignal signal = only();
        assertThat(signal.kind()).isEqualTo(SignalKind.CHARGEBACK_RECEIVED);
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(signal.attributes().get("merchant_subject_ref")).contains(merchant.toString());

        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.CHARGEBACK_RECEIVED, event(
                "payments.ChargebackReceived", UUID.randomUUID(), "payments", chargebackPayload(dispute, intent,
                        "CHARGED_BACK")))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one dispute in its envelope and another");
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.CHARGEBACK_RECEIVED, event(
                "payments.ChargebackReceived", dispute, "payments", chargebackPayload(dispute, intent, "WON"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stage is not CHARGED_BACK")
                .hasMessageNotContaining("WON");
        assertThat(store.inserted).hasSize(1);
    }

    @Test
    @DisplayName("bnpl.BnplApplicationSubmitted: about the applicant the event names, its product; the application in"
            + " the envelope and the payload must agree; nothing of risk's assessments is asked")
    void bnplApplicationSubmitted() {
        UUID application = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        handle(RiskSignalIntake.Source.BNPL_APPLICATION_SUBMITTED, event("bnpl.BnplApplicationSubmitted", application,
                "bnpl", applicationPayload(application, party)));
        RiskSignal signal = only();
        assertThat(signal.kind()).isEqualTo(SignalKind.BNPL_APPLICATION_SUBMITTED);
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(signal.attributes().get("product")).contains("BNPL_PAY_IN_N");
        assertThat(attributionsAsked).isEmpty();
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.BNPL_APPLICATION_SUBMITTED, event(
                "bnpl.BnplApplicationSubmitted", UUID.randomUUID(), "bnpl", applicationPayload(application, party))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one application in its envelope and another");
    }

    @Test
    @DisplayName("each abuse event once is one signal; the same event again - past the dedupe - records nothing and"
            + " announces nothing (the unique beneath); an event of version 2 is refused loudly, nothing recorded")
    void aDuplicateIsNoneAndAnUnknownVersionIsRefused() {
        UUID intent = UUID.randomUUID();
        judged.put(intent, new RiskAssessmentStore.JudgedPayment(UUID.randomUUID(), UUID.randomUUID()));
        ReceivedEvent refund = event("payments.RefundCompleted", UUID.randomUUID(), "payments",
                refundPayload(intent, "COMPLETED"));
        handle(RiskSignalIntake.Source.REFUND_COMPLETED, refund);
        handle(RiskSignalIntake.Source.REFUND_COMPLETED, refund);
        assertThat(store.inserted).as("offered twice").hasSize(2);
        assertThat(store.inserted.get(1).source().id()).isEqualTo(store.inserted.get(0).source().id());
        assertThat(outbox.envelopes).as("born once, announced once").hasSize(1);

        for (RiskSignalIntake.Source source : java.util.List.of(RiskSignalIntake.Source.REFUND_COMPLETED,
                RiskSignalIntake.Source.CHARGEBACK_RECEIVED, RiskSignalIntake.Source.BNPL_APPLICATION_SUBMITTED)) {
            ReceivedEvent v2 = new ReceivedEvent(EventId.next(IDS), source.eventType(), 2, 1, UUID.randomUUID(),
                    "aggregate", OBSERVED, "payments", CorrelationId.of("flow"), CausationId.of("cause"),
                    "application/json", refundPayload(intent, "COMPLETED").toBytes());
            assertThatThrownBy(() -> intake(source).handle(null, v2)).as(source.name())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("version 2");
        }
        assertThat(store.inserted).hasSize(2);
    }

    @Test
    @DisplayName("a refund of a payment risk never judged records no signal and is acknowledged - in no ratio's"
            + " denominator, never a stall; a refund not COMPLETED is a contract the build cannot read, refused")
    void anUnjudgedPaymentsRefundRecordsNothing() {
        UUID intent = UUID.randomUUID();
        handle(RiskSignalIntake.Source.REFUND_COMPLETED, event("payments.RefundCompleted", UUID.randomUUID(),
                "payments", refundPayload(intent, "COMPLETED")));
        assertThat(attributionsAsked).containsExactly(intent);
        assertThat(store.inserted).isEmpty();
        assertThat(outbox.envelopes).isEmpty();
        assertThatThrownBy(() -> handle(RiskSignalIntake.Source.REFUND_COMPLETED, event("payments.RefundCompleted",
                UUID.randomUUID(), "payments", refundPayload(intent, "FAILED"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("status is not COMPLETED");
        assertThatThrownBy(() -> new RiskSignalIntake(RiskSignalIntake.Source.CHARGEBACK_RECEIVED, signals, hints, IDS,
                RiskFlowScope.NONE)).as("an attributed source wired without risk's assessments")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EventPayload refundPayload(UUID intent, String status) {
        return EventPayload.of().with("status", status).with("intentId", intent.toString())
                .with("attemptId", UUID.randomUUID().toString());
    }

    private static EventPayload chargebackPayload(UUID dispute, UUID intent, String stage) {
        return EventPayload.of().with("stage", stage).with("disputeId", dispute.toString())
                .with("attemptId", UUID.randomUUID().toString()).with("intentId", intent.toString());
    }

    private static EventPayload applicationPayload(UUID application, UUID party) {
        return EventPayload.of().with("applicationId", application.toString()).with("partyId", party.toString())
                .with("product", "BNPL_PAY_IN_N").with("plan", "PAY_IN_3")
                .with("checkoutSessionId", UUID.randomUUID().toString())
                .with("decisionRequestId", UUID.randomUUID().toString())
                .with("termsVersionId", UUID.randomUUID().toString()).with("termsVersion", "1");
    }

    // -----------------------------------------------------------------

    /** Risk's own judged payments, by intent, as the suite plants them; every intent asked, in order. */
    private final Map<UUID, RiskAssessmentStore.JudgedPayment> judged = new java.util.HashMap<>();
    private final List<UUID> attributionsAsked = new ArrayList<>();

    private RiskSignalIntake intake(RiskSignalIntake.Source source) {
        if (source.isAttributed()) {
            return new RiskSignalIntake(source, signals, hints, IDS, RiskFlowScope.NONE, (unitOfWork, intent) -> {
                attributionsAsked.add(intent);
                return Optional.ofNullable(judged.get(intent));
            });
        }
        return new RiskSignalIntake(source, signals, hints, IDS, RiskFlowScope.NONE);
    }

    private void handle(RiskSignalIntake.Source source, ReceivedEvent event) {
        intake(source).handle(null, event);
    }

    private RiskSignal only() {
        assertThat(store.inserted).hasSize(1);
        return store.inserted.get(0);
    }

    private static ReceivedEvent event(String type, UUID aggregate, String producer, EventPayload payload) {
        return raw(type, aggregate, producer, new String(payload.toBytes(), StandardCharsets.UTF_8));
    }

    private static ReceivedEvent raw(String type, UUID aggregate, String producer, String json) {
        return new ReceivedEvent(EventId.next(IDS), type, 1, 1, aggregate, "aggregate", OBSERVED, producer,
                CorrelationId.of("flow-" + aggregate), CausationId.of("cause"), "application/json",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static EventPayload condition(String idField, UUID id) {
        return condition(idField, id, "91");
    }

    private static EventPayload condition(String idField, UUID id, String daysPastDue) {
        return EventPayload.of()
                .with(idField, id.toString())
                .with("product", "PERSONAL_LOAN")
                .with("businessDate", "2026-10-22")
                .with("daysPastDue", daysPastDue)
                .with("bucket", "B4_90_PLUS")
                .with("defaulted", "true")
                .with("agreementVersion", "2");
    }

    /** Born on the first insert of each source event, already after - the unique, in memory. */
    static final class RecordingStore implements RiskSignalStore {
        final List<RiskSignal> inserted = new ArrayList<>();

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            boolean born = inserted.stream().noneMatch(row -> row.source().id().equals(signal.source().id()));
            inserted.add(signal);
            return born;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            throw new UnsupportedOperationException();
        }
    }

    static final class RecordingOutbox implements OutboxWriter<Connection> {
        final List<EventEnvelope> envelopes = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();

        @Override
        public void write(Connection unitOfWork, EventEnvelope envelope, byte[] payload, String payloadMediaType) {
            envelopes.add(envelope);
            payloads.add(payload);
        }
    }

    static final class RecordingHints implements MonitoringHints {
        final List<CompletedMovement> movements = new ArrayList<>();

        @Override
        public void movementCompleted(Connection unitOfWork, CompletedMovement movement) {
            movements.add(movement);
        }
    }
}
