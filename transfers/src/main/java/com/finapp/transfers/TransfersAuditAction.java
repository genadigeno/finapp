package com.finapp.transfers;

import com.finapp.platform.audit.AuditableAction;

/**
 * The transfers module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * command whose design fixes their meaning — the {@code P2-TSK-005}/{@code P3-TSK-012}
 * precedent, exercised for this module by {@code P4-TSK-001}'s deliberate deferral.
 */
public enum TransfersAuditAction implements AuditableAction {

    /**
     * A transfer execution was judged: the one record per command, whatever the judgement —
     * {@code COMPLETED} with its posting or {@code FAILED} with its enumerated reason, both
     * being committed domain outcomes (ADR-0043/0044). The status and reason travel in the
     * change summary as enumerated names; the amount never does ({@code INV-AUD-02}).
     *
     * <p>No reason required: executing a transfer is a person's own act with their own money
     * (the {@code accounts.AccountOpened} reasoning), and the {@code FAILED} case's "why" is
     * the enumerated {@code FailureReason} on the row itself, not free prose. Emitted by
     * {@code TransferExecution} in the execution transaction, by the executing call only — a
     * replayed retry is not a second act.
     */
    TRANSFER_EXECUTED(
            "transfers.TransferExecuted",
            "A transfer execution was judged: COMPLETED with its posting or FAILED with its"
                    + " enumerated reason; the record names the transfer, the accounts and the"
                    + " outcome, never an amount.",
            false),

    /**
     * A destination was saved (`P4-TSK-007`). The record is the trail creating a destination
     * leaves — the act where an account takeover monetises, which is why the surface demands
     * the second factor of an enrolled identity — naming the beneficiary and the destination
     * account by identifier, <strong>never the display name</strong> ({@code RESTRICTED-PII},
     * {@code INV-AUD-02}).
     *
     * <p>No reason required: saving a destination is a person's own act (the
     * {@code consent.ConsentGranted} reasoning). Emitted by the creating call only — a
     * converged retry saved nothing and records nothing.
     */
    BENEFICIARY_ADDED(
            "transfers.BeneficiaryAdded",
            "A party saved a transfer destination; the record names the beneficiary and the"
                    + " destination account by identifier, never the display name.",
            false),

    /**
     * A saved destination was removed (`P4-TSK-007`). The row survives as evidence
     * (`P4-TSK-006`); this record names who ended it and when.
     *
     * <p>No reason required: removing one's own convenience needs no justification (the
     * {@code consent.ConsentWithdrawn} reasoning). Emitted by the winning removal only — a
     * converged retry and a stranger's attempt moved nothing and record nothing.
     */
    BENEFICIARY_REMOVED(
            "transfers.BeneficiaryRemoved",
            "A party removed a saved transfer destination; the removed row survives as"
                    + " evidence.",
            false),

    /**
     * A completed transfer was reversed by an operator (`P4-TSK-009`): {@code COMPLETED ->
     * REVERSED} with the new referencing entry, the original left byte-identical
     * ({@code INV-REV-01/-02}).
     *
     * <p><strong>Reason required</strong> (`PHASE_4_PLAN.md` §11): the reversal is a privileged
     * act taken over somebody else's money, and a quiet one is how an accomplice undoes a
     * customer's transfer — the justification enters the trail at the moment the operator
     * writes it, made structurally mandatory by {@code AuditRecord}'s own constructor. The
     * summary names the transfer and both entries by identifier, never an amount
     * ({@code INV-AUD-02}). Emitted by {@code TransferReversal} in the reversal transaction —
     * the winning move only, since the loser of a concurrent race writes nothing at all.
     */
    TRANSFER_REVERSED(
            "transfers.TransferReversed",
            "An operator reversed a completed transfer with a recorded reason; the record"
                    + " names the transfer, the original entry and the reversal entry, never"
                    + " an amount.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    TransfersAuditAction(String code, String description, boolean requiresReason) {
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
