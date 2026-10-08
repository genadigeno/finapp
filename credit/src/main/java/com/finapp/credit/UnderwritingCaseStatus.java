package com.finapp.credit;

import java.util.EnumSet;
import java.util.Set;

/**
 * An underwriting case's lifecycle (`P10-TSK-018`; CREDIT_DECISIONING_LIFECYCLES.md section 3.3, ADR-0089 point 3) - the
 * aggregate's half of the three layers: {@code credit V013}'s trigger admits exactly these edges for every writer, its
 * {@code CHECK}s hold each state's shape, and the history records each edge.
 */
public enum UnderwritingCaseStatus {

    /** In the queue: born by a referral, or released back. Only an OPEN case expires, with its request. */
    OPEN,
    /** Taken by one underwriter, who decides it whatever the request's validity. */
    ASSIGNED,
    /** Approved above the product's four-eyes threshold: a different underwriter approves, or refuses it back. */
    AWAITING_SECOND,
    /** The decision is recorded. Terminal. */
    DECIDED,
    /** Closed undecided with its request - expired or abandoned. Terminal. */
    CLOSED;

    public Set<UnderwritingCaseStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(ASSIGNED, CLOSED);
            case ASSIGNED -> EnumSet.of(OPEN, AWAITING_SECOND, DECIDED, CLOSED);
            case AWAITING_SECOND -> EnumSet.of(ASSIGNED, DECIDED, CLOSED);
            case DECIDED, CLOSED -> EnumSet.noneOf(UnderwritingCaseStatus.class);
        };
    }

    public boolean canTransitionTo(UnderwritingCaseStatus target) {
        return permittedTransitions().contains(target);
    }

    public boolean terminal() {
        return this == DECIDED || this == CLOSED;
    }
}
