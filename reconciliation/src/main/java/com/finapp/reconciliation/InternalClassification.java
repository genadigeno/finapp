package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What the platform's own records say about an external reference, as
 * {@link InternalReferenceLookup} answered at raise (`P8-TSK-010`, ADR-0069 §1) — frozen on
 * the break, because the live state moves on. Declaration order is the aggregation order:
 * when several references answer, the STRONGEST knowledge wins ({@code COMPLETED} over
 * {@code TERMINAL} over {@code IN_FLIGHT} over {@code UNKNOWN}) — the typing precedence
 * over the answer is the raising leg's (ADR-0069 §2).
 */
public enum InternalClassification {

    /** Nothing internal names it. */
    UNKNOWN,

    /** Known and not completed: a capture still pending, a refund dispatched. */
    IN_FLIGHT,

    /** Known and terminal: a failed refund, a voided attempt, a lost dispute. */
    TERMINAL,

    /** Known and completed. */
    COMPLETED;

    /** The stronger of the two — the lookup's aggregation rule. */
    public InternalClassification strongest(InternalClassification other) {
        return ordinal() >= other.ordinal() ? this : other;
    }

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
