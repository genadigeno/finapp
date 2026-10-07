package com.finapp.credit;

/**
 * The family a {@link ReasonCode} belongs to - the {@code reason_code.category} column, whose
 * {@code CHECK} lists exactly these names (ReasonCodeCatalogueTest).
 */
public enum ReasonCategory {

    /** The applicant is outside the product's eligibility - age, residency. */
    ELIGIBILITY,

    /** The bureau's record of past credit - insolvency, defaults, delinquencies, thin files. */
    CREDIT_HISTORY,

    /** The scorecard's assessment. */
    SCORE,

    /** Income against expenditure, obligations and the stressed repayment. */
    AFFORDABILITY,

    /** Total credit exposure against the policy's maximum. */
    EXPOSURE,

    /** The data needed was missing, unverifiable or unusable. */
    DATA,

    /** The risk seam's signal. */
    RISK,

    /** A limit of the policy itself, not a judgement of the applicant. */
    POLICY
}
