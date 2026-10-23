package com.finapp.risk;

import java.util.List;
import java.util.Objects;

/**
 * One score band of a judged operation's policy version (`P13-TSK-002`; ADR-0108, ADR-0109): the closed interval
 * {@code [from, to]} of the score [0, 1000] mapped to a decision. A version's bands are contiguous, ascending and cover
 * the whole range ({@link #contiguous}); each decision is one the operation admits (judged by {@link RiskPolicy}).
 */
public record ScoreBand(int from, int to, RiskDecision decision) {

    /** The score's ceiling: Σ contributions clamped to [0, 1000] (ADR-0109 decision 3). */
    public static final int CEILING = 1000;

    public ScoreBand {
        Objects.requireNonNull(decision, "decision");
        if (from < 0 || to > CEILING || from > to) {
            throw new RiskPolicy.PolicyInvalid("a score band is a closed interval of [0, " + CEILING + "]");
        }
    }

    /** True when {@code score} falls in this band. */
    public boolean covers(int score) {
        return score >= from && score <= to;
    }

    /**
     * {@code bands} judged as a whole: at least one, the first from 0, each next starting where the previous ended plus
     * one, the last ending at the ceiling.
     */
    static List<ScoreBand> contiguous(List<ScoreBand> bands) {
        Objects.requireNonNull(bands, "bands");
        if (bands.isEmpty()) {
            throw new RiskPolicy.PolicyInvalid("a judged operation's version carries at least one score band");
        }
        int expected = 0;
        for (ScoreBand band : bands) {
            if (band.from() != expected) {
                throw new RiskPolicy.PolicyInvalid("the score bands are contiguous from 0: a band starts at " + band.from()
                        + " where " + expected + " was expected");
            }
            expected = band.to() + 1;
        }
        if (expected != CEILING + 1) {
            throw new RiskPolicy.PolicyInvalid("the score bands end at " + CEILING);
        }
        return List.copyOf(bands);
    }
}
