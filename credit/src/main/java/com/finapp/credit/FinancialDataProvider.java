package com.finapp.credit;

import java.util.EnumSet;
import java.util.Set;

/**
 * The financial-data provider boundary (`P10-TSK-007`; ADR-0085 section 2): the applicant's verified monthly income
 * and committed expenditure, read from their accounts - a {@link CreditDataSource} of kind {@code FINANCIAL_DATA},
 * gated on its own purpose, {@code FINANCIAL_DATA_ACCESS}: a bureau consent never admits this pull
 * ({@code INV-CRD-03}).
 *
 * <p>Verified figures feed affordability (`P10-TSK-009`): income is the lesser of verified and declared, expenditure
 * the greater (PHASE_10_PLAN.md section 12.3), so a missing verified figure is an {@code Absent} the policy reasons
 * about, never a default.
 */
public interface FinancialDataProvider extends CreditDataSource {

    /** The attribute codes a financial-data answer normalises to - each present, or stated absent. */
    Set<CreditAttributeCode> ATTRIBUTES = Set.copyOf(EnumSet.of(
            CreditAttributeCode.FINDATA_MONTHLY_INCOME,
            CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE));

    @Override
    default CreditSourceKind kind() {
        return CreditSourceKind.FINANCIAL_DATA;
    }

    @Override
    default Set<CreditAttributeCode> attributes() {
        return ATTRIBUTES;
    }
}
