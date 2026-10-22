package com.finapp.risk;

/**
 * The case machine's states (`P13-TSK-001`; {@code RISK_LIFECYCLES.md} section 4.6; {@code PHASE_13_PLAN.md} section 5):
 * {@code OPEN → IN_INVESTIGATION → ESCALATED → DISPOSED}, a close from {@code OPEN} admitted for a false positive, a
 * case re-opened never - a new case references it. The transitions, their guards and the four-eyes rule are `-016`'s;
 * declared here so every later task names one vocabulary.
 */
public enum CaseStatus {
    OPEN,
    IN_INVESTIGATION,
    ESCALATED,
    DISPOSED;

    /** True while an investigator may still act on the case. */
    public boolean open() {
        return this != DISPOSED;
    }
}
