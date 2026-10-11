package com.finapp.lending;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The lending module's audited acts (`P11-TSK-004`, `P11-TSK-005`; {@code INV-AUD-01}, {@code INV-AUD-04};
 * PHASE_11_PLAN.md section 11): every act on a lending-capital contribution and on a loan terms version, with its
 * reason, and every serving of a lending report. Never an amount, a rate, a reference or a balance in a summary
 * ({@code INV-AUD-02}).
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

    /** A lending officer proposed a new version of a product's terms. */
    TERMS_VERSION_PROPOSED(
            "lending.TermsVersionProposed",
            "A holder of LENDING_ADMINISTER proposed a whole new version of a loan product's terms - rate, fees,"
                    + " servicing conventions, engine and template versions, judged complete and within bounds"
                    + " (lending.TermsInvalid otherwise) and immutable from this moment (INV-LND-05) - awaiting a DIFFERENT"
                    + " holder's activation (INV-AUD-04). The summary names the product and the version number only.",
            true),

    /** A different lending officer activated a terms version, retiring its predecessor. */
    TERMS_VERSION_ACTIVATED(
            "lending.TermsVersionActivated",
            "A holder of LENDING_ADMINISTER other than its proposer activated a loan terms version (INV-AUD-04, also held"
                    + " by lending V003's four-eyes CHECK), retiring the product's predecessor in the same transaction so"
                    + " one version is in force at every instant; the event lending.LoanTermsVersionActivated carries the"
                    + " version. Every later offer pins it; existing agreements keep theirs (INV-HIST-04).",
            true),

    /** A terms version was rejected, or withdrawn by its proposer. */
    TERMS_VERSION_REJECTED(
            "lending.TermsVersionRejected",
            "A proposed loan terms version was rejected - or withdrawn by its proposer; it was never in force and the"
                    + " product is free for a new proposal.",
            true),

    /** A customer accepted a loan offer: the agreement committed, the decision consumed, the loan account opened. */
    OFFER_ACCEPTED(
            "lending.OfferAccepted",
            "A customer accepted their own loan offer from a MULTI_FACTOR session (P11-TSK-012, A19): agreement version 1"
                    + " committed as an exact copy of the offer's terms, credit's decision consumed (credit's own"
                    + " credit.DecisionConsumed beside it), the loan account and its six ledger accounts opened, exposure"
                    + " and - for a loan - capital committed, all in one transaction under the party's credit profile lock."
                    + " The summary names the offer, the loan and the agreement version - never an amount (INV-AUD-02);"
                    + " the acceptance evidence is its own record. Losers of a race record nothing.",
            false),

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
