package com.finapp.merchant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A merchant's BNPL enablement machine (`P12-TSK-006`; OQ-8 - a four-eyes operator act):
 *
 * <pre>
 *   PROPOSED --a second, distinct operator--> ACTIVE --any operator, risk-reducing--> WITHDRAWN
 *       \--any operator, the proposer included--> REJECTED
 * </pre>
 *
 * <p>`V010`'s {@code CHECK}s, its partial unique index and its edge trigger are generated from this enum
 * ({@code BnplEnablementMigrationTest}). A withdrawn or rejected enablement is never revived: enabling the merchant
 * again is a new proposal and a new pair of eyes.
 */
public enum BnplEnablementStatus {

    /** An operator proposed offering BNPL at the merchant. Nothing is offered. */
    PROPOSED,

    /** A second operator approved it. The merchant may offer BNPL (with a priced product). */
    ACTIVE,

    /** Terminal: the proposal was turned down - by a second operator, or withdrawn by its proposer. */
    REJECTED,

    /** Terminal: an operator stopped BNPL at the merchant - one person's act, as a suspension is. */
    WITHDRAWN;

    /** The states reachable from this one. */
    public Set<BnplEnablementStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(WITHDRAWN);
            case REJECTED, WITHDRAWN -> EnumSet.noneOf(BnplEnablementStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** Live: a proposal awaiting its second person, or the enablement in force. One per merchant (`V010`). */
    public boolean isLive() {
        return this == PROPOSED || this == ACTIVE;
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The live states as a SQL literal list, for the one-live index. */
    public static String liveSqlValueList() {
        return Arrays.stream(values())
                .filter(BnplEnablementStatus::isLive)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
