package com.finapp.fx;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The FX module's error codes (`P9-TSK-007`, `PHASE_9_PLAN.md` §9): the pricing policy's and the
 * availability switch's refusals. Each is actionable by the operator who met it; none names an
 * amount or a rate.
 */
@RequiredArgsConstructor
public enum FxErrorCode implements ErrorCode {

    /** The pricing policy version, or the enable request, does not exist. */
    NOT_FOUND("fx.NotFound", 404, "No FX policy record matches the requested identifier."),

    /** The approver proposed it - four eyes is two persons ({@code INV-AUD-04}). */
    SELF_APPROVAL_REFUSED(
            "fx.SelfApprovalRefused",
            409,
            "A proposal is approved by someone other than its proposer; the proposer may reject it."),

    /** It was already decided, or retired. */
    PROPOSAL_NOT_PENDING("fx.ProposalNotPending", 409, "The proposal is no longer awaiting a decision."),

    /** One proposal at a time - decide or reject the pending one first. */
    PROPOSAL_PENDING(
            "fx.ProposalPending", 409, "A proposal already awaits a decision; decide or reject it first."),

    /** Enabling was proposed for a subject that is already available. */
    ALREADY_AVAILABLE("fx.AlreadyAvailable", 409, "The pair or provider is already available."),

    /** A pricing policy names a provider this build does not declare. */
    PROVIDER_NOT_DECLARED(
            "fx.ProviderNotDeclared", 422, "The provider is not declared by this build."),

    /** The pair is not quoted by the active pricing policy - or no policy is active (`P9-TSK-008`). */
    PAIR_NOT_OFFERED("fx.PairNotOffered", 422, "The currency pair is not offered."),

    /** The fixed leg is outside the pair's notional bounds. */
    AMOUNT_OUT_OF_RANGE("fx.AmountOutOfRange", 422, "The amount is outside the pair's bounds."),

    /**
     * The caller's standing does not admit a quote. One code for every standing - no party, not a
     * customer, not yet verified, suspended - so the door is no oracle (the merchant.NotEligible
     * precedent).
     */
    CUSTOMER_NOT_ELIGIBLE("fx.CustomerNotEligible", 422, "The customer cannot request a quote."),

    /** The pair is disabled by the kill switch. */
    PAIR_SUSPENDED("fx.PairSuspended", 409, "The currency pair is suspended; try again later."),

    /** A pricing policy was activated while the quote was being priced; retry with a new key. */
    POLICY_STALE("fx.PolicyStale", 409, "The pricing policy changed; request a new quote with a new key."),

    /** The owner already holds the cap of live quotes. */
    TOO_MANY_OPEN_QUOTES("fx.TooManyOpenQuotes", 429, "Too many open quotes; let one expire or cancel it."),

    /** No usable rate: every provider failed, or the reference is stale. Retry with a new key. */
    RATE_UNAVAILABLE("fx.RateUnavailable", 503, "No rate is available right now; retry with a new key."),

    /** No quote of the caller's has this identifier - one answer for absent and another's. */
    QUOTE_NOT_FOUND("fx.QuoteNotFound", 404, "No quote matches the requested identifier."),

    /** The quote is no longer live. */
    QUOTE_NOT_CANCELLABLE("fx.QuoteNotCancellable", 409, "The quote can no longer be cancelled."),

    /** The proposal or the decision is malformed; the detail names the defect. */
    PRICING_POLICY_INVALID(
            "fx.PricingPolicyInvalid", 422, "The FX policy request is not well formed.");

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
