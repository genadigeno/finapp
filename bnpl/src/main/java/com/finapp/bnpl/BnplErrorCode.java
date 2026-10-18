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
            + " accepted.");

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
