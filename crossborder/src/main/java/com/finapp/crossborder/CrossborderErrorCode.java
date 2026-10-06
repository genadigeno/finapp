package com.finapp.crossborder;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The crossborder module's error codes (`P9-TSK-015`, `PHASE_9_PLAN.md` §9): the corridor policy's and
 * the corridor switch's refusals. Each is actionable by the operator who met it; none names an amount.
 */
@RequiredArgsConstructor
public enum CrossborderErrorCode implements ErrorCode {

    /** The corridor policy version, or the enable request, does not exist. */
    NOT_FOUND("crossborder.NotFound", 404, "No corridor policy record matches the requested identifier."),

    /** The approver proposed it - four eyes is two persons ({@code INV-AUD-04}). */
    SELF_APPROVAL_REFUSED(
            "crossborder.SelfApprovalRefused",
            409,
            "A proposal is approved by someone other than its proposer; the proposer may reject it."),

    /** It was already decided, or retired. */
    PROPOSAL_NOT_PENDING("crossborder.ProposalNotPending", 409, "The proposal is no longer awaiting a decision."),

    /** One proposal at a time - decide or reject the pending one first. */
    PROPOSAL_PENDING(
            "crossborder.ProposalPending", 409, "A proposal already awaits a decision; decide or reject it first."),

    /** Enabling was proposed for a corridor that is already available. */
    ALREADY_AVAILABLE("crossborder.AlreadyAvailable", 409, "The corridor is already available."),

    /** A corridor names a rail this build does not declare, or one not covering its destination. */
    RAIL_NOT_DECLARED(
            "crossborder.RailNotDeclared",
            422,
            "A candidate rail is not declared by this build as covering the corridor's destination."),

    /** A corridor requires data the platform does not hold. */
    REQUIRED_DATA_UNSATISFIABLE(
            "crossborder.RequiredDataUnsatisfiable", 422, "The corridor requires data the platform does not hold."),

    /** The proposal or the decision is malformed; the detail names the defect. */
    CORRIDOR_POLICY_INVALID(
            "crossborder.CorridorPolicyInvalid", 422, "The corridor policy request is not well formed."),

    /**
     * Reserved for the Phase 13 limit seam (`P9-TSK-016`, ADR-0081 point 8): {@link CrossBorderLimitCheck}
     * refused the instruction, and nothing was consumed. Phase 9's {@link PermitAllUntilPhase13} never
     * produces it; reserving it now means Phase 13 changes no contract.
     */
    LIMIT_REFUSED("crossborder.LimitRefused", 422, "The transfer exceeds a limit that applies to it."),

    /**
     * Reserved for the Phase 13 risk seam (`P9-TSK-016`, ADR-0081 point 8): {@link CrossBorderRiskDecision}
     * refused the instruction before the offer was accepted, and nothing was consumed.
     */
    RISK_REFUSED("crossborder.RiskRefused", 422, "The transfer cannot be accepted."),

    /**
     * No available corridor delivers the beneficiary's currency in its country, no candidate rail is
     * eligible, or the provider attests another destination (`P9-TSK-017`) - nothing registered.
     */
    CORRIDOR_NOT_OFFERED("crossborder.CorridorNotOffered", 422, "Payments to this destination are not offered."),

    /**
     * The provider's payee check did not match and the customer did not acknowledge it (`P9-TSK-017`,
     * ADR-0080 section 3) - nothing registered; the same grant may be presented again with the acknowledgement.
     */
    NO_MATCH_UNACKNOWLEDGED(
            "crossborder.NoMatchUnacknowledged",
            422,
            "The account holder's name did not match; acknowledge this to register the beneficiary."),

    /** The provider refused the grant - invalid, expired or already used (`P9-TSK-017`). */
    GRANT_REFUSED("crossborder.GrantRefused", 422, "The beneficiary authorisation was not accepted."),

    /**
     * The corridor provider could not be reached or answered unreadably (`P9-TSK-017`) - nothing registered;
     * retry with a new idempotency key and the same grant.
     */
    PROVIDER_UNAVAILABLE("crossborder.ProviderUnavailable", 503, "The payment provider is unavailable; try again."),

    /** No beneficiary of the caller's has this identifier - the uniform 404 (`P9-TSK-017`). */
    BENEFICIARY_NOT_FOUND("crossborder.BeneficiaryNotFound", 404, "No beneficiary matches the requested identifier."),

    /**
     * The beneficiary cannot be paid (`P9-TSK-018`, {@code INV-XB-02}) - one byte-identical answer for every
     * reason: being screened, in review, blocked, revoked, unknown or another customer's, or refused by a
     * re-screen. Nothing is priced.
     */
    BENEFICIARY_NOT_PAYABLE("crossborder.BeneficiaryNotPayable", 422, "Payments to this beneficiary are not possible."),

    /** The destination amount exceeds the corridor's maximum (`P9-TSK-018`) - nothing is priced or kept. */
    AMOUNT_EXCEEDS_CORRIDOR_LIMIT(
            "crossborder.AmountExceedsCorridorLimit", 422, "The amount exceeds the limit for this destination."),

    /**
     * The corridor policy version pinned at the claim was superseded before the offer was recorded
     * (`P9-TSK-018`, {@code INV-HIST-04}) - retry with a new idempotency key to be priced under its successor.
     */
    POLICY_STALE("crossborder.PolicyStale", 409, "The corridor terms changed; request a new offer."),

    /**
     * The beneficiary's clearance had lapsed and the re-screen could not reach its provider (`P9-TSK-018`,
     * ADR-0081 point 5) - nothing priced, nothing held; retry with a new key.
     */
    SCREENING_UNAVAILABLE("crossborder.ScreeningUnavailable", 503, "The beneficiary could not be verified just now; try again."),

    /** No offer of the caller's has this quote id - the uniform 404 (`P9-TSK-018`). */
    OFFER_NOT_FOUND("crossborder.OfferNotFound", 404, "No offer matches the requested identifier."),

    /**
     * The beneficiary's clearance lapsed between the offer and the authorization (`P9-TSK-019`, ADR-0081 point
     * 3) - nothing held; request a new offer, which re-screens.
     */
    SCREENING_REQUIRED("crossborder.ScreeningRequired", 409, "The beneficiary must be verified again; request a new offer."),

    /** The corridor is disabled (`P9-TSK-019`) - nothing held or sent; try again later. */
    CORRIDOR_UNAVAILABLE("crossborder.CorridorUnavailable", 503, "Payments to this destination are paused; try again later."),

    /** No payment of the caller's has this identifier - the uniform 404 (`P9-TSK-019`). */
    PAYMENT_NOT_FOUND("crossborder.PaymentNotFound", 404, "No payment matches the requested identifier."),

    /**
     * The payment is past recall (`P9-TSK-024`, ADR-0079 point 5): the provider accepted it, or it already ended -
     * nothing written; a payment the provider accepted can only come back as a return.
     */
    NOT_CANCELLABLE("crossborder.NotCancellable", 409, "This payment can no longer be cancelled.");

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
