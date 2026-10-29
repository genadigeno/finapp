package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * An accounting break's lifecycle (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 6; ADR-0117) - born {@code OPEN}
 * once; acknowledged with a disclosure under four eyes; resolved by reference to the correcting entry the owning
 * domain posted. Never deleted, never re-opened: a recurrence is a new break.
 */
public enum BreakStatus {

    OPEN,
    ACKNOWLEDGED,
    RESOLVED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<BreakStatus> next() {
        return switch (this) {
            case OPEN -> EnumSet.of(ACKNOWLEDGED, RESOLVED);
            case ACKNOWLEDGED -> EnumSet.of(RESOLVED);
            case RESOLVED -> EnumSet.noneOf(BreakStatus.class);
        };
    }

    /** True when {@code target} is one edge away. */
    public boolean canMoveTo(BreakStatus target) {
        return next().contains(target);
    }

    /** True while the break is unresolved - what {@code finapp.accounting.break.open} counts. */
    public boolean open() {
        return this != RESOLVED;
    }
}
