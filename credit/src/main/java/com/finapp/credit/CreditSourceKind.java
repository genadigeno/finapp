package com.finapp.credit;

/**
 * The kinds of external source credit data is collected from (ADR-0085, PHASE_10_PLAN.md
 * section 3).
 *
 * <p>Each kind is its own processing of the person's data and needs its own recorded lawful
 * basis ({@code INV-CRD-03}): the {@link CreditConsentGate} is asked per kind, and a basis for one
 * kind never admits the other. A data request names its kind; a policy's maximum data age and its
 * unavailable-source fallback are per kind (P10-TSK-006, -007, -012).
 */
public enum CreditSourceKind {

    /** A credit bureau - the person's credit report. */
    BUREAU,

    /** A financial-data provider - the person's account and transaction data. */
    FINANCIAL_DATA
}
