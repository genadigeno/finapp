package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of act a journal entry records (`P3-TSK-005`).
 *
 * <p>Three, under the deliberately-few licence: all three are named outright by
 * {@code PHASE_3_PLAN.md} §5, so their existence is fixed even though two arrive later. The
 * type is <strong>not</strong> a lifecycle — an entry never changes kind, because the rows are
 * append-only and the kind is a fact about the act that wrote them.
 *
 * <p>Persisted values, in a generated {@code CHECK} ({@code JournalEntryMigrationTest}
 * reconciles) — and the reason rule hangs off it in the schema: an {@code ADJUSTMENT} must
 * carry a reason ({@code INV-REV-04}), stated as an implication rather than an equality so
 * `P3-TSK-016` stays free to decide whether a reversal carries one.
 */
public enum JournalEntryType {

    /** An ordinary commanded posting — a flow's own records carry the why (`P3-TSK-006`). */
    POSTING,

    /**
     * A new entry whose lines are an original's with directions swapped, referencing it
     * ({@code INV-REV-01}). The referencing column is `P3-TSK-016`'s own migration.
     */
    REVERSAL,

    /**
     * A person chose to move value the system would not have moved: reason required
     * ({@code INV-REV-04}), elevated permission, the highest-risk financial action on the
     * platform (`P3-TSK-017`).
     */
    ADJUSTMENT;

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
