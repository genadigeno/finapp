package com.finapp.merchant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The payout destination machine (`P6-TSK-011`, ADR-0056, {@code CHECKOUT_MERCHANT_LIFECYCLES.md}
 * §6):
 *
 * <pre>
 * PROPOSED ──► APPROVED ──► EFFECTIVE ──► SUPERSEDED
 *    │   └──► REJECTED   │
 *    └──────► WITHDRAWN ◄┘
 * </pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate
 * every transition rather than the ones somebody remembered. `V006`'s status {@code CHECK}s,
 * its trigger's edges and its one-open index are generated from here;
 * {@code PayoutDestinationMigrationTest} fails the build if they disagree.
 *
 * <p><strong>Every state has a producer</strong> (ADR-0044): an operator proposes; a second,
 * distinct operator approves or rejects ({@code INV-AUD-04}); an administrator withdraws; the
 * platform's effectuation sweep produces {@code EFFECTIVE} once the cooling-off has elapsed, and
 * {@code SUPERSEDED} for the destination it replaces, in the same transaction.
 *
 * <p><strong>{@code APPROVED → WITHDRAWN} is the edge that gives the cooling-off its teeth.</strong>
 * A cooling-off that nothing can act on is a delay, not a control: the window exists so that a
 * change nobody meant can be stopped before any money follows it.
 */
public enum PayoutDestinationStatus {

    /** An operator proposed the destination. Nothing pays to it. */
    PROPOSED,

    /** A second operator approved it; the cooling-off is running. Nothing pays to it yet. */
    APPROVED,

    /** The destination payouts go to. At most one per merchant (`V006`'s partial index). */
    EFFECTIVE,

    /** Terminal: a later destination took effect in its place, in one transaction. */
    SUPERSEDED,

    /** Terminal: the second pair of eyes said no. */
    REJECTED,

    /** Terminal: withdrawn before it took effect — during the proposal or the cooling-off. */
    WITHDRAWN;

    /** The states reachable from this one. */
    public Set<PayoutDestinationStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED, WITHDRAWN);
            case APPROVED -> EnumSet.of(EFFECTIVE, WITHDRAWN);
            case EFFECTIVE -> EnumSet.of(SUPERSEDED);
            case SUPERSEDED, REJECTED, WITHDRAWN -> EnumSet.noneOf(PayoutDestinationStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(PayoutDestinationStatus target) {
        return permittedTransitions().contains(target);
    }

    /**
     * An open change: proposed, or approved and cooling off. A merchant has at most one open
     * change at a time (`V006`'s partial unique index), and these are what the pending gauge
     * counts.
     */
    public boolean isOpen() {
        return this == PROPOSED || this == APPROVED;
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraints. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The open states as a SQL literal list, for the one-open index and the pending count. */
    public static String openSqlValueList() {
        return Arrays.stream(values())
                .filter(PayoutDestinationStatus::isOpen)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
