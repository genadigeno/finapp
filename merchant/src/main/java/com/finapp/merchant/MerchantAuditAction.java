package com.finapp.merchant;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The merchant module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning (`P6-TSK-003`) — the deliberately-few licence. The
 * key's issuance and revocation arrive with `P6-TSK-002`; the destination flow's with
 * `P6-TSK-011`; the payout's with `P6-TSK-012`.
 */
@RequiredArgsConstructor
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
            true),

    /**
     * An operator created a named pricing identity (`P6-TSK-004`). No reason required: a named
     * container carries no price, and the judgement worth reasoning about is the
     * {@link #FEE_SCHEDULE_VERSION_CREATED version} — the {@code MERCHANT_API_KEY_ISSUED}
     * split, for the same reason.
     */
    FEE_SCHEDULE_CREATED(
            "merchant.FeeScheduleCreated",
            "An operator created a fee schedule; the record names the schedule by identifier"
                    + " with its name and currency.",
            false),

    /**
     * An operator set what the platform charges, effective forward (`P6-TSK-004`, ADR-0050).
     * <strong>Reasoned, always</strong> ({@code INV-AUD-03}): a price change is a commercial
     * judgement, it is irreversible in the sense that matters — the version can never be
     * edited, only superseded — and an unexplained one is exactly what a reviewer reading a
     * disputed merchant statement needs explained.
     */
    FEE_SCHEDULE_VERSION_CREATED(
            "merchant.FeeScheduleVersionCreated",
            "An operator created a fee schedule version - immutable, effective forward; the"
                    + " record names the version by identifier with its terms; the reason is"
                    + " required.",
            true),

    /**
     * An operator changed which schedule prices a merchant (`P6-TSK-004`). Reasoned: it
     * changes a counterparty's commercial terms. Emitted by the moving call only — an
     * assignment that converges on the schedule the merchant is already on changed nothing,
     * and records nothing.
     */
    MERCHANT_FEE_SCHEDULE_ASSIGNED(
            "merchant.MerchantFeeScheduleAssigned",
            "An operator assigned a merchant to a fee schedule; the record names the merchant"
                    + " and both schedules by identifier; the reason is required.",
            true),

    /**
     * An operator proposed where a merchant's payouts should go (`P6-TSK-011`, ADR-0056).
     * Nothing pays to it until a second operator approves and its cooling-off elapses.
     * <strong>Reasoned, always</strong> ({@code INV-AUD-03}): a destination change is the act
     * that redirects a counterparty's money. The record names the destination and the merchant
     * by identifier and <strong>never the bank reference or its suffix</strong>
     * ({@code INV-AUD-02}). Emitted by the creating call only — a keyed replay records nothing.
     */
    PAYOUT_DESTINATION_PROPOSED(
            "merchant.PayoutDestinationProposed",
            "An operator proposed a payout destination for a merchant; the record names the"
                    + " destination and the merchant by identifier, never the bank reference;"
                    + " the reason is required.",
            true),

    /**
     * A second operator, distinct from the proposer, approved the destination
     * ({@code INV-AUD-04}); its cooling-off started, and the record carries the deadline.
     */
    PAYOUT_DESTINATION_APPROVED(
            "merchant.PayoutDestinationApproved",
            "A second operator, distinct from the proposer, approved a payout destination and its"
                    + " cooling-off started; the reason is required.",
            true),

    /**
     * The proposer tried to approve their own destination and was refused — recorded as
     * {@code DENIED} in a transaction that commits nothing else, because a control with no
     * evidence that it ever refused anything is one nobody can show is working
     * ({@code INV-AUD-03}, {@code INV-AUD-04}). The attempted approval's reason is kept: it is
     * the only record of what the refused actor said they were doing.
     */
    PAYOUT_DESTINATION_APPROVAL_REFUSED(
            "merchant.PayoutDestinationApprovalRefused",
            "The proposer of a payout destination tried to approve it and was refused"
                    + " (INV-AUD-04); recorded as DENIED with the attempted reason.",
            true),

    /** The second pair of eyes said no: {@code PROPOSED → REJECTED}, terminal. */
    PAYOUT_DESTINATION_REJECTED(
            "merchant.PayoutDestinationRejected",
            "An operator rejected a proposed payout destination - terminal; the reason is"
                    + " required.",
            true),

    /**
     * An operator withdrew a change before it took effect — during the proposal, or during the
     * cooling-off, which is the act that makes the cooling-off a control. Terminal.
     */
    PAYOUT_DESTINATION_WITHDRAWN(
            "merchant.PayoutDestinationWithdrawn",
            "An operator withdrew a payout destination change before it took effect - terminal;"
                    + " the reason is required.",
            true),

    /**
     * The platform made an approved destination effective once its cooling-off elapsed, and
     * superseded the previous one in the same transaction. The platform's own act, through the
     * effectuation sweep's enumerated {@code enterSystem()} site — no reason, because no person
     * decided anything at that moment; the decisions are the proposal and approval records.
     */
    PAYOUT_DESTINATION_EFFECTIVE(
            "merchant.PayoutDestinationEffective",
            "The platform made an approved payout destination effective once its cooling-off"
                    + " elapsed, superseding the previous one in the same transaction; the record"
                    + " names both by identifier.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

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
