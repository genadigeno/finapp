package com.finapp.paymentmethods;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The payment-method machine ({@code INV-LIFE-01}, `P5-TSK-004`):
 *
 * <pre>ACTIVE ──&gt; DETACHED</pre>
 *
 * <p>Two states, one edge, and <strong>{@code DETACHED} is terminal</strong>
 * ({@code INV-LIFE-04}) with the {@code Beneficiary} asymmetry exactly: detaching frees the
 * one-live (party, token) slot, so attaching the same instrument again is legitimate and is a
 * <em>new</em> aggregate — the detached row survives as evidence of the instrument that once
 * stood attached, never resurrected and never deleted. Updated display metadata is
 * detach-and-reattach, never an edit.
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate
 * every transition rather than the ones somebody remembered. The schema {@code CHECK} is
 * generated from {@link #sqlValueList()}, and {@link #sqlTerminalValueList()} has two consumers
 * that must agree: {@code V002}'s partial one-live index predicate and the store's converge
 * read. {@code PaymentMethodMigrationTest} fails the build if this enum and {@code V002} drift.
 */
public enum PaymentMethodStatus {

    /** Attached and resolvable as a payment instrument. Birth state. */
    ACTIVE,

    /**
     * Detached by its owner. Terminal ({@code INV-LIFE-04}): the row is evidence — what Phase 8
     * uses to explain a historical payment's instrument — and re-attaching the token mints a
     * new aggregate through the freed slot.
     */
    DETACHED;

    /** The states reachable from this one. */
    public Set<PaymentMethodStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(DETACHED);
            case DETACHED -> EnumSet.noneOf(PaymentMethodStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(PaymentMethodStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for {@code V002}'s {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list — {@code V002}'s one-live index predicate and
     * the store's converge read, generated so "terminal" and "frees the slot" are one
     * definition.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(PaymentMethodStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
