package com.finapp.ledger;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The adjustment proposal's lifecycle (`P3-TSK-021`, {@code INV-LIFE-01}): proposed, then
 * decided — once, ever.
 *
 * <p>Both terminal states are terminal ({@code INV-LIFE-04}). A rejected proposal is never
 * revived and an approved one is never re-approved: a new adjustment is a <em>new</em>
 * proposal with its own identifier, so the trail names exactly which request each person
 * answered. The one-way machine is also the approval's idempotency ({@code INV-IDEM-01}
 * through state): {@code PROPOSED → APPROVED} happens at most once for every writer, and a
 * retried approval converges on the recorded entry rather than posting again.
 */
public enum AdjustmentProposalStatus {

    /** Standing: the initiator has asked, and no second person has answered. */
    PROPOSED,

    /** Terminal. A second person approved; the journal entry posted in their transaction. */
    APPROVED,

    /** Terminal. Declined — by a second person, or withdrawn by the initiator. */
    REJECTED;

    /** The states reachable from this one — the {@code HoldStatus} idiom. */
    public Set<AdjustmentProposalStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.noneOf(AdjustmentProposalStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint (`P0-TSK-022`). */
    public static String sqlValueList() {
        return EnumSet.allOf(AdjustmentProposalStatus.class).stream()
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
