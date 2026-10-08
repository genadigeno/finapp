package com.finapp.credit;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The credit module's audited acts (`P10-TSK-006`, {@code INV-AUD-01}): every access to a person's credit data is
 * recorded where it is opened; every act on a scorecard model version (`P10-TSK-011`, {@code INV-AUD-04}) with its
 * reason.
 */
@RequiredArgsConstructor
public enum CreditAuditAction implements AuditableAction {

    /**
     * The platform opened a bureau data request on the applicant's behalf (`P10-TSK-006`, ADR-0085) - written once,
     * by the opener, in the transaction that births the request; a retry is the same access under the same reference
     * and is an attempt row, never a second act.
     */
    BUREAU_DATA_REQUESTED(
            "credit.BureauDataRequested",
            "The platform opened a credit bureau data request for a decision request, under a current lawful basis"
                    + " (INV-CRD-03); the summary names the request and the source kind, never an attribute.",
            false),

    /**
     * The platform opened a financial-data request on the applicant's behalf (`P10-TSK-007`, ADR-0085) - the bureau
     * act's twin for its own source, under its own purpose ({@code FINANCIAL_DATA_ACCESS}).
     */
    FINANCIAL_DATA_REQUESTED(
            "credit.FinancialDataRequested",
            "The platform opened a financial-data request for a decision request, under a current lawful basis for"
                    + " that source alone (INV-CRD-03); the summary names the request and the source kind, never a figure.",
            false),

    /** A holder of CREDIT_POLICY_ADMINISTER proposed a whole scorecard model version (`P10-TSK-011`, ADR-0086). */
    SCORECARD_VERSION_PROPOSED(
            "credit.ScorecardVersionProposed",
            "A holder of CREDIT_POLICY_ADMINISTER proposed a whole scorecard model version: its bands born with it and"
                    + " frozen by trigger (INV-CRD-05), awaiting a DIFFERENT person's activation (INV-AUD-04).",
            true),

    /** A different person activated a proposed scorecard model version, retiring its predecessor (`P10-TSK-011`). */
    SCORECARD_VERSION_ACTIVATED(
            "credit.ScorecardVersionActivated",
            "A different person activated a proposed scorecard model version (INV-AUD-04), retiring its predecessor in the"
                    + " same transaction; the event credit.ScorecardModelVersionActivated carries the version.",
            true),

    /** A proposed scorecard model version was rejected, or withdrawn by its proposer (`P10-TSK-011`). */
    SCORECARD_VERSION_REJECTED(
            "credit.ScorecardVersionRejected",
            "A proposed scorecard model version was rejected - or withdrawn by its proposer; no model changed.",
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
