package com.finapp.lending;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The lending module's error codes (`P11-TSK-004`, `P11-TSK-005`; PHASE_11_PLAN.md section 9): the capital and terms
 * administrations' refusals, and the capital shortfall its headroom judges. Each is actionable by the operator who met
 * it; none names an amount, a rate, a reference or a balance.
 */
@RequiredArgsConstructor
public enum LendingErrorCode implements ErrorCode {

    /** No lending record has the identifier. */
    NOT_FOUND("lending.NotFound", 404, "No lending record matches the requested identifier."),

    /**
     * Four eyes is two people ({@code INV-AUD-04}): the approver proposed the contribution or the terms version. The
     * title read "A contribution is approved ..." until `P11-TSK-005` gave the code its second subject; the code is
     * unchanged.
     */
    SELF_APPROVAL_REFUSED(
            "lending.SelfApprovalRefused",
            403,
            "A proposal is approved by someone other than its proposer; the proposer may reject it."),

    /** A contribution of the currency already awaits a decision. */
    PROPOSAL_PENDING(
            "lending.ProposalPending", 409, "A proposal already awaits a decision; decide or reject it first."),

    /** The contribution was already decided - by somebody else, or differently. */
    PROPOSAL_NOT_PENDING(
            "lending.ProposalNotPending", 409, "The proposal is no longer awaiting a decision."),

    /** A proposal or a decision carries no reason, one past its bound, or one holding an instrument shape. */
    REASON_REQUIRED("lending.ReasonRequired", 422, "A reason is required."),

    /**
     * A contribution is not well formed - a currency the platform does not support, an amount that is not positive or
     * not at the currency's scale, or a reference outside its family or already used.
     */
    CONTRIBUTION_INVALID("lending.ContributionInvalid", 422, "The contribution is not well formed."),

    /**
     * The loan terms version was already decided - activated or rejected by somebody else, or differently - the loser
     * of a decision race (`P11-TSK-005`, credit's {@code PolicyStale}).
     */
    TERMS_STALE("lending.TermsStale", 409, "The terms version is no longer awaiting a decision."),

    /**
     * A terms proposal is refused whole (`P11-TSK-005`, {@code INV-LND-09}): a convention no engine version implements
     * (a rate type, rounding mode, day count, or zone), an allocation order omitting or repeating a component, another
     * product's terms, an unregistered engine version or agreement template, or a value outside the bounds lending or the
     * product declares.
     */
    TERMS_INVALID("lending.TermsInvalid", 422, "The terms are not complete or not within bounds."),

    /**
     * The terms cannot amortise at the product's declared amount and term bounds - some period's principal portion
     * would not be positive (ADR-0092 section 3, ADR-0093 section 5). Catalogued by `P11-TSK-005`; met from the terms
     * proposal door once `P11-TSK-006` wires the schedule engine that judges it.
     */
    TERMS_NOT_AMORTISING(
            "lending.TermsNotAmortising",
            422,
            "The terms cannot amortise across the product's amount and term bounds; nothing was proposed."),

    /**
     * The act would deploy more principal than the currency's recognised lending capital leaves free
     * ({@code INV-LND-13}): headroom judged under the capital row lock. Declared by `P11-TSK-004`; met from the loan
     * acceptance (`P11-TSK-012`), the draw (`-021`) and the reversal that re-instates principal (`-020`).
     */
    CAPITAL_UNAVAILABLE(
            "lending.CapitalUnavailable",
            422,
            "There is not enough recognised lending capital for this act; nothing was done.");

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
