package com.finapp.bnpl;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The bnpl module's error codes (`P12-TSK-004`; {@code PHASE_12_PLAN.md} section 9, {@code BNPL_LIFECYCLES.md} section
 * 4.1): the terms administration's refusals and the closed product's - each actionable by the caller who met it; none
 * names an amount, a rate or a person. Later tasks add their own beside these.
 */
@RequiredArgsConstructor
public enum BnplErrorCode implements ErrorCode {

    /** No bnpl record has the identifier. */
    NOT_FOUND("bnpl.NotFound", 404, "No BNPL record matches the requested identifier."),

    /** Four eyes is two people ({@code INV-AUD-04}): the approver proposed the terms version. */
    SELF_APPROVAL_REFUSED(
            "bnpl.SelfApprovalRefused",
            403,
            "A proposal is approved by someone other than its proposer; the proposer may reject it."),

    /** A terms version of the product already awaits a decision. */
    PROPOSAL_PENDING("bnpl.ProposalPending", 409, "A proposal already awaits a decision; decide or reject it first."),

    /**
     * The terms version was already decided - activated or rejected by somebody else, or differently - the loser of a
     * decision race (lending's {@code TermsStale}).
     */
    TERMS_STALE("bnpl.TermsStale", 409, "The terms version is no longer awaiting a decision."),

    /** A proposal or a decision carries no reason, one past its bound, or one holding an instrument shape. */
    REASON_REQUIRED("bnpl.ReasonRequired", 422, "A reason is required."),

    /**
     * A terms proposal is refused whole: a convention no engine version implements, a plan the product does not list or
     * credit cannot decide, another product's plan terms, a non-zero pay-in-4 rate, an allocation order omitting or
     * repeating a component, an unregistered engine version or agreement template, or a value outside the bounds bnpl
     * declares.
     */
    TERMS_INVALID("bnpl.TermsInvalid", 422, "The terms are not complete or not within bounds."),

    /**
     * Some purchase within credit's amount and plan bounds could not be scheduled under the terms - a pay-in-N instalment
     * repaying no principal (the schedule engine's judgement with the Phase 11 gate's half-unit margin), or a pay-in-4
     * instalment of nothing.
     */
    TERMS_NOT_AMORTISING(
            "bnpl.TermsNotAmortising",
            422,
            "The terms cannot schedule every purchase within the product's bounds; nothing was proposed."),

    /**
     * No terms version of the product is {@code ACTIVE}, so it is not offered (ADR-0102 decision 4, the production-closed
     * rule): met from the application door (`P12-TSK-010`) through {@code BnplTermsAdministration#pinActive}.
     */
    PRODUCT_NOT_OFFERED("bnpl.ProductNotOffered", 422, "The BNPL product is not offered."),

    /**
     * The checkout session already has an open BNPL application, or the customer an open application - or credit
     * request - for the product (`P12-TSK-010`; {@code INV-BNPL-03}).
     */
    APPLICATION_OPEN(
            "bnpl.ApplicationOpen",
            409,
            "A BNPL application is already open for this checkout or product; wait for its decision or withdraw it."),

    /** The application is past withdrawal - decided or closed, or credit has evaluated its request (`P12-TSK-010`). */
    APPLICATION_NOT_WITHDRAWABLE(
            "bnpl.ApplicationNotWithdrawable", 409, "The BNPL application can no longer be withdrawn."),

    /**
     * The checkout's merchant may not offer the product now - not trading, not BNPL-enabled, or the product not priced
     * (`P12-TSK-010`). Never which: the customer cannot act on the merchant's configuration.
     */
    MERCHANT_NOT_ENABLED("bnpl.MerchantNotEnabled", 422, "The merchant does not offer this BNPL product."),

    /**
     * The checkout session cannot be financed: no open, unexpired session answers the token, it is not in the product's
     * currency, or the sale does not cover the merchant's fee (`P12-TSK-010`).
     */
    SESSION_NOT_ELIGIBLE("bnpl.SessionNotEligible", 422, "The checkout session is not eligible for BNPL."),

    /** The purchase amount is outside the product's bounds - credit's (`P12-TSK-010`). The detail names the bound. */
    AMOUNT_OUT_OF_RANGE("bnpl.AmountOutOfRange", 422, "The purchase amount is outside the BNPL product's bounds."),

    /**
     * The offer is no longer open to the act - already declined, expired (its {@code expires_at} at or before the
     * database clock, though the sweep may not have recorded it yet) or accepted (`P12-TSK-011`, first raised by the
     * decline; the acceptance's too, `P12-TSK-013`).
     */
    OFFER_NOT_ACCEPTABLE("bnpl.OfferNotAcceptable", 409, "The BNPL offer is no longer open: it was declined, expired or"
            + " accepted."),

    /** The terms hash the customer echoed is not the offer's (`P12-TSK-013`): read the offer and accept what it shows. */
    TERMS_CHANGED("bnpl.TermsChanged", 409, "The terms accepted are not the BNPL offer's."),

    /**
     * The checkout session can no longer be paid by the purchase (`P12-TSK-013`): a card or wallet confirmation won it,
     * it completed, ended or expired on the database clock - judged at once under its row lock, nothing else taken.
     */
    SESSION_NOT_PAYABLE("bnpl.SessionNotPayable", 409, "The checkout session can no longer be paid by this BNPL offer."),

    /**
     * The currency's recognised lending capital cannot fund the purchase now (`P12-TSK-013`; {@code INV-BNPL-10},
     * {@code INV-LND-13}) - judged under the capital row's lock; the checkout may fall back to another payment method.
     */
    CAPITAL_UNAVAILABLE("bnpl.CapitalUnavailable", 422, "The BNPL purchase cannot be funded now."),

    /**
     * The wallet cannot fund the payment (B6, the wallet the only source): pay-in-4's first instalment, collected at purchase
     * (`P12-TSK-013`) - nothing committed, the customer may top up and accept the same offer again before it expires - or a
     * repayment (`P12-TSK-018`) - nothing posted. An absent wallet, or one not {@code ACTIVE}, is folded in (the Phase 11
     * precedent).
     */
    INSUFFICIENT_FUNDS("bnpl.InsufficientFunds", 422, "The wallet cannot fund the payment."),

    /**
     * The BNPL agreement is {@code CLOSED} (`P12-TSK-018`; {@code INV-LIFE-04}): a repayment - later a collection, a payoff
     * or a fee - on it is refused, nothing posted.
     */
    OBLIGATION_CLOSED("bnpl.ObligationClosed", 409, "The BNPL agreement is closed."),

    /**
     * The payoff quote no longer ends the agreement (`P12-TSK-022`; T-pay, scenario 11): executed on a date other than its
     * good-through date, or what the agreement owes moved since it was quoted (a refund, a repayment, a collection, a fee or
     * a new agreement version committed first) - recomputed under the obligation's lock and refused rather than adjusted;
     * nothing was posted. A new quote answers the new figure.
     */
    PAYOFF_QUOTE_STALE("bnpl.PayoffQuoteStale", 409, "The payoff quote is stale."),

    /**
     * The refund exceeds what remains of the purchase - {@code R > G - refunded} (`P12-TSK-021`; {@code INV-BNPL-07}),
     * judged under the purchase's row lock; nothing was written. The detail never names the remainder.
     */
    REFUND_EXCEEDS_PURCHASE(
            "bnpl.RefundExceedsPurchase", 422, "The refund exceeds what remains of the BNPL purchase."),

    /** The purchase is already {@code FULLY_REFUNDED} (`P12-TSK-021`): terminal, nothing more to refund; nothing written. */
    PURCHASE_FULLY_REFUNDED("bnpl.PurchaseFullyRefunded", 409, "The BNPL purchase is already fully refunded."),

    /**
     * The merchant's API key was not issued with the {@code BNPL_REFUND} scope (`P12-TSK-021`; ADR-0105 decision 9): a key
     * issued before the scope existed carries none (fails closed). Recorded {@code FAILED}; nothing else written - the
     * operator who administers the merchant's keys issues one with the scope.
     */
    REFUND_SCOPE_REQUIRED(
            "bnpl.RefundScopeRequired", 403, "This API key may not refund BNPL purchases; a key with the refund scope may."),

    /**
     * The refund cannot be posted now (`P12-TSK-021`): the customer's wallet cannot take the excess of a refund on a
     * {@code CLOSED} agreement (a closed agreement holds no credit balance, OQ-15), or the merchant's payable cannot be
     * debited. Nothing was written; retry later under the same key.
     */
    REFUND_NOT_POSTABLE("bnpl.RefundNotPostable", 409, "The BNPL refund cannot be posted now; nothing was written."),

    /**
     * An operator tried to refund a BNPL purchase of their own party (`P12-TSK-021`; the self-dealing rule,
     * {@code INV-LND-11} by analogy) - or, since `P12-TSK-024`, to propose, approve or reject a repayment reversal or a
     * waiver on an agreement of their own party (fail-closed: an operator the platform cannot place in a party is refused
     * too) - refused before anything was written, the attempt recorded {@code FAILED}.
     */
    SELF_DEALING_REFUSED(
            "bnpl.SelfDealingRefused", 403, "An employee never acts on a BNPL purchase or agreement of their own party;"
                    + " nothing was done."),

    /**
     * The repayment reversal or waiver was already decided - approved or rejected by somebody else, or differently - the
     * loser of a decision race (`P12-TSK-024`; lending's {@code ProposalNotPending}). Nothing was written.
     */
    PROPOSAL_NOT_PENDING("bnpl.ProposalNotPending", 409, "The proposal is no longer awaiting a decision."),

    /**
     * The repayment cannot be reversed (`P12-TSK-024`; the Phase 11 {@code RepaymentNotReversible} precedent): it is
     * already reversed, it is a payoff's (which closed its agreement - `P12-TSK-022`), or the credit it held has since been
     * applied (the exact swap would drive the credit balance below zero). Nothing was written.
     */
    REPAYMENT_NOT_REVERSIBLE("bnpl.RepaymentNotReversible", 409, "The BNPL repayment cannot be reversed."),

    /**
     * The waiver exceeds what may still be forgiven of the fee (`P12-TSK-024`; {@code INV-REV-02}, the Phase 11 gate's
     * R3): its unpaid remainder, and never what was already paid, released by a refund or waived. Judged under the
     * obligation's lock at the proposal and again at the approval - nothing was posted, the proposal still {@code PROPOSED}.
     */
    WAIVER_EXCEEDS_DUE("bnpl.WaiverExceedsDue", 422, "The waiver exceeds what is due of the fee."),

    /**
     * The wallet the reversed repayment debited cannot be credited now - absent or not {@code ACTIVE} (`P12-TSK-024`;
     * lending's {@code WalletUnavailable}). Nothing was posted; the proposal is still {@code PROPOSED}. Since the Phase 12
     * to 13 gate (A7-1), also a dispute of a {@code CLOSED} agreement whose customer has no {@code ACTIVE} wallet - the only
     * account its refund outcome could be credited to: refused at the opening, nothing written.
     */
    WALLET_UNAVAILABLE("bnpl.WalletUnavailable", 409, "The customer's wallet cannot be credited now; nothing was posted."),

    /**
     * The purchase already has an open dispute (`P12-TSK-023`; ADR-0106 decisions 1 and 7): a second dispute is refused, and
     * so is a merchant's or an operator's refund - the merchant settles a disputed purchase by accepting the dispute.
     * Judged under the purchase's row lock; nothing was written.
     */
    DISPUTE_OPEN("bnpl.DisputeOpen", 409, "The BNPL purchase has an open dispute."),

    /**
     * The dispute is not in the act's from-state (`P12-TSK-023`; ADR-0106 decision 2): already answered, resolved or
     * withdrawn, or - for the merchant's response - its response deadline has passed on the database clock (the deadline
     * resolves it as a refund); nothing was written.
     */
    DISPUTE_NOT_ACTIONABLE("bnpl.DisputeNotActionable", 409, "The BNPL dispute is not open to this act.");

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
