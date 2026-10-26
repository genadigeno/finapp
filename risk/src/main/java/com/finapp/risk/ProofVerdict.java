package com.finapp.risk;

/**
 * A census's verdict (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.3, 15; F8) - the {@code verdict} tag of
 * {@code finapp.risk.census}, the names {@link VelocityProof.Verdict} uses too. A census <em>names</em> a difference;
 * nothing here, nor anywhere a census is read, corrects one: a person acts.
 */
public enum ProofVerdict {
    /** Every row judged agrees with what it must equal. */
    HELD,
    /** At least one difference was found - the report's rows name it, the meter alerts on it. */
    DIVERGED,
    /** Nothing was there to judge. */
    VACUOUS;

    /** The verdict of {@code judged} rows and {@code differences} found among them. */
    public static ProofVerdict of(long judged, long differences) {
        if (judged < 0 || differences < 0) {
            throw new IllegalArgumentException("a census counts from zero");
        }
        return differences > 0 ? DIVERGED : judged == 0 ? VACUOUS : HELD;
    }

    /** The worse of two verdicts: {@code DIVERGED}, then {@code HELD}, then {@code VACUOUS}. */
    public ProofVerdict and(ProofVerdict other) {
        if (this == DIVERGED || other == DIVERGED) {
            return DIVERGED;
        }
        return this == HELD || other == HELD ? HELD : VACUOUS;
    }

    /** {@code verdict} of the velocity proof under the census's names. */
    public static ProofVerdict of(VelocityProof.Verdict verdict) {
        return valueOf(verdict.name());
    }
}
