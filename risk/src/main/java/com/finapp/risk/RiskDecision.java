package com.finapp.risk;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a risk evaluation can decide (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.3; ADR-0108) - derived from
 * what each operation can do with a judgement, never adopted as a list.
 *
 * <p><strong>Declared in precedence order</strong>: when the rules of one evaluation disagree, the strictest decision
 * wins - {@code DENY > HOLD > REVIEW > CHALLENGE > ALLOW} - and a hard rule's decision beats a score band's. The
 * declaration order IS the precedence ({@link #strictest}), checked as the class loads.
 *
 * <p><strong>Each operation admits a subset</strong> ({@link #admittedBy}): a policy version mapping a band to a decision
 * the operation does not admit is refused at the policy door ({@code risk.PolicyInvalid}, `-002`), so no policy can ever
 * demand what no consumer honours. {@code HOLD} is admitted only where the operation has a native hold-then-dispatch
 * shape: the withdrawal today; the card payment only when `-010`'s second slice (authorise-and-hold) lands, which edits
 * this declaration beside the slice - never before.
 */
public enum RiskDecision {

    /** Refuse: the seam answers {@code REFUSE}; the caller commits its reserved reason; a case opens when the policy says so. */
    DENY,

    /** Accept the command, move nothing until a person decides: the withdrawal commits {@code HELD} with its hold kept. */
    HOLD,

    /** Refuse now and open a case with the assessment as its first evidence. */
    REVIEW,

    /** Proceed only under {@code MULTI_FACTOR} assurance; an unsatisfied challenge is a refusal with its own reason. */
    CHALLENGE,

    /** The movement may proceed: the seam answers {@code PERMIT}. */
    ALLOW;

    static {
        // The precedence is the declaration order; a reordering is a reviewed change to this comment and to
        // RiskVocabulariesTest, never a silent one.
        if (DENY.ordinal() != 0 || ALLOW.ordinal() != values().length - 1) {
            throw new IllegalStateException("RiskDecision is declared strictest first and ALLOW last");
        }
    }

    /** The stricter of two decisions - the declaration order. */
    public static RiskDecision strictest(RiskDecision a, RiskDecision b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /**
     * The decisions {@code operation} admits - what its consumer can honour. Empty for an operation that decides
     * nothing: {@code CREDIT_SIGNAL} answers a rating and {@code MONITORING} raises alerts.
     */
    public static Set<RiskDecision> admittedBy(RiskOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return switch (operation) {
            case TRANSFER, CROSS_BORDER -> EnumSet.of(ALLOW, CHALLENGE, DENY, REVIEW);
            case WITHDRAWAL -> EnumSet.of(ALLOW, CHALLENGE, DENY, HOLD, REVIEW);
            // HOLD joins CARD_PAYMENT with `-010`'s second slice, beside the capture-and-hold it needs.
            case CARD_PAYMENT, WALLET_PAYMENT -> EnumSet.of(ALLOW, CHALLENGE, DENY);
            case CREDIT_SIGNAL, MONITORING -> EnumSet.noneOf(RiskDecision.class);
        };
    }

    /** True when {@code operation}'s consumer can honour this decision. */
    public boolean isAdmittedBy(RiskOperation operation) {
        return admittedBy(operation).contains(this);
    }
}
