package com.finapp.paymentmethods;

import com.finapp.platform.audit.AuditableAction;

/**
 * The paymentmethods module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with
 * the surface whose design fixes their meaning — the deliberately-few licence `P5-TSK-001`
 * recorded, exercised by `P5-TSK-005`.
 */
public enum PaymentmethodsAuditAction implements AuditableAction {

    /**
     * An instrument was attached (`P5-TSK-005`). The record is the trail attaching an
     * instrument leaves — the act where an account takeover monetises, which is why the
     * surface demands the second factor of an enrolled identity — naming the payment method by
     * identifier, <strong>never the token and never the display metadata</strong>
     * ({@code RESTRICTED-PII} at the register, {@code INV-AUD-02}).
     *
     * <p>No reason required: attaching one's own instrument is a person's own act (the
     * {@code transfers.BeneficiaryAdded} reasoning). Emitted by the creating call only — a
     * converged retry attached nothing and records nothing.
     */
    PAYMENT_METHOD_ATTACHED(
            "paymentmethods.PaymentMethodAttached",
            "A party attached a tokenised payment instrument; the record names the payment"
                    + " method by identifier, never the token or the display metadata.",
            false),

    /**
     * An instrument was detached (`P5-TSK-005`). The row survives as evidence (`P5-TSK-004`);
     * this record names who ended it and when.
     *
     * <p>No reason required: removing one's own convenience needs no justification (the
     * {@code transfers.BeneficiaryRemoved} reasoning). Emitted by the winning detach only — a
     * converged retry and a stranger's attempt moved nothing and record nothing.
     */
    PAYMENT_METHOD_DETACHED(
            "paymentmethods.PaymentMethodDetached",
            "A party detached a payment instrument; the detached row survives as evidence.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    PaymentmethodsAuditAction(String code, String description, boolean requiresReason) {
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
