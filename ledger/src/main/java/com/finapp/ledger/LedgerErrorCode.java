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
                    + " proposal.");

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
