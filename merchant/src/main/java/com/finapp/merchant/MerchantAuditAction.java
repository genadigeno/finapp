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
    /**
     * An operator issued an API credential to a merchant (`P6-TSK-002`). The record names the
     * key by its PUBLIC id and the merchant by identifier — never the secret and never its
     * hash ({@code INV-AUD-02}), which is the reason the key id is public at all: an auditor
     * tying a later merchant action back to its issuance needs the identifier, not the
     * credential. No reason required: provisioning access a merchant has contracted for is
     * routine, and the judgement worth reasoning about is the REVOCATION.
     */
    MERCHANT_API_KEY_ISSUED(
            "merchant.MerchantApiKeyIssued",
            "An operator issued an API key to a merchant; the record names the key by its"
                    + " public id and the merchant by identifier, never the secret.",
            false),

    /**
     * An operator revoked a merchant's API credential ({@code ACTIVE → REVOKED}, terminal).
     * Reasoned, always: withdrawing a counterparty's access is a security judgement, and a
     * revoked key is never reinstated — so the why is the only record of what prompted it
     * ({@code INV-AUD-03}).
     */
    MERCHANT_API_KEY_REVOKED(
            "merchant.MerchantApiKeyRevoked",
            "An operator revoked a merchant's API key - terminal, never reinstated; the reason"
                    + " is required.",
            true),

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
