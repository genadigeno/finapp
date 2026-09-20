package com.finapp.payments;

import com.finapp.platform.audit.AuditableAction;

/**
 * The payments module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning ({@code P5-TSK-009}) — exactly as
 * {@code package-info}'s deliberately-few licence promised. The capture's and the refund's
 * actions arrive with theirs ({@code P5-TSK-010}/{@code -015}).
 */
public enum PaymentsAuditAction implements AuditableAction {

    /**
     * A person asked to fund their wallet: the intent committed {@code REQUIRES_CONFIRMATION}
     * (ADR-0045 — acceptance, not execution). The record names the intent, the instrument and
     * the wallet account by identifier; the amount never appears ({@code INV-AUD-02}). Emitted
     * by the creating call only — an idempotent replay created nothing and records nothing.
     */
    PAYMENT_INTENT_CREATED(
            "payments.PaymentIntentCreated",
            "A person created a payment intent; the record names the intent, the instrument and"
                    + " the wallet account by identifier, never an amount.",
            false),

    /**
     * A person confirmed their intent: {@code PROCESSING} and the attempt's dispatch committed
     * in one transaction, before the provider is asked (ADR-0046). Emitted by the winning
     * confirm only — a converging retry moved nothing and records nothing.
     */
    PAYMENT_CONFIRMED(
            "payments.PaymentConfirmed",
            "A person confirmed a payment intent; the dispatch committed before the provider"
                    + " call, and the record names the intent and the attempt, never an amount.",
            false),

    /**
     * A person withdrew their intent from the confirmation window ({@code REQUIRES_CONFIRMATION
     * → CANCELLED} — the window ADR-0045 created for exactly this). Emitted by the winning
     * cancel only.
     */
    PAYMENT_CANCELLED(
            "payments.PaymentCancelled",
            "A person cancelled a payment intent before confirmation; nothing was dispatched"
                    + " and nothing was posted.",
            false),

    /**
     * The platform applied a provider's answer to a dispatched operation — as the
     * <strong>platform</strong>, through an enumerated {@code enterSystem()} site, because a
     * provider's answer has no session ({@code PHASE_5_PLAN.md} §11). The summary carries the
     * verdict and the committed states as enumerated names; amounts and provider vocabulary
     * never appear ({@code INV-AUD-02}, {@code INV-PAY-03}).
     */
    PAYMENT_OUTCOME_APPLIED(
            "payments.PaymentOutcomeApplied",
            "The platform applied a provider outcome to a dispatched payment operation through"
                    + " a conditional transition; the record names the operation and the"
                    + " committed states, never an amount or a provider code.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    PaymentsAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
