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
