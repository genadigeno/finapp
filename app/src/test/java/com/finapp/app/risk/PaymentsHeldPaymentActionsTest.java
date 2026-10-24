package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.CaptureHold;
import com.finapp.payments.HeldPayments;
import com.finapp.payments.PaymentIntentId;
import com.finapp.risk.ActionKind;
import com.finapp.risk.ActionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Risk's {@code HeldPaymentActions} port over payments' keyed doors on a held card payment (`P13-TSK-010`, slice 2;
 * {@code INV-RSK-02}): the key, the intent and the reason code passed through untouched to payments' own capture and
 * void, payments' answer mapped word for word (DONE, ALREADY, REFUSED), an id that is no platform identifier REFUSED
 * without asking payments, a deployment with no card provider refused loudly, and payments' throws propagating for the
 * action machine to re-drive - the two action kinds it executes now available. Unwired to any risk caller until
 * `-012`/`-017`; the doors themselves are payments' {@code HeldPaymentsTest}'s and the owed
 * {@code PaymentRiskSeamDatabaseTest}'s.
 */
@DisplayName("risk's held-payment actions over payments' doors (P13-TSK-010)")
class PaymentsHeldPaymentActionsTest {

    private final List<String> asked = new ArrayList<>();
    private HeldPayments.Answer answer =
            new HeldPayments.Answer(HeldPayments.HeldOutcome.DONE, Optional.of(CaptureHold.Resolution.CAPTURE), false);

    private final PaymentsHeldPaymentActions actions = new PaymentsHeldPaymentActions(
            new PaymentsHeldPaymentActions.Doors() {
                @Override
                public HeldPayments.Answer capture(String key, PaymentIntentId intent, String reason) {
                    asked.add("capture " + key + " " + intent.value() + " " + reason);
                    return answer;
                }

                @Override
                public HeldPayments.Answer voidHold(String key, PaymentIntentId intent, String reason) {
                    asked.add("void " + key + " " + intent.value() + " " + reason);
                    return answer;
                }
            });

    @Test
    @DisplayName("the capture and the void reach payments' doors with the action's key, the intent and the reason"
            + " code, untouched - the two action kinds available beside them")
    void theDoorsAreReachedUntouched() {
        UUID intent = RiskEvaluationWorld.IDS.next();
        assertThat(actions.capture(intent, "risk.action:a1", "OVERRIDE_APPROVED")).isEqualTo(ActionOutcome.DONE);
        answer = new HeldPayments.Answer(HeldPayments.HeldOutcome.DONE, Optional.of(CaptureHold.Resolution.VOID), false);
        assertThat(actions.voidHold(intent, "risk.action:a2", "FRAUD_CONFIRMED")).isEqualTo(ActionOutcome.DONE);
        assertThat(asked).containsExactly(
                "capture risk.action:a1 " + intent + " OVERRIDE_APPROVED",
                "void risk.action:a2 " + intent + " FRAUD_CONFIRMED");
        assertThat(ActionKind.CAPTURE_HELD_PAYMENT.available()).isTrue();
        assertThat(ActionKind.VOID_HELD_PAYMENT.available()).isTrue();
    }

    @Test
    @DisplayName("payments' answer is risk's, word for word: ALREADY when the hold was resolved by another hand - the"
            + " deadline's race lost - and REFUSED for no such held payment or a lapsed capture")
    void theOutcomesMapWordForWord() {
        UUID intent = RiskEvaluationWorld.IDS.next();
        answer = new HeldPayments.Answer(HeldPayments.HeldOutcome.ALREADY,
                Optional.of(CaptureHold.Resolution.EXPIRED), false);
        assertThat(actions.capture(intent, "risk.action:b", "OVERRIDE_APPROVED")).isEqualTo(ActionOutcome.ALREADY);
        answer = new HeldPayments.Answer(HeldPayments.HeldOutcome.REFUSED, Optional.empty(), false);
        assertThat(actions.voidHold(intent, "risk.action:c", "FRAUD_CONFIRMED")).isEqualTo(ActionOutcome.REFUSED);
        for (HeldPayments.HeldOutcome outcome : HeldPayments.HeldOutcome.values()) {
            assertThat(PaymentsHeldPaymentActions.outcome(new HeldPayments.Answer(outcome,
                    outcome == HeldPayments.HeldOutcome.DONE ? Optional.of(CaptureHold.Resolution.CAPTURE)
                            : Optional.empty(), false)).name()).isEqualTo(outcome.name());
        }
    }

    @Test
    @DisplayName("an id that is no platform identifier names no payment: REFUSED, payments never asked")
    void aForeignIdIsRefused() {
        assertThat(actions.capture(UUID.randomUUID(), "risk.action:d", "OVERRIDE_APPROVED"))
                .isEqualTo(ActionOutcome.REFUSED);
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("a deployment with no card provider holds no card payment: a door asked there is refused loudly")
    void noProviderIsALoudRefusal() {
        PaymentsHeldPaymentActions unprovided = new PaymentsHeldPaymentActions(() -> null);
        assertThatThrownBy(() -> unprovided.voidHold(RiskEvaluationWorld.IDS.next(), "risk.action:e", "FRAUD_CONFIRMED"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("card provider");
    }

    @Test
    @DisplayName("payments' throws propagate - the action machine records FAILED and re-drives the same key")
    void throwsPropagate() {
        PaymentsHeldPaymentActions failing = new PaymentsHeldPaymentActions(new PaymentsHeldPaymentActions.Doors() {
            @Override
            public HeldPayments.Answer capture(String key, PaymentIntentId intent, String reason) {
                throw new IllegalStateException("the authorisation is still in flight");
            }

            @Override
            public HeldPayments.Answer voidHold(String key, PaymentIntentId intent, String reason) {
                throw new IllegalStateException("another flight holds " + key);
            }
        });
        assertThatThrownBy(() -> failing.capture(RiskEvaluationWorld.IDS.next(), "risk.action:f", "OVERRIDE_APPROVED"))
                .isInstanceOf(IllegalStateException.class);
    }
}
