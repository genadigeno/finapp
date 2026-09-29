package com.finapp.reconciliation;

/**
 * How a rule allocates (`P8-TSK-011`, ADR-0068 §3) — the closed vocabulary `V002`'s `rule`
 * rows carry. `P8-TSK-011` executes {@code ONE_TO_ONE}; {@code CHECK} and
 * {@code CORRECTION} are `P8-TSK-012`'s, {@code GROUP_BY_VALUE_DATE} is `P8-TSK-016`'s, and
 * {@code PARTIAL} has no seeded rule in v1 (a counterparty that settles one operation in
 * parts). The engine leaves an unlanded cardinality's item {@code UNMATCHED} with no grace
 * clock, never parked.
 */
public enum Cardinality {
    ONE_TO_ONE,
    PARTIAL,
    GROUP_BY_VALUE_DATE,
    CORRECTION,
    CHECK;

    /** The `V005` strategy {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return java.util.Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
