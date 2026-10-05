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
            "crossborder.CorridorPolicyInvalid", 422, "The corridor policy request is not well formed.");

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
