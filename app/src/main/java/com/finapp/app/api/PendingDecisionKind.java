package com.finapp.app.api;

import java.util.List;
import java.util.Objects;

/**
 * Every kind of proposal a pending-decision queue serves (`P17-TSK-127`), and what its subject is: the one vocabulary the
 * {@link PendingDecisions.PendingDecision#kind()} and {@link PendingDecisions.PendingDecision#subjectKind()} fields are
 * drawn from. A desk names its kind by a constant here, never by a string, so no queue can serve a kind this list does not
 * hold, and the published contract's {@code x-extensible-enum} for both fields is derived from this list, never hand-kept
 * ({@code OpenApiDocument}).
 *
 * <p>This is a vocabulary, not an approval service (finapp-admin-panel ADR-C133 decision 4): every queue is still read from its
 * own domain's store, and every decision still taken at that domain's own doors.
 *
 * <p><strong>The wire name</strong> is {@code <module>.<ProposalKind>} ({@code payments.RefundProposal}), the form every desk
 * has emitted since `P17-TSK-040`. <strong>The subject kinds</strong> are the domain's own upper-case words, the ones the
 * domain's single views already serve ({@code FEE_SCHEDULE}, {@code MERCHANT}, {@code OUTBOX_EVENT}, {@code PAIR}): read with
 * the kind, so {@code PRODUCT} under {@code lending.TermsVersion} is a lending product. A kind with no subject kind acts on
 * nothing but itself; a kind with two names its subject's kind row by row.
 *
 * <p>The list is open: a new four-eyes domain adds a constant, and a client tolerates a kind it does not know, which is why
 * the contract publishes it as an extensible enumeration rather than a closed {@code enum}.
 */
public enum PendingDecisionKind {
    ACCOUNTING_MAPPING_VERSION("accounting.MappingVersion"),
    ACCOUNTING_VALUATION_RATE_SET("accounting.ValuationRateSet", "PERIOD"),
    ACCOUNTING_CLOSE_REQUEST("accounting.CloseRequest", "PERIOD"),
    ACCOUNTING_REOPEN_REQUEST("accounting.ReopenRequest", "PERIOD"),
    ACCOUNTING_UNLOCK_REQUEST("accounting.UnlockRequest", "PERIOD"),
    ACCOUNTING_PRIOR_PERIOD_ADJUSTMENT("accounting.PriorPeriodAdjustment", "PERIOD"),
    ACCOUNTING_REGULATORY_OBLIGATION("accounting.RegulatoryObligation"),
    ACCOUNTING_REGULATORY_SUBMISSION("accounting.RegulatorySubmission", "PERIOD"),
    ACCOUNTS_RESTRICTION("accounts.Restriction", "ACCOUNT"),
    BNPL_WAIVER("bnpl.Waiver", "AGREEMENT"),
    BNPL_REPAYMENT_REVERSAL("bnpl.RepaymentReversal", "AGREEMENT"),
    BNPL_TERMS_VERSION("bnpl.TermsVersion", "PRODUCT"),
    BNPL_REFUND_PROPOSAL("bnpl.RefundProposal", "PURCHASE"),
    BNPL_DISPUTE_RESOLUTION("bnpl.DisputeResolution", "PURCHASE"),
    BNPL_WRITE_OFF("bnpl.WriteOff", "AGREEMENT"),
    BNPL_FEE_ASSIGNMENT("bnpl.FeeAssignment", "MERCHANT"),
    CREDIT_POLICY_VERSION("credit.PolicyVersion", "PRODUCT"),
    CREDIT_SCORECARD_VERSION("credit.ScorecardVersion", "SCORECARD_FAMILY"),
    CREDIT_REVIEW_CASE_DECISION("credit.ReviewCaseDecision", "DECISION_REQUEST"),
    CROSSBORDER_CORRIDOR_ENABLE_REQUEST("crossborder.CorridorEnableRequest", "CORRIDOR"),
    CROSSBORDER_CORRIDOR_POLICY("crossborder.CorridorPolicy", "POLICY_VERSION"),
    DEADLETTER_DISCARD("deadletter.Discard", "OUTBOX_EVENT", "INBOX_RECORD"),
    FX_ENABLE_REQUEST("fx.EnableRequest", "PAIR", "PROVIDER"),
    FX_PRICING_POLICY("fx.PricingPolicy", "POLICY_VERSION"),
    FX_TRADE_REVERSAL("fx.TradeReversal", "TRADE"),
    LEDGER_ADJUSTMENT("ledger.Adjustment"),
    LEDGER_PROJECTION_REBUILD("ledger.ProjectionRebuild", "LEDGER_ACCOUNT"),
    LENDING_TERMS_VERSION("lending.TermsVersion", "PRODUCT"),
    LENDING_CAPITAL_CONTRIBUTION("lending.CapitalContribution", "CURRENCY"),
    LENDING_WAIVER("lending.Waiver", "LOAN"),
    LENDING_AMENDMENT("lending.Amendment", "LOAN"),
    LENDING_REPAYMENT_REVERSAL("lending.RepaymentReversal", "LOAN"),
    LENDING_WRITE_OFF("lending.WriteOff", "LOAN"),
    MERCHANT_FEE_SCHEDULE_VERSION("merchant.FeeScheduleVersion", "FEE_SCHEDULE"),
    MERCHANT_FEE_ASSIGNMENT("merchant.FeeAssignment", "MERCHANT"),
    MERCHANT_PAYOUT_DESTINATION("merchant.PayoutDestination", "MERCHANT"),
    MERCHANT_PAYOUT_PROPOSAL("merchant.PayoutProposal", "MERCHANT"),
    MERCHANT_CLOSURE("merchant.Closure", "MERCHANT"),
    MERCHANT_BNPL_ENABLEMENT("merchant.BnplEnablement", "MERCHANT"),
    PAYMENTS_REFUND_PROPOSAL("payments.RefundProposal", "PAYMENT"),
    RECONCILIATION_BREAK_RESOLUTION("reconciliation.BreakResolution", "BREAK"),
    RECONCILIATION_BATCH_REPUDIATION("reconciliation.BatchRepudiation", "SETTLEMENT_BATCH"),
    RECONCILIATION_RULE_SET("reconciliation.RuleSet", "SOURCE"),
    RISK_SUPPRESSION("risk.Suppression", "ALERT"),
    RISK_DISPOSITION("risk.Disposition", "CASE"),
    RISK_ACTION("risk.RiskAction", "CASE"),
    RISK_OVERRIDE("risk.Override", "ASSESSMENT"),
    RISK_REGULATORY_REPORT("risk.RegulatoryReport", "CASE"),
    RISK_POLICY_VERSION("risk.PolicyVersion", "OPERATION"),
    RISK_SCREENING_LIST_VERSION("risk.ScreeningListVersion", "SCREENING_LIST"),
    TRANSFERS_REVERSAL("transfers.Reversal", "TRANSFER");

    private final String wire;
    private final List<String> subjectKinds;

    PendingDecisionKind(String wire, String... subjectKinds) {
        this.wire = wire;
        this.subjectKinds = List.of(subjectKinds);
    }

    /** The name served in {@code kind}: {@code <module>.<ProposalKind>}. */
    public String wire() {
        return wire;
    }

    /** Every subject kind an item of this kind can name; empty where it acts on nothing but itself. */
    public List<String> subjectKinds() {
        return subjectKinds;
    }

    /**
     * The subject kind of an item naming {@code subjectId}: none where there is no subject, the kind's one subject kind where
     * it has one. A kind with two subject kinds names its subject's kind row by row ({@link #subjectKind(String, Object)}).
     */
    String subjectKind(Object subjectId) {
        if (subjectId == null || subjectKinds.isEmpty()) {
            return null;
        }
        if (subjectKinds.size() > 1) {
            throw new IllegalStateException(wire + " names its subject's kind row by row: one of " + subjectKinds);
        }
        return subjectKinds.get(0);
    }

    /** {@code named}, checked against this kind's subject kinds, so no item serves a subject kind the contract does not list. */
    String subjectKind(String named, Object subjectId) {
        if (subjectId == null) {
            return null;
        }
        Objects.requireNonNull(named, "subjectKind");
        if (!subjectKinds.contains(named)) {
            throw new IllegalArgumentException(wire + " has no subject kind " + named + ": one of " + subjectKinds);
        }
        return named;
    }
}
