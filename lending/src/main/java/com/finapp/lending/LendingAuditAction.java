package com.finapp.lending;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The lending module's audited acts (`P11-TSK-004`, {@code INV-AUD-01}, {@code INV-AUD-04}; PHASE_11_PLAN.md section
 * 11): every act on a lending-capital contribution, with its reason, and every serving of a lending report. Never an
 * amount, a reference or a balance in a summary ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public enum LendingAuditAction implements AuditableAction {

    /** A treasury officer proposed a contribution of the platform's own money to lending capital. */
    CAPITAL_CONTRIBUTION_PROPOSED(
            "lending.CapitalContributionProposed",
            "A holder of LENDING_CAPITAL_ADMINISTER proposed a contribution of the platform's own money to lending capital"
                    + " - currency, amount and the bank reference its transfer will carry - awaiting a DIFFERENT holder's"
                    + " approval (INV-AUD-04). Never capital by itself (INV-LND-13). The summary names the currency only.",
            true),

    /** A different holder approved a contribution, opening its expectation. */
    CAPITAL_CONTRIBUTION_APPROVED(
            "lending.CapitalContributionApproved",
            "A holder of LENDING_CAPITAL_ADMINISTER other than its proposer approved a lending-capital contribution"
                    + " (INV-AUD-04, also held by lending V002's four-eyes CHECK), opening its"
                    + " LENDING_CAPITAL_CONTRIBUTION expectation in the same transaction; capital exists only once bank"
                    + " recognition matches the statement line (INV-LND-13, INV-SET-06).",
            true),

    /** A contribution was rejected, or withdrawn by its proposer. */
    CAPITAL_CONTRIBUTION_REJECTED(
            "lending.CapitalContributionRejected",
            "A proposed lending-capital contribution was rejected - or withdrawn by its proposer; no expectation was"
                    + " opened and no capital can arise from it.",
            true),

    /** A holder of LENDING_INVESTIGATE was served a lending report. */
    REPORT_READ(
            "lending.ReportRead",
            "A holder of LENDING_INVESTIGATE was served a lending report - since P11-TSK-004 the capital report: per"
                    + " currency the recognised capital, the committed and outstanding principal, the headroom and the"
                    + " safeguarding term, and each contribution's derived recognition - read in one REPEATABLE READ"
                    + " snapshot and committed with this record, so no report is served without it. Amounts are an"
                    + " audited report, never a metric (ADR-0072). The summary names the report and its row counts.",
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
