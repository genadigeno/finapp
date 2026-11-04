package com.finapp.accounting;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The accounting module's audited acts (`P14-TSK-001`; {@code INV-AUD-01}, {@code INV-AUD-04}; {@code PHASE_14_PLAN.md}
 * section 12) - declared per task, the {@code RiskAuditAction} precedent: the refusal of the role split arrives with the
 * roles, catalogued in {@code AUDITABLE_ACTIONS.md} and, until the first four-eyes door raises it, named in
 * {@code AuditCompletenessTest}'s deliberate absences with that task; every later {@code accounting.*} and
 * {@code regulatory.*} act arrives with the task that raises it. A summary carries ids and codes - never an amount, a GL
 * figure or an owner reference ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public enum AccountingAuditAction implements AuditableAction {

    /** A proposer tried to approve their own proposal and was refused. */
    SELF_APPROVAL_REFUSED(
            "accounting.SelfApprovalRefused",
            "A proposer tried to approve their own accounting proposal - a GL mapping version's activation (P14-TSK-003),"
                    + " a period's unlock, close or reopen (P14-TSK-005, -012), a valuation rate set (P14-TSK-014), a"
                    + " break's acknowledgement (P14-TSK-010), a prior-period adjustment (P14-TSK-013) or a regulatory"
                    + " submission's approval (P14-TSK-018) - and was refused 403 accounting.SelfApprovalRefused before"
                    + " anything was written (INV-AUD-04; also held by each four-eyes row's approver <> proposer CHECK)."
                    + " The roles' grants are disjoint but their holders need not be, so this is the control that binds a"
                    + " person holding both ACCOUNTANT and FINANCIAL_CONTROLLER. Recorded FAILED in a transaction of its"
                    + " own after the act rolled back, so the attempt survives. Its target the proposal; the summary"
                    + " names it and the act - never an amount or an owner reference.",
            false),

    /** An accountant read the posting rule register (`P14-TSK-002`, ACCOUNTING_READ) - every serving recorded. */
    POSTING_RULES_READ(
            "accounting.PostingRulesRead",
            "An accountant read the posting rule register - every registered namespace's rule, version, line shape and"
                    + " dating class (P14-TSK-002, ADR-0117 decision 1; ACCOUNTING_READ). Code data, no customer's or"
                    + " merchant's figure; recorded at every serving as an audited operator read. Its target the"
                    + " catalogue version; the summary the version and the rule count.",
            false),

    /** An accountant proposed a GL mapping version (`P14-TSK-003`, ACCOUNTING_MAPPING_PROPOSE). */
    MAPPING_PROPOSED(
            "accounting.MappingProposed",
            "An accountant proposed a GL mapping version - a whole GL chart and its purpose -> GL account rules,"
                    + " effective from a stated calendar month, immutable from this moment (P14-TSK-003, ADR-0113"
                    + " decision 3, INV-ACC-07; POST /v1/operator/accounting/mapping-versions, ACCOUNTING_MAPPING_PROPOSE)."
                    + " Reasoned. Its target the version; the summary its number, effective month and account and rule"
                    + " counts - never a figure.",
            true),

    /** A second person activated a GL mapping version (`P14-TSK-003`, ACCOUNTING_MAPPING_APPROVE). */
    MAPPING_ACTIVATED(
            "accounting.MappingActivated",
            "A person other than its proposer activated a GL mapping version, total over the ledger's purposes and"
                    + " classification-compatible with each purpose's ledger type as the activation's transaction read"
                    + " the chart, retiring the version in force in the same transaction (P14-TSK-003, INV-ACC-06,"
                    + " INV-ACC-07, INV-AUD-04; POST .../mapping-versions/{id}/activation, ACCOUNTING_MAPPING_APPROVE)."
                    + " Reasoned. Its target the version; the summary its number, effective month and the retired"
                    + " predecessor's number. accounting.GlMappingVersionActivated is published with it.",
            true),

    /** A GL mapping version was rejected (`P14-TSK-003`, ACCOUNTING_MAPPING_APPROVE). */
    MAPPING_REJECTED(
            "accounting.MappingRejected",
            "A holder of ACCOUNTING_MAPPING_APPROVE rejected a proposed GL mapping version - terminal; a correction is a"
                    + " new proposal (P14-TSK-003; POST .../mapping-versions/{id}/rejection). Reasoned. Its target the"
                    + " version; the summary its number and whether the rejecter proposed it.",
            true),

    /** The system opened an accounting period (`P14-TSK-005`). */
    PERIOD_OPENED(
            "accounting.PeriodOpened",
            "The system opened an accounting period - the calendar month after the latest, insert-if-absent, one per"
                    + " month (P14-TSK-005, ADR-0114 decision 3, C9; AccountingPeriods.openNext, scheduled by"
                    + " P14-TSK-011). Its target the period; the summary its month and revision."
                    + " accounting.AccountingPeriodOpened is published with it.",
            false),

    /** A person locked an accounting period (`P14-TSK-005`, ACCOUNTING_CLOSE_PROPOSE). */
    PERIOD_LOCKED(
            "accounting.PeriodLocked",
            "An accountant locked an accounting period after its grace, the previous period closed: the ledger's posting"
                    + " cutoff advanced to the period end and the GL mapping version in force pinned, in the same"
                    + " transaction - from its commit no entry dated in the period commits (P14-TSK-005, INV-ACC-03,"
                    + " INV-ACC-08, INV-ACC-07; POST .../periods/{id}/lock, ACCOUNTING_CLOSE_PROPOSE). Reason optional."
                    + " Its target the period; the summary its month, revision and pinned version number."
                    + " accounting.AccountingPeriodLocked is published with it.",
            false),

    /** A person proposed unlocking a locked period (`P14-TSK-005`, ACCOUNTING_CLOSE_PROPOSE). */
    PERIOD_UNLOCK_PROPOSED(
            "accounting.PeriodUnlockProposed",
            "An accountant proposed unlocking a LOCKED accounting period - nothing moves until a second person approves"
                    + " (P14-TSK-005, INV-AUD-04; POST .../periods/{id}/unlock-proposal, ACCOUNTING_CLOSE_PROPOSE)."
                    + " Reasoned. Its target the period; the summary its month and revision.",
            true),

    /** A second person approved an unlock (`P14-TSK-005`, ACCOUNTING_CLOSE_APPROVE). */
    PERIOD_UNLOCKED(
            "accounting.PeriodUnlocked",
            "A person other than its proposer approved an accounting period's unlock: LOCKED -> OPEN, the ledger's"
                    + " posting cutoff moved back one period and the pin released, in the same transaction (P14-TSK-005,"
                    + " INV-AUD-04, INV-ACC-08; POST .../periods/{id}/unlock-approval, ACCOUNTING_CLOSE_APPROVE)."
                    + " Reasoned. Its target the period; the summary its month and revision.",
            true),

    /** An accountant read the GL - balances, an account's activity or an entry (`P14-TSK-007`, ACCOUNTING_READ). */
    GL_READ(
            "accounting.GlRead",
            "An accountant read the General Ledger - a period's GL figures, one GL account's activity drilled to its"
                    + " journal lines, or one journal entry's GL view - computed from the journal in one REPEATABLE READ"
                    + " READ ONLY snapshot under the mapping version the period pins (P14-TSK-007, INV-ACC-02,"
                    + " INV-ACC-06, INV-ACC-14; GET /v1/operator/accounting/gl/..., ACCOUNTING_READ). Recorded at every"
                    + " serving, in a transaction of its own after the read and before the answer. Its target the"
                    + " period, the GL account or the entry - or the report run, for a run's figure's lines under its version"
                    + " (P14-TSK-016, GET /v1/operator/accounting/report-runs/{runId}/lines); the summary the door, the period, the currency, the"
                    + " version number and its counts - figures= and unmapped= for the balances, lines= and"
                    + " page=first|next for an activity page, lines= for an entry - never an amount or an owner"
                    + " reference.",
            false),

    /** A report run completed FINAL or PROVISIONAL, or failed (`P14-TSK-008`, ACCOUNTING_REPORT_GENERATE). */
    REPORT_GENERATED(
            "accounting.ReportGenerated",
            "A report run concluded (P14-TSK-008, ADR-0115, INV-ACC-04, INV-ACC-14; POST"
                    + " /v1/operator/accounting/report-runs, ACCOUNTING_REPORT_GENERATE, or the orphan leg as the platform):"
                    + " completed FINAL (a period no longer OPEN, under the lock it was claimed in) or PROVISIONAL (an OPEN"
                    + " period) with its content and content_sha256 in one conditional edge - recorded SUCCEEDED with"
                    + " accounting.ReportRunCompleted in the same commit - or failed with a closed code (DEFINITION_UNKNOWN,"
                    + " DEFINITION_FAILED, PERIOD_MOVED, CONTENT_INVALID, ATTEMPTS_EXHAUSTED, GENERATION_TIMED_OUT) - recorded FAILED. Its target"
                    + " the run; the summary its definition, version, period, revision and basis with the status or the"
                    + " failure code - on a translated run its completeness and untranslated currencies by code (P14-TSK-015's review, I-1(a)), on a definition's refusal its reason and subjects by code (P14-TSK-016) - never a figure or the content.",
            false),

    /** A FINAL report run was regenerated and compared (`P14-TSK-008`, ACCOUNTING_REPORT_GENERATE). */
    REPORT_REGENERATED(
            "accounting.ReportRegenerated",
            "An accountant regenerated a FINAL report run from its pinned inputs in a read-only snapshot and compared the"
                    + " hashes (P14-TSK-008, INV-ACC-04; POST .../report-runs/{runId}/regeneration,"
                    + " ACCOUNTING_REPORT_GENERATE): IDENTICAL, or DIVERGED - metered finapp.accounting.report.diverged and"
                    + " never repaired. Its target the run; the summary its definition, version, period, revision and the"
                    + " result.",
            false),

    /** An accountant read a report run's content (`P14-TSK-008`, ACCOUNTING_READ) - every serving recorded. */
    REPORT_READ(
            "accounting.ReportRead",
            "An accountant read a report run with its content - CONFIDENTIAL aggregates (P14-TSK-008, PHASE_14_PLAN.md"
                    + " section 12; GET /v1/operator/accounting/report-runs/{runId}, ACCOUNTING_READ); recorded before it is"
                    + " served, in a transaction of its own, so nothing is served unrecorded. Its target the run; the summary"
                    + " its definition, version, period, revision, basis and status - never the content.",
            false),

    /** A person captured a valuation rate set for a locked period (`P14-TSK-014`, ACCOUNTING_CLOSE_PROPOSE). */
    VALUATION_SET_CAPTURED(
            "accounting.ValuationSetCaptured",
            "An accountant captured a valuation rate set for a LOCKED accounting period - per valued currency the"
                    + " reference's last EUR-based observation before the period's end, within the staleness bound, with"
                    + " its source, observed_at and snapshot id; PROPOSED and immutable from this moment (P14-TSK-014,"
                    + " ADR-0116 decision 2, INV-ACC-11, INV-FX-05; POST /v1/operator/accounting/valuation-rate-sets,"
                    + " ACCOUNTING_CLOSE_PROPOSE). Reason optional. Its target the set; the summary its period, as-of"
                    + " instant, pair count and bound - never a rate.",
            false),

    /** A second person approved a valuation rate set (`P14-TSK-014`, ACCOUNTING_CLOSE_APPROVE). */
    VALUATION_SET_APPROVED(
            "accounting.ValuationSetApproved",
            "A person other than its capturer approved a valuation rate set - the period's one approved set, while the"
                    + " period is LOCKED (P14-TSK-014, INV-ACC-11, INV-AUD-04; POST .../valuation-rate-sets/{setId}/approval,"
                    + " ACCOUNTING_CLOSE_APPROVE). Reasoned. Its target the set; the summary its period and pair count.",
            true),

    /** A valuation rate set was rejected (`P14-TSK-014`, ACCOUNTING_CLOSE_APPROVE). */
    VALUATION_SET_REJECTED(
            "accounting.ValuationSetRejected",
            "A holder of ACCOUNTING_CLOSE_APPROVE rejected a proposed valuation rate set - terminal; a correction is a"
                    + " new capture (P14-TSK-014; POST .../valuation-rate-sets/{setId}/rejection). Reasoned. Its target"
                    + " the set; the summary its period and whether the rejecter captured it.",
            true),

    /** A check raised an accounting break (`P14-TSK-010`) - the run's actor, the platform's for the close schedule. */
    BREAK_RAISED(
            "accounting.BreakRaised",
            "A GL-side check - GL = ledger, rule conformance or source completeness - found a discrepancy no break of the"
                    + " period and check yet held and raised it, born OPEN once per (period, check, subject), never"
                    + " corrected (P14-TSK-010, ADR-0117 decision 3, INV-ACC-12, INV-REC-02; AccountingChecks.run, invoked"
                    + " by the close run, P14-TSK-011). Recorded in the raise's own transaction with the break, its"
                    + " RAISED case-file row and accounting.AccountingBreakRaised - a second raiser records nothing. Its"
                    + " target the break; the summary the period, check, type and the register version judged by -"
                    + " never an amount.",
            false),

    /** An accountant proposed acknowledging a break with a disclosure (`P14-TSK-010`, ACCOUNTING_ADJUST_PROPOSE). */
    BREAK_ACKNOWLEDGEMENT_PROPOSED(
            "accounting.BreakAcknowledgementProposed",
            "An accountant proposed acknowledging an OPEN accounting break with a written disclosure - nothing moves until"
                    + " a second person approves (P14-TSK-010, INV-ACC-05, INV-AUD-04; POST"
                    + " /v1/operator/accounting/breaks/{breakId}/acknowledgement-proposal, ACCOUNTING_ADJUST_PROPOSE)."
                    + " Reasoned: the disclosure. Its target the break; the summary its period, check and type.",
            true),

    /** A second person approved a break's acknowledgement (`P14-TSK-010`, ACCOUNTING_ADJUST_APPROVE). */
    BREAK_ACKNOWLEDGED(
            "accounting.BreakAcknowledged",
            "A person other than its proposer approved an accounting break's acknowledgement: OPEN -> ACKNOWLEDGED, its"
                    + " disclosure what the close discloses (P14-TSK-010, INV-ACC-05, INV-AUD-04; POST"
                    + " .../breaks/{breakId}/acknowledgement-approval, ACCOUNTING_ADJUST_APPROVE). Reasoned. Its target the"
                    + " break; the summary its period, check and type.",
            true),

    /** A break was resolved by reference to its correcting entry (`P14-TSK-010`, ACCOUNTING_ADJUST_APPROVE). */
    BREAK_RESOLVED(
            "accounting.BreakResolved",
            "A financial controller resolved an accounting break by reference to the correcting journal entry the owning"
                    + " domain posted through its own door - nothing is posted here (P14-TSK-010, INV-ACC-12; POST"
                    + " .../breaks/{breakId}/resolution, ACCOUNTING_ADJUST_APPROVE). Reasoned. Its target the break; the"
                    + " summary its period, check, type, the status it left and the correcting entry's id. A refused"
                    + " resolution - the entry unknown, the subject itself, named in the break's evidence, or posted"
                    + " before what it would correct - is recorded FAILED in a transaction of its own, nothing changed.",
            true),

    /** An operator read the accounting breaks - a period's page or one break (`P14-TSK-010`, the review's M-5). */
    BREAK_READ(
            "accounting.BreakRead",
            "An accountant read a page of a period's accounting breaks (GET /v1/operator/accounting/breaks,"
                    + " ACCOUNTING_READ) or a financial controller read one break with its pending disclosure (GET"
                    + " .../breaks/{breakId}, ACCOUNTING_ADJUST_APPROVE) - CONFIDENTIAL findings (P14-TSK-010, the"
                    + " independent review's M-5; the GlRead and ReportRead precedent). Recorded SUCCEEDED in a"
                    + " transaction of its own before the answer, so nothing is served unrecorded; an unknown break"
                    + " records nothing. Its target the period or the break; the summary the door, the period, the status"
                    + " asked, the count and page=first|next - never a subject, evidence or a disclosure.",
            false),

    /** A person asked a close check's next attempt (`P14-TSK-011`, ACCOUNTING_CLOSE_PROPOSE). */
    CLOSE_CHECK_RERUN(
            "accounting.CloseCheckRerun",
            "An accountant asked a close check's next attempt: a new PENDING row a worker claims, the earlier attempts"
                    + " frozen (P14-TSK-011, INV-ACC-09; POST /v1/operator/accounting/periods/{periodId}/close-run/checks/"
                    + "{check}/rerun, ACCOUNTING_CLOSE_PROPOSE). Reasoned. Its target the run; the summary the period, the"
                    + " check and the attempt - never a figure.",
            true),

    /** An operator read a close run with its evidence (`P14-TSK-011`, ACCOUNTING_READ). */
    CLOSE_RUN_READ(
            "accounting.CloseRunRead",
            "An accountant read a period's close run - each check's attempts with their evidence, CONFIDENTIAL aggregates"
                    + " (P14-TSK-011; GET /v1/operator/accounting/periods/{periodId}/close-run, ACCOUNTING_READ; the"
                    + " ReportRead and BreakRead precedent). Recorded SUCCEEDED in a transaction of its own before the"
                    + " answer, so nothing is served unrecorded; a period with no run records nothing. Its target the run;"
                    + " the summary the period, whether the run answers the present lock and whether it is complete -"
                    + " never evidence or a figure.",
            false),

    /** An operator read an operational report (`P14-TSK-017`, ACCOUNTING_READ) - every serving recorded. */
    OPERATIONAL_REPORT_READ(
            "accounting.OperationalReportRead",
            "An accountant read an operational report - RECONCILIATION_STATUS or CLOSE_STATUS, CONFIDENTIAL aggregates and"
                    + " codes generated on a read-only snapshot and stored nowhere (P14-TSK-017, ADR-0115 As built; GET"
                    + " /v1/operator/accounting/periods/{periodId}/operational-reports/{definition}, ACCOUNTING_READ; the"
                    + " ReportRead and CloseRunRead precedent). Recorded SUCCEEDED in a transaction of its own before the"
                    + " answer, so nothing is served unrecorded. Its target the period; the summary the definition, its"
                    + " version, the month and the content's sha256 - never the content or a figure.",
            false),

    /** A person proposed a period's close (`P14-TSK-012`, ACCOUNTING_CLOSE_PROPOSE). */
    PERIOD_CLOSE_PROPOSED(
            "accounting.PeriodCloseProposed",
            "An accountant proposed closing a LOCKED accounting period on the attempts they read, a person's reason per DISCLOSED attempt frozen in the close record (P14-TSK-012,"
                    + " INV-ACC-09; POST .../periods/{id}/close-proposal, ACCOUNTING_CLOSE_PROPOSE). Reasoned. Its target the period; the summary its month, revision, the run, the"
                    + " proposal and the attempts disclosed by check and number - never a reason or a figure. accounting.AccountingPeriodCloseProposed is published with it.",
            true),

    /** A second person approved a period's close (`P14-TSK-012`, ACCOUNTING_CLOSE_APPROVE). */
    PERIOD_CLOSED(
            "accounting.PeriodClosed",
            "A person other than its proposer approved an accounting period's close: CLOSE_PROPOSED -> CLOSED, on the proposal they read, what it read still standing (P14-TSK-012,"
                    + " INV-ACC-09, INV-AUD-04; POST .../periods/{id}/close-approval, ACCOUNTING_CLOSE_APPROVE). Reasoned. Its target the period; the summary its month, revision, the run, the"
                    + " proposal and the valuation rate set. accounting.AccountingPeriodClosed is published with it.",
            true),

    /** A proposal was rejected (`P14-TSK-012`, ACCOUNTING_CLOSE_APPROVE). */
    PERIOD_CLOSE_REJECTED(
            "accounting.PeriodCloseRejected",
            "A holder of ACCOUNTING_CLOSE_APPROVE rejected a period's close proposal: CLOSE_PROPOSED -> LOCKED, the same lock and run, the proposal kept; a next proposal is a new row"
                    + " (P14-TSK-012; POST .../periods/{id}/close-rejection). Reasoned. May be the proposer's. Its target the period; the summary its month, revision, the proposal and whether"
                    + " the rejecter proposed it.",
            true),

    /** A person proposed reopening the latest closed period (`P14-TSK-012`, ACCOUNTING_CLOSE_PROPOSE). */
    PERIOD_REOPEN_PROPOSED(
            "accounting.PeriodReopenProposed",
            "An accountant proposed reopening the latest CLOSED accounting period - nothing moves until a second person approves (P14-TSK-012, INV-ACC-10, INV-AUD-04; POST"
                    + " .../periods/{id}/reopen-proposal, ACCOUNTING_CLOSE_PROPOSE). Reasoned. Its target the period; the summary its month, revision and how many adjustments stand posted against it, which the act named (P14-TSK-022).",
            true),

    /** A second person approved a reopen (`P14-TSK-012`, ACCOUNTING_CLOSE_APPROVE). */
    PERIOD_REOPENED(
            "accounting.PeriodReopened",
            "A person other than its proposer approved a period's reopen: CLOSED -> OPEN at revision + 1, the ledger's posting cutoff back exactly one period, the period's FINAL report runs"
                    + " SUPERSEDED and the pin released, in one transaction; nothing deleted (P14-TSK-012, INV-ACC-10, INV-AUD-04; POST .../periods/{id}/reopen-approval, ACCOUNTING_CLOSE_APPROVE)."
                    + " Reasoned. Its target the period; the summary its month, the new revision, how many runs were superseded and how many adjustments stand posted against it, which the act named (P14-TSK-022). accounting.AccountingPeriodReopened is published with it.",
            true),

    /** A controller read what it decides (`P14-TSK-012`, ACCOUNTING_CLOSE_APPROVE) - every serving recorded. */
    CLOSE_PROPOSAL_READ(
            "accounting.CloseProposalRead",
            "A controller read a period's close proposal - the attempts it names with their evidence and the person's reason per DISCLOSED attempt (P14-TSK-012; GET"
                    + " .../periods/{id}/close-proposal, ACCOUNTING_CLOSE_APPROVE; a controller holds no ACCOUNTING_READ). Recorded SUCCEEDED in a transaction of its own before the answer. Its"
                    + " target the period; the summary its month, the proposal, its status and whether it still stands - never a reason or a figure.",
            false),

    /** An accountant proposed a prior-period adjustment (`P14-TSK-013`, ACCOUNTING_ADJUST_PROPOSE). */
    PRIOR_PERIOD_ADJUSTMENT_PROPOSED(
            "accounting.PriorPeriodAdjustmentProposed",
            "An accountant proposed correcting a CLOSED accounting period at the revision they read, dated today in the OPEN month holding"
                    + " today - nothing posts until a second person approves (P14-TSK-013, INV-ACC-10, INV-REV-04; POST"
                    + " /v1/operator/accounting/prior-period-adjustments, ACCOUNTING_ADJUST_PROPOSE). Reasoned. Recorded with"
                    + " ledger.AdjustmentProposed, whose reason is identifiers only. Its target the adjustment; the summary the corrected month,"
                    + " revision, target month, posting date, the ledger's proposal and the counts of lines and earlier corrections - never an"
                    + " amount.",
            true),

    /** A second person approved a prior-period adjustment and its entry posted (`P14-TSK-013`, ACCOUNTING_ADJUST_APPROVE). */
    PRIOR_PERIOD_ADJUSTMENT_POSTED(
            "accounting.PriorPeriodAdjustmentPosted",
            "A person other than its proposer approved a prior-period adjustment, naming the date, the revision and the month's posted"
                    + " corrections they read; the ledger's ACCOUNTING-origin entry posted in the same transaction (P14-TSK-013, INV-ACC-10,"
                    + " INV-AUD-04, INV-LED-01; POST .../prior-period-adjustments/{id}/approval, ACCOUNTING_ADJUST_APPROVE). Reasoned. Recorded with"
                    + " ledger.AdjustmentPosted; accounting.PriorPeriodAdjustmentPosted and ledger.JournalEntryPosted are published with it. Its"
                    + " target the adjustment; the summary the months, revision, posting date, proposal, entry and the count of earlier posted"
                    + " corrections - never an amount.",
            true),

    /** A prior-period adjustment was rejected (`P14-TSK-013`, ACCOUNTING_ADJUST_APPROVE). */
    PRIOR_PERIOD_ADJUSTMENT_REJECTED(
            "accounting.PriorPeriodAdjustmentRejected",
            "A holder of ACCOUNTING_ADJUST_APPROVE rejected a standing prior-period adjustment, the ledger's proposal with it - final; the way on"
                    + " for one that can no longer post, so it asks nothing of either month (P14-TSK-013; POST"
                    + " .../prior-period-adjustments/{id}/rejection). Reasoned. May be the proposer's. Recorded with ledger.AdjustmentRejected. Its"
                    + " target the adjustment; the summary the corrected month, revision, proposal and whether the rejecter proposed it.",
            true),

    /** An operator read prior-period adjustments (`P14-TSK-013`) - every serving recorded. */
    PRIOR_PERIOD_ADJUSTMENT_READ(
            "accounting.PriorPeriodAdjustmentRead",
            "An operator read prior-period adjustments - a controller one with its lines and the proposer's reason (GET"
                    + " .../prior-period-adjustments/{id}), or those standing (GET .../prior-period-adjustments), under"
                    + " ACCOUNTING_ADJUST_APPROVE; an accountant a month's (GET .../periods/{periodId}/prior-period-adjustments), under"
                    + " ACCOUNTING_READ (P14-TSK-013, INV-ACC-10). Recorded before it is served, in a transaction of its own. Its target the"
                    + " adjustment or the period; the summary the door and a count or a status - never an amount or a reason.",
            false),

    /** A controller read a FINAL statement (`P14-TSK-022`, ACCOUNTING_CLOSE_APPROVE) - every serving recorded. */
    FINAL_STATEMENT_READ(
            "accounting.FinalStatementRead",
            "A holder of ACCOUNTING_CLOSE_APPROVE read a FINAL BALANCE_SHEET or INCOME_STATEMENT, its routes withheld, so a controller can read"
                    + " the statement of a close it approved (P14-TSK-022, the owner's ruling (v); GET .../report-runs/{runId}/final-statement)."
                    + " Recorded before it is served, in a transaction of its own; a failed record fails the read. No other definition or status"
                    + " is served, so nothing is recorded for one. Its target the run; the summary the definition, the period, its revision and"
                    + " status as read, the run's revision and its issued hash - never a figure.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
