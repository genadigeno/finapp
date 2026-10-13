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

    /** The platform disbursed a loan: posting rule 1, the loan ACTIVE, the schedule fixed - a system act. */
    LOAN_DISBURSED(
            "lending.LoanDisbursed",
            "The platform disbursed an instalment loan to the borrower's wallet (P11-TSK-014, ADR-0097; a system act - the"
                    + " acceptance is the instruction, no person disburses): posting rule 1 (DR LOAN_PRINCIPAL / CR the"
                    + " wallet, less an origination fee to LOAN_FEE_INCOME) under the key lending.disbursement:<loan>, the"
                    + " disbursement POSTED and the loan ACTIVE, the accrual start and schedule version 1 fixed, all in one"
                    + " transaction under the party's credit profile lock and the loan's row lock. Its target the loan; the"
                    + " summary names the loan, the journal entry, the value date and the path - never an amount or the"
                    + " wallet (INV-AUD-02). Losers of a race and retries record nothing.",
            false),

    /** A holder of LENDING_INVESTIGATE was served a lending report. */
    REPORT_READ(
            "lending.ReportRead",
            "A holder of LENDING_INVESTIGATE was served a lending report - since P11-TSK-004 the capital report: per"
                    + " currency the recognised capital, the committed and outstanding principal, the headroom and the"
                    + " safeguarding term, and each contribution's derived recognition - read in one REPEATABLE READ"
                    + " snapshot and committed with this record, so no report is served without it. Amounts are an"
                    + " audited report, never a metric (ADR-0072). The summary names the report and its row counts.",
            false),

    /** A loan servicing agent proposed the reversal of one repayment. */
    REPAYMENT_REVERSAL_PROPOSED(
            "lending.RepaymentReversalProposed",
            "A holder of LOAN_SERVICE proposed the exact reversal of one repayment of a loan not of their own party"
                    + " (P11-TSK-020, ADR-0099 point 4), with a reason screened for card and account numbers, awaiting a"
                    + " DIFFERENT holder of LOAN_SERVICE_APPROVE (INV-AUD-04, INV-LND-11). Nothing is posted by a proposal."
                    + " The summary names the loan and the repayment - never an amount.",
            true),

    /** A different agent approved a repayment reversal: rule 10 posted, the allocations negated. */
    REPAYMENT_REVERSAL_APPROVED(
            "lending.RepaymentReversalApproved",
            "A holder of LOAN_SERVICE_APPROVE other than its proposer (also held by lending V016's four-eyes CHECK)"
                    + " approved a repayment reversal: posting rule 10 - the exact ReversalService swap of the repayment's"
                    + " entry, the wallet credited back - its allocations negated by rows, what it paid due again, in one"
                    + " transaction under the party's credit profile lock and the loan's row lock (INV-REV-02, INV-LND-11)."
                    + " The summary names the loan, the repayment and both journal entries - never an amount.",
            true),

    /** A repayment reversal was rejected, or withdrawn by its proposer. */
    REPAYMENT_REVERSAL_REJECTED(
            "lending.RepaymentReversalRejected",
            "A proposed repayment reversal was rejected - or withdrawn by its proposer; nothing was posted and the"
                    + " repayment may be proposed for reversal again.",
            true),

    /** A loan servicing agent read a loan account. */
    LOAN_READ(
            "lending.LoanRead",
            "A holder of LOAN_SERVICE read a loan account through the operator door (P11-TSK-020, ADR-0099 point 8): its"
                    + " state, agreement and balances derived from journal lines, its repayments with their derived status"
                    + " and its reversals - read in one REPEATABLE READ snapshot and committed with this record, so no read"
                    + " is served without it. The summary names the loan and the row counts - never an amount.",
            false),

    /** An operator tried to service a loan of their own party - refused, recorded. */
    SELF_DEALING_REFUSED(
            "lending.SelfDealingRefused",
            "An operator tried to propose, approve or reject a servicing correction on a loan of their own party - or"
                    + " is no identity the platform can place in a party - and was refused before anything was written"
                    + " (P11-TSK-020, ADR-0099 point 2, the Phase 10 to 11 transition's R1, INV-LND-11). Recorded FAILED in"
                    + " a transaction of its own after the act rolled back, so the attempt survives. The summary names the"
                    + " loan and the act.",
            false),

    /** A loan servicing agent proposed a fee or interest waiver. */
    WAIVER_PROPOSED(
            "lending.WaiverProposed",
            "A holder of LOAN_SERVICE proposed a waiver on a loan not of their own party (P11-TSK-025, A14, ADR-0099) - a"
                    + " fee's unpaid remainder (FEE), a paid fee refunded to the credit balance (FEE_REFUND) or billed"
                    + " interest (INTEREST) - with a reason screened for card and account numbers, within what is due now,"
                    + " awaiting a DIFFERENT holder of LOAN_SERVICE_APPROVE (INV-AUD-04, INV-LND-11). Nothing is posted by a"
                    + " proposal. The summary names the loan, the kind and the target - never an amount.",
            true),

    /** A different agent approved a waiver: rule 8, 8a or 9 posted. */
    WAIVER_APPROVED(
            "lending.WaiverApproved",
            "A holder of LOAN_SERVICE_APPROVE other than its proposer (also held by lending V021's four-eyes CHECK) approved"
                    + " a waiver, bounded by what is due now under the loan's row lock: posting rule 8 (ReversalService of the"
                    + " fee's rule 4 entry, bounded by it - INV-REV-02), 8a (DR LOAN_FEE_INCOME / CR LOAN_CREDIT_BALANCE) or 9"
                    + " (DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE), the account closed in the same transaction when it"
                    + " zeroed the last receivable (INV-LND-11, INV-LND-08). The summary names the loan, the kind, the target"
                    + " and the journal entry - never an amount.",
            true),

    /** A waiver was rejected, or withdrawn by its proposer. */
    WAIVER_REJECTED(
            "lending.WaiverRejected",
            "A proposed waiver was rejected - or withdrawn by its proposer; nothing was posted and the fee or interest may"
                    + " be proposed for waiver again.",
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
