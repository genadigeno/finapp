package com.finapp.risk;

/**
 * Why an evaluation could not run, recorded beside its fail-safe (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section 12.3;
 * ADR-0108 decision 4; {@code INV-RSK-04}) - {@code risk_assessment.unavailable_cause}. The reason code says what the
 * decision was ({@link ReasonCode#NO_POLICY_ACTIVE} or {@link ReasonCode#RISK_UNAVAILABLE}); the cause says which
 * failure answered it, for the operator and for the replay that re-derives it.
 */
public enum UnavailableCause {

    /** No version of the operation is {@code ACTIVE}: the compiled {@code ALLOW} under {@code NO_POLICY_ACTIVE}. */
    NO_POLICY,

    /**
     * The pin's own read failed: no policy is in hand, so no band can be read - the compiled {@code ALLOW} under
     * {@code RISK_UNAVAILABLE}, the policy version recorded absent.
     */
    PIN_UNREADABLE,

    /** A read behind the savepoint failed (a store, a window in another currency, {@code RiskInputs}). */
    READ_FAILED,

    /** A read behind the savepoint met the 250 ms statement timeout (SQLSTATE 57014). */
    TIMEOUT,

    /** The engine refused ({@link EvaluationFailed}): another currency, a rule that threw, an unadmitted decision. */
    ENGINE_REFUSED;

    /** The reason code a fail-safe of this cause carries. */
    public ReasonCode reason() {
        return this == NO_POLICY ? ReasonCode.NO_POLICY_ACTIVE : ReasonCode.RISK_UNAVAILABLE;
    }

    /** True when no policy version could be pinned - the assessment names none. */
    public boolean unpinned() {
        return this == NO_POLICY || this == PIN_UNREADABLE;
    }
}
