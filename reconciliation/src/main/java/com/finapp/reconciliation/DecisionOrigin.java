package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which leg evaluated (`P8-TSK-011`, ADR-0068 §5) — stated whole: {@code RUN} is produced
 * here; {@code REMATCH} arrives with `P8-TSK-013`, {@code REPROCESS} with `P8-TSK-022` and
 * {@code MANUAL} with `P8-TSK-015`; {@code RECOGNITION} with `P11-TSK-004` - a bank statement's
 * capital line matched to its contribution's expectation inside the statement's own acceptance
 * ({@link CapitalRecognition}), where the cash is recognised, never by a run.
 */
public enum DecisionOrigin {
    RUN,
    REMATCH,
    REPROCESS,
    MANUAL,
    RECOGNITION;

    /** The current {@code CHECK}'s value list (`V022`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The list `V005` stated, before `V022` added {@code RECOGNITION}. */
    public static String sqlValueListBeforeV022() {
        return Arrays.stream(values())
                .filter(value -> value != RECOGNITION)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
