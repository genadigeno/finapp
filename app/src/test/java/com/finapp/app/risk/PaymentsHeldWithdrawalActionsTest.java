package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.Withdrawals;
import com.finapp.risk.ActionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Risk's {@code HeldWithdrawalActions} port over payments' keyed doors (`P13-TSK-009`; {@code INV-RSK-02}): the key, the
 * withdrawal and the reason code passed through untouched to payments' own release and denial, payments' answer mapped
 * word for word (DONE, ALREADY, REFUSED), an id that is no platform identifier REFUSED without asking payments, a
 * deployment with no push rail refused loudly, and payments' throws propagating for the action machine to re-drive.
 * Unwired to any risk caller until `-012`/`-017`; the doors themselves are payments' {@code WithdrawalsTest}'s and the
 * owed {@code WithdrawalHoldDatabaseTest}'s.
 */
@DisplayName("risk's held-withdrawal actions over payments' doors (P13-TSK-009)")
class PaymentsHeldWithdrawalActionsTest {

    private final List<String> asked = new ArrayList<>();
    private Withdrawals.Held answer = new Withdrawals.Held(Withdrawals.HeldOutcome.DONE,
            Optional.of(WithdrawalStatus.DISPATCHED), false);

    private final PaymentsHeldWithdrawalActions actions = new PaymentsHeldWithdrawalActions(
            new PaymentsHeldWithdrawalActions.Doors() {
                @Override
                public Withdrawals.Held release(String key, WithdrawalId id, String reason) {
                    asked.add("release " + key + " " + id.value() + " " + reason);
                    return answer;
                }

                @Override
                public Withdrawals.Held deny(String key, WithdrawalId id, String reason) {
                    asked.add("deny " + key + " " + id.value() + " " + reason);
                    return answer;
                }
            });

    @Test
    @DisplayName("the release and the denial reach payments' doors with the action's key, the withdrawal and the reason"
            + " code, untouched")
    void theDoorsAreReachedUntouched() {
        UUID withdrawal = RiskEvaluationWorld.IDS.next();
        assertThat(actions.release(withdrawal, "risk.action:a1", "OVERRIDE_APPROVED")).isEqualTo(ActionOutcome.DONE);
        answer = new Withdrawals.Held(Withdrawals.HeldOutcome.DONE, Optional.of(WithdrawalStatus.FAILED), false);
        assertThat(actions.deny(withdrawal, "risk.action:a2", "CASE_DISPOSED")).isEqualTo(ActionOutcome.DONE);
        assertThat(asked).containsExactly(
                "release risk.action:a1 " + withdrawal + " OVERRIDE_APPROVED",
                "deny risk.action:a2 " + withdrawal + " CASE_DISPOSED");
    }

    @Test
    @DisplayName("payments' answer is risk's, word for word: ALREADY when the withdrawal had left HELD - the expiry's"
            + " race lost - and REFUSED for no such withdrawal")
    void theOutcomesMapWordForWord() {
        UUID withdrawal = RiskEvaluationWorld.IDS.next();
        answer = new Withdrawals.Held(Withdrawals.HeldOutcome.ALREADY, Optional.of(WithdrawalStatus.FAILED), false);
        assertThat(actions.release(withdrawal, "risk.action:b", "OVERRIDE_APPROVED")).isEqualTo(ActionOutcome.ALREADY);
        answer = new Withdrawals.Held(Withdrawals.HeldOutcome.REFUSED, Optional.empty(), false);
        assertThat(actions.deny(withdrawal, "risk.action:c", "CASE_DISPOSED")).isEqualTo(ActionOutcome.REFUSED);
        for (Withdrawals.HeldOutcome outcome : Withdrawals.HeldOutcome.values()) {
            assertThat(PaymentsHeldWithdrawalActions.outcome(new Withdrawals.Held(outcome,
                    outcome == Withdrawals.HeldOutcome.REFUSED ? Optional.empty() : Optional.of(WithdrawalStatus.HELD),
                    false)).name()).isEqualTo(outcome.name());
        }
    }

    @Test
    @DisplayName("an id that is no platform identifier names no withdrawal: REFUSED, payments never asked")
    void aForeignIdIsRefused() {
        assertThat(actions.release(UUID.randomUUID(), "risk.action:d", "OVERRIDE_APPROVED"))
                .isEqualTo(ActionOutcome.REFUSED);
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("a deployment with no push rail holds no withdrawal: a door asked there is refused loudly")
    void noRailIsALoudRefusal() {
        PaymentsHeldWithdrawalActions unrailed = new PaymentsHeldWithdrawalActions(() -> null);
        assertThatThrownBy(() -> unrailed.release(RiskEvaluationWorld.IDS.next(), "risk.action:e", "OVERRIDE_APPROVED"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("push rail");
    }

    @Test
    @DisplayName("payments' throws propagate - the action machine records FAILED and re-drives the same key")
    void throwsPropagate() {
        PaymentsHeldWithdrawalActions failing = new PaymentsHeldWithdrawalActions(
                new PaymentsHeldWithdrawalActions.Doors() {
                    @Override
                    public Withdrawals.Held release(String key, WithdrawalId id, String reason) {
                        throw new IllegalStateException("another flight holds " + key);
                    }

                    @Override
                    public Withdrawals.Held deny(String key, WithdrawalId id, String reason) {
                        throw new IllegalArgumentException("a reason code, never a note");
                    }
                });
        assertThatThrownBy(() -> failing.release(RiskEvaluationWorld.IDS.next(), "risk.action:f", "OVERRIDE_APPROVED"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("risk.action:f");
        assertThatThrownBy(() -> failing.deny(RiskEvaluationWorld.IDS.next(), "risk.action:g", "a note"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
