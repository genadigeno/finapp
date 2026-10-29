package com.finapp.accounting;

/**
 * The classification of a GL account (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.1; ADR-0113 decision 3) - the
 * five elements of the accounting equation, named <strong>exactly</strong> as the ledger's account types are, so a
 * mapping rule is classification-compatible when the GL account's classification and the purpose's ledger type carry
 * the same name ({@code INV-ACC-06}; checked at activation through {@code LedgerChart} by `P14-TSK-003`, and held equal
 * to the ledger's vocabulary by {@code AccountingLedgerVocabularyTest} in {@code app}, the one module that sees both).
 *
 * <p>Strings, not the ledger's enum: {@code accounting} has no ledger edge, so the comparison is by name - the
 * {@code PostingRuleRegister}'s "purposes as strings" shape.
 */
public enum GlClassification {

    /** A resource the platform controls - cash, clearing receivables, loans and BNPL receivables. Debit-normal. */
    ASSET(true),

    /** An obligation - customer funds, merchant payables, clearing payables. Credit-normal. */
    LIABILITY(false),

    /** The residual interest - lending capital; retained earnings are computed, never posted (owner decision C8). */
    EQUITY(false),

    /** Income earned - fees, interest, the FX spread, realised gains. Credit-normal. */
    REVENUE(false),

    /** Cost incurred - processing and dispute costs, realised losses, write-offs. Debit-normal. */
    EXPENSE(true);

    private final boolean debitNormal;

    GlClassification(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    /** True when a debit increases an account of this classification (ASSET, EXPENSE). */
    public boolean debitNormal() {
        return debitNormal;
    }

    /** True for the balance sheet's three elements; false for the income statement's two. */
    public boolean balanceSheet() {
        return this == ASSET || this == LIABILITY || this == EQUITY;
    }
}
