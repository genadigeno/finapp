package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * A valuation rate set's lifecycle (`P14-TSK-014`; {@code PHASE_14_PLAN.md} section 6; ADR-0116 decision 2;
 * {@code INV-ACC-11}) - the closed edge table {@code accounting V005}'s trigger enforces for every writer, each edge a
 * conditional {@code UPDATE ... WHERE status = 'PROPOSED'}:
 *
 * <pre>
 * PROPOSED --approve (a person other than its capturer)--> APPROVED
 * PROPOSED --reject---------------------------------------> REJECTED
 * </pre>
 *
 * <p>Both decisions are terminal and the set's rates never change: a correction is a new capture, and at most one set
 * per period is ever {@link #APPROVED} (the partial unique {@code valuation_rate_set_one_approved_per_period}). No
 * supersession edge exists - an approved set is the period's for good.
 */
public enum ValuationRateSetStatus {

    PROPOSED,
    APPROVED,
    REJECTED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<ValuationRateSetStatus> next() {
        return this == PROPOSED ? EnumSet.of(APPROVED, REJECTED) : EnumSet.noneOf(ValuationRateSetStatus.class);
    }

    /** True when {@code target} is one edge away. A status never moves to itself. */
    public boolean canMoveTo(ValuationRateSetStatus target) {
        return next().contains(target);
    }

    /** True for the edge a second person completes: the approval ({@code INV-AUD-04}); a rejection is anyone's. */
    public boolean fourEyesTo(ValuationRateSetStatus target) {
        return canMoveTo(target) && target == APPROVED;
    }
}
