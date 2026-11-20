package com.finapp.merchant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A closure proposal's machine (`P17-TSK-031`, ADR-0154): {@code PROPOSED → APPROVED | REJECTED}, decided once by a person who
 * did not propose it ({@code INV-AUD-04}). The approval closes the merchant in the same transaction; a rejection moves
 * nothing. V019's {@code CHECK} is {@link #sqlValueList()}.
 */
public enum ClosureProposalStatus {

    /** Awaiting a holder of {@code MERCHANT_CLOSE_APPROVE} who did not propose it. At most one per merchant. */
    PROPOSED,

    /** Terminal: the merchant was closed. */
    APPROVED,

    /** Terminal: nothing moved. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<ClosureProposalStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.noneOf(ClosureProposalStatus.class);
        };
    }

    public boolean canTransitionTo(ClosureProposalStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));
    }
}
