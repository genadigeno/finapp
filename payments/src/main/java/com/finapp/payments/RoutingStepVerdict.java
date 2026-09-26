package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a routing step says about its candidate (`P7-TSK-003`, ADR-0060 §2/§5).
 *
 * <p>{@code REJECTED} is judged before anything is sent; {@code ABANDONED} is the fallback's
 * verdict on a rail that was chosen and then met {@link RoutingRejection#NOTHING_SENT} —
 * knowledge, never ambiguity. At most one step of a decision is {@code CHOSEN} and not yet
 * abandoned, which is what "a payment is dispatched on at most one rail at a time" looks like
 * as data (`INV-RAIL-02`).
 */
public enum RoutingStepVerdict {
    CHOSEN,
    REJECTED,
    ABANDONED;

    /** The values as a SQL literal list — `V013`'s `CHECK`s are generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
