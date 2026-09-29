package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which side of {@code SUSPENSE_UNMATCHED} the item's value sits on (`P8-TSK-010`, ADR-0070):
 * a {@code CREDIT} item is value the platform holds and cannot attribute (an unrecognised
 * liability to someone); a {@code DEBIT} item is value that left with no record explaining
 * it (an unrecognised loss or receivable). Fixed at birth, and never netted against the
 * other side — not in a posting, not in the report.
 */
public enum SuspenseSide {

    /** An INBOUND remainder parked: DR the position / CR suspense. */
    CREDIT,

    /** An OUTBOUND remainder parked: DR suspense / CR the position. */
    DEBIT;

    /** The park line's side for an item direction (ADR-0070 §2's opener table). */
    public static SuspenseSide of(ExpectationDirection direction) {
        return direction == ExpectationDirection.INBOUND ? CREDIT : DEBIT;
    }

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
