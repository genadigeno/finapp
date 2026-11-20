package com.finapp.kyc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a reviewer's request for information (`P17-TSK-126`, ADR-0155 decision 2): {@code OPEN → ANSWERED | EXPIRED},
 * both terminal. A request closes once; a further question is a new request ({@code INV-LIFE-04}).
 *
 * <p>The names are persisted values, in kyc {@code V016}'s {@code CHECK} generated from {@link #sqlValueList()}; the
 * {@code InformationRequestMigrationTest} reconciles the two.
 */
public enum InformationRequestStatus {

    /** The customer owes an answer, and the case is {@code AWAITING_CUSTOMER}. At most one per case. */
    OPEN,

    /** Terminal. The customer answered before the deadline; the case returned to {@code IN_REVIEW}. */
    ANSWERED,

    /** Terminal. The deadline passed on the database clock unanswered; the case returned to {@code IN_REVIEW}. */
    EXPIRED;

    /** The states reachable from this one - the {@code ReviewTaskStatus} idiom. */
    public Set<InformationRequestStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(ANSWERED, EXPIRED);
            case ANSWERED, EXPIRED -> EnumSet.noneOf(InformationRequestStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(InformationRequestStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
