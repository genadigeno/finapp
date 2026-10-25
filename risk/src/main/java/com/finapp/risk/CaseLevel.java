package com.finapp.risk;

import java.util.Optional;

/**
 * How far a case has been escalated (`P13-TSK-016`; {@code PHASE_13_PLAN.md} section 3.1 "Escalation";
 * {@code RISK_LIFECYCLES.md} section 4.6; ADR-0110 decision 2) - born {@link #L1}; each escalation raises it exactly one
 * step, to {@link #L3} at most ({@code DESIGN-016} D11). The level says how far up the case has gone; the
 * {@link CaseQueue} says which desk works it now.
 */
public enum CaseLevel {

    /** Born here: the first line's investigation. */
    L1,

    /** Escalated once. */
    L2,

    /** Escalated twice - the top: a further escalation is refused ({@code risk.CaseActNotAdmitted}). */
    L3;

    /** The level one escalation reaches from this one - empty at the top. */
    public Optional<CaseLevel> next() {
        return ordinal() + 1 < values().length ? Optional.of(values()[ordinal() + 1]) : Optional.empty();
    }
}
