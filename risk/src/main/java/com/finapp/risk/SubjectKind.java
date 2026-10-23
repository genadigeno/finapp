package com.finapp.risk;

/**
 * What a signal, an alert or a case is about (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 3.1) - the subject kinds,
 * each referenced by identifier only, never by constraint (ADR-0029) and never by a row of another module. A velocity
 * window is keyed on a {@link VelocityDimension} instead - the role a subject plays in a movement, not what it is
 * (`P13-TSK-006`: a transfer's source and destination are both {@link #ACCOUNT}s and are counted apart).
 *
 * <p><strong>Two kinds the plan did not name, added by `P13-TSK-005`.</strong> A signal records what its source
 * event <em>said</em> and nothing read from the producer's tables (section 12.5), and the platform's events name
 * their own aggregate - an identity, a loan, a BNPL obligation - never the party behind it ({@code EventPayload}
 * carries identifiers and codes only, {@code INV-AUD-02}). So the subject of identity's credential, recovery and
 * suspension events is the {@link #IDENTITY}, and of lending's and bnpl's delinquency and default events the
 * {@link #CREDIT_AGREEMENT}. The party behind either is an immutable fact the consumer resolves through a port at
 * evaluation time, where it is needed - the ATO rule (`-013`) joins an identity's signals to the party's movements that
 * way; an action still targets the {@link #PARTY} ({@link ActionKind#SUSPEND_IDENTITY}).
 */
public enum SubjectKind {

    /** A party - the customer behind every identity, account and application. */
    PARTY,

    /** A ledger-backed account - a wallet, or a destination account. */
    ACCOUNT,

    /** A device, by identity's hash of its description - never the description (OQ-4; {@link DeviceHash}). */
    DEVICE,

    /** A merchant. */
    MERCHANT,

    /** A cross-border beneficiary. */
    BENEFICIARY,

    /** A session. */
    SESSION,

    /** An identity - the subject identity's events name (`P13-TSK-005`); its party is identity's fact, through a port. */
    IDENTITY,

    /**
     * A credit agreement - a loan or a BNPL obligation, by the id its module's events carry (`P13-TSK-005`); which
     * module is the signal's kind's business ({@link SignalKind#LOAN_DEFAULTED}, {@link SignalKind#BNPL_DEFAULTED}),
     * and the borrower is the module's fact, through a port.
     */
    CREDIT_AGREEMENT
}
