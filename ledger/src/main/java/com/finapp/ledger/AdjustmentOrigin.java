package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whose machinery may decide an adjustment proposal (`P8-TSK-006`, ADR-0071 §6).
 *
 * <p>A {@code MANUAL} proposal is the generic four-eyes door's — proposed and decided under
 * {@code LEDGER_ADJUST}. A {@code RECONCILIATION} proposal is a break resolution's ledger
 * half: proposed and decided only through {@link AdjustmentService}'s owned methods, inside
 * the resolution's own transaction, so the break's state and the proposal's move together
 * ({@code INV-REC-03}). <strong>Each door refuses the other's proposals</strong>
 * ({@code ledger.AdjustmentOriginMismatch}): without that, a {@code LEDGER_ADJUST} holder
 * could post a resolution's lines while the break still stood {@code RESOLUTION_PROPOSED},
 * and neither the stale check nor the break's machine would be consulted (ADR-0071 §6, F-a).
 *
 * <p>The origin is part of the frozen payload (`V015` re-states the freeze): a proposal
 * cannot change hands after a person has read it.
 */
public enum AdjustmentOrigin {
    MANUAL,
    RECONCILIATION;

    /** The `V015` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
