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
            false);

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
