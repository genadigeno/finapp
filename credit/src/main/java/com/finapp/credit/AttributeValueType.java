package com.finapp.credit;

/**
 * The type of a credit attribute's value (PHASE_10_PLAN.md section 12.2), declared by its
 * {@link CreditAttributeCode}.
 *
 * <p>Closed and exact: there is no floating-point type ({@code INV-CRD-12}). Money is minor units
 * with an explicit currency; a count or an age is an integer; a code is a member of a closed set
 * (a country, a risk signal's answer, the source kind a marker names).
 */
public enum AttributeValueType {

    /** A whole number - a count, an age in years, a bureau's score as the bureau states it. */
    INTEGER,

    /** An amount in minor units with its explicit currency. */
    MONEY,

    /** A yes or no fact. */
    BOOLEAN,

    /** A member of a closed set of codes. */
    CODE
}
