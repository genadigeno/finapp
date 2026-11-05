package com.finapp.ledger;

import java.util.EnumSet;
import java.util.Set;

/**
 * A projection rebuild's lifecycle (`P14-TSK-026`, ADR-0119; {@code INV-LIFE-01}, {@code INV-AUD-04}) - the closed edge
 * table {@code ledger V031}'s trigger enforces for every writer:
 *
 * <pre>
 * PROPOSED --approve (a second person), the row moved-------------> APPLIED
 * PROPOSED --approve (a second person), nothing to correct--------> UNCHANGED
 * PROPOSED --reject (anyone holding the door, the proposer too)---> REJECTED
 * </pre>
 *
 * <p>All three decisions are terminal: a later drift is a new proposal. {@code UNCHANGED} is an approval that found the
 * projection already what the journal says and wrote nothing to it - the act recorded, the row untouched.
 */
public enum ProjectionRebuildStatus {

    PROPOSED,
    APPLIED,
    UNCHANGED,
    REJECTED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<ProjectionRebuildStatus> next() {
        return this == PROPOSED ? EnumSet.of(APPLIED, UNCHANGED, REJECTED) : EnumSet.noneOf(ProjectionRebuildStatus.class);
    }

    /** True when {@code target} is one edge away. A status never moves to itself. */
    public boolean canMoveTo(ProjectionRebuildStatus target) {
        return next().contains(target);
    }

    /** True for the two outcomes of an approval - the four-eyes edges ({@code INV-AUD-04}). */
    public boolean approved() {
        return this == APPLIED || this == UNCHANGED;
    }
}
