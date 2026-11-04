package com.finapp.accounting;

/**
 * The closed vocabulary of a regulatory obligation's validation rules (`P14-TSK-018`; ADR-0118 decision 1). A rule is a type
 * and the form lines it names; it is <strong>never SQL, a script or an expression from a row</strong>, so nothing an obligation
 * says can run. Each is judged per currency, never across currencies, on the exact amounts; a line a rule names that has no
 * amount in a currency another of its lines has is a finding, never a zero ({@link RegulatoryValidation}).
 */
public enum RegulatoryRuleType {

    /** Every line on the left is zero or more. Names no right side. */
    NON_NEGATIVE,

    /** The sum of the left lines equals the sum of the right lines. */
    EQUALS,

    /** The sum of the left lines is at least the sum of the right lines. */
    NOT_LESS_THAN
}
