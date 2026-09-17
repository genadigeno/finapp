package com.finapp.ledger;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The hold lifecycle (`P3-TSK-015`, {@code INV-LIFE-01}): the smallest machine that is still a
 * machine, because a reservation has exactly two facts to be — standing, or ended.
 *
 * <p>{@code RELEASED} is terminal ({@code INV-LIFE-04}): a released hold is never re-armed. A
 * new reservation is a <em>new</em> hold with its own identifier, so the availability history
 * stays monotonic and every audit record names the reservation it actually concerned. Capture
 * — turning a reservation into a movement — is not a state here: it is a <em>release plus a
 * posting</em> in the capturing flow's one transaction (Phase 4/5's design), because value
 * moving is the journal's fact, never a hold row's.
 */
public enum HoldStatus {

    /** Standing: the amount is subtracted from available balance ({@code INV-BAL-04}). */
    ACTIVE,

    /** Terminal ({@code INV-LIFE-04}). The reservation ended; availability was restored. */
    RELEASED;

    /** The states reachable from this one — the {@code KycCaseStatus} idiom. */
    public Set<HoldStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(RELEASED);
            case RELEASED -> EnumSet.noneOf(HoldStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(HoldStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint (`P0-TSK-022`). */
    public static String sqlValueList() {
        return EnumSet.allOf(HoldStatus.class).stream()
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
