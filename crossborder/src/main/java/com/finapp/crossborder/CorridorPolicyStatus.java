package com.finapp.crossborder;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A corridor policy version's position (`P9-TSK-015`, the lifecycle document §3.9):
 * {@code PROPOSED -> ACTIVE} (a different approver) {@code | REJECTED} (anyone - the proposer's
 * withdrawal included), {@code ACTIVE -> RETIRED} only beside its successor. {@code crossborder
 * V002}'s trigger holds the same edges for every writer.
 */
public enum CorridorPolicyStatus {
    PROPOSED,
    ACTIVE,
    RETIRED,
    REJECTED;

    /** Whether the machine has the edge {@code this -> target}. */
    public boolean canMoveTo(CorridorPolicyStatus target) {
        return switch (this) {
            case PROPOSED -> target == ACTIVE || target == REJECTED;
            case ACTIVE -> target == RETIRED;
            case RETIRED, REJECTED -> false;
        };
    }

    /** The values as a SQL literal list - {@code crossborder V002}'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
