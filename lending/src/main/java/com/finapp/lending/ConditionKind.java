package com.finapp.lending;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which aspect of a loan's condition a {@link LoanConditionEvent} records a change of (`P11-TSK-024`; ADR-0098 point 3,
 * {@code LENDING_LIFECYCLES.md} section 5): its days-past-due bucket, or its default flag. A condition, never a lifecycle
 * state. The servicing hold (A9) would be a third kind - designed, not built.
 */
public enum ConditionKind {

    /** The delinquency bucket changed - {@code CURRENT} to a bucket, between buckets, or back to {@code CURRENT} (cure). */
    DELINQUENCY,

    /** The default flag changed - set at the pinned threshold (L7), cleared at cure (A8). */
    DEFAULT;

    /** The members as a SQL {@code IN} list - {@code lending V020}'s {@code kind} {@code CHECK}. */
    static String sqlValueList() {
        return Arrays.stream(values()).map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
    }
}
