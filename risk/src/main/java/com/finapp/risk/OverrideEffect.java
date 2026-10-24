package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;

/**
 * What an approved override issues (`P13-TSK-012`; {@code PHASE_13_PLAN.md} section 12.3; ADR-0108 decision 7, ADR-0112
 * decision 2; {@code DESIGN-012} D2-D4; {@code risk V009}'s {@code decision_override_effect_is_known}) - fixed at the
 * proposal and judged against the overridden assessment, by the domain ({@link #of}) and by {@code risk V009}'s birth
 * rule, each alone.
 *
 * <ul>
 *   <li>A {@code HOLD} is resolved through its owner's keyed door, in the owner's own transactions: a held withdrawal
 *       {@link #RELEASE_HELD_WITHDRAWAL released} or {@link #DENY_HELD_WITHDRAWAL denied} ({@link HeldWithdrawalActions}),
 *       a held card payment {@link #CAPTURE_HELD_PAYMENT captured} or {@link #VOID_HELD_PAYMENT voided}
 *       ({@link HeldPaymentActions}) - each one of ADR-0112's {@link ActionKind}s, keyed {@code risk.action:<override
 *       id>}.</li>
 *   <li>A {@code DENY} or {@code REVIEW} refused a command that is terminal in its owner's machine - a transfer's
 *       {@code FAILED(RISK_REFUSED)}, a withdrawal's, a refused intent, crossborder's {@code 422} - so the override
 *       issues {@link #RESUBMISSION}: nothing dispatched, the officers' judgement that the refusal was wrong recorded,
 *       and the customer's resubmission judged afresh by the policy in force. The plan's one-time allowance for the same
 *       command ref is not built (D4): a transfer's retry carries a new command ref (`-008`), and a cross-border retry on
 *       the same quote is answered from its seal before any input is read (`-004`, `-011`).</li>
 * </ul>
 */
public enum OverrideEffect {

    RELEASE_HELD_WITHDRAWAL(ActionKind.RELEASE_HELD_WITHDRAWAL, "WITHDRAWAL"),

    DENY_HELD_WITHDRAWAL(ActionKind.DENY_HELD_WITHDRAWAL, "WITHDRAWAL"),

    CAPTURE_HELD_PAYMENT(ActionKind.CAPTURE_HELD_PAYMENT, "PAYMENT_INTENT"),

    VOID_HELD_PAYMENT(ActionKind.VOID_HELD_PAYMENT, "PAYMENT_INTENT"),

    /** Nothing dispatched: the refused command is terminal; its resubmission is judged afresh. */
    RESUBMISSION(null, null);

    private final ActionKind action;
    private final String targetKind;

    OverrideEffect(ActionKind action, String targetKind) {
        this.action = action;
        this.targetKind = targetKind;
    }

    /** The action kind dispatched through the owning domain - empty for a {@link #RESUBMISSION}. */
    public Optional<ActionKind> action() {
        return Optional.ofNullable(action);
    }

    /** True when an approval dispatches something through payments. */
    public boolean dispatches() {
        return action != null;
    }

    /** What the action's target is - the withdrawal or the payment intent; empty for a {@link #RESUBMISSION}. */
    public Optional<String> targetKind() {
        return Optional.ofNullable(targetKind);
    }

    /**
     * The effect an override of {@code decision} on {@code operation} issues for {@code resolution}, or empty when the
     * decision admits no override that way: an {@code ALLOW} or a {@code CHALLENGE} is never overridden, and a
     * {@link OverrideResolution#REFUSE} of a refusal has nothing held to refuse.
     */
    public static Optional<OverrideEffect> of(RiskOperation operation, RiskDecision decision,
            OverrideResolution resolution) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(resolution, "resolution");
        boolean proceed = resolution == OverrideResolution.PROCEED;
        return switch (decision) {
            case ALLOW, CHALLENGE -> Optional.empty();
            case DENY, REVIEW -> proceed ? Optional.of(RESUBMISSION) : Optional.empty();
            case HOLD -> switch (operation) {
                case WITHDRAWAL -> Optional.of(proceed ? RELEASE_HELD_WITHDRAWAL : DENY_HELD_WITHDRAWAL);
                case CARD_PAYMENT -> Optional.of(proceed ? CAPTURE_HELD_PAYMENT : VOID_HELD_PAYMENT);
                // No other operation admits HOLD (RiskDecision.admittedBy); a sealed HOLD elsewhere cannot exist.
                default -> Optional.empty();
            };
        };
    }
}
