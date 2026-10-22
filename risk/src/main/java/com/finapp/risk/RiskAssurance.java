package com.finapp.risk;

/**
 * The assurance of the acting session, as risk reads it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 12.2, 12.3) -
 * through the {@code SessionAssurance} port, because risk cannot see {@code identity}. A {@link RiskDecision#CHALLENGE}
 * is satisfied by {@link #MULTI_FACTOR}; a policy's challenge assurance and {@link RuleKind#SESSION_ASSURANCE_BELOW} are
 * written in these terms.
 */
public enum RiskAssurance {
    SINGLE_FACTOR,
    MULTI_FACTOR;

    /** True when this assurance satisfies {@code required}. */
    public boolean satisfies(RiskAssurance required) {
        return ordinal() >= required.ordinal();
    }
}
