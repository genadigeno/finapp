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

    /**
     * Four eyes is two parties ({@code INV-AUD-04}): the approver proposed the version, or (`P10-TSK-018`,
     * {@code INV-CRD-11}) the second approver of a case made its first decision.
     */
    SELF_APPROVAL_REFUSED(
            "credit.SelfApprovalRefused",
            403,
            "A second approval is someone else's: a version's activation is not its proposer's, nor a case's second"
                    + " approval its first decider's."),

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
    PRODUCT_NOT_OFFERED("credit.ProductNotOffered", 422, "The product is not offered."),

    /**
     * The requested amount or term lies outside the product's published bounds, or is not in its currency (`P10-TSK-014`,
     * {@code INV-CRD-12}). A {@code 422}: a value the caller chose and can correct.
     */
    AMOUNT_OUT_OF_RANGE(
            "credit.AmountOutOfRange", 422, "The requested amount or term is outside the product's bounds."),

    /**
     * The applicant already holds an open request for this product (`P10-TSK-014`; one open per party and product). A
     * {@code 409} naming the open request: wait for it, or cancel it, and retry.
     */
    DECISION_REQUEST_OPEN(
            "credit.DecisionRequestOpen", 409, "An open decision request for this product already exists."),

    /** The request has moved past cancellation - it was evaluated, or it is closed (`P10-TSK-014`). */
    REQUEST_NOT_CANCELLABLE(
            "credit.RequestNotCancellable", 409, "The decision request can no longer be cancelled."),

    /**
     * The caller is not a verified customer in good standing (`P10-TSK-014`): the remedy is to complete verification and
     * retry - the {@code accounts.AccountOpeningRefused} shape, a {@code 409} and cause-blind, deliberately not a
     * {@code 403} (which means "you hold no role", unfixable by the caller).
     */
    APPLICANT_NOT_ELIGIBLE(
            "credit.ApplicantNotEligible",
            409,
            "The applicant is not a verified customer in good standing; complete verification and retry."),

    /**
     * Credit evidence that cannot be decrypted now - a key not configured or rotated away (`P10-TSK-017`). A
     * {@code 503}: the evidence is kept, the read is recorded FAILED, and nothing is served.
     */
    EVIDENCE_UNREADABLE("credit.EvidenceUnreadable", 503, "The credit evidence cannot be read now."),

    /**
     * The review case is not available for the act (`P10-TSK-018`): another underwriter holds it, or it has moved on -
     * decided, closed, released, or no longer in the state the act needs. Re-read the queue.
     */
    CASE_TAKEN("credit.CaseTaken", 409, "The review case is held by another underwriter or has moved on."),

    /** A person's approval of a request whose evaluation triggered a hard decline (`P10-TSK-018`, ADR-0089 point 2). */
    HARD_DECLINE_NOT_OVERRIDABLE(
            "credit.HardDeclineNotOverridable", 422, "A hard decline cannot be approved by a person; it may be declined."),

    /**
     * A person's approval above the referral's ceiling ({@code approvable_minor}: the request capped by every cap the
     * evaluation triggered - a referral approves no amount of its own) or beyond the party's exposure limit, re-read under the
     * profile lock (`P10-TSK-018`, G7, {@code INV-CRD-09}): nothing recorded, the case unchanged - decide again, a
     * decline or a smaller approval.
     */
    EXPOSURE_LIMIT_EXCEEDED(
            "credit.ExposureLimitExceeded",
            422,
            "The approval exceeds what the evaluation allows or the party's exposure limit; decline, or approve less.");

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
