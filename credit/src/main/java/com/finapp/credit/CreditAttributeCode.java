package com.finapp.credit;

import lombok.RequiredArgsConstructor;

/**
 * Every input the decision engine may read (PHASE_10_PLAN.md section 12.2, ADR-0085 section 3).
 *
 * <p><strong>Closed.</strong> A rule, the scorecard or the affordability arithmetic reads an
 * attribute by one of these codes, and a snapshot carries attributes keyed by them; an attribute
 * the snapshot lacks is an evaluation error, never a default ({@code INV-CRD-07}). A new code is a
 * reviewed code change, never a string (ADR-0084 section 6).
 *
 * <p><strong>Markers.</strong> {@link #SOURCE_UNAVAILABLE} and {@link #CURRENCY_NOT_SUPPORTED}
 * are attributes too, not errors: they record that a source's data is {@code ABSENT} and why,
 * so the policy reasons about the absence explicitly - the unavailable-source fallback fires on
 * the first ({@code INV-CRD-10}) - and the snapshot explains it on replay.
 *
 * <p>An attribute is not a score, and neither is an outcome ({@code INV-CRD-04}): even
 * {@link #BUREAU_EXTERNAL_SCORE} is an <em>input</em>, the bureau's number as it stated it.
 */
@RequiredArgsConstructor
public enum CreditAttributeCode {

    /** The bureau's own score, as the bureau stated it - an input, never the platform's score. */
    BUREAU_EXTERNAL_SCORE(AttributeValueType.INTEGER, false),

    /** Credit accounts the bureau reports open. */
    BUREAU_ACTIVE_ACCOUNTS(AttributeValueType.INTEGER, false),

    /** Delinquencies the bureau reports in the last 24 months. */
    BUREAU_DELINQUENCIES_24M(AttributeValueType.INTEGER, false),

    /** Defaults the bureau reports in the last 72 months. */
    BUREAU_DEFAULTS_72M(AttributeValueType.INTEGER, false),

    /** Whether the bureau reports an insolvency proceeding. */
    BUREAU_INSOLVENCY_FLAG(AttributeValueType.BOOLEAN, false),

    /** The monthly repayments the bureau reports on existing credit. */
    BUREAU_MONTHLY_OBLIGATIONS(AttributeValueType.MONEY, false),

    /** The total outstanding balance the bureau reports - the external part of exposure. */
    BUREAU_TOTAL_BALANCE(AttributeValueType.MONEY, false),

    /** Monthly income observed by the financial-data provider - verified income. */
    FINDATA_MONTHLY_INCOME(AttributeValueType.MONEY, false),

    /** Monthly committed expenditure observed by the financial-data provider. */
    FINDATA_MONTHLY_COMMITTED_EXPENDITURE(AttributeValueType.MONEY, false),

    /** Monthly income as the applicant declared it. */
    DECLARED_MONTHLY_INCOME(AttributeValueType.MONEY, false),

    /** Monthly expenditure as the applicant declared it. */
    DECLARED_MONTHLY_EXPENDITURE(AttributeValueType.MONEY, false),

    /** The party's age in whole years on the database clock. */
    PARTY_AGE_YEARS(AttributeValueType.INTEGER, false),

    /** The party's country of residence (ISO 3166-1 alpha-2). */
    PARTY_RESIDENCY_COUNTRY(AttributeValueType.CODE, false),

    /** The platform's own outstanding credit to the party - zero until Phase 11's loans exist (`P10-TSK-010`). */
    PLATFORM_OUTSTANDING_CREDIT(AttributeValueType.MONEY, false),

    /** The approved, unconsumed, unlapsed amount of this party's earlier decisions. */
    PLATFORM_RESERVED_EXPOSURE(AttributeValueType.MONEY, false),

    /** The risk seam's answer - {@code NOT_ASSESSED} until Phase 13's {@code risk} answers it. */
    RISK_SIGNAL(AttributeValueType.CODE, false),

    /** Marker: a source answered nothing by its deadline; its attributes are {@code ABSENT}. */
    SOURCE_UNAVAILABLE(AttributeValueType.CODE, true),

    /** Marker: a source reported money in a currency other than the product's; that money is {@code ABSENT}. */
    CURRENCY_NOT_SUPPORTED(AttributeValueType.CODE, true);

    private final AttributeValueType valueType;
    private final boolean marker;

    /** The type of this attribute's value. */
    public AttributeValueType valueType() {
        return valueType;
    }

    /** Whether this attribute records an absence and its cause rather than a datum. */
    public boolean marker() {
        return marker;
    }
}
