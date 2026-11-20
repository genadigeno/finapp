package com.finapp.transfers;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A reversal proposal's machine (`P17-TSK-031`, ADR-0154): {@code PROPOSED → APPROVED | REJECTED}, decided once by a person who
 * did not propose it ({@code INV-AUD-04}). The approval reverses the transfer in the same transaction; a rejection moves
 * nothing. V005's {@code CHECK} is {@link #sqlValueList()}.
 */
public enum ReversalProposalStatus {

    /** Awaiting a holder of {@code TRANSFER_REVERSE_APPROVE} who did not propose it. At most one per transfer. */
    PROPOSED,

    /** Terminal: the transfer was reversed. */
    APPROVED,

    /** Terminal: nothing moved. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<ReversalProposalStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.noneOf(ReversalProposalStatus.class);
        };
    }

    public boolean canTransitionTo(ReversalProposalStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));
    }
}
