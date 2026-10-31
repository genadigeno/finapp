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
            "The GL mapping version takes effect from a month before a version already in force, or at or before a"
                    + " period already locked or closed; a mapping never applies retroactively."),

    /** The period doors (`P14-TSK-005`): the period is not in the state the act needs. */
    PERIOD_STALE(
            "accounting.PeriodStale",
            409,
            "The accounting period is not in the state the act needs - past it, or awaiting another act; nothing was"
                    + " changed."),

    /** The lock (`P14-TSK-005`): the grace after the month end has not passed, or the previous period is not closed. */
    PERIOD_NOT_LOCKABLE(
            "accounting.PeriodNotLockable",
            409,
            "The accounting period cannot lock yet - its grace after the month end has not passed, or the previous"
                    + " period is not closed (periods lock in order); nothing was changed."),

    /** The lock and the unlock (`P14-TSK-005`): postings held the cutoff through the ledger's bounded attempts. */
    CUTOFF_BUSY(
            "accounting.CutoffBusy",
            503,
            "The posting cutoff could not be moved right now - postings held it through every bounded attempt; nothing"
                    + " was changed, retry."),

    /** Every reasoned accounting act (`P14-TSK-003` first). */
    REASON_REQUIRED("accounting.ReasonRequired", 422, "A reason is required."),

    /** The GL doors (`P14-TSK-007`): no mapping version is pinned by, or in force for, the period. */
    NO_ACTIVE_MAPPING(
            "accounting.NoActiveMapping",
            409,
            "No GL mapping version is pinned by, or in force for, the period - the GL cannot be read for it until a"
                    + " version is activated under four eyes."),

    /** The GL doors (`P14-TSK-007`): a statement of the read outlived the snapshot's statement timeout. */
    READ_TIMED_OUT(
            "accounting.ReadTimedOut",
            503,
            "The accounting read did not finish within its statement timeout; nothing was served - retry, or narrow"
                    + " the read."),

    /** The report run doors (`P14-TSK-008`): the build holds no definition of the code (or the run's version). */
    REPORT_DEFINITION_UNKNOWN(
            "accounting.ReportDefinitionUnknown",
            422,
            "No report definition of this code (or of the run's version) is held by this build; nothing was generated."),

    /** The generation door (`P14-TSK-008`): the parameters are not a bounded, plain set the definition takes. */
    REPORT_PARAMETERS_INVALID(
            "accounting.ReportParametersInvalid",
            422,
            "The report parameters are not a bounded set of plain values the definition takes, or the valuation rate set"
                    + " is missing where the definition translates or given where it does not; nothing was claimed."),

    /** The regeneration door (`P14-TSK-008`): only a FINAL run, its period still under the lock it was generated in. */
    REPORT_RUN_STALE(
            "accounting.ReportRunStale",
            409,
            "The report run is not FINAL - generating, provisional, failed or superseded - or its period no longer holds"
                    + " the lock it was generated under; only a FINAL run is regenerated and compared."),

    /** The break doors (`P14-TSK-010`): the break is not in the state the act needs. */
    BREAK_STALE(
            "accounting.BreakStale",
            409,
            "The accounting break is not in the state the act needs - resolved, acknowledged, or awaiting another"
                    + " person's acknowledgement; nothing was changed."),

    /** The resolution (`P14-TSK-010`): the correcting entry named does not exist, or is the break's own subject. */
    CORRECTING_ENTRY_INVALID(
            "accounting.CorrectingEntryInvalid",
            422,
            "The correcting entry named does not exist in the journal, or is the entry the break is about; the break"
                    + " was not resolved.");

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
