package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which way a park posting moved value (`P8-TSK-010`, ADR-0070 §2): a {@code PARK} put a
 * remainder into suspense; an {@code UNPARK} posted the exact inverse under a NEW park id.
 */
public enum ParkKind {
    PARK,
    UNPARK;

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
