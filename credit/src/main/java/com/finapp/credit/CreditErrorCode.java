package com.finapp.credit;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The credit module's error codes (`P10-TSK-011`, PHASE_10_PLAN.md section 11): the scorecard administration's
 * refusals. Each is actionable by the operator who met it; none names a figure or a band.
 */
@RequiredArgsConstructor
public enum CreditErrorCode implements ErrorCode {

    /** No scorecard model version has the identifier. */
    NOT_FOUND("credit.NotFound", 404, "No credit record matches the requested identifier."),

    /** The approver proposed it - four eyes is two parties ({@code INV-AUD-04}). */
    SELF_APPROVAL_REFUSED(
            "credit.SelfApprovalRefused",
            403,
            "A version is activated by someone other than its proposer; the proposer may reject it."),

    /** The version was already decided, or retired. */
    POLICY_STALE("credit.PolicyStale", 409, "The version is no longer awaiting a decision."),

    /** One proposal per family at a time. */
    PROPOSAL_PENDING("credit.ProposalPending", 409, "A proposal already awaits a decision; decide or reject it first."),

    /** The points table is not well formed; the detail names the defect. */
    SCORECARD_INVALID("credit.ScorecardInvalid", 422, "The scorecard is not well formed."),

    /** A proposal or a decision carries no reason. */
    REASON_REQUIRED("credit.ReasonRequired", 422, "A reason is required."),

    /**
     * A credit policy is incomplete or ill typed - no fallback for a source kind it reads, an unknown attribute or
     * reason code, an operand of the wrong type, an adverse effect without an adverse code (`P10-TSK-012`,
     * {@code INV-CRD-10}); the detail names the defect, never a threshold.
     */
    POLICY_INCOMPLETE("credit.PolicyIncomplete", 422, "The credit policy is incomplete or not well formed."),

    /** The product is not one the platform offers (ADR-0084 section 6). */
    PRODUCT_NOT_OFFERED("credit.ProductNotOffered", 422, "The product is not offered.");

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
