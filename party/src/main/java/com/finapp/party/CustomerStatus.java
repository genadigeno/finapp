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
    CLOSED,

    /**
     * Terminal. The KYC decision refused onboarding (`P2-TSK-014`, ADR-0035).
     *
     * <p>Reachable only from {@code PENDING}: rejection is what can happen to a relationship
     * that never became usable, and an {@code ACTIVE} relationship that must end ends by
     * {@code CLOSED}. Deliberately not mapped onto {@code CLOSED}, which would overload one
     * terminal with two meanings — "ended" and "refused" — and make this projection unfaithful
     * to the decision it mirrors ({@code INV-KYC-05}). Re-onboarding after changed
     * circumstances is a <em>new</em> Customer ({@code INV-LIFE-04}), which the one-live index
     * permits because a terminal state frees the slot.
     */
    REJECTED;

    /**
     * The states reachable from this one.
     *
     * <p>Declared here rather than in the aggregate so the machine is readable in one place, and
     * so a test can enumerate every transition rather than the ones somebody remembered to write.
     */
    public Set<CustomerStatus> permittedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(ACTIVE, CLOSED, REJECTED);
            case ACTIVE -> EnumSet.of(SUSPENDED, CLOSED);
            case SUSPENDED -> EnumSet.of(ACTIVE, CLOSED);
            case CLOSED, REJECTED -> EnumSet.noneOf(CustomerStatus.class);
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

    /**
     * The terminal states as a SQL literal list, for the one-live-relationship index predicate
     * (`P2-TSK-014`, the {@code KycCaseStatus.sqlTerminalValueList} shape).
     *
     * <p>Generated so that "terminal" and "frees the party's one-live slot" are one definition:
     * {@code PartyEnumMigrationTest}'s original one-terminal assertion existed precisely to
     * break the day a second terminal arrived — it did, here — and the repair it demanded is
     * this derivation rather than a wider hand-written literal. A state added to this machine
     * without a decision about which side of the predicate it sits on fails that reconciliation,
     * never a duplicate-customer incident.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(CustomerStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
