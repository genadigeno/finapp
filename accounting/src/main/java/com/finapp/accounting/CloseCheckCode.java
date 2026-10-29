package com.finapp.accounting;

/**
 * The close run's readiness checks (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.5; ADR-0114, ADR-0117) - the nine
 * a {@code LOCKED} period's close run claims and runs, leaderless, each a born-once attempt row
 * ({@code UNIQUE (close_run_id, check_code, attempt)}, `P14-TSK-011`); a period is proposed for close only with every
 * check {@code PASSED} or {@code DISCLOSED} by a reasoned record ({@code INV-ACC-09}). Which checks may be disclosed and
 * which must pass is the close run's rule (`-011`), not this vocabulary's. Each check reads through a port - it never
 * re-implements a proof the owning domain already has.
 */
public enum CloseCheckCode {

    /** The ledger's trial balance is zero per currency, cumulative and for the period ({@code INV-ACC-01}). */
    LEDGER_TRIAL_BALANCE,

    /** GL = ledger per period and currency under the pinned version, every line mapped exactly once ({@code INV-ACC-06}). */
    GL_EQUALS_LEDGER,

    /** Every entry in the period has a registered namespace and a conforming line shape ({@code INV-ACC-12}). */
    RULE_CONFORMANCE,

    /** The product proofs' verdicts through {@code SourceProofs}: no missing, duplicate or discrepant entry. */
    SOURCE_COMPLETENESS,

    /** Lending's and BNPL's accruals caught up through the period's end, through {@code AccrualCompleteness} (C3). */
    ACCRUALS_COMPLETE,

    /** The ledger's balance projections verified against the journal. */
    PROJECTION_VERIFIED,

    /** Reconciliation's open breaks, suspense balance and ageing as of the cutoff, disclosed ({@code INV-ACC-05}). */
    RECONCILIATION_DISCLOSURE,

    /** The period's valuation rate set approved under four eyes ({@code INV-ACC-11}). */
    VALUATION_SET_APPROVED,

    /** The period's own accounting breaks resolved or acknowledged with a disclosure. */
    OPEN_ACCOUNTING_BREAKS
}
