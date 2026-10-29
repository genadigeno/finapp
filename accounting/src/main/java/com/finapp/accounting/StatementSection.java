package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * The financial statement section a GL account is presented in (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 13.1,
 * 13.6; ADR-0113 decision 3) - each GL account of a mapping version names one, and the balance sheet and the income
 * statement (`P14-TSK-016`) are read from them.
 *
 * <p><strong>Reference sections, for qualified review</strong> (owner decision C2: the chart's codes and sections are
 * illustrative, not a statutory chart). What is not reference is the rule each section carries: a section belongs to
 * exactly one statement and admits only classifications of that statement - a balance-sheet section never presents a
 * revenue, an income-statement section never an asset - so a GL account whose section does not admit its
 * classification is refused at the mapping version's activation (`P14-TSK-003`). Two sections admit two
 * classifications on purpose: {@link #SUSPENSE} (an unmatched item parks either way, and is disclosed,
 * {@code INV-ACC-05}) and {@link #FX_RESULT} (realised gains and losses, the spread and the rounding residual presented
 * together). Retained earnings and the current-period result are computed in the statement, never mapped (owner decision
 * C8), so no section exists for them.
 */
public enum StatementSection {

    CASH_AND_BANK(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.ASSET)),
    CLEARING_AND_SETTLEMENT_RECEIVABLES(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.ASSET)),
    LOANS_AND_BNPL_RECEIVABLES(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.ASSET)),
    OTHER_ASSETS(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.ASSET)),
    CUSTOMER_FUNDS(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.LIABILITY)),
    MERCHANT_PAYABLES(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.LIABILITY)),
    CLEARING_AND_SETTLEMENT_PAYABLES(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.LIABILITY)),
    OTHER_LIABILITIES(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.LIABILITY)),
    SUSPENSE(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.ASSET, GlClassification.LIABILITY)),
    CAPITAL(Statement.BALANCE_SHEET, EnumSet.of(GlClassification.EQUITY)),
    FEE_AND_COMMISSION_INCOME(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.REVENUE)),
    INTEREST_INCOME(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.REVENUE)),
    FX_RESULT(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.REVENUE, GlClassification.EXPENSE)),
    OTHER_INCOME(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.REVENUE)),
    PROCESSING_AND_DISPUTE_COSTS(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.EXPENSE)),
    CREDIT_LOSSES(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.EXPENSE)),
    OTHER_EXPENSES(Statement.INCOME_STATEMENT, EnumSet.of(GlClassification.EXPENSE));

    /** The two statements a section can belong to. */
    public enum Statement {
        BALANCE_SHEET,
        INCOME_STATEMENT
    }

    private final Statement statement;
    private final Set<GlClassification> admitted;

    StatementSection(Statement statement, Set<GlClassification> admitted) {
        this.statement = statement;
        this.admitted = Set.copyOf(admitted);
    }

    /** The statement this section is presented in. */
    public Statement statement() {
        return statement;
    }

    /** The classifications a GL account presented here may carry. Immutable. */
    public Set<GlClassification> admitted() {
        return admitted;
    }

    /** True when a GL account of {@code classification} may be presented in this section. */
    public boolean admits(GlClassification classification) {
        return admitted.contains(classification);
    }
}
