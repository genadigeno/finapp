package com.finapp.transfers;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The transfers module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * command whose design fixes their meaning — the {@code P2-TSK-005}/{@code P3-TSK-012}
 * precedent, exercised for this module by {@code P4-TSK-001}'s deliberate deferral.
 */
@RequiredArgsConstructor
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
            true),

    /**
     * A holder of {@code TRANSFER_REVERSE} proposed a completed transfer's reversal (`P17-TSK-031`, ADR-0154): nothing moved
     * yet; a holder of {@code TRANSFER_REVERSE_APPROVE} who did not propose it decides. The approval's record is
     * {@link #TRANSFER_REVERSED}, written by the reversal itself under the approver.
     */
    REVERSAL_PROPOSED(
            "transfers.ReversalProposed",
            "A completed transfer's reversal was proposed with a reason; nothing moved until a second person approves.",
            true),

    /** A holder of {@code TRANSFER_REVERSE_APPROVE} rejected a reversal it did not propose (`P17-TSK-031`); nothing moved. */
    REVERSAL_REJECTED(
            "transfers.ReversalRejected",
            "A proposed transfer reversal was rejected by a second person, with a reason; nothing moved.",
            true),

    /**
     * A holder of {@code TRANSFER_READ} was served a page of a customer's transfers (`P17-TSK-027`, gap G-35;
     * {@code GET /v1/operator/transfers}, on a {@code MULTI_FACTOR} session; ADR-C132 decision 5): once per page, the customer
     * the target, the window, the status filter, whether it followed a cursor and how many it served the summary - never an
     * amount. Written by app's {@code RecordedReads} in the read's own transaction.
     */
    TRANSFERS_LISTED(
            "transfers.TransfersListed",
            "A page of a customer's transfers was served to an operator; the customer, the window, the filter and a count"
                    + " recorded, never an amount.",
            false),

    /**
     * A holder of {@code TRANSFER_READ} read one transfer with its journal entry's reference (`P17-TSK-027`;
     * {@code GET /v1/operator/transfers/{id}}, on a {@code MULTI_FACTOR} session): the transfer the target, its customer and
     * status the summary - never an amount or the customer's own reference text.
     */
    TRANSFER_READ(
            "transfers.TransferRead",
            "An operator read a transfer and its journal entry's reference; the transfer, its customer and status recorded,"
                    + " never an amount.",
            false),

    /** An overview reader was served the transfers report (`P17-TSK-039`, PLATFORM_OVERVIEW_READ) - totals, never a transfer. */
    TRANSFERS_REPORT_READ(
            "transfers.TransfersReportRead",
            "An overview reader was served a day's transfers report - counts and totals by outcome and currency, never a"
                    + " transfer (PLATFORM_OVERVIEW_READ); every serving is recorded, in the read's transaction.",
            false),

    /**
     * A holder of {@code TRANSFER_REVERSE_APPROVE} read a reversal proposal with the transfer it would reverse (`P17-TSK-124`;
     * {@code GET /v1/operator/transfer-reversals/{id}}, on a {@code MULTI_FACTOR} session): the proposal the target, its
     * transfer and status the summary - never an amount. Written by app's {@code RecordedReads} in the read's own transaction.
     */
    REVERSAL_PROPOSAL_READ(
            "transfers.ReversalProposalRead",
            "An approver read a transfer reversal proposal and the transfer it would reverse (TRANSFER_REVERSE_APPROVE); the"
                    + " proposal, its transfer and status recorded, never an amount; every serving, in the read's transaction.",
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
