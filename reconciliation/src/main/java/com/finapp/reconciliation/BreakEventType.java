package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What one {@code break_event} row records (`P8-TSK-010`, ADR-0069) — the break's
 * append-only history. Stated whole now; `P8-TSK-010` appends only {@code RAISED}, the
 * others arrive with their producers (`-013` escalation, `-014` assignment and
 * reclassification, `-012`/`-015` resolution).
 */
public enum BreakEventType {

    /** Birth — the platform detected a stored fact. */
    RAISED,

    /** An assignment (the first one moves {@code OPEN → INVESTIGATING}). */
    ASSIGNED,

    /** A reasoned type change in {@code OPEN} or {@code INVESTIGATING}; cause frozen. */
    RECLASSIFIED,

    /** The sweep raised the grade for an ageing band or the value threshold — forward only. */
    SEVERITY_ESCALATED,

    /**
     * Terminal: an approved resolution, {@code EVIDENCED}, or a timing difference's one-person
     * zero-value acknowledge.
     */
    RESOLVED;

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
