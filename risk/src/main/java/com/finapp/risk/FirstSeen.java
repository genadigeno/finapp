package com.finapp.risk;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * How long ago a payee or a device was first seen (`P13-TSK-003`; {@link RuleKind#NEW_PAYEE},
 * {@link RuleKind#NEW_DEVICE}) - a number of whole days, or {@link #never()} when nothing has ever been observed
 * about it ({@code SignalReadings.isNovel}): a never-seen payee or device is new under any lookback.
 */
public record FirstSeen(OptionalInt daysAgo) {

    public FirstSeen {
        Objects.requireNonNull(daysAgo, "daysAgo");
        if (daysAgo.isPresent() && (daysAgo.getAsInt() < 0 || daysAgo.getAsInt() > EvaluationInputs.MAX_DAYS)) {
            throw new IllegalArgumentException("days since first seen is 0 to " + EvaluationInputs.MAX_DAYS);
        }
    }

    /** Never observed - novel. */
    public static FirstSeen never() {
        return new FirstSeen(OptionalInt.empty());
    }

    public static FirstSeen daysAgo(int days) {
        return new FirstSeen(OptionalInt.of(days));
    }

    /** True when never seen, or first seen fewer than {@code lookbackDays} ago. */
    public boolean within(int lookbackDays) {
        return daysAgo.isEmpty() || daysAgo.getAsInt() < lookbackDays;
    }

    /** The canonical text: the days, or {@code NEVER}. */
    public String canonical() {
        return daysAgo.isPresent() ? Integer.toString(daysAgo.getAsInt()) : "NEVER";
    }
}
