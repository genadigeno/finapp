package com.finapp.app.risk;

import com.finapp.payments.HeldPayments;
import com.finapp.payments.PaymentIntentId;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.HeldPaymentActions;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Risk's {@link HeldPaymentActions} port over payments' keyed doors on a held card payment (`P13-TSK-010`, slice 2;
 * {@code PHASE_13_PLAN.md} sections 5, 7.4, 12.8; ADR-0112; {@code INV-RSK-02}): {@link HeldPayments#captureHeld} and
 * {@link HeldPayments#voidHeld}, each resolving the capture hold under its row lock in payments' own transaction, keyed
 * by the action's key and audited by payments as the caller's act, then handing the money to payments' capture or void
 * command. Risk records the outcome; payments owns the effect. {@code PaymentsHeldWithdrawalActions}' shape.
 *
 * <p><strong>Called by risk since `P13-TSK-012`</strong>: {@code OverrideActions.dispatch} - an approved override's
 * capture or void of a held card payment, from the approval door after its commit and from the re-drive leg, each on no
 * risk transaction; `-017`'s action schedule next.
 *
 * <p><strong>The mapping</strong>: payments' {@code DONE}, {@code ALREADY} and {@code REFUSED} are risk's, word for word.
 * An id that is no platform identifier names no payment: {@code REFUSED}. Everything else payments throws - an
 * authorisation still in flight, a key reused for another payment, a malformed reason, a storage failure - propagates,
 * for the action machine to record {@code FAILED} and re-drive under the same key.
 */
public final class PaymentsHeldPaymentActions implements HeldPaymentActions {

    /** Payments' two doors - {@link HeldPayments}' own, or a suite's. */
    interface Doors {
        HeldPayments.Answer capture(String key, PaymentIntentId intent, String reason);

        HeldPayments.Answer voidHold(String key, PaymentIntentId intent, String reason);

        /**
         * The engine's doors - present exactly when a card provider is; a deployment with none can hold no card
         * payment, so a door asked there is a wiring defect, refused loudly.
         */
        static Doors over(Supplier<HeldPayments> engine) {
            Objects.requireNonNull(engine, "engine");
            return new Doors() {
                @Override
                public HeldPayments.Answer capture(String key, PaymentIntentId intent, String reason) {
                    return held().captureHeld(key, intent, reason);
                }

                @Override
                public HeldPayments.Answer voidHold(String key, PaymentIntentId intent, String reason) {
                    return held().voidHeld(key, intent, reason);
                }

                private HeldPayments held() {
                    HeldPayments held = engine.get();
                    if (held == null) {
                        throw new IllegalStateException("a held payment's door was asked on a deployment with no card"
                                + " provider: no card payment can be held there");
                    }
                    return held;
                }
            };
        }
    }

    private final Doors doors;

    public PaymentsHeldPaymentActions(Supplier<HeldPayments> engine) {
        this(Doors.over(engine));
    }

    PaymentsHeldPaymentActions(Doors doors) {
        this.doors = Objects.requireNonNull(doors, "doors");
    }

    @Override
    public ActionOutcome capture(UUID intentId, String idempotencyKey, String reason) {
        return idOf(intentId).map(id -> outcome(doors.capture(idempotencyKey, id, reason)))
                .orElse(ActionOutcome.REFUSED);
    }

    @Override
    public ActionOutcome voidHold(UUID intentId, String idempotencyKey, String reason) {
        return idOf(intentId).map(id -> outcome(doors.voidHold(idempotencyKey, id, reason)))
                .orElse(ActionOutcome.REFUSED);
    }

    private static Optional<PaymentIntentId> idOf(UUID intentId) {
        Objects.requireNonNull(intentId, "intentId");
        try {
            return Optional.of(PaymentIntentId.of(intentId));
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
    }

    static ActionOutcome outcome(HeldPayments.Answer answer) {
        return switch (answer.outcome()) {
            case DONE -> ActionOutcome.DONE;
            case ALREADY -> ActionOutcome.ALREADY;
            case REFUSED -> ActionOutcome.REFUSED;
        };
    }
}
