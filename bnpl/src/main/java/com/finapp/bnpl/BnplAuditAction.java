package com.finapp.bnpl;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The bnpl module's audited acts (`P12-TSK-004`; {@code INV-AUD-01}, {@code INV-AUD-04}; {@code PHASE_12_PLAN.md} section
 * 11): every act on a BNPL terms version, with its reason, and the refused self-approval. Never an amount, a rate or a
 * reason in a summary ({@code INV-AUD-02}). Later tasks add their own beside these.
 */
@RequiredArgsConstructor
public enum BnplAuditAction implements AuditableAction {

    /** A BNPL officer proposed a new version of a product's terms. */
    TERMS_VERSION_PROPOSED(
            "bnpl.TermsVersionProposed",
            "A holder of BNPL_ADMINISTER proposed a whole new version of a BNPL product's terms - plans, rate (pay-in-N),"
                    + " late fee, allocation, delinquency bounds, the dispute window, the offer validity cap, engines and"
                    + " template, judged complete and within bounds (bnpl.TermsInvalid, bnpl.TermsNotAmortising otherwise)"
                    + " and immutable from this moment (INV-HIST-04) - awaiting a DIFFERENT holder's activation"
                    + " (INV-AUD-04). The summary names the product and the version number only.",
            true),

    /** A different BNPL officer activated a terms version, retiring its predecessor. */
    TERMS_VERSION_ACTIVATED(
            "bnpl.TermsVersionActivated",
            "A holder of BNPL_ADMINISTER other than its proposer activated a BNPL terms version (INV-AUD-04, also held by"
                    + " bnpl V002's four-eyes CHECK), retiring the product's predecessor in the same transaction so one"
                    + " version is in force at every instant; the event bnpl.BnplTermsVersionActivated carries the version."
                    + " Every later offer pins it; existing agreements keep theirs (INV-HIST-04).",
            true),

    /** A terms version was rejected, or withdrawn by its proposer. */
    TERMS_VERSION_REJECTED(
            "bnpl.TermsVersionRejected",
            "A proposed BNPL terms version was rejected - or withdrawn by its proposer; it was never in force and the"
                    + " product is free for a new proposal.",
            true),

    /** A proposer tried to approve their own proposal and was refused. */
    SELF_APPROVAL_REFUSED(
            "bnpl.SelfApprovalRefused",
            "A proposer tried to approve their own BNPL proposal - a terms version's activation (act=terms:activate) -"
                    + " and was refused before anything was written (INV-AUD-04; also held by the table's four-eyes"
                    + " CHECK). Recorded FAILED in a transaction of its own after the act rolled back, so the attempt"
                    + " survives (the Phase 11 completion gate's R4, applied from bnpl's first door). Its target the"
                    + " proposal; the summary names it and the act - never an amount.",
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
