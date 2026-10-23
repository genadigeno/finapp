package com.finapp.risk;

/**
 * What the velocity windows did with one judged movement (`P13-TSK-004`; {@code INV-RSK-03}; {@code risk_assessment
 * .velocity_outcome}) - the evidence the velocity proof needs from every assessment (`P13-TSK-006`'s finding).
 */
public enum VelocityOutcome {

    /** Permitted, and counted in every window of every dimension it carries, at the assessment's cut instant. */
    COUNTED,

    /** Refused ({@code DENY}, {@code HOLD}, {@code REVIEW}, an unsatisfied {@code CHALLENGE}): counted nowhere (OQ-1). */
    NOT_PERMITTED,

    /**
     * Permitted, but its count itself failed behind its savepoint (a fail-safe's count meeting the same failure that
     * made the evaluation unavailable): the money moves uncounted, and this row says so - never silent.
     */
    UNCOUNTED
}
