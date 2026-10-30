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
            "A proposal is decided by someone other than its proposer; the proposer may reject it."),

    /** The mapping doors' (`P14-TSK-003`): the version is not coherent in itself. */
    MAPPING_INVALID(
            "accounting.MappingInvalid",
            422,
            "The GL mapping version is not coherent - a code repeated, a rule naming an account the version does not"
                    + " hold, two rules for one purpose, or a section that does not admit its account's classification;"
                    + " nothing was proposed."),

    /** Activation (`P14-TSK-003`): a purpose of the ledger's chart is not mapped. */
    MAPPING_NOT_TOTAL(
            "accounting.MappingNotTotal",
            422,
            "The GL mapping version does not map every purpose of the ledger's chart; it was not activated."),

    /** Activation (`P14-TSK-003`): a rule's GL classification is not its purpose's ledger type, or names no purpose. */
    MAPPING_INCOMPATIBLE(
            "accounting.MappingIncompatible",
            422,
            "A rule of the GL mapping version maps a purpose to a GL account of another classification than the"
                    + " purpose's ledger type, or names a purpose the ledger does not hold; it was not activated."),

    /** The mapping doors (`P14-TSK-003`): no longer PROPOSED, or superseded by the version in force. */
    MAPPING_STALE(
            "accounting.MappingStale",
            409,
            "The GL mapping version is no longer awaiting a decision, or is superseded by the version in force."),

    /** Activation (`P14-TSK-003`): the version would take effect before a version already in force. */
    MAPPING_RETROACTIVE(
            "accounting.MappingRetroactive",
            409,
            "The GL mapping version takes effect from a month before a version already in force; a mapping never"
                    + " applies retroactively."),

    /** Every reasoned accounting act (`P14-TSK-003` first). */
    REASON_REQUIRED("accounting.ReasonRequired", 422, "A reason is required.");

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
