package com.finapp.risk;

/**
 * What a signal, a velocity window, an alert or a case is about (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 3.1,
 * 12.4) - the subject kinds, each referenced by identifier only, never by constraint (ADR-0029) and never by a row of
 * another module. A velocity dimension is one of these ({@link RuleKind.ParameterType#SUBJECT_KIND}).
 */
public enum SubjectKind {

    /** A party - the customer behind every identity, account and application. */
    PARTY,

    /** A ledger-backed account - a wallet, or a destination account. */
    ACCOUNT,

    /** A device, by identity's hash of its description - never the description (OQ-4). */
    DEVICE,

    /** A merchant. */
    MERCHANT,

    /** A cross-border beneficiary. */
    BENEFICIARY,

    /** A session. */
    SESSION
}
