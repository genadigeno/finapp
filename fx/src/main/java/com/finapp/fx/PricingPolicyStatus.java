package com.finapp.fx;

/**
 * A pricing policy version's position (`P9-TSK-007`, the lifecycle document §3.9):
 * {@code PROPOSED -> ACTIVE} (a different approver) {@code | REJECTED} (anyone - the proposer's
 * withdrawal included), {@code ACTIVE -> RETIRED} only beside its successor. {@code fx V004}'s
 * trigger holds the same edges for every writer.
 */
public enum PricingPolicyStatus {
    PROPOSED,
    ACTIVE,
    RETIRED,
    REJECTED;

    /** Whether the machine has the edge {@code this -> target}. */
    public boolean canMoveTo(PricingPolicyStatus target) {
        return switch (this) {
            case PROPOSED -> target == ACTIVE || target == REJECTED;
            case ACTIVE -> target == RETIRED;
            case RETIRED, REJECTED -> false;
        };
    }
}
