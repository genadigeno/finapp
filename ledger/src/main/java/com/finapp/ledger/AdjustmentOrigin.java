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
 * <p>An {@code ACCOUNTING} proposal is a prior-period adjustment's ledger half (`P14-TSK-013`, `V030`; ADR-0113 decision 4;
 * {@code INV-ACC-10}): proposed and decided only through {@link AdjustmentService}'s accounting-owned methods, in the
 * transaction that moves accounting's own row. <strong>Three owners, three doors, and each refuses the others'</strong>: a
 * reconciliation resolution cannot approve a prior-period adjustment, nor accounting a resolution, nor a {@code LEDGER_ADJUST}
 * holder either. Its lines are held to the same closure as {@code MANUAL}'s.
 *
 * <p>The origin is part of the frozen payload (`V015` re-states the freeze): a proposal
 * cannot change hands after a person has read it.
 */
public enum AdjustmentOrigin {
    MANUAL,
    RECONCILIATION,
    ACCOUNTING;

    /** The latest {@code CHECK}'s value list (`V030`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
