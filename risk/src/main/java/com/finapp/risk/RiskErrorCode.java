package com.finapp.risk;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The risk module's error codes (`P13-TSK-001`; {@code ERROR_CONTRACT.md} `risk`; {@code PHASE_13_PLAN.md} section 9) -
 * declared per task, the {@code BnplErrorCode} precedent: the three the role split owns arrive with the roles; the nine
 * others of the plan's twelve arrive with the doors that raise them, so no catalogued code is one a client could never
 * receive. {@code risk.SelfApprovalRefused} and {@code risk.SelfDealingRefused} are {@code 403} - a forbidden act, not
 * a conflict (the credit, lending and bnpl precedent).
 */
@RequiredArgsConstructor
public enum RiskErrorCode implements ErrorCode {

    NOT_FOUND("risk.NotFound", 404, "No risk record matches the requested identifier."),

    SELF_APPROVAL_REFUSED(
            "risk.SelfApprovalRefused",
            403,
            "A proposal is decided by someone other than its proposer; the proposer may reject it."),

    SELF_DEALING_REFUSED(
            "risk.SelfDealingRefused",
            403,
            "A holder never acts on a case, override or report whose subject is their own party; nothing was done."),

    // The policy doors' four (P13-TSK-002), the register's planned statuses kept.
    POLICY_NOT_ACTIVE(
            "risk.PolicyNotActive",
            409,
            "No risk policy version is active for the operation, or the version named is not the active one."),

    POLICY_STALE("risk.PolicyStale", 409, "The risk policy version is no longer awaiting a decision, or is superseded."),

    POLICY_INVALID(
            "risk.PolicyInvalid",
            422,
            "The policy version is not complete, a rule parameter is outside its kind's schema, or a band maps to a"
                    + " decision the operation does not admit; nothing was proposed."),

    /** The policy doors' (P13-TSK-002) and the reprocessing door's (P13-TSK-019) - one code, the wave's. */
    REASON_REQUIRED("risk.ReasonRequired", 422, "A reason is required."),

    // The reprocessing door's refusal of a point ahead of the watermark (P13-TSK-019).
    REPROCESSING_NOT_BEHIND(
            "risk.ReprocessingNotBehind",
            422,
            "A reprocessing restarts at or before the monitoring watermark's sequence; a point ahead of it would skip"
                    + " entries. Nothing was changed.");

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
