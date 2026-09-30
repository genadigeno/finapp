package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * An external item's line type (`P8-TSK-009`) — reconciliation's MIRROR of settlement's
 * canonical vocabulary, name for name, because an item is the working copy of one immutable
 * settlement line and no build edge may exist between the modules (ADR-0064). The copies
 * are held equal by an `app` guard test, which is the one place that sees both.
 *
 * <p>{@link #allocating()} is the matcher's and the proof's split (ADR-0065 §2,
 * `INV-REC-06`): a {@code PROCESSING_FEE} line's effect IS the recognition entry, so it
 * never allocates against an expectation and never enters the position identity's items
 * term — it takes the {@code CHECKED} path at matching. Everything else asserts value
 * already in the position and allocates.
 */
public enum ExternalLineType {
    CAPTURE,
    REFUND,
    CHARGEBACK,
    CHARGEBACK_REVERSAL,
    DISPUTE_FEE,
    PROCESSING_FEE,
    COUNTERPARTY_ADJUSTMENT,
    OTHER_IN,
    OTHER_OUT;

    /** Whether this line claims value in the position — false exactly for the fee. */
    public boolean allocating() {
        return this != PROCESSING_FEE;
    }

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The allocating members as a SQL list — the items-term reading's filter. */
    public static String sqlAllocatingList() {
        return Arrays.stream(values())
                .filter(ExternalLineType::allocating)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
