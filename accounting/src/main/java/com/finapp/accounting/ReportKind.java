package com.finapp.accounting;

/**
 * The report definitions a report run is generated from (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.6;
 * ADR-0115) - each a versioned definition in code producing an immutable run: {@code FINAL} for a {@code LOCKED} or
 * {@code CLOSED} period (reproducible, {@code INV-ACC-04}), {@code PROVISIONAL} for an open one (labelled, never
 * issued). {@link #TRIAL_BALANCE}'s completed run is {@code DELIVERY_PLAN.md}'s {@code TrialBalanceGenerated}. The
 * existing live operator reports of lending, bnpl, reconciliation and merchant stay operational and are not kinds here.
 */
public enum ReportKind {

    /** Per GL account and currency: opening, debits, credits, closing; zero per currency; equal to the ledger. */
    TRIAL_BALANCE,
    /** Per GL account, drill-down to the journal lines. */
    GL_ACTIVITY,
    /** Closing balances per GL account and currency. */
    BALANCES,
    /** Fee and interest revenue. */
    FEE_AND_INTEREST_REVENUE,
    /** Receivables and payables. */
    RECEIVABLES_AND_PAYABLES,
    /** Settlement and clearing positions. */
    SETTLEMENT_AND_CLEARING_POSITIONS,
    /** Suspense, with reconciliation's ageing. */
    SUSPENSE,
    /** Realised FX gains and losses, the spread and the rounding residual. */
    FX_GAINS_AND_LOSSES,
    /** Lending's portfolio re-stated as of the period's end, through a port. */
    LOAN_PORTFOLIO,
    /** BNPL's exposure re-stated as of the period's end, through a port. */
    BNPL_EXPOSURE,
    /** Reconciliation's status as of the cutoff. */
    RECONCILIATION_STATUS,
    /** The period's close run and its checks. */
    CLOSE_STATUS,
    /** The balance sheet, from the statement sections; retained earnings computed (C8). */
    BALANCE_SHEET,
    /** The income statement, from the statement sections; the current-period result computed (C8). */
    INCOME_STATEMENT
}
