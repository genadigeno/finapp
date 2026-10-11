package com.finapp.lending;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A lending-capital contribution's machine (`P11-TSK-004`, ADR-0096 section 4): {@code PROPOSED -> APPROVED |
 * REJECTED} and nothing else. Recognised capital is NOT a state here - it is reconciliation's fact, bank recognition of
 * the statement line the approved contribution's expectation matched, read as {@link CapitalRecognitionStatus}.
 * {@code lending V002}'s edge {@code CHECK} is this machine's every-writer rank.
 */
public enum CapitalContributionStatus {

    /** A treasury officer proposed it; it awaits a different holder's approval, or anyone's rejection. */
    PROPOSED,

    /** A different holder approved it and opened its expectation. Terminal. */
    APPROVED,

    /** Rejected - or withdrawn by its proposer. Terminal. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<CapitalContributionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.noneOf(CapitalContributionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The value list {@code lending V002}'s edge {@code CHECK} names. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
