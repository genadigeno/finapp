package com.finapp.app.payments;

import static com.finapp.app.payments.CallbackHintWorld.AMOUNT;
import static com.finapp.app.payments.CallbackHintWorld.cardBody;
import static com.finapp.app.payments.CallbackHintWorld.clearingBody;
import static com.finapp.app.payments.CallbackHintWorld.disputeBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.CallbackHint;
import com.finapp.payments.ClearingAnswer;
import com.finapp.payments.DisputeAnswer;
import com.finapp.payments.DisputeNotice;
import com.finapp.payments.DisputeReason;
import com.finapp.payments.DisputeStage;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.HintedOutcome;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStatus;
import com.finapp.platform.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The card door's callbacks as hints (`P15-TSK-036`, ADR-0083, {@code THREAT_MODEL.md} T8), one forged-but-signed callback
 * per kind the door adopted: authorization, capture, void, refund, clearing and dispute. Forged here means a correct
 * signature on a claim the PSP's own inquiry does not support, which is what a stolen webhook key produces.
 *
 * <p>The seam under assertion is {@code CallbackAppliers}: "moves nothing" means nothing reached it, and "the inquiry
 * decides" means what reached it carries the inquiry's words, not the callback's.
 */
@DisplayName("the card door: a callback is a hint (P15-TSK-036)")
class PaymentWebhookHintsTest {

    private static final byte[] ANSWER = "{\"status\":\"whatever-the-psp-said\"}".getBytes(StandardCharsets.UTF_8);

    private final CallbackHintWorld world = new CallbackHintWorld();

    @AfterEach
    void close() {
        world.close();
    }

    private void assertMovedNothingAndKeptTheEvidence() {
        assertThat(world.applied).as("nothing reaches an applier").isEmpty();
        assertThat(world.retained(EvidenceKind.WEBHOOK)).as("the signed bytes are retained").isEqualTo(1);
    }

    // ------------------------------------------------------------------ forged, per kind

    @Test
    @DisplayName("a forged authorization approval moves nothing the inquiry does not confirm")
    void aForgedAuthorizationMovesNothing() {
        PaymentAttempt attempt = world.authDispatched();
        String operation = attempt.authorizationReference().value();
        world.queries.put(operation, QueryAnswer.unrecognised(ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("query " + operation);
        assertThat(world.retained(EvidenceKind.QUERY_RESULT)).as("the answer is retained too").isEqualTo(1);
    }

    @Test
    @DisplayName("a forged capture approval moves nothing the inquiry does not confirm")
    void aForgedCaptureMovesNothing() {
        PaymentAttempt attempt = world.captureDispatched();
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.unrecognised(ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("query " + operation);
    }

    @Test
    @DisplayName("a forged void approval moves nothing, and a void is attributed at all")
    void aForgedVoidMovesNothing() {
        PaymentAttempt attempt = world.voidDispatched();
        String operation = attempt.voidReference().value();
        world.queries.put(operation, QueryAnswer.unrecognised(ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("query " + operation);
    }

    @Test
    @DisplayName("a forged refund approval moves nothing the inquiry does not confirm")
    void aForgedRefundMovesNothing() {
        Refund refund = world.refundOn(world.captured("psp-cap"), RefundStatus.DISPATCHED, null);
        String operation = refund.providerIdempotencyReference().value();
        world.queries.put(operation, QueryAnswer.unrecognised(ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("query " + operation);
    }

    @Test
    @DisplayName("a forged clearing notice records nothing when the PSP says the capture has not cleared, and is counted")
    void aForgedClearingMovesNothing() {
        PaymentAttempt attempt = world.captured("psp-cap");
        String operation = attempt.captureReference().value();
        world.clearings.put(operation, ClearingAnswer.notCleared(ANSWER));

        world.deliverCard(clearingBody(operation));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.concluded.values()).as("the hint is concluded refuted").containsExactly(HintedOutcome.REFUTED);
        assertThat(world.webhookCount("unmappable")).as("a refutation is counted as a break").isEqualTo(1);
    }

    @Test
    @DisplayName("a forged dispute posts no chargeback when the PSP says there is no such dispute, and is counted")
    void aForgedDisputeMovesNothing() {
        PaymentAttempt attempt = world.captured("psp-cap");
        world.disputes.put("dsp_forged", DisputeAnswer.unrecognised(ANSWER));

        world.deliverCard(disputeBody(attempt.captureReference().value(), "dsp_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("dispute dsp_forged");
        assertThat(world.concluded.values()).containsExactly(HintedOutcome.REFUTED);
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }

    @Test
    @DisplayName("a dispute the PSP places on another payment moves nothing here: attribution comes from the inquiry too")
    void aDisputeOnAnotherOperationMovesNothing() {
        PaymentAttempt attempt = world.captured("psp-cap");
        ProviderReference dispute = new ProviderReference("dsp_real");
        world.disputes.put(dispute.value(), DisputeAnswer.found(
                new ProviderIdempotencyReference("someone-elses-capture"), chargeback(dispute), ANSWER));

        world.deliverCard(disputeBody(attempt.captureReference().value(), dispute.value()));

        assertThat(world.applied).isEmpty();
        assertThat(world.concluded.values()).containsExactly(HintedOutcome.CONTRADICTED);
    }

    private static DisputeNotice chargeback(ProviderReference dispute) {
        return new DisputeNotice(
                "simulated-card", dispute, DisputeStage.CHARGED_BACK, DisputeReason.FRAUD, AMOUNT, Optional.empty());
    }

    // ------------------------------------------------------------------ the inquiry's words, never the callback's

    @Test
    @DisplayName("the callback says approved, the PSP says declined: the applier hears declined")
    void theInquiryOverridesTheCallbacksWord() {
        PaymentAttempt attempt = world.captureDispatched();
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.declined(ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_forged"));

        assertThat(world.applied).containsExactly("capture DECLINED -");
    }

    @Test
    @DisplayName("a genuine callback concludes through its inquiry, with the inquiry's reference")
    void aGenuineCaptureConcludesThroughItsInquiry() {
        PaymentAttempt attempt = world.captureDispatched();
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_real"), ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_claimed"));

        assertThat(world.applied).containsExactly("capture APPROVED psp_real");
        assertThat(world.webhookCount("processed")).isEqualTo(1);
    }

    @Test
    @DisplayName("each operation's genuine statement reaches its own applier: authorization, void, refund")
    void eachOperationReachesItsOwnApplier() {
        PaymentAttempt authorizing = world.authDispatched();
        world.queries.put(authorizing.authorizationReference().value(),
                QueryAnswer.approved(new ProviderReference("psp_auth_real"), ANSWER));
        world.deliverCard(cardBody(authorizing.authorizationReference().value(), "approved", "x"));

        PaymentAttempt voiding = world.voidDispatched();
        world.queries.put(voiding.voidReference().value(),
                QueryAnswer.approved(new ProviderReference("psp_void_real"), ANSWER));
        world.deliverCard(cardBody(voiding.voidReference().value(), "approved", "x"));

        Refund refund = world.refundOn(world.captured("psp-cap"), RefundStatus.UNKNOWN, null);
        world.queries.put(refund.providerIdempotencyReference().value(),
                QueryAnswer.approved(new ProviderReference("psp_rfd_real"), ANSWER));
        world.deliverCard(cardBody(refund.providerIdempotencyReference().value(), "approved", "x"));

        assertThat(world.applied).containsExactly(
                "authorization APPROVED psp_auth_real",
                "void APPROVED psp_void_real",
                "refund APPROVED psp_rfd_real");
    }

    @Test
    @DisplayName("a genuine clearing records the PSP's references, not the callback's, and concludes its hint")
    void aGenuineClearingRecordsTheInquirysReferences() {
        PaymentAttempt attempt = world.captured("psp-cap");
        String operation = attempt.captureReference().value();
        world.clearings.put(operation,
                ClearingAnswer.cleared(new ProviderReference("arn_real"), new ProviderReference("ntid_real"), ANSWER));

        world.deliverCard(clearingBody(operation));

        assertThat(world.applied).containsExactly("clearing arn_real ntid_real");
        assertThat(world.concluded.values()).containsExactly(HintedOutcome.ADOPTED);
    }

    @Test
    @DisplayName("a genuine dispute applies the PSP's statement, not the callback's stage or amount")
    void aGenuineDisputeAppliesTheInquirysStatement() {
        PaymentAttempt attempt = world.captured("psp-cap");
        ProviderReference dispute = new ProviderReference("dsp_real");
        world.disputes.put(dispute.value(), DisputeAnswer.found(attempt.captureReference(), chargeback(dispute), ANSWER));

        world.deliverCard(disputeBody(attempt.captureReference().value(), dispute.value()));

        assertThat(world.applied).containsExactly("dispute dsp_real CHARGED_BACK " + AMOUNT.minorUnits());
    }

    // ------------------------------------------------------------------ an inquiry that fails adopts nothing

    @Test
    @DisplayName("an inquiry answering nothing usable adopts nothing: acknowledged, evidence kept, the sweep decides")
    void anIndeterminateInquiryLeavesTheOutcomeToTheSweep() {
        PaymentAttempt attempt = world.captureDispatched();
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.indeterminate(ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_claimed"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.webhookCount("processed")).as("handled: the sweep decides").isEqualTo(1);
    }

    @Test
    @DisplayName("an inquiry that throws adopts nothing and the delivery is still acknowledged")
    void aThrowingInquiryLeavesTheOutcomeToTheSweep() {
        PaymentAttempt attempt = world.captureDispatched();

        world.inquiriesThrow = true;
        world.deliverCard(cardBody(attempt.captureReference().value(), "approved", "psp_claimed"));

        assertMovedNothingAndKeptTheEvidence();
    }

    @Test
    @DisplayName("UNRECOGNISED on a live dispatch never fails it: only the sweep's bounds license that")
    void unrecognisedOnAFreshDispatchConcludesNothing() {
        PaymentAttempt attempt = world.authDispatched();
        String operation = attempt.authorizationReference().value();
        world.queries.put(operation, QueryAnswer.unrecognised(ANSWER));

        world.deliverCard(cardBody(operation, "declined", "psp_claimed"));

        assertThat(world.applied).isEmpty();
    }

    @Test
    @DisplayName("a clearing whose inquiry fails keeps its hint open for the sweep, which finishes it")
    void aFailedClearingInquiryIsFinishedByTheSweep() {
        PaymentAttempt attempt = world.captured("psp-cap");
        String operation = attempt.captureReference().value();

        world.deliverCard(clearingBody(operation));

        assertThat(world.applied).isEmpty();
        assertThat(world.hintRows).hasSize(1);
        CallbackHint hint = world.hintRows.get(0);
        assertThat(hint.kind()).isEqualTo(CallbackHint.Kind.CARD_CLEARING);
        assertThat(world.concluded).as("no conclusion yet").isEmpty();

        world.clearings.put(operation,
                ClearingAnswer.cleared(new ProviderReference("arn_real"), new ProviderReference("ntid_real"), ANSWER));
        assertThat(world.hintResolution.sweep().concluded()).isEqualTo(1);

        assertThat(world.applied).containsExactly("clearing arn_real ntid_real");
        assertThat(world.concluded).containsEntry(hint.id(), HintedOutcome.ADOPTED);
    }

    // ------------------------------------------------------------------ dedupe, and the transaction boundary

    @Test
    @DisplayName("a duplicate delivery makes no second inquiry, though its bytes are retained")
    void aDuplicateMakesNoSecondInquiry() {
        PaymentAttempt attempt = world.captureDispatched();
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_real"), ANSWER));
        String body = cardBody(operation, "approved", "psp_real");

        world.deliverCard(body);
        world.deliverCard(body);
        world.deliverCard(body);

        assertThat(world.asked).hasSize(1);
        assertThat(world.applied).hasSize(1);
        assertThat(world.retained(EvidenceKind.WEBHOOK)).isEqualTo(3);
        assertThat(world.webhookCount("duplicate")).isEqualTo(2);
    }

    @Test
    @DisplayName("a contended dedupe record is refused unacknowledged, and asks nothing")
    void aContendedDeliveryAsksNothing() {
        PaymentAttempt attempt = world.captureDispatched();
        world.inboxContended = true;

        assertThatThrownBy(() -> world.deliverCard(cardBody(attempt.captureReference().value(), "approved", "x")))
                .isInstanceOf(ApiException.class);

        assertThat(world.asked).isEmpty();
    }

    @Test
    @DisplayName("an unsigned callback writes nothing and asks nothing")
    void anUnsignedCallbackIsRefusedBeforeAnything() {
        PaymentAttempt attempt = world.captureDispatched();
        String body = cardBody(attempt.captureReference().value(), "approved", "x");
        String timestamp = Long.toString(Instant.now(CallbackHintWorld.CLOCK).getEpochSecond());

        assertThatThrownBy(() -> world.cardDoor.deliver(body.getBytes(StandardCharsets.UTF_8), timestamp, "00"))
                .isInstanceOf(ApiException.class);

        assertThat(world.asked).isEmpty();
        assertThat(world.retained).isEmpty();
    }

    @Test
    @DisplayName("every inquiry is made holding no transaction (the provider double fails the test otherwise)")
    void noInquiryRunsInsideATransaction() {
        PaymentAttempt capturing = world.captureDispatched();
        PaymentAttempt captured = world.captured("psp-cap");

        world.deliverCard(cardBody(capturing.captureReference().value(), "approved", "x"));
        world.deliverCard(clearingBody(captured.captureReference().value()));
        world.deliverCard(disputeBody(captured.captureReference().value(), "dsp_any"));

        assertThat(world.asked).hasSize(3);
    }

    // ------------------------------------------------------------------ the three residues (the card door's two)

    @Test
    @DisplayName("a late answer agreeing with the row converges quietly")
    void aLateAgreeingAnswerConverges() {
        PaymentAttempt attempt = world.captured("psp_real");
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_real"), ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_real"));

        assertThat(world.applied).isEmpty();
        assertThat(world.webhookCount("processed")).isEqualTo(1);
        assertThat(world.webhookCount("unmappable")).isZero();
    }

    @Test
    @DisplayName("a late answer contradicting a captured row is flagged and moves nothing")
    void aLateDeclineOnACapturedRowIsFlaggedAsAContradiction() {
        PaymentAttempt attempt = world.captured("psp_real");
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.declined(ANSWER));

        world.deliverCard(cardBody(operation, "declined", "x"));

        assertThat(world.applied).isEmpty();
        assertThat(world.webhookCount("unmappable")).as("a contradiction is counted as a break").isEqualTo(1);
    }

    @Test
    @DisplayName("an approval on a row the platform failed is flagged: the PSP says it took money our books say it did not")
    void aLateApprovalOnAFailedRowIsFlaggedAsAContradiction() {
        PaymentAttempt attempt = world.failedAtCapture();
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_late"), ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_late"));

        assertThat(world.applied).isEmpty();
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }

    @Test
    @DisplayName("an approval under another reference on a captured row is flagged")
    void anApprovalUnderAnotherReferenceIsFlagged() {
        PaymentAttempt attempt = world.captured("psp_real");
        String operation = attempt.captureReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_other"), ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_other"));

        assertThat(world.applied).isEmpty();
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }

    @Test
    @DisplayName("a refund of another rail is not attributed at the card door: no inquiry, no outcome, evidence unattributed")
    void aRefundAttributedAcrossRailsIsRefused() {
        PaymentAttempt instantPayIn = world.executed("scheme-1");
        Refund aReturn = world.refundOn(instantPayIn, RefundStatus.DISPATCHED, null);
        String operation = aReturn.providerIdempotencyReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_real"), ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_real"));

        assertThat(world.asked).as("no inquiry for another rail's refund").isEmpty();
        assertThat(world.applied).isEmpty();
        assertThat(world.retained).singleElement().satisfies(row -> assertThat(row.unattributed()).isTrue());
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }

    @Test
    @DisplayName("a refund late answer contradicting the row is flagged and moves nothing")
    void aLateRefundAnswerContradictingTheRowIsFlagged() {
        Refund refund = world.refundOn(world.captured("psp-cap"), RefundStatus.FAILED, null);
        String operation = refund.providerIdempotencyReference().value();
        world.queries.put(operation, QueryAnswer.approved(new ProviderReference("psp_rfd"), ANSWER));

        world.deliverCard(cardBody(operation, "approved", "psp_rfd"));

        assertThat(world.applied).isEmpty();
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }
}
