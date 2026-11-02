package com.finapp.accounting;

/**
 * The report definitions a report run is generated from (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.6;
 * ADR-0115) - each a versioned definition in code producing an immutable run: {@code FINAL} for a {@code LOCKED} or
 * {@code CLOSED} period (reproducible, {@code INV-ACC-04}), {@code PROVISIONAL} for an open one (labelled, never
 * issued). {@link #TRIAL_BALANCE}'s completed run is {@code DELIVERY_PLAN.md}'s {@code TrialBalanceGenerated}. The
 * existing live operator reports of lending, bnpl, reconciliation and merchant stay operational and are not kinds here.
 * *(`P14-TSK-017` renamed four to the entry's and ADR-0115's codes - a definition's code is its kind's name - and two are
 * never a run: {@code OperationalReportDefinition}.)*
 */
public enum ReportKind {

    /** Per GL account and currency: opening, debits, credits, closing; zero per currency; equal to the ledger. */
    TRIAL_BALANCE,
    /** Per GL account, drill-down to the journal lines. */
    GL_ACTIVITY,
    /** Closing balances per GL account and currency. */
    BALANCES,
    /** Fee, interest and spread revenue: the period's movement by purpose. */
    REVENUE,
    /** Receivables and payables: balances by purpose as of the period's end. */
    RECEIVABLES_PAYABLES,
    /** Settlement and clearing positions, and cash: balances by purpose as of the period's end. */
    SETTLEMENT_POSITIONS,
    /** Suspense: the ledger's balance, with the register's items and ageing as of the cutoff. */
    SUSPENSE,
    /** Realised FX gains and losses, the spread and the rounding residual. */
    FX_RESULTS,
    /** The loan book restated as of the period's end from ledger balances by purpose, never lending's live rows. */
    LOAN_PORTFOLIO,
    /** The BNPL book restated as of the period's end from ledger balances by purpose, never bnpl's live rows. */
    BNPL_EXPOSURE,
    /** Operational, never a run: the month's reconciliation runs, reconciliation's disclosure, past-the-cutoff postings. */
    RECONCILIATION_STATUS,
    /** Operational, never a run: the close run's state, its checks, the breaks and the approved valuation rate set. */
    CLOSE_STATUS,
    /** The balance sheet, from the statement sections; retained earnings computed (C8). */
    BALANCE_SHEET,
    /** The income statement, from the statement sections; the current-period result computed (C8). */
    INCOME_STATEMENT
}
