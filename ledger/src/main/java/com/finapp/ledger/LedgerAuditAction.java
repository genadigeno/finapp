package com.finapp.ledger;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * What the {@code ledger} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally: actions belong to the module that performs them,
 * and {@code platform} sits below every business module. See {@code AUDITABLE_ACTIONS.md} §2.
 *
 * <p><strong>Deliberately few.</strong> The licence is {@code P1-TSK-003}'s: declare an action
 * whose <em>design</em> is already fixed, never one a later task's design will shape. A posting
 * and a manual adjustment met that bar at the skeleton ({@code PHASE_3_PLAN.md} §11,
 * {@code INV-LED-05}/{@code INV-REV-04}); the hold pair arrived with the aggregate whose design
 * fixed their meaning (`P3-TSK-015`), exactly as the skeleton's note promised. Reversals and
 * account creation stay absent on purpose: whether each is its own action, and what its record
 * carries, is decided by the task that builds it.
 *
 * <p><strong>The audit record is not the posting, and neither substitutes.</strong> The journal
 * entry is the financial fact and already carries its actor and correlation ({@code INV-LED-05});
 * the audit record is the trail of the <em>act</em> of posting, under a different retention and
 * access regime ({@code INV-AUD-01}, ADR-0010).
 *
 * <p>Every action here is emitted by its owning service. <em>(This paragraph claimed
 * {@code ADJUSTMENT_POSTED} sat in {@code AuditCompletenessTest.NOT_YET_EMITTED} until
 * `P3-TSK-021` — stale since `P3-TSK-017` emitted it; corrected where it lived.)</em>
 */
@RequiredArgsConstructor
public enum LedgerAuditAction implements AuditableAction {

    /**
     * A balanced journal entry was posted.
     *
     * <p>No reason required: a posting is commanded by a platform flow - a transfer, a payment, a
     * reversal - whose own records carry the why, and a mandatory reason on it would produce a
     * column of the command's name ({@code AUDITABLE_ACTIONS.md} §4). What makes the record
     * defensible is attribution and correlation, which {@code INV-LED-05} already requires of the
     * entry itself. The code matches the event type the same posting publishes, as
     * {@code identity.AuthenticationSucceeded} does: one fact, named once, in two registries.
     * Emitted by {@code P3-TSK-006}.
     */
    JOURNAL_ENTRY_POSTED(
            "ledger.JournalEntryPosted",
            "A balanced journal entry was posted to the ledger; the record names the entry, never"
                    + " an amount.",
            false),

    /**
     * A person posted a manual adjusting entry.
     *
     * <p><strong>Reason required</strong>, and the invariant says so in as many words:
     * {@code INV-REV-04} - every manual adjustment records a reason code and the authorising actor.
     * An adjustment is a human choosing to move value the system would not have moved by itself,
     * which makes it the highest-risk financial action on the platform and its justification the
     * only evidence it was legitimate. Four-eyes above a threshold ({@code INV-AUD-04}) is recorded
     * debt, not implied by this flag. Emitted by {@code P3-TSK-017}.
     */
    ADJUSTMENT_POSTED(
            "ledger.AdjustmentPosted",
            "A person posted a manual adjusting entry; the reason and the authorising actor are"
                    + " recorded.",
            true),

    /**
     * A person proposed a manual adjustment, awaiting a second person's approval.
     *
     * <p><strong>Reason required</strong> — {@code INV-REV-04}'s justification enters the
     * trail at the moment the initiator writes it, not at approval. This record and
     * {@code ADJUSTMENT_POSTED} are the four-eyes trail ({@code INV-AUD-04}): two acts, two
     * records, each naming its own actor — the initiator here, the approver there — which is
     * how the "second actor column" ADR-0010 anticipated dissolves rather than gets paid.
     * Emitted by {@code AdjustmentService.propose} (`P3-TSK-021`), by the creating call only.
     */
    ADJUSTMENT_PROPOSED(
            "ledger.AdjustmentProposed",
            "A person proposed a manual adjustment for a second person's approval; the reason"
                    + " and the initiator are recorded.",
            true),

    /**
     * A standing adjustment proposal was rejected — by a second person, or withdrawn by its
     * initiator.
     *
     * <p>No reason required: declining to move value is not the high-risk act the reason
     * regime exists for, and a mandatory justification for saying no would produce a column
     * of {@code "no"}. What the record carries is who declined, which is the fact an
     * investigator wants. Emitted by {@code AdjustmentService.reject} (`P3-TSK-021`), by the
     * deciding call only — a converged repeat records nothing.
     */
    ADJUSTMENT_REJECTED(
            "ledger.AdjustmentRejected",
            "A standing adjustment proposal was rejected or withdrawn; the record names the"
                    + " proposal and who declined it.",
            false),

    /**
     * A hold was placed against an account's available balance.
     *
     * <p>No reason required - the {@code JOURNAL_ENTRY_POSTED} argument verbatim: a hold is
     * commanded by a platform flow whose own records carry the why, and what makes the record
     * defensible is attribution and correlation. The code matches the event type the same
     * placement publishes: one fact, named once, in two registries. Emitted by
     * {@code HoldService.place} (`P3-TSK-015`), in the placing transaction, by the acting
     * call only.
     */
    HOLD_PLACED(
            "ledger.HoldPlaced",
            "A hold was placed against an account's available balance; the record names the"
                    + " hold and the account, never an amount.",
            false),

    /**
     * A standing hold was released and availability restored.
     *
     * <p>No reason, same argument. Emitted by {@code HoldService.release} - by the call whose
     * conditional move won, so a converged retry records nothing (`P3-TSK-015`).
     */
    HOLD_RELEASED(
            "ledger.HoldReleased",
            "A standing hold was released, restoring available balance; the record names the"
                    + " hold and the account, never an amount.",
            false),

    /**
     * A person proposed rebuilding one account's balance projection from its journal (`P14-TSK-026`, ADR-0119).
     *
     * <p><strong>Reason required</strong>: a rebuild repairs derived data the verification found drifting, and why the
     * proposer believes the journal and not the row is the record an investigator reads first. The figures the proposer
     * observed are on the rebuild's row ({@code ledger.projection_rebuild}), which this record names - never in the
     * summary ({@code INV-AUD-02}). Emitted by {@code ProjectionRebuilds.propose}, by the creating call only.
     */
    PROJECTION_REBUILD_PROPOSED(
            "ledger.ProjectionRebuildProposed",
            "A person proposed rebuilding an account's balance projection from its journal for a second person's"
                    + " approval; the reason and the proposer are recorded, the observed figures on the rebuild it names.",
            true),

    /**
     * A second person approved a projection rebuild: the projection was set to what the journal and the holds derive
     * ({@code APPLIED}), or found already so and left untouched ({@code UNCHANGED}) (`P14-TSK-026`, ADR-0119;
     * {@code INV-AUD-04}).
     *
     * <p>No reason: the proposal carries the why, and the four-eyes trail is two records each naming its own actor - the
     * {@code ADJUSTMENT_POSTED} reasoning. The summary names the account, the outcome, the applied-entry counts before and
     * after and which figures moved; the figures before and after are on the rebuild row, in the same transaction. Emitted
     * by {@code ProjectionRebuilds.approve}, by the deciding call only - a converged retry records nothing.
     */
    PROJECTION_REBUILT(
            "ledger.ProjectionRebuilt",
            "A second person approved a projection rebuild - the row set to what the journal derives, or found so and"
                    + " untouched; the record names the rebuild, the outcome and which figures moved, never an amount.",
            false),

    /**
     * A standing projection rebuild was rejected, or withdrawn by its proposer (`P14-TSK-026`).
     *
     * <p>No reason required - the {@code ADJUSTMENT_REJECTED} argument verbatim. Emitted by {@code
     * ProjectionRebuilds.reject}, by the deciding call only.
     */
    PROJECTION_REBUILD_REJECTED(
            "ledger.ProjectionRebuildRejected",
            "A standing projection rebuild was rejected or withdrawn; the record names the rebuild and who declined it.",
            false),

    /**
     * A proposer tried to approve their own projection rebuild and was refused (`P14-TSK-026`'s fix round, I-1;
     * {@code INV-AUD-04}) - accounting's {@code accounting.SelfApprovalRefused} shape: recorded {@code FAILED} in a
     * transaction of its own after the act rolled back, so the attempt survives the rollback that keeps the refusal
     * writing nothing. Its target the rebuild; the summary names it, its account and the act - never an amount.
     *
     * <p>No reason: the attempt is the fact. Emitted by {@code ProjectionRebuilds.recordRefusal}, called by the desk after
     * the refused approval's transaction rolled back - and, since {@code P14-DOC-001} (the exit review, routed by
     * `P14-TSK-026`'s fix round), by {@code AdjustmentService.recordRefusal} for a {@code MANUAL} adjustment's approval by
     * its own initiator, whose target is the proposal and whose summary names it and the act.
     */
    SELF_APPROVAL_REFUSED(
            "ledger.SelfApprovalRefused",
            "A proposer tried to approve their own projection rebuild or manual adjustment and was refused before anything"
                    + " was written; recorded FAILED in a transaction of its own after the act rolled back. The record"
                    + " names the rebuild or the proposal and the act, never an amount.",
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
