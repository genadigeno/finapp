package com.finapp.risk;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One fail-safe band of a judged operation's policy version (`P13-TSK-002`; owner decision C1; ADR-0108 decision 4):
 * the amount interval {@code [fromMinor, toMinor)} in minor units of the version's currency - the last open-ended -
 * mapped to the decision taken when the evaluation is unavailable: {@code ALLOW} (recorded) or {@code DENY}, nothing
 * else, because an unavailable risk cannot hold, review or challenge what it never judged. A field of the version, so
 * the owner changes it by activation and never by release.
 */
public record FailSafeBand(long fromMinor, OptionalLong toMinor, RiskDecision decision) {

    public FailSafeBand {
        Objects.requireNonNull(toMinor, "toMinor");
        Objects.requireNonNull(decision, "decision");
        if (fromMinor < 0) {
            throw new RiskPolicy.PolicyInvalid("a fail-safe band starts at a non-negative amount");
        }
        if (toMinor.isPresent() && toMinor.getAsLong() <= fromMinor) {
            throw new RiskPolicy.PolicyInvalid("a fail-safe band ends above where it starts");
        }
        if (decision != RiskDecision.ALLOW && decision != RiskDecision.DENY) {
            throw new RiskPolicy.PolicyInvalid("a fail-safe band decides ALLOW or DENY, never " + decision
                    + ": an unavailable evaluation holds, reviews or challenges nothing");
        }
    }

    /** True when {@code amountMinor} falls in this band. */
    public boolean covers(long amountMinor) {
        return amountMinor >= fromMinor && (toMinor.isEmpty() || amountMinor < toMinor.getAsLong());
    }

    /**
     * {@code bands} judged as a whole: at least one, the first from 0, each next starting where the previous ended, the
     * last - and only the last - open-ended.
     */
    static List<FailSafeBand> contiguous(List<FailSafeBand> bands) {
        Objects.requireNonNull(bands, "bands");
        if (bands.isEmpty()) {
            throw new RiskPolicy.PolicyInvalid("a judged operation's version carries at least one fail-safe band (C1)");
        }
        long expected = 0;
        for (int i = 0; i < bands.size(); i++) {
            FailSafeBand band = bands.get(i);
            if (band.fromMinor() != expected) {
                throw new RiskPolicy.PolicyInvalid("the fail-safe bands are contiguous from 0: a band starts at "
                        + band.fromMinor() + " where " + expected + " was expected");
            }
            boolean last = i == bands.size() - 1;
            if (last != band.toMinor().isEmpty()) {
                throw new RiskPolicy.PolicyInvalid("exactly the last fail-safe band is open-ended");
            }
            if (!last) {
                expected = band.toMinor().getAsLong();
            }
        }
        return List.copyOf(bands);
    }
}
