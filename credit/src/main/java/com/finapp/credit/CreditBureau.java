package com.finapp.credit;

import java.util.EnumSet;
import java.util.Set;

/**
 * The credit bureau boundary (`P10-TSK-005`; ADR-0085 section 2, ADR-0008's port shape): where the
 * person's credit report comes from.
 *
 * <p><strong>Verdicts, never vocabulary</strong> ({@code INV-PAY-03}'s rule, applied to credit
 * data): no provider status, path or field crosses this port. An adapter normalises its wire onto
 * the closed {@link BureauAnswer} - data, partial data or unavailable - totally, and never throws
 * for a provider fault. Whatever it does not understand is {@code Unavailable}, which carries no
 * attribute, so a faulty bureau can never approve anything ({@code INV-CRD-10}).
 *
 * <p><strong>Our reference is the provider's idempotency key</strong>: ten instances pulling under
 * one reference cost one counted pull, and a retry after a lost response answers the first.
 *
 * <p>The adapter records its normaliser version on every answer; replay reads the stored attributes
 * and never re-normalises, so a normaliser change cannot move a past decision ({@code INV-CRD-01}).
 */
public interface CreditBureau {

    /** The attribute codes a bureau's answer normalises to - each present, or stated absent. */
    Set<CreditAttributeCode> ATTRIBUTES = Set.copyOf(EnumSet.of(
            CreditAttributeCode.BUREAU_EXTERNAL_SCORE,
            CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS,
            CreditAttributeCode.BUREAU_DELINQUENCIES_24M,
            CreditAttributeCode.BUREAU_DEFAULTS_72M,
            CreditAttributeCode.BUREAU_INSOLVENCY_FLAG,
            CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
            CreditAttributeCode.BUREAU_TOTAL_BALANCE));

    /** The bureau's code - its declaration's, its evidence's and its meter's. */
    String code();

    /** Pulls the subject's report under our reference. Never throws for a provider fault. */
    BureauAnswer pull(BureauRequest request);
}
