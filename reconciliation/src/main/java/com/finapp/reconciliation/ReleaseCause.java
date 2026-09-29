package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The path that took value out of a suspense item (`P8-TSK-010`, ADR-0070 §3) — one
 * {@code suspense_release} row per release, each naming its cause and, through
 * {@code cause_ref}, the decision, resolution or repudiation behind it. Stated whole now
 * (the `V002` machine precedent): `P8-TSK-010` produces only {@code UNPARK} (the primitive
 * the tests drive); the rest arrive with their tasks and stay inert until then.
 */
public enum ReleaseCause {

    /** The park's exact inverse: a late allocation's unpark (`-013`), or this task's tests. */
    UNPARK,

    /** A counterparty's correlated correction offsets the parked value (`P8-TSK-012`). */
    CORRECTION_OFFSET,

    /** An approved posting resolution took it (`P8-TSK-015`: transfer, write-off, gain). */
    RESOLUTION,

    /** The four-eyes offset of one CREDIT against one DEBIT item — no posting (`-015`). */
    OFFSET_SUSPENSE,

    /** A repudiation released or re-opened it (`P8-TSK-023`, ADR-0070 §10). */
    REPUDIATION;

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
