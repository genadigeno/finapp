package com.finapp.app.risk;

import com.finapp.payments.WithdrawalId;
import com.finapp.payments.Withdrawals;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.HeldWithdrawalActions;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Risk's {@link HeldWithdrawalActions} port over payments' keyed doors (`P13-TSK-009`; {@code PHASE_13_PLAN.md}
 * sections 5, 7.4, 12.8; ADR-0112; {@code INV-RSK-02}): {@link Withdrawals#releaseHeld} and {@link Withdrawals#denyHeld},
 * each in payments' own transactions under the withdrawal row's lock, keyed by the action's key and audited by payments
 * as the caller's act. Risk records the outcome; payments owns the effect.
 *
 * <p><strong>Called by risk</strong> (`P13-TSK-012`; folded into the action machine by `P13-TSK-017`): T-act's dispatch,
 * {@code ActionDispatch} through {@code RiskActions.dispatch} - an approved override's or a case's release or denial,
 * driven by {@code RiskActionDriver} after the approval's commit and by {@code RiskActionSchedule}'s re-drive, on no risk
 * transaction and as the platform ({@code SystemActor}), never under the approving person's session.
 *
 * <p><strong>The mapping</strong>: payments' {@code DONE}, {@code ALREADY} and {@code REFUSED} are risk's, word for word.
 * An id that is no platform identifier names no withdrawal: {@code REFUSED}. Everything else payments throws - a key
 * reused for another withdrawal, a flight still holding the key, a malformed reason, a storage failure - propagates: the
 * action machine records nothing and re-drives under the same key when its permit falls due.
 */
public final class PaymentsHeldWithdrawalActions implements HeldWithdrawalActions {

    /** Payments' two doors - {@link Withdrawals}' own, or a suite's. */
    interface Doors {
        Withdrawals.Held release(String key, WithdrawalId id, String reason);

        Withdrawals.Held deny(String key, WithdrawalId id, String reason);

        /**
         * The engine's doors - present exactly when the push rail is; a deployment with none can hold no withdrawal, so
         * a door asked there is a wiring defect, refused loudly.
         */
        static Doors over(Supplier<Withdrawals> engine) {
            Objects.requireNonNull(engine, "engine");
            return new Doors() {
                @Override
                public Withdrawals.Held release(String key, WithdrawalId id, String reason) {
                    return withdrawals().releaseHeld(key, id, reason);
                }

                @Override
                public Withdrawals.Held deny(String key, WithdrawalId id, String reason) {
                    return withdrawals().denyHeld(key, id, reason);
                }

                private Withdrawals withdrawals() {
                    Withdrawals withdrawals = engine.get();
                    if (withdrawals == null) {
                        throw new IllegalStateException("a held withdrawal's door was asked on a deployment with no"
                                + " push rail: no withdrawal can be held there");
                    }
                    return withdrawals;
                }
            };
        }
    }

    private final Doors doors;

    public PaymentsHeldWithdrawalActions(Supplier<Withdrawals> engine) {
        this(Doors.over(engine));
    }

    PaymentsHeldWithdrawalActions(Doors doors) {
        this.doors = Objects.requireNonNull(doors, "doors");
    }

    @Override
    public ActionOutcome release(UUID withdrawalId, String idempotencyKey, String reason) {
        return idOf(withdrawalId)
                .map(id -> outcome(doors.release(idempotencyKey, id, reason)))
                .orElse(ActionOutcome.REFUSED);
    }

    @Override
    public ActionOutcome deny(UUID withdrawalId, String idempotencyKey, String reason) {
        return idOf(withdrawalId)
                .map(id -> outcome(doors.deny(idempotencyKey, id, reason)))
                .orElse(ActionOutcome.REFUSED);
    }

    private static Optional<WithdrawalId> idOf(UUID withdrawalId) {
        Objects.requireNonNull(withdrawalId, "withdrawalId");
        try {
            return Optional.of(WithdrawalId.of(withdrawalId));
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
    }

    static ActionOutcome outcome(Withdrawals.Held held) {
        return switch (held.outcome()) {
            case DONE -> ActionOutcome.DONE;
            case ALREADY -> ActionOutcome.ALREADY;
            case REFUSED -> ActionOutcome.REFUSED;
        };
    }
}
