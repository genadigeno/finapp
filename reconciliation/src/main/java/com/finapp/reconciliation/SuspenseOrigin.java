package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How a suspense item's value reached {@code SUSPENSE_UNMATCHED} (`P8-TSK-010`, ADR-0070
 * §2's openers) — three now; {@code REPUDIATION} joins by reconciliation `V011`
 * (`P8-TSK-023`), the origin whose {@code origin_ref} is the already-released item it
 * reverses. {@code origin_ref} is the origin's own row — the external item, the Phase 7
 * parking, the settlement line — one uniform {@code UNIQUE}, the backfill's and every
 * opener's converging arbiter.
 */
public enum SuspenseOrigin {

    /** A park: the run leg's definitive classes, the grace leg, the rematch (`-011`/`-013`). */
    RECON_PARK,

    /** A bank line no remittance pattern attributes, inside recognition (`P8-TSK-016`). */
    BANK_UNATTRIBUTED,

    /** A pay-in confirmation the platform could not credit, through the port (`-020`). */
    UNMATCHED_CONFIRMATION;

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
