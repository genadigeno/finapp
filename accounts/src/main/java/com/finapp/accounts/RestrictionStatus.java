package com.finapp.accounts;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A restriction's own machine (`P17-TSK-028`, ADR-0153 decision 4): {@code PROPOSED → APPROVED | REJECTED}, each decision once
 * and by a person who did not propose it ({@code INV-AUD-04}). The approval moves the agreement in the same transaction; a
 * rejection moves nothing. Declared on the enum so a test enumerates every edge and V003's {@code CHECK} is generated from
 * {@link #sqlValueList()}.
 */
public enum RestrictionStatus {

    /** Awaiting a holder of {@code ACCOUNT_RESTRICT_APPROVE} who did not propose it. At most one per agreement. */
    PROPOSED,

    /** Terminal: the agreement moved. */
    APPROVED,

    /** Terminal: nothing moved. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<RestrictionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.noneOf(RestrictionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(RestrictionStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));
    }
}
