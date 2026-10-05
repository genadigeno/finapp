package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A counterparty screening's position (`P9-TSK-016`, the lifecycle document §3.8, ADR-0081 point 2):
 *
 * <pre>
 * REQUESTED --the provider answered--> CLEAR | IN_REVIEW
 *     \--provider unavailable--> UNAVAILABLE --retry--> CLEAR | IN_REVIEW | UNAVAILABLE
 * IN_REVIEW --a person, with a reason--> RELEASED | BLOCKED
 * </pre>
 *
 * {@code CLEAR}, {@code RELEASED} and {@code BLOCKED} are terminal for that screening; a re-screen is
 * a new screening. {@code kyc V009}'s trigger holds the same edges for every writer.
 */
public enum CounterpartyScreeningStatus {
    REQUESTED,
    CLEAR,
    IN_REVIEW,
    UNAVAILABLE,
    RELEASED,
    BLOCKED;

    /** Whether the machine has the edge {@code this -> target}. */
    public boolean canMoveTo(CounterpartyScreeningStatus target) {
        return switch (this) {
            case REQUESTED -> target == CLEAR || target == IN_REVIEW || target == UNAVAILABLE;
            case UNAVAILABLE -> target == CLEAR || target == IN_REVIEW || target == UNAVAILABLE;
            case IN_REVIEW -> target == RELEASED || target == BLOCKED;
            case CLEAR, RELEASED, BLOCKED -> false;
        };
    }

    /**
     * Whether this outcome clears the counterparty - an automatic {@code CLEAR} or a person's
     * {@code RELEASE}; how long a clearance stays current is the caller's policy, judged from its
     * decision time.
     */
    public boolean clears() {
        return this == CLEAR || this == RELEASED;
    }

    /** The values as a SQL literal list - {@code kyc V009}'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
