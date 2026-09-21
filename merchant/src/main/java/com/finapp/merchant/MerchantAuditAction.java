package com.finapp.merchant;

import com.finapp.platform.audit.AuditableAction;

/**
 * The merchant module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning (`P6-TSK-003`) — the deliberately-few licence. The
 * key's issuance and revocation arrive with `P6-TSK-002`; the destination flow's with
 * `P6-TSK-011`; the payout's with `P6-TSK-012`.
 */
public enum MerchantAuditAction implements AuditableAction {

    /**
     * An operator onboarded a merchant: the commercial relationship and its payable ledger
     * account committed together. The record names the merchant, the party and the payable
     * account by identifier ({@code INV-AUD-02}). Emitted by the creating call only — an
     * idempotent replay created nothing and records nothing.
     */
    MERCHANT_ONBOARDED(
            "merchant.MerchantOnboarded",
            "An operator onboarded a merchant; the record names the merchant, its organisation"
                    + " party and its payable ledger account by identifier.",
            false),

    /**
     * An operator froze new business ({@code ACTIVE → SUSPENDED}). Reasoned, always: a
     * suspension is a judgement about a counterparty, and a judgement without its reasoning
     * cannot be reviewed or reversed responsibly ({@code INV-AUD-03}).
     */
    MERCHANT_SUSPENDED(
            "merchant.MerchantSuspended",
            "An operator suspended a merchant - new dispatches refuse, landed money still"
                    + " lands; the reason is required.",
            true),

    /** An operator lifted the freeze ({@code SUSPENDED → ACTIVE}). Reasoned, symmetrically. */
    MERCHANT_REINSTATED(
            "merchant.MerchantReinstated",
            "An operator reinstated a suspended merchant; the reason is required.",
            true),

    /**
     * An operator ended the relationship ({@code ACTIVE → CLOSED}, terminal). Reasoned: the
     * books survive the relationship ({@code INV-HIST-01}), and so must the why.
     */
    MERCHANT_CLOSED(
            "merchant.MerchantClosed",
            "An operator closed a merchant - terminal; the payable position and its history"
                    + " remain; the reason is required.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    MerchantAuditAction(String code, String description, boolean requiresReason) {
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
