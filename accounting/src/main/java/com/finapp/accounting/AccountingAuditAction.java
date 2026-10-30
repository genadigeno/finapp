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
            true);

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
