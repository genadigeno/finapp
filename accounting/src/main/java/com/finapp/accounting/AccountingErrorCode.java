package com.finapp.accounting;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The accounting module's error codes (`P14-TSK-001`; {@code ERROR_CONTRACT.md} `accounting`; {@code PHASE_14_PLAN.md}
 * section 10) - declared per task, the {@code RiskErrorCode} precedent: the two the role split owns arrive with the
 * roles; every other code arrives with the door that raises it, so no catalogued code is one a client could never
 * receive. {@code accounting.SelfApprovalRefused} is {@code 403} - a forbidden act, not a conflict (the credit, lending,
 * bnpl and risk precedent). Every detail is stranger-safe: never an amount, a balance, a GL figure or an owner reference.
 */
@RequiredArgsConstructor
public enum AccountingErrorCode implements ErrorCode {

    NOT_FOUND("accounting.NotFound", 404, "No accounting record matches the requested identifier."),

    SELF_APPROVAL_REFUSED(
            "accounting.SelfApprovalRefused",
            403,
            "A proposal is decided by someone other than its proposer; the proposer may reject it.");

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
