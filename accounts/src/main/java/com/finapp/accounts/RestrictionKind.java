package com.finapp.accounts;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a restriction does to an agreement (`P17-TSK-028`, ADR-0153): the two edges {@link CustomerAccountStatus} has declared
 * since Phase 3 and nothing produced - {@code ACTIVE → SUSPENDED} and back. A suspended agreement refuses new
 * customer-initiated outflows at each command's own check and admits everything else (ADR-0153 decision 2; the owner's
 * ruling of 2026-11-20, "New outflows only").
 */
public enum RestrictionKind {

    /** {@code ACTIVE → SUSPENDED}. */
    SUSPENSION(CustomerAccountStatus.ACTIVE, CustomerAccountStatus.SUSPENDED),

    /** {@code SUSPENDED → ACTIVE}. */
    REINSTATEMENT(CustomerAccountStatus.SUSPENDED, CustomerAccountStatus.ACTIVE);

    private final CustomerAccountStatus from;
    private final CustomerAccountStatus to;

    RestrictionKind(CustomerAccountStatus from, CustomerAccountStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("a restriction is an edge of the agreement's machine: " + from + " → " + to);
        }
        this.from = from;
        this.to = to;
    }

    /** The status the agreement must hold for this restriction to apply. */
    public CustomerAccountStatus from() {
        return from;
    }

    /** The status the approval moves the agreement to. */
    public CustomerAccountStatus to() {
        return to;
    }

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
    }
}
