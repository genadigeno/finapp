package com.finapp.kyc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a {@link VerificationCheck} (`P2-TSK-009`, ADR-0038, {@code INV-LIFE-01}).
 *
 * <pre>
 * REQUESTED → DISPATCHED → { CLEAR | HIT | INDETERMINATE }
 * </pre>
 *
 * <p><strong>The terminal states are the outcomes</strong> — one machine, not a status beside an
 * outcome column that could disagree with it.
 *
 * <p><strong>{@code INDETERMINATE} is terminal for the check</strong> ({@code INV-LIFE-03}): a
 * timeout, an unreachable provider, garbage, or a state we do not recognise is an answer of its
 * own kind — <em>we do not know</em> — recorded as exactly that, never as an assumed success or
 * failure. Its resolution is a <strong>new check</strong> on the same case, so a check never
 * flaps and the evidence of the failed attempt stays true (ADR-0038; the invariant arriving
 * three phases before its catalogued owner, as {@code INV-CON-03} did in Phase 1).
 *
 * <p>The names are persisted values, in a {@code CHECK} constraint generated from
 * {@link #sqlValueList()} and a one-in-flight partial-index predicate generated from
 * {@link #sqlTerminalValueList()}; {@code VerificationCheckMigrationTest} fails the build if this
 * enum and the schema disagree — the {@code P0-TSK-022} pattern, both artefacts.
 */
public enum CheckStatus {

    /** The question exists; nobody has asked the provider yet. */
    REQUESTED,

    /**
     * The dispatch is durable and the provider call may be in flight — or an instance died
     * mid-call, which is why this state exists at all: a crash between dispatch and outcome
     * leaves a visible, reconcilable fact rather than an unknown ({@code INV-LIFE-03}).
     */
    DISPATCHED,

    /** Terminal. The provider's answer normalised to: nothing adverse. */
    CLEAR,

    /**
     * Terminal for the check. Something adverse to be resolved by a person — never auto-cleared,
     * never auto-rejected ({@code INV-KYC-04}); the routing to review is `P2-TSK-010`'s.
     */
    HIT,

    /** Terminal. We do not know, and we say so. A new check is how it is resolved. */
    INDETERMINATE;

    /** The states reachable from this one — the machine, readable in one place. */
    public Set<CheckStatus> permittedTransitions() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(DISPATCHED);
            case DISPATCHED -> EnumSet.of(CLEAR, HIT, INDETERMINATE);
            case CLEAR, HIT, INDETERMINATE -> EnumSet.noneOf(CheckStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(CheckStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list, for the one-in-flight index predicate.
     *
     * <p>Generated so "terminal" has one definition: a status added without a decision about
     * whether it holds the in-flight slot either lets two identical questions fly at once or
     * blocks a type's retry forever — both silent without the reconciliation.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(CheckStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
