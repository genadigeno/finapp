package com.finapp.risk;

/**
 * The operations risk judges or watches (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.1; ADR-0108) - the key a
 * risk policy version is written for, one {@code ACTIVE} version per operation (ADR-0109).
 *
 * <p><strong>Five judge a money movement</strong> synchronously, in the caller's transaction, through a seam the
 * consuming module declares and {@code app} implements over this module's evaluation API: nothing here reaches a
 * consumer, and nothing a consumer owns is reached from here. <strong>Two do not</strong>: {@link #CREDIT_SIGNAL}
 * answers credit's seam with the customer risk profile's rating, never a decision; {@link #MONITORING} is the sweep's
 * policy key - the monitoring rules over the ledger's journal and the rating bands of the profile - and no seam at all.
 *
 * <p>A member added here is a new policy key and a new seam adapter, never a widening of an existing one: the
 * admitted decisions ({@link RiskDecision#admittedBy}) and the rule kinds that may carry it ({@link RuleKind#operations})
 * are declared per member, and {@code RiskVocabulariesTest} pins them.
 */
public enum RiskOperation {

    /** An internal transfer - {@code transfers.TransferLimitCheck} and {@code TransferRiskDecision} (`-008`). */
    TRANSFER(true),

    /** A wallet withdrawal to an external account - payments' {@code WithdrawalRiskDecision} (`-009`). */
    WITHDRAWAL(true),

    /** A checkout payment confirmed on a card - payments' {@code PaymentRiskDecision} (`-010`). */
    CARD_PAYMENT(true),

    /** A checkout payment confirmed from a wallet - payments' {@code PaymentRiskDecision} (`-010`). */
    WALLET_PAYMENT(true),

    /** A cross-border payment - {@code crossborder.CrossBorderLimitCheck} and {@code CrossBorderRiskDecision} (`-011`). */
    CROSS_BORDER(true),

    /** Credit's {@code CreditRiskSignal}: the profile's rating with seam version 2, not a movement (`-007`). */
    CREDIT_SIGNAL(false),

    /** The sweep's policy: monitoring rules and the profile's rating bands, not a seam (`-019`, `-020`, `-007`). */
    MONITORING(false);

    private final boolean judgesAMovement;

    RiskOperation(boolean judgesAMovement) {
        this.judgesAMovement = judgesAMovement;
    }

    /** True when a seam consults this operation inside a money-moving command's own transaction (T-eval). */
    public boolean judgesAMovement() {
        return judgesAMovement;
    }
}
