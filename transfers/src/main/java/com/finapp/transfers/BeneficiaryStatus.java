package com.finapp.transfers;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The beneficiary machine ({@code INV-LIFE-01}):
 *
 * <pre>ACTIVE ──&gt; REMOVED</pre>
 *
 * <p>Two states, one edge, and <strong>{@code REMOVED} is terminal</strong> ({@code INV-LIFE-04})
 * with the {@code kyc_case}/{@code customer_account} asymmetry rather than the login-identifier
 * one: removal frees the one-live slot, so saving the same destination again is legitimate and is
 * a <em>new</em> aggregate — the removed row survives as evidence of the destination that once
 * stood saved, never resurrected and never deleted.
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate every
 * transition rather than the ones somebody remembered. The schema {@code CHECK} is generated from
 * {@link #sqlValueList()}, and {@link #sqlTerminalValueList()} has <strong>two</strong> consumers
 * that must agree: {@code V003}'s partial one-live index predicate and the store's converge read —
 * one definition, so "live here" and "guarded there" cannot disagree ({@code P2-TSK-005}'s
 * reasoning). {@code BeneficiaryMigrationTest} fails the build if this enum and {@code V003}
 * drift.
 */
public enum BeneficiaryStatus {

    /** Saved and usable as a transfer destination. Birth state — a saved destination stands. */
    ACTIVE,

    /**
     * Removed by its owner. Terminal ({@code INV-LIFE-04}): the row is evidence, and a person
     * saving the destination again gets a new aggregate through the freed slot.
     */
    REMOVED;

    /** The states reachable from this one. */
    public Set<BeneficiaryStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(REMOVED);
            case REMOVED -> EnumSet.noneOf(BeneficiaryStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(BeneficiaryStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for {@code V003}'s {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list — {@code V003}'s one-live index predicate and the
     * store's converge read, generated so "terminal" and "frees the slot" are one definition.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(BeneficiaryStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
