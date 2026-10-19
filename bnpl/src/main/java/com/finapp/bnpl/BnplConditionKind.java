package com.finapp.bnpl;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which aspect of a BNPL obligation's condition a {@link BnplConditionEvent} records a change of (`P12-TSK-020`;
 * {@code PHASE_12_PLAN.md} section 12.8; lending's {@code ConditionKind}): its days-past-due bucket, or its default flag.
 * A condition, never a lifecycle state - the obligation stays {@code ACTIVE}. The dispute hold (`P12-TSK-023`) amends the
 * derivation, not this set.
 */
public enum BnplConditionKind {

    /** The delinquency bucket changed - {@code CURRENT} to a bucket, between buckets, or back to {@code CURRENT} (cure). */
    DELINQUENCY,

    /** The default flag changed - set at the pinned threshold (A14: 90), cleared at cure. */
    DEFAULT;

    /** The members as a SQL {@code IN} list - {@code bnpl V013}'s {@code kind} {@code CHECK}. */
    static String sqlValueList() {
        return Arrays.stream(values()).map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
    }
}
