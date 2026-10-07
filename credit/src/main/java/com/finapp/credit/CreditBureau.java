package com.finapp.credit;

import java.util.EnumSet;
import java.util.Set;

/**
 * The credit bureau boundary (`P10-TSK-005`; ADR-0085 section 2): where the person's credit report comes from - a
 * {@link CreditDataSource} of kind {@code BUREAU}, gated on {@code CREDIT_BUREAU_ACCESS}.
 *
 * <p>The adapter records its normaliser version on every answer; replay reads the stored attributes and never
 * re-normalises, so a normaliser change cannot move a past decision ({@code INV-CRD-01}).
 */
public interface CreditBureau extends CreditDataSource {

    /** The attribute codes a bureau's answer normalises to - each present, or stated absent. */
    Set<CreditAttributeCode> ATTRIBUTES = Set.copyOf(EnumSet.of(
            CreditAttributeCode.BUREAU_EXTERNAL_SCORE,
            CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS,
            CreditAttributeCode.BUREAU_DELINQUENCIES_24M,
            CreditAttributeCode.BUREAU_DEFAULTS_72M,
            CreditAttributeCode.BUREAU_INSOLVENCY_FLAG,
            CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
            CreditAttributeCode.BUREAU_TOTAL_BALANCE));

    @Override
    default CreditSourceKind kind() {
        return CreditSourceKind.BUREAU;
    }

    @Override
    default Set<CreditAttributeCode> attributes() {
        return ATTRIBUTES;
    }
}
