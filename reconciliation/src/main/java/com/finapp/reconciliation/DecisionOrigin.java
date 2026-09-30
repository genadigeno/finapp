package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which leg evaluated (`P8-TSK-011`, ADR-0068 §5) — stated whole: {@code RUN} is produced
 * here; {@code REMATCH} arrives with `P8-TSK-013`, {@code REPROCESS} with `P8-TSK-022` and
 * {@code MANUAL} with `P8-TSK-015`.
 */
public enum DecisionOrigin {
    RUN,
    REMATCH,
    REPROCESS,
    MANUAL;

    /** The `V005` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
