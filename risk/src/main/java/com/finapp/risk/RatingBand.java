package com.finapp.risk;

import java.util.List;
import java.util.Objects;

/**
 * One rating band of the {@code MONITORING} policy version (`P13-TSK-002`; {@code PHASE_13_PLAN.md} sections 12.2,
 * 12.5; {@code INV-RSK-10}): the closed interval {@code [from, to]} of the party's signal score [0, 1000] mapped to the
 * rating the profile recompute sets (`-007`). Never {@link RiskRating#PROHIBITED} - a four-eyes disposition's alone
 * ({@code INV-RSK-07}). A version's bands are contiguous, ascending and cover the whole range.
 */
public record RatingBand(int from, int to, RiskRating rating) {

    public RatingBand {
        Objects.requireNonNull(rating, "rating");
        if (from < 0 || to > ScoreBand.CEILING || from > to) {
            throw new RiskPolicy.PolicyInvalid("a rating band is a closed interval of [0, " + ScoreBand.CEILING + "]");
        }
        if (rating.onlyByDisposition()) {
            throw new RiskPolicy.PolicyInvalid("a rating band never sets " + rating + ": only a four-eyes disposition does");
        }
    }

    /** True when {@code score} falls in this band. */
    public boolean covers(int score) {
        return score >= from && score <= to;
    }

    /** {@code bands} judged as a whole - {@link ScoreBand#contiguous}'s rule for ratings. */
    static List<RatingBand> contiguous(List<RatingBand> bands) {
        Objects.requireNonNull(bands, "bands");
        if (bands.isEmpty()) {
            throw new RiskPolicy.PolicyInvalid("the monitoring version carries at least one rating band");
        }
        int expected = 0;
        for (RatingBand band : bands) {
            if (band.from() != expected) {
                throw new RiskPolicy.PolicyInvalid("the rating bands are contiguous from 0: a band starts at "
                        + band.from() + " where " + expected + " was expected");
            }
            expected = band.to() + 1;
        }
        if (expected != ScoreBand.CEILING + 1) {
            throw new RiskPolicy.PolicyInvalid("the rating bands end at " + ScoreBand.CEILING);
        }
        return List.copyOf(bands);
    }
}
