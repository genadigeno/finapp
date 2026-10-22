package com.finapp.risk;

/**
 * What an owning domain answered to a risk action (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 12.8, 14;
 * ADR-0112) - recorded on the action, never retried by risk: a domain's refusal is a recorded outcome, not an error.
 */
public enum ActionOutcome {

    /** The domain performed the effect. */
    DONE,

    /** The effect was already in place - an identity already suspended, a re-driven key. */
    ALREADY,

    /** The domain refused - its own rule said no; the case records why it asked. */
    REFUSED
}
