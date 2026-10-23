package com.finapp.risk;

import java.util.List;

/**
 * The Risk Score of one evaluation (`P13-TSK-003`; {@code GLOSSARY.md} Risk Score; {@code INV-RSK-04},
 * {@code INV-CRD-04}): the sum of the fired rules' contributions, clamped to [0, {@link ScoreBand#CEILING}] - an
 * integer, never a credit score, never summed with one; the profile's rating is compared as a code and never enters
 * here. Integer arithmetic only ({@code INV-MON-01}).
 */
public record Score(int value) {

    public static final Score ZERO = new Score(0);

    public Score {
        if (value < 0 || value > ScoreBand.CEILING) {
            throw new IllegalArgumentException("a score is 0 to " + ScoreBand.CEILING);
        }
    }

    /** {@code sum} clamped into the range - a sum past the ceiling is the ceiling, never an overflow or a wrap. */
    public static Score clamped(long sum) {
        if (sum < 0) {
            return ZERO;
        }
        return sum > ScoreBand.CEILING ? new Score(ScoreBand.CEILING) : new Score((int) sum);
    }

    /** The sum of the hits' contributions, clamped. */
    public static Score of(List<RuleHit> hits) {
        long sum = 0;
        for (RuleHit hit : hits) {
            sum += hit.contribution();
        }
        return clamped(sum);
    }
}
