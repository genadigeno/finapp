package com.finapp.party;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a commercial relationship (`PHASE_1_PLAN.md` §4, {@code INV-LIFE-01}).
 *
 * <p>{@code PENDING → ACTIVE → SUSPENDED ⇄ ACTIVE}, and {@code → CLOSED} from any of them.
 *
 * <p><strong>{@code CLOSED} is terminal, and that is the point of the enum rather than a detail
 * of it.</strong> {@code INV-LIFE-04} says a terminal state is terminal: subsequent economic
 * changes are new operations. Reopening a closed relationship would make history non-monotonic —
 * reports issued while it was closed would stop being reproducible — so re-establishing a
 * relationship with the same Party is a <em>new</em> Customer, with its own identifier and its own
 * dates. The old one stays closed and stays true.
 *
 * <p>The names are persisted values, in a {@code CHECK} constraint generated from
 * {@link #sqlValueList()}, so the enum and the schema are one definition and renaming a constant
 * is a schema change rather than a refactor — the {@code P0-TSK-022} pattern.
 */
public enum CustomerStatus {

    /**
     * The relationship exists but is not yet usable.
     *
     * <p>Modelled explicitly rather than represented by an inactive {@code ACTIVE}, because the
     * gap between "we have recorded this person" and "this person may transact" is where KYC
     * happens (Phase 2). A model without it would need a boolean beside the status, which is the
     * same thing with nothing enforcing the pair.
     */
    PENDING,

    /** The relationship is usable. */
    ACTIVE,

    /**
     * Usable relationship, temporarily stopped.
     *
     * <p>Reversible on purpose, and the only reversible transition here. Suspension is a
     * protective act — a fraud signal, a compliance hold — and unwinding it must not require
     * inventing a new relationship, because the relationship never ended.
     */
    SUSPENDED,

    /** Terminal. The relationship has ended and cannot be restarted. */
    CLOSED;

    /**
     * The states reachable from this one.
     *
     * <p>Declared here rather than in the aggregate so the machine is readable in one place, and
     * so a test can enumerate every transition rather than the ones somebody remembered to write.
     */
    public Set<CustomerStatus> permittedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(ACTIVE, CLOSED);
            case ACTIVE -> EnumSet.of(SUSPENDED, CLOSED);
            case SUSPENDED -> EnumSet.of(ACTIVE, CLOSED);
            case CLOSED -> EnumSet.noneOf(CustomerStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(CustomerStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
