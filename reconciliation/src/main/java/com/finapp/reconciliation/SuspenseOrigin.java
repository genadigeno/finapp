package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How a suspense item's value reached {@code SUSPENSE_UNMATCHED} (`P8-TSK-010`, ADR-0070
 * §2's openers) — four, {@code REPUDIATION} joining by reconciliation `V013`
 * (`P8-TSK-023`), the origin whose {@code origin_ref} is the already-released item it
 * answers. {@code origin_ref} is the origin's own row — the external item, the Phase 7
 * parking, the settlement line — one uniform {@code UNIQUE}, the backfill's and every
 * opener's converging arbiter.
 */
public enum SuspenseOrigin {

    /** A park: the run leg's definitive classes, the grace leg, the rematch (`-011`/`-013`). */
    RECON_PARK,

    /** A bank line no remittance pattern attributes, inside recognition (`P8-TSK-016`). */
    BANK_UNATTRIBUTED,

    /** A pay-in confirmation the platform could not credit, through the port (`-020`). */
    UNMATCHED_CONFIRMATION,

    /**
     * A repudiation answering a value a resolution already released (`P8-TSK-023`,
     * ADR-0070 §10): the line the reversal or the inverse posting carries, on the
     * opposite side, owned by a {@code PROCESSING_ERROR} break.
     */
    REPUDIATION;

    /** `V013`'s {@code CHECK} value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** `V004`'s value list: the three openers before the repudiation's. */
    public static String sqlValueListBeforeV013() {
        return Arrays.stream(values())
                .filter(value -> value != REPUDIATION)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
