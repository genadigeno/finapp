package com.finapp.ledger;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The failures this module reports to a client (`P3-TSK-017`).
 *
 * <p>Namespaced {@code ledger.*} ({@code ERROR_CONTRACT.md} §4) and permanent: a client's
 * error handling is written against these strings, so one is deprecated rather than renamed.
 * <strong>No title and no detail ever names an amount</strong> ({@code INV-AUD-02}): which
 * numbers were involved is the caller's own request.
 */
@RequiredArgsConstructor
public enum LedgerErrorCode implements ErrorCode {

    /**
     * The adjustment's lines do not balance per currency ({@code INV-LED-01}).
     *
     * <p>A {@code 422}: the lines are values the caller chose and must correct (the
     * {@code P1-TSK-026} reasoning). Which currency is unbalanced is deliberately not
     * echoed — the caller holds their own request, and a detail that grew specific is how
     * an error body starts carrying amounts.
     */
    UNBALANCED_ADJUSTMENT(
            "ledger.UnbalancedAdjustment",
            422,
            "The adjustment's debits and credits must be equal per currency, at one scale."),

    /**
     * A line names an account the chart does not have, or a currency foreign to that
     * account ({@code INV-MON-02}; `V005`'s composite binding).
     *
     * <p>A {@code 422}, one code for both causes: an operator holding {@code LEDGER_ADJUST}
     * reads the chart anyway, so no oracle is opened, and the two causes have one remedy —
     * name a real account in its own currency.
     */
    UNKNOWN_ACCOUNT(
            "ledger.UnknownAccount",
            422,
            "A line names an unknown ledger account, or a currency foreign to it."),

    /**
     * The account stopped accepting postings (`P3-TSK-014`: not {@code ACTIVE}).
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong> in the
     * {@code P1-TSK-018} sense: the request was well-formed and the world's state refuses
     * it — an adjustment against a closed account is corrected by adjusting a different
     * account, not by retrying this one.
     */
    ACCOUNT_NOT_POSTABLE(
            "ledger.AccountNotPostable",
            409,
            "The account no longer accepts postings."),

    /**
     * The initiator tried to approve their own proposal (`P3-TSK-021`,
     * {@code INV-AUD-04}).
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong> in the
     * {@code P1-TSK-018} sense: the proposal is fine and still standing — have a second
     * authorised person approve it. Nothing was written.
     */
    SELF_APPROVAL_REFUSED(
            "ledger.SelfApprovalRefused",
            409,
            "An adjustment requires a second approver distinct from its initiator."),

    /**
     * A decision named a proposal that is already decided (`P3-TSK-021`,
     * {@code INV-LIFE-04}).
     *
     * <p>One code for both surfaces (approve and reject), because the remedy is one: read
     * the proposal to learn its outcome, and raise a <em>new</em> proposal for a new
     * adjustment. The converging cases — the same approver's retry, a repeated rejection —
     * never reach this code; what it names is a genuinely different decision than the one
     * recorded.
     */
    PROPOSAL_NOT_OPEN(
            "ledger.ProposalNotOpen",
            409,
            "The adjustment proposal is already decided; a new adjustment is a new"
                    + " proposal."),

    /**
     * A generic adjustment named a reconciled position (`P8-TSK-006`, ADR-0071,
     * {@code INV-REC-06}).
     *
     * <p>A {@code 422}: the line set is the caller's to correct, and the remedy is not a
     * retry against this surface at all — value in a clearing or suspense account moves
     * only through a reconciliation break's four-eyes resolution. Which position is named
     * (the purpose, an enumerated word); which account and amount are not.
     */
    ADJUSTMENT_ON_RECONCILED_POSITION(
            "ledger.AdjustmentOnReconciledPosition",
            422,
            "A reconciled position cannot be adjusted here; value there moves only through"
                    + " a reconciliation break's resolution."),

    /**
     * A decision reached a proposal another door owns (`P8-TSK-006`, ADR-0071 §6).
     *
     * <p>A {@code 409} and actionable in the {@code P1-TSK-018} sense: the proposal is fine
     * and still standing — it is simply not this surface's to decide. A reconciliation
     * proposal is decided by the resolution flow that also moves the break, and a manual
     * one by the generic four-eyes door.
     */
    ADJUSTMENT_ORIGIN_MISMATCH(
            "ledger.AdjustmentOriginMismatch",
            409,
            "The proposal belongs to another origin's door and cannot be decided here."),

    /**
     * The entry is dated on or before the posting cutoff - in a locked accounting period (`P14-TSK-004`, ADR-0114,
     * {@code INV-ACC-03}, {@code INV-ACC-08}).
     *
     * <p>A {@code 409} and actionable in the {@code P1-TSK-018} sense: the request is well-formed and the world's
     * state refuses it - a correction of a locked period lands in the open period (a prior-period adjustment after
     * close), or the period is unlocked under four eyes. Nothing was written. Which date and which period are not
     * echoed - the caller holds its own request.
     */
    PERIOD_CLOSED(
            "ledger.PeriodClosed",
            409,
            "The posting date is in a locked accounting period; post in the open period."),

    /**
     * Nothing to rebuild (`P14-TSK-026`, ADR-0119): the account's projection reads what its journal and holds derive, or
     * an entry committed to it mid-reading. A {@code 409}: read again; nothing was written.
     */
    PROJECTION_NOT_DRIFTING(
            "ledger.ProjectionNotDrifting",
            409,
            "The account's balance projection agrees with its journal, or was posted to while being read; nothing to rebuild."),

    /**
     * A rebuild of the account already awaits a decision (`P14-TSK-026`; V031's one standing rebuild per account). A
     * {@code 409}: decide that one first.
     */
    PROJECTION_REBUILD_STANDING(
            "ledger.ProjectionRebuildStanding",
            409,
            "A rebuild of this account's balance projection already awaits a decision."),

    /**
     * The account's projection cannot be rebuilt by this door (`P14-TSK-026`): an underivable history, a row at a scale its
     * history does not have, or a row standing for no entries - each the migrator's to correct. A {@code 409}.
     */
    PROJECTION_NOT_REBUILDABLE(
            "ledger.ProjectionNotRebuildable",
            409,
            "The account's balance projection cannot be rebuilt here; its history or its row's scale needs the migrator."),

    /**
     * The proposer tried to approve their own rebuild (`P14-TSK-026`, {@code INV-AUD-04}). A {@code 409}: the rebuild
     * stands for a second person; nothing was written.
     */
    REBUILD_SELF_APPROVAL_REFUSED(
            "ledger.RebuildSelfApprovalRefused",
            409,
            "A projection rebuild requires a second approver distinct from its proposer."),

    /**
     * A decision named a rebuild already decided differently (`P14-TSK-026`). A {@code 409}: read it for its outcome; a
     * later drift is a new proposal.
     */
    REBUILD_NOT_OPEN(
            "ledger.RebuildNotOpen",
            409,
            "The projection rebuild is already decided; a later drift is a new proposal.");

    private final String code;
    private final int status;
    private final String title;

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
