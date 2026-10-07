package com.finapp.fx;

import java.time.Duration;

/**
 * What the cover's legs tell the meters, after their transactions committed (`P9-TSK-012`;
 * ADR-0077's operational impact): verdicts, counts and ages only - never an amount, a reference or
 * a rate (ADR-0072). {@code app} implements it over Micrometer; {@link #NONE} for the suites.
 */
public interface CoverObserver {

    /** Observes nothing. */
    CoverObserver NONE = new CoverObserver() {};

    /** The closed outcome vocabulary of {@code finapp.fx.cover{provider, kind, outcome}}. */
    enum Outcome {
        /** The provider executed and the acting applier booked it. */
        EXECUTED,
        /** The execution deviated on the fixed leg - booked, flagged, alerting. */
        OFF_PLAN,
        /**
         * The execution's computed leg differs from the firm quote it was executed under (the Phase 9 to 10
         * transition) - booked as executed, flagged {@code computed_deviation}, alerting.
         */
        COMPUTED_DEVIATION,
        /** The provider's executed rate does not explain its executed amounts - booked from the amounts, alerting. */
        RATE_INCOHERENT,
        /** A definitive refusal. */
        REJECTED,
        /** A send's answer was lost: the cover is UNKNOWN until an inquiry knows. */
        UNKNOWN,
        /** A fresh plausible firm quote and a new reference. */
        REQUOTED,
        /** A requote refused (declined, unanswered or implausible) - backoff, alerting. */
        REQUOTE_REFUSED,
        /** A rejected cover its quote no longer wants. */
        VOIDED,
        /** An answer that contradicts the cover (another attempt, another currency) - evidence only, alerting. */
        ANOMALY
    }

    default void outcome(String providerCode, CoverKind kind, Outcome outcome) {}

    /** From the cover's birth to its execution - the uncovered position's life. */
    default void executed(String providerCode, CoverKind kind, Duration sinceBirth) {}
}
