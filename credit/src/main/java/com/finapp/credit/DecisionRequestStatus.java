package com.finapp.credit;

import java.util.EnumSet;
import java.util.Set;

/**
 * A credit decision request's lifecycle (`P10-TSK-014`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1,
 * {@code INV-LIFE-01}). The aggregate's half of the three layers: {@code credit V010}'s trigger admits exactly these
 * edges for every writer, and the history records each.
 */
public enum DecisionRequestStatus {

    SUBMITTED,
    COLLECTING,
    READY,
    EVALUATED,
    IN_REVIEW,
    DECIDED,
    CANCELLED,
    EXPIRED,
    ABANDONED;

    /** The open states - the one-open-per-product index's predicate and the expiry's scope. */
    public static final Set<DecisionRequestStatus> OPEN =
            Set.copyOf(EnumSet.of(SUBMITTED, COLLECTING, READY, EVALUATED, IN_REVIEW));

    /** The states the applicant may cancel from: before an evaluation exists. */
    public static final Set<DecisionRequestStatus> CANCELLABLE = Set.copyOf(EnumSet.of(SUBMITTED, COLLECTING, READY));

    public Set<DecisionRequestStatus> permittedTransitions() {
        return switch (this) {
            case SUBMITTED -> EnumSet.of(COLLECTING, CANCELLED, EXPIRED, ABANDONED);
            case COLLECTING -> EnumSet.of(READY, CANCELLED, EXPIRED, ABANDONED);
            case READY -> EnumSet.of(COLLECTING, EVALUATED, CANCELLED, EXPIRED, ABANDONED);
            case EVALUATED -> EnumSet.of(DECIDED, IN_REVIEW, EXPIRED, ABANDONED);
            case IN_REVIEW -> EnumSet.of(DECIDED, EXPIRED, ABANDONED);
            case DECIDED, CANCELLED, EXPIRED, ABANDONED -> EnumSet.noneOf(DecisionRequestStatus.class);
        };
    }

    public boolean open() {
        return OPEN.contains(this);
    }

    public boolean canTransitionTo(DecisionRequestStatus target) {
        return permittedTransitions().contains(target);
    }
}
