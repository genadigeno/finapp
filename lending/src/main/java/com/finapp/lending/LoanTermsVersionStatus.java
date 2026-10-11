package com.finapp.lending;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A loan terms version's machine (`P11-TSK-005`; ADR-0092 section 1, {@code LENDING_LIFECYCLES.md} section 4.1):
 * {@code PROPOSED -> ACTIVE -> RETIRED}, {@code PROPOSED -> REJECTED}. {@code lending V003}'s edge trigger and the
 * history's edge {@code CHECK} are this machine's every-writer ranks.
 */
public enum LoanTermsVersionStatus {

    /** Proposed by a holder of {@code LENDING_ADMINISTER}; awaits a different holder's activation, or anyone's rejection. */
    PROPOSED,

    /** In force for its product - at most one per product, from its effective start. */
    ACTIVE,

    /** Superseded: retired only beside its successor's activation, in the same transaction. Terminal. */
    RETIRED,

    /** Rejected - or withdrawn by its proposer. Terminal; never in force. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<LoanTermsVersionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(RETIRED);
            case RETIRED, REJECTED -> EnumSet.noneOf(LoanTermsVersionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The value list {@code lending V003}'s status {@code CHECK} names. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
