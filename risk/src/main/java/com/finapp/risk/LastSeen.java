package com.finapp.risk;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * How long ago something last happened on the identity - a credential change, a recovery (`P13-TSK-003`;
 * {@link RuleKind#RECENT_CREDENTIAL_CHANGE}, {@link RuleKind#RECENT_RECOVERY}) - in whole hours, or {@link #never()}
 * when it never has: "read and found none" is a present value, not an absent reading.
 */
public record LastSeen(OptionalLong hoursAgo) {

    public LastSeen {
        Objects.requireNonNull(hoursAgo, "hoursAgo");
        if (hoursAgo.isPresent() && (hoursAgo.getAsLong() < 0 || hoursAgo.getAsLong() > EvaluationInputs.MAX_HOURS)) {
            throw new IllegalArgumentException("hours since last seen is 0 to " + EvaluationInputs.MAX_HOURS);
        }
    }

    /** Never happened. */
    public static LastSeen never() {
        return new LastSeen(OptionalLong.empty());
    }

    public static LastSeen hoursAgo(long hours) {
        return new LastSeen(OptionalLong.of(hours));
    }

    /** True when it happened fewer than {@code lookbackHours} ago. */
    public boolean within(int lookbackHours) {
        return hoursAgo.isPresent() && hoursAgo.getAsLong() < lookbackHours;
    }

    /** The canonical text: the hours, or {@code NEVER}. */
    public String canonical() {
        return hoursAgo.isPresent() ? Long.toString(hoursAgo.getAsLong()) : "NEVER";
    }
}
