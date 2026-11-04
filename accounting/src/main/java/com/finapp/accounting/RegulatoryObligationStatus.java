package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * A regulatory obligation version's lifecycle (`P14-TSK-018`; {@code accounting V010}; ADR-0118 decision 1): proposed by one
 * person, activated or rejected by a second, retired by its successor's activation. The content is frozen from the proposal.
 */
public enum RegulatoryObligationStatus {

    PROPOSED,
    ACTIVE,
    REJECTED,
    RETIRED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<RegulatoryObligationStatus> next() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(RETIRED);
            case REJECTED, RETIRED -> EnumSet.noneOf(RegulatoryObligationStatus.class);
        };
    }

    /** True when {@code target} is one edge away. */
    public boolean canMoveTo(RegulatoryObligationStatus target) {
        return next().contains(target);
    }
}
