package com.finapp.lending;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The lending module's error codes (`P11-TSK-004`, `P11-TSK-005`, `P11-TSK-010`, `P11-TSK-011`, `P11-TSK-012`,
 * `P11-TSK-021`; PHASE_11_PLAN.md section 9): the capital and terms administrations' refusals, the capital shortfall its
 * headroom judges, the loan application's refusals, the offer's and the acceptance's, and the draw's. Each is actionable by the caller who met it; none names an amount, a rate, a reference or a
 * balance.
 */
@RequiredArgsConstructor
public enum LendingErrorCode implements ErrorCode {

    /** No lending record has the identifier. */
    NOT_FOUND("lending.NotFound", 404, "No lending record matches the requested identifier."),

    /**
     * Four eyes is two people ({@code INV-AUD-04}): the approver proposed the contribution or the terms version. The
     * title read "A contribution is approved ..." until `P11-TSK-005` gave the code its second subject; the code is
     * unchanged.
     */
    SELF_APPROVAL_REFUSED(
            "lending.SelfApprovalRefused",
            403,
            "A proposal is approved by someone other than its proposer; the proposer may reject it."),

    /** A contribution of the currency already awaits a decision. */
    PROPOSAL_PENDING(
            "lending.ProposalPending", 409, "A proposal already awaits a decision; decide or reject it first."),

    /** The contribution was already decided - by somebody else, or differently. */
    PROPOSAL_NOT_PENDING(
            "lending.ProposalNotPending", 409, "The proposal is no longer awaiting a decision."),

    /** A proposal or a decision carries no reason, one past its bound, or one holding an instrument shape. */
    REASON_REQUIRED("lending.ReasonRequired", 422, "A reason is required."),

    /**
     * A contribution is not well formed - a currency the platform does not support, an amount that is not positive or
     * not at the currency's scale, or a reference outside its family or already used.
     */
    CONTRIBUTION_INVALID("lending.ContributionInvalid", 422, "The contribution is not well formed."),

    /**
     * The loan terms version was already decided - activated or rejected by somebody else, or differently - the loser
     * of a decision race (`P11-TSK-005`, credit's {@code PolicyStale}).
     */
    TERMS_STALE("lending.TermsStale", 409, "The terms version is no longer awaiting a decision."),

    /**
     * A terms proposal is refused whole (`P11-TSK-005`, {@code INV-LND-09}): a convention no engine version implements
     * (a rate type, rounding mode, day count, or zone), an allocation order omitting or repeating a component, another
     * product's terms, an unregistered engine version or agreement template, or a value outside the bounds lending or the
     * product declares. Its second subject (`P11-TSK-027`): an amendment refused whole - a credit line (more limit is a new
     * decision), a loan not yet scheduled, a higher rate, a shorter remaining term, a whole term past the product's, a change
     * of nothing, nothing outstanding to restructure, or a schedule the engine refuses.
     */
    TERMS_INVALID("lending.TermsInvalid", 422, "The terms are not complete or not within bounds."),

    /**
     * The terms cannot amortise at the product's declared amount and term bounds - some period's principal portion
     * would not be positive (ADR-0092 section 3, ADR-0093 section 5). Catalogued by `P11-TSK-005`; met from the terms
     * proposal door once `P11-TSK-006` wires the schedule engine that judges it.
     */
    TERMS_NOT_AMORTISING(
            "lending.TermsNotAmortising",
            422,
            "The terms cannot amortise across the product's amount and term bounds; nothing was proposed."),

    /**
     * The act would deploy more principal than the currency's recognised lending capital leaves free
     * ({@code INV-LND-13}): headroom judged under the capital row lock. Declared by `P11-TSK-004`; met from the loan
     * acceptance (`P11-TSK-012`), the draw (`-021`) and the reversal that re-instates principal (`-020`).
     */
    CAPITAL_UNAVAILABLE(
            "lending.CapitalUnavailable",
            422,
            "There is not enough recognised lending capital for this act; nothing was done."),

    /**
     * The party already holds an open loan application - or an open credit decision request - for the product
     * (`P11-TSK-010`): credit's one-open-request partial unique the arbiter, lending's one-open partial unique the
     * backstop. Wait for its decision, or withdraw it, and retry.
     */
    APPLICATION_OPEN(
            "lending.ApplicationOpen",
            409,
            "An application for this product is already open; wait for its decision or withdraw it."),

    /**
     * The application can no longer be withdrawn (`P11-TSK-010`): it is decided or closed, or credit has evaluated its
     * decision request - the decision will arrive, and the customer may decline the offer.
     */
    APPLICATION_NOT_WITHDRAWABLE(
            "lending.ApplicationNotWithdrawable", 409, "The application can no longer be withdrawn."),

    /**
     * No terms version of the product is {@code ACTIVE} (`P11-TSK-010`; L11): the product is not offered - production's
     * state until the activation gate opens ({@code PHASE_11_PLAN.md} section 1.1).
     */
    PRODUCT_NOT_OFFERED("lending.ProductNotOffered", 422, "The product is not offered."),

    /**
     * The amount or limit, the term, or the repayment or statement day is outside the product's bounds
     * (`P11-TSK-010`): the figures judged by credit's product (the one truth for them), the day by the product's
     * {@code ACTIVE} terms version. Nothing was submitted.
     */
    AMOUNT_OUT_OF_RANGE(
            "lending.AmountOutOfRange",
            422,
            "The amount, limit, term or repayment day is outside the product's bounds; nothing was submitted."),

    /**
     * The offer can no longer be acted on (`P11-TSK-011`): it is declined, expired - on the database clock, swept or
     * not - or accepted. First met from the decline ({@code LENDING_LIFECYCLES.md} section 4.4); the acceptance
     * (`P11-TSK-012`) answers it too.
     */
    OFFER_NOT_ACCEPTABLE("lending.OfferNotAcceptable", 409, "The offer can no longer be accepted or declined."),

    /**
     * The acceptance echoed a terms hash that is not the offer's (`P11-TSK-012`, ADR-0092 point 6, {@code INV-LND-05}):
     * the customer accepted terms other than the ones recorded - nothing was written. Read the offer again and accept
     * what it shows. Its second subject (`P11-TSK-027`): an amendment's acceptance echoing a document hash that is not the
     * amendment's - nothing was amended; the title is unchanged (the {@code SelfApprovalRefused} precedent).
     */
    TERMS_CHANGED("lending.TermsChanged", 409, "The terms accepted are not the offer's; nothing was accepted."),

    /**
     * The payout destination is not one the acceptance can take (`P11-TSK-012`, ADR-0097, A25, {@code INV-RAIL-03}): an
     * external account was preferred and none named, one was named for a wallet payout or a credit line, or the one named
     * is not the customer's own live bank account. Refused before anything was written.
     */
    PAYOUT_DESTINATION_INVALID(
            "lending.PayoutDestinationInvalid",
            422,
            "The payout destination is not the customer's own verified bank account, or is not expected here."),

    /**
     * The draw exceeds the line's available limit (`P11-TSK-021`, {@code INV-LND-14}): the limit less the drawn principal,
     * derived from journal lines under the line's row lock - zero once the drawn principal reaches the limit. Nothing was
     * drawn; a principal repayment restores the limit.
     */
    LIMIT_EXCEEDED("lending.LimitExceeded", 422, "The draw exceeds the line's available limit; nothing was drawn."),

    /**
     * The line's draws are suspended (`P11-TSK-021`; A25, ADR-0098): an amount is past due or the line is defaulted - a
     * derived condition, read under the line's row lock. Nothing was drawn; bring the line up to date, and draws resume.
     */
    DRAWS_SUSPENDED(
            "lending.DrawsSuspended",
            409,
            "The line's draws are suspended while an amount is past due or the line is in default; nothing was drawn."),

    /**
     * The account cannot be drawn (`P11-TSK-021`): it is an instalment loan, or a credit line no longer {@code ACTIVE} - a
     * closing or closed line takes no further draws (ADR-0100 point 8). Nothing was drawn.
     */
    LINE_NOT_DRAWABLE(
            "lending.LineNotDrawable", 409, "The account is not a credit line open for draws; nothing was drawn."),

    /**
     * The borrower's wallet cannot receive the draw now (`P11-TSK-021`; A25 - a line pays out to the wallet only): the
     * wallet cannot be had, or the ledger refuses to post to it. Nothing was drawn and the key is not spent; retry once
     * the wallet is available. Its second subject (`P11-TSK-020`): an approved repayment reversal whose refund the wallet
     * cannot take now (not {@code ACTIVE}) - nothing was posted and the proposal stays {@code PROPOSED}. The title read
     * "The wallet cannot receive the draw now; nothing was drawn." until then; the code is unchanged (the
     * {@code SelfApprovalRefused} precedent).
     */
    WALLET_UNAVAILABLE(
            "lending.WalletUnavailable", 409, "The wallet cannot receive the money now; nothing was posted."),

    /**
     * The account takes no repayment (`P11-TSK-018`; {@code INV-LND-08}): it is {@code CLOSED} or {@code CANCELLED}, or not
     * yet disbursed. Nothing was posted. (Until `P11-TSK-023` a credit line answered this too; a line now takes
     * repayments while {@code ACTIVE} or {@code CLOSING}.)
     */
    LOAN_NOT_REPAYABLE("lending.LoanNotRepayable", 409, "The loan cannot be repaid; nothing was posted."),

    /**
     * The repayment cannot be funded (`P11-TSK-018`; ADR-0095 point 4, {@code INV-BAL-04}): the wallet's available balance,
     * judged under its row lock, is below the amount - or the wallet cannot be debited now. Nothing was posted and the key
     * is not spent; retry with a smaller amount or once funds are available.
     */
    INSUFFICIENT_FUNDS(
            "lending.InsufficientFunds",
            422,
            "The wallet's available balance cannot fund the repayment; nothing was posted."),

    /**
     * The loan is closed (`P11-TSK-020`; A12, {@code INV-LIFE-04}, ADR-0099 point 4): a closed account is terminal, so no
     * repayment of it - the one that closed it, or any earlier one - is reversed. Nothing was proposed or posted; a
     * proposal already standing may still be rejected. Its second subject (`P11-TSK-023`): a closure request for a line
     * already {@code CLOSED} - nothing was done. (A repayment reversal is also refused this code on a {@code CLOSING} line
     * - `P11-TSK-020`'s rule admits only an {@code ACTIVE} account, recorded as an owner judgement.)
     */
    LOAN_CLOSED("lending.LoanClosed", 409, "The loan is closed; nothing was done."),

    /**
     * An operator acted on a loan of their own party (`P11-TSK-020`; ADR-0099 point 2, the Phase 10 to 11 transition's R1,
     * {@code INV-LND-11}): an employee never services their own party's loan. Refused before anything was written, and
     * the attempt recorded {@code FAILED} ({@code lending.SelfDealingRefused}).
     */
    SELF_DEALING_REFUSED(
            "lending.SelfDealingRefused", 403, "An employee never services a loan of their own party; nothing was done."),

    /**
     * The repayment cannot be reversed now (`P11-TSK-020`; ADR-0099 point 4, {@code INV-REV-02}): it is already reversed,
     * or what it held as the loan's credit balance has since been applied - the exact swap would drive the credit balance
     * below zero, and later allocations are never re-cut. Nothing was proposed or posted.
     */
    REPAYMENT_NOT_REVERSIBLE(
            "lending.RepaymentNotReversible", 409, "The repayment cannot be reversed; nothing was posted."),

    /**
     * The account cannot be closed by request (`P11-TSK-023`; ADR-0100 point 8, {@code INV-LIFE-04}): it is an instalment
     * loan, which closes only by being repaid - a closure is a consequence of its last receivable settling, never a
     * command. A closed line answers {@link #LOAN_CLOSED}. Nothing was done.
     */
    LINE_NOT_CLOSABLE(
            "lending.LineNotClosable", 409, "The account is not a credit line that can be closed by request; nothing was done."),

    /**
     * The waiver exceeds what is due now (`P11-TSK-025`; A14, ADR-0099, {@code INV-LND-11}, {@code INV-REV-02}): a fee waiver
     * above the fee's unpaid remainder, a refund above what was paid of it less what was refunded, an interest waiver above
     * the item's unpaid billed interest - derived under the loan's row lock from the rows the ledger is proven against. A
     * proposal is not born; an approval posts nothing and leaves the proposal {@code PROPOSED}.
     */
    WAIVER_EXCEEDS_DUE(
            "lending.WaiverExceedsDue", 422, "The waiver exceeds what is due now; nothing was posted."),

    /**
     * The payoff quote no longer ends the account (`P11-TSK-026`; ADR-0095 point 8, scenario 7, {@code INV-LND-08}): it is
     * executed on a date other than its good-through date, or what the account owes moved since it was made - a repayment,
     * an automatic collection, a draw, a reversal or a fee committed first. The amount is never adjusted: nothing was
     * posted; request a new quote.
     */
    PAYOFF_QUOTE_STALE(
            "lending.PayoffQuoteStale", 409, "The payoff quote no longer ends the account; nothing was posted."),

    /**
     * The amendment can no longer be approved or accepted (`P11-TSK-027`; ADR-0099 point 5, scenario 8, {@code INV-LND-05}):
     * the agreement version it amends is no longer the loan's - another amendment or a prepayment moved it - the loan is
     * no longer {@code ACTIVE} (a payoff closed it), or its offer expired unaccepted. Nothing was written; a new proposal
     * against the agreement in force is the act that fits.
     */
    AMENDMENT_LAPSED(
            "lending.AmendmentLapsed", 409, "The amendment has lapsed; nothing was amended.");

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
