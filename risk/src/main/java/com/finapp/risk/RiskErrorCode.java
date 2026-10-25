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

    // The override doors' two (P13-TSK-012): what cannot be overridden, and a proposal already decided.
    ACTION_NOT_PROPOSABLE(
            "risk.ActionNotProposable",
            409,
            "The override cannot be proposed now: the decision is an ALLOW or a CHALLENGE, nothing is held to refuse, or"
                    + " the assessment already has a live proposal or an approved override."),

    OVERRIDE_STALE("risk.OverrideStale", 409, "The override is no longer awaiting a decision."),

    // The alert's machine (P13-TSK-015; its door P13-TSK-018's): an alert attached or suppressed, and a suppression
    // not in the state the act needs - the register's 409s (OverrideStale's shape).
    ALERT_NOT_OPEN("risk.AlertNotOpen", 409, "The alert is already attached to a case or suppressed."),

    SUPPRESSION_STALE(
            "risk.SuppressionStale",
            409,
            "The alert's suppression is not awaiting this act: a proposal already awaits a decision, or none does."),

    // The case's doors (P13-TSK-016): the two the plan names and the three DESIGN-016 D10 names - the state conflicts
    // 409 (the register's precedent), the requests the case cannot take as asked 422.
    CASE_REVISION_STALE(
            "risk.CaseRevisionStale",
            409,
            "The case changed since it was read; read it again and resend with its current revision."),

    CASE_NOT_OPEN("risk.CaseNotOpen", 409, "The case is disposed; a new case may reference it."),

    CASE_ACT_NOT_ADMITTED(
            "risk.CaseActNotAdmitted",
            409,
            "The case's state does not admit this act now: a disposition already awaits a second person, the"
                    + " disposition named is not awaiting a decision, or the case is at its top level."),

    CASE_NOT_TAKEN(
            "risk.CaseNotTaken",
            422,
            "The act needs the case taken by an investigator first: an escalation, or a disposition that is two"
                    + " persons' act."),

    CASE_REQUEST_INVALID(
            "risk.CaseRequestInvalid",
            422,
            "The case act is not admitted as asked: a kind, subject, queue or disposition outside what the case takes,"
                    + " or PROHIBITED asked of a disposition that does not confirm a party's case; nothing was done."),

    // The queues' refusal of a read they cannot answer as asked (P13-TSK-018, DESIGN-018 D12): a filter outside the
    // vocabulary, a page below one row, or a cursor naming no row - 422, the request the queue cannot take.
    QUEUE_REQUEST_INVALID(
            "risk.QueueRequestInvalid",
            422,
            "The queue read is not admitted as asked: a status or kind outside the queue's vocabulary, a page of fewer"
                    + " than one row, or a cursor that names no row of the queue; nothing was served."),

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
