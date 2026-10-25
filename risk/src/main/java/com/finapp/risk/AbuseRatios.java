package com.finapp.risk;

import java.util.Optional;

/**
 * The abuse ratios' arithmetic (`P13-TSK-014`; {@code PHASE_13_PLAN.md} section 12.6; {@code INV-MON-01}) - pure: a
 * share is sealed as its two figures, never as a quotient, so {@link Ratio#atLeast} compares it with a policy's basis
 * points exactly, by cross-multiplication in {@code long}.
 *
 * <p><strong>A zero denominator is no ratio at all.</strong> A subject with no counted movement in the window has no
 * share to judge - "refunds over nothing" is neither 0 % nor 100 % - so nothing is sealed and the rule over it reads
 * {@code ABSENT}: it does not fire, and its hit says it could not look. A figure outside the engine's bound is refused,
 * never clamped (the engine's inputs' rule).
 */
public final class AbuseRatios {

    private AbuseRatios() {}

    /**
     * The share {@code numerator} over {@code denominator} - empty when the denominator is zero.
     *
     * @throws IllegalArgumentException for a negative figure, or one above {@link EvaluationInputs#MAX_MINOR}
     */
    public static Optional<Ratio> of(long numerator, long denominator) {
        if (numerator < 0 || denominator < 0) {
            throw new IllegalArgumentException("a ratio's figures are counts: never negative");
        }
        if (denominator == 0) {
            return Optional.empty();
        }
        return Optional.of(new Ratio(numerator, denominator));
    }
}
