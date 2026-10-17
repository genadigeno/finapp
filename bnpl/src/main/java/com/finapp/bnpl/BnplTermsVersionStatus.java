package com.finapp.bnpl;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A BNPL terms version's machine (`P12-TSK-004`; ADR-0102 decision 4, {@code BNPL_LIFECYCLES.md} section 4.1):
 * {@code PROPOSED -> ACTIVE -> RETIRED}, {@code PROPOSED -> REJECTED} - lending's terms machine
 * ({@code LoanTermsVersionStatus}), bnpl's own. {@code bnpl V002}'s edge trigger and the history's edge {@code CHECK} are
 * this machine's every-writer ranks.
 */
public enum BnplTermsVersionStatus {

    /** Proposed by a holder of {@code BNPL_ADMINISTER}; awaits a different holder's activation, or anyone's rejection. */
    PROPOSED,

    /** In force for its product - at most one per product, from its effective start. */
    ACTIVE,

    /** Superseded: retired only beside its successor's activation, in the same transaction. Terminal. */
    RETIRED,

    /** Rejected - or withdrawn by its proposer. Terminal; never in force. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<BnplTermsVersionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(RETIRED);
            case RETIRED, REJECTED -> EnumSet.noneOf(BnplTermsVersionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The value list {@code bnpl V002}'s status {@code CHECK} names. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
