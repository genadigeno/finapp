package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A break's severity (`P8-TSK-010`, ADR-0069 §5) — computed, stored, and only ever rising:
 * the base by type (refined by direction, expectation kind or cause), one level up at the
 * pinned per-currency {@code high_value_minor}, one level per ageing band crossed
 * (`P8-TSK-013`), capped at {@code CRITICAL}. The column is forward-only by trigger, so a
 * relabel or a loosened threshold can never quiet an alert.
 *
 * <p>Declaration order IS the ordering ({@link #atLeast}, {@link #oneUp}).
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** One level louder, capped at {@code CRITICAL}. */
    public Severity oneUp() {
        return this == CRITICAL ? CRITICAL : values()[ordinal() + 1];
    }

    /** The louder of the two — reclassification keeps the higher grade (ADR-0069 §5). */
    public Severity atLeast(Severity other) {
        return ordinal() >= other.ordinal() ? this : other;
    }

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The `V004` forward-only rule: every legal (old, new) pair where the grade rises —
     * the transition trigger refuses anything else, for every writer.
     */
    public static String sqlForwardOnlyRule() {
        return Arrays.stream(values())
                .map(
                        from ->
                                "(OLD.severity = '" + from.name() + "' AND NEW.severity IN ("
                                        + Arrays.stream(values())
                                                .filter(to -> to.ordinal() >= from.ordinal())
                                                .map(to -> "'" + to.name() + "'")
                                                .collect(Collectors.joining(", "))
                                        + "))")
                .collect(Collectors.joining(" OR "));
    }
}
