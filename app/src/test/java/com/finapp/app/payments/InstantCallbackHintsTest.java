package com.finapp.app.payments;

import static com.finapp.app.payments.CallbackHintWorld.AMOUNT;
import static com.finapp.app.payments.CallbackHintWorld.instantBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.CallbackHint;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.ExecutionAnswer;
import com.finapp.payments.HintedOutcome;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PushInquiryAnswer;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalStatus;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The instant door's callbacks as hints (`P15-TSK-036`, ADR-0083, {@code THREAT_MODEL.md} T8), one forged-but-signed
 * callback per kind the door adopted or echoed: the pay-in on a waiting attempt, value on a concluded attempt, value naming
 * nothing of ours, the return, the withdrawal.
 */
@DisplayName("the instant door: a callback is a hint (P15-TSK-036)")
class InstantCallbackHintsTest {

    private static final byte[] ANSWER = "{\"status\":\"whatever-the-scheme-said\"}".getBytes(StandardCharsets.UTF_8);
    private static final ProviderReference REAL = new ProviderReference("scheme_real");

    private final CallbackHintWorld world = new CallbackHintWorld();

    @AfterEach
    void close() {
        world.close();
    }

    private void assertMovedNothingAndKeptTheEvidence() {
        assertThat(world.applied).as("nothing reaches an applier, parking included").isEmpty();
        assertThat(world.retained(EvidenceKind.WEBHOOK)).as("the signed bytes are retained").isEqualTo(1);
    }

    private static PushInquiryAnswer executed() {
        return PushInquiryAnswer.executed(REAL, Optional.of("cycle-real"), AMOUNT, ANSWER);
    }

    // ------------------------------------------------------------------ forged, per kind

    @Test
    @DisplayName("a forged execution on a waiting pay-in posts nothing the inquiry does not confirm")
    void aForgedExecutionMovesNothing() {
        PaymentAttempt attempt = world.awaitingPayer();
        String reference = attempt.endToEndReference().value();
        world.initiations.put(reference, PushInquiryAnswer.unrecognised(ANSWER));

        world.deliverInstant(instantBody(reference, "executed", "scheme_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("initiation " + reference);
    }

    @Test
    @DisplayName("a forged rejection on a waiting pay-in fails nothing the inquiry does not confirm")
    void aForgedRejectionMovesNothing() {
        PaymentAttempt attempt = world.awaitingPayer();
        String reference = attempt.endToEndReference().value();

        world.deliverInstant(instantBody(reference, "rejected", "scheme_forged"));

        assertMovedNothingAndKeptTheEvidence();
    }

    @Test
    @DisplayName("a forged execution on a concluded pay-in parks nothing the inquiry does not confirm")
    void aForgedExecutionOnAConcludedAttemptParksNothing() {
        PaymentAttempt attempt = world.pushFailed();
        String reference = attempt.endToEndReference().value();
        world.initiations.put(reference, PushInquiryAnswer.rejected(ANSWER));

        world.deliverInstant(instantBody(reference, "executed", "scheme_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.concluded.values()).as("the scheme agrees it failed").containsExactly(HintedOutcome.CONVERGED);
    }

    @Test
    @DisplayName("a forged execution naming nothing of ours parks nothing, and is counted")
    void aForgedUnattributedExecutionParksNothing() {
        world.executions.put("scheme_forged", ExecutionAnswer.unrecognised(ANSWER));

        world.deliverInstant(instantBody("nothingofours1", "executed", "scheme_forged"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.asked).containsExactly("execution scheme_forged");
        assertThat(world.concluded.values()).containsExactly(HintedOutcome.REFUTED);
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }

    @Test
    @DisplayName("a forged confirmation of a return moves nothing the inquiry does not confirm")
    void aForgedReturnMovesNothing() {
        Refund aReturn = world.refundOn(world.executed("scheme-orig"), RefundStatus.DISPATCHED, null);
        String reference = aReturn.providerIdempotencyReference().value();
        world.returns.put(reference, PushInquiryAnswer.unrecognised(ANSWER));

        world.deliverInstant(instantBody(reference, "executed", "scheme_forged"));

        assertThat(world.applied).isEmpty();
        assertThat(world.asked).containsExactly("return " + reference);
        assertThat(world.retained).filteredOn(row -> row.kind() == EvidenceKind.WEBHOOK)
                .singleElement().satisfies(row -> assertThat(row.refund()).isPresent());
    }

    @Test
    @DisplayName("a forged confirmation of a withdrawal moves nothing the inquiry does not confirm")
    void aForgedWithdrawalMovesNothing() {
        Withdrawal withdrawal = world.withdrawal(WithdrawalStatus.DISPATCHED);
        String reference = withdrawal.reference().value();
        world.transfers.put(reference, PushInquiryAnswer.unrecognised(ANSWER));

        world.deliverInstant(instantBody(reference, "executed", "scheme_forged"));

        assertThat(world.applied).isEmpty();
        assertThat(world.asked).containsExactly("transfer " + reference);
        assertThat(world.retained).filteredOn(row -> row.kind() == EvidenceKind.WEBHOOK)
                .singleElement().satisfies(row -> assertThat(row.withdrawal()).isPresent());
    }

    // ------------------------------------------------------------------ genuine, per kind

    @Test
    @DisplayName("a genuine execution concludes through its inquiry, with the scheme's reference and amount")
    void aGenuineExecutionConcludesThroughItsInquiry() {
        PaymentAttempt attempt = world.awaitingPayer();
        String reference = attempt.endToEndReference().value();
        world.initiations.put(reference, executed());

        world.deliverInstant(instantBody(reference, "executed", "scheme_claimed"));

        assertThat(world.applied)
                .containsExactly("execution ACCEPTED scheme_real " + AMOUNT.minorUnits() + " from AWAITING_PAYER");
        assertThat(world.webhookCount("processed")).isEqualTo(1);
    }

    @Test
    @DisplayName("value the scheme confirms on a concluded pay-in reaches the applier's concluded branch")
    void aGenuineExecutionOnAConcludedAttemptReachesTheApplier() {
        PaymentAttempt attempt = world.pushFailed();
        String reference = attempt.endToEndReference().value();
        world.initiations.put(reference, executed());

        world.deliverInstant(instantBody(reference, "executed", "scheme_claimed"));

        assertThat(world.applied)
                .containsExactly("execution ACCEPTED scheme_real " + AMOUNT.minorUnits() + " from AWAITING_PAYER");
        assertThat(world.hintRows).singleElement()
                .satisfies(hint -> assertThat(hint.kind()).isEqualTo(CallbackHint.Kind.INSTANT_CONCLUDED_EXECUTION));
    }

    @Test
    @DisplayName("value naming nothing of ours parks on the scheme's own record: its amount, its reference")
    void aGenuineUnattributedExecutionParksTheInquirysAmount() {
        world.executions.put("scheme_claimed",
                ExecutionAnswer.executed(REAL, Optional.empty(), Optional.empty(), AMOUNT, ANSWER));

        world.deliverInstant(instantBody("nothingofours2", "executed", "scheme_claimed"));

        assertThat(world.applied).containsExactly("park scheme_real " + AMOUNT.minorUnits());
        assertThat(world.retained).filteredOn(row -> row.kind() == EvidenceKind.QUERY_RESULT)
                .singleElement().satisfies(row -> assertThat(row.parking()).isPresent());
    }

    @Test
    @DisplayName("an execution the scheme places on a reference of ours parks nothing: a row of ours explains it")
    void anExecutionTheSchemePlacesOnOurReferenceParksNothing() {
        Withdrawal withdrawal = world.withdrawal(WithdrawalStatus.DISPATCHED);
        world.executions.put("scheme_ours", ExecutionAnswer.executed(
                new ProviderReference("scheme_ours"), Optional.of(withdrawal.reference()), Optional.empty(), AMOUNT, ANSWER));

        world.deliverInstant(instantBody("nothingofours3", "executed", "scheme_ours"));

        assertThat(world.applied).as("value that went out must never park as value that came in").isEmpty();
        assertThat(world.concluded.values()).containsExactly(HintedOutcome.CONVERGED);
    }

    @Test
    @DisplayName("a genuine return confirmation reaches the refund's applier in the provider-neutral verdict")
    void aGenuineReturnConcludesThroughItsInquiry() {
        Refund aReturn = world.refundOn(world.executed("scheme-orig"), RefundStatus.DISPATCHED, null);
        String reference = aReturn.providerIdempotencyReference().value();
        world.returns.put(reference, PushInquiryAnswer.accepted(REAL, Optional.empty(), ANSWER));

        world.deliverInstant(instantBody(reference, "executed", "scheme_claimed"));

        assertThat(world.applied).containsExactly("refund APPROVED scheme_real");
    }

    @Test
    @DisplayName("a genuine withdrawal confirmation reaches the withdrawal's applier with the scheme's reference")
    void aGenuineWithdrawalConcludesThroughItsInquiry() {
        Withdrawal withdrawal = world.withdrawal(WithdrawalStatus.DISPATCHED);
        world.transfers.put(withdrawal.reference().value(), PushInquiryAnswer.accepted(REAL, Optional.empty(), ANSWER));

        world.deliverInstant(instantBody(withdrawal.reference().value(), "executed", "scheme_claimed"));

        assertThat(world.applied).containsExactly("withdrawal ACCEPTED scheme_real");
    }

    // ------------------------------------------------------------------ inquiry failure, duplicates, rails

    @Test
    @DisplayName("an inquiry answering nothing usable adopts nothing and the sweep decides")
    void anIndeterminateInquiryLeavesTheOutcomeToTheSweep() {
        PaymentAttempt attempt = world.awaitingPayer();
        String reference = attempt.endToEndReference().value();
        world.initiations.put(reference, PushInquiryAnswer.indeterminate(ANSWER));

        world.deliverInstant(instantBody(reference, "executed", "scheme_claimed"));

        assertMovedNothingAndKeptTheEvidence();
        assertThat(world.webhookCount("processed")).isEqualTo(1);
    }

    @Test
    @DisplayName("an inquiry that throws adopts nothing and the delivery is still acknowledged")
    void aThrowingInquiryLeavesTheOutcomeToTheSweep() {
        PaymentAttempt attempt = world.awaitingPayer();

        world.inquiriesThrow = true;
        world.deliverInstant(instantBody(attempt.endToEndReference().value(), "executed", "scheme_claimed"));

        assertMovedNothingAndKeptTheEvidence();
    }

    @Test
    @DisplayName("value naming nothing of ours, inquiry failing: nothing parks, the hint stays, the sweep parks it")
    void aFailedUnattributedInquiryIsFinishedByTheSweep() {
        world.deliverInstant(instantBody("nothingofours4", "executed", "scheme_claimed"));

        assertThat(world.applied).isEmpty();
        assertThat(world.hintRows).hasSize(1);
        assertThat(world.concluded).isEmpty();

        world.executions.put("scheme_claimed",
                ExecutionAnswer.executed(REAL, Optional.empty(), Optional.empty(), AMOUNT, ANSWER));
        assertThat(world.hintResolution.sweep().concluded()).isEqualTo(1);

        assertThat(world.applied).containsExactly("park scheme_real " + AMOUNT.minorUnits());
    }

    @Test
    @DisplayName("a duplicate delivery makes no second inquiry, though its bytes are retained")
    void aDuplicateMakesNoSecondInquiry() {
        PaymentAttempt attempt = world.awaitingPayer();
        String reference = attempt.endToEndReference().value();
        world.initiations.put(reference, executed());
        String body = instantBody(reference, "executed", "scheme_claimed");

        world.deliverInstant(body);
        world.deliverInstant(body);

        assertThat(world.asked).hasSize(1);
        assertThat(world.applied).hasSize(1);
        assertThat(world.retained(EvidenceKind.WEBHOOK)).isEqualTo(2);
    }

    @Test
    @DisplayName("a card refund's reference is no return on this rail: no inquiry, no outcome")
    void aCardRefundIsNotARefundOnThisRail() {
        Refund cardRefund = world.refundOn(world.captured("psp-cap"), RefundStatus.DISPATCHED, null);
        String reference = cardRefund.providerIdempotencyReference().value();

        world.deliverInstant(instantBody(reference, "rejected", "scheme_claimed"));

        assertThat(world.asked).isEmpty();
        assertThat(world.applied).isEmpty();
    }

    @Test
    @DisplayName("a rejection on an executed pay-in is flagged and moves nothing")
    void aRejectionOnAnExecutedAttemptIsNotAdopted() {
        PaymentAttempt attempt = world.executed("scheme_real");
        EndToEndReference reference = attempt.endToEndReference();
        world.initiations.put(reference.value(), executed());

        world.deliverInstant(instantBody(reference.value(), "rejected", "scheme_claimed"));

        assertThat(world.applied).as("a word on a concluded row asks nothing and moves nothing").isEmpty();
        assertThat(world.asked).isEmpty();
    }

    @Test
    @DisplayName("an unreadable word is counted and asks nothing")
    void anUnreadableWordAsksNothing() {
        PaymentAttempt attempt = world.awaitingPayer();

        world.deliverInstant(instantBody(attempt.endToEndReference().value(), "paused", "scheme_claimed"));

        assertThat(world.asked).isEmpty();
        assertThat(world.webhookCount("unmappable")).isEqualTo(1);
    }
}
