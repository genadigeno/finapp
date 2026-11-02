package com.finapp.accounting;

/**
 * The close run's readiness checks (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.5; ADR-0114, ADR-0117) - the nine
 * a {@code LOCKED} period's close run claims and runs, leaderless, each a born-once attempt row
 * ({@code UNIQUE (close_run_id, check_code, attempt)}, `P14-TSK-011`); a period is proposed for close only with every
 * check {@code PASSED} or {@code DISCLOSED} by a reasoned record ({@code INV-ACC-09}). Each check reads through a port - it
 * never re-implements a proof the owning domain already has.
 *
 * <p><strong>Which may be disclosed</strong> (`P14-TSK-011`; {@link #disclosable()}, and {@code accounting V007}'s
 * {@code close_check_disclosed_only_where_it_may} for every writer): a check whose subject can stand on the record with the
 * close going ahead - a break acknowledged under four eyes, an open reconciliation position, an accrual that landed past
 * the cutoff. The trial balance, GL = ledger, the projections and the valuation set are {@code PASSED} or they block.
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
    OPEN_ACCOUNTING_BREAKS;

    /** True when an attempt may conclude {@code DISCLOSED}; every other check is {@code PASSED} or it blocks the close. */
    public boolean disclosable() {
        return switch (this) {
            case RULE_CONFORMANCE, SOURCE_COMPLETENESS, ACCRUALS_COMPLETE, RECONCILIATION_DISCLOSURE,
                    OPEN_ACCOUNTING_BREAKS -> true;
            case LEDGER_TRIAL_BALANCE, GL_EQUALS_LEDGER, PROJECTION_VERIFIED, VALUATION_SET_APPROVED -> false;
        };
    }

    /** True for the three checks that raise accounting breaks ({@link AccountingChecks}). */
    public boolean raisesBreaks() {
        return BreakFinding.CHECKS.contains(this);
    }
}
