package com.finapp.identity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a means of authenticating (`PHASE_1_PLAN.md` §4, {@code INV-LIFE-01}).
 *
 * <p>{@code ACTIVE ⇄ SUSPENDED}, and {@code → CLOSED} from either. Terminal is terminal
 * ({@code INV-LIFE-04}).
 *
 * <p><strong>There is no {@code PENDING}.</strong> A Customer relationship can exist before it is
 * usable, because recording who someone is and deciding they may transact are separate steps. An
 * Identity has no equivalent gap: it either can be used to authenticate or it cannot, and a state
 * meaning "exists but cannot authenticate" is exactly {@code SUSPENDED}. Adding a fourth state
 * that behaves identically to an existing one is how a state machine stops being a description of
 * anything.
 *
 * <p><strong>Why this is not the Customer's status.</strong> They move independently and that is
 * the whole reason they are separate aggregates: suspending someone's login after a credential
 * compromise must not suspend their commercial relationship, and closing a relationship must not
 * silently delete the record of who logged in. A single status column shared by both would make
 * each of those an accident waiting for the first support ticket.
 *
 * <p>Persisted values, in a generated {@code CHECK} constraint — the {@code P0-TSK-022} pattern.
 */
public enum IdentityStatus {

    /** Can authenticate. */
    ACTIVE,

    /**
     * Cannot authenticate, reversibly.
     *
     * <p>Where an administrator's suspension lands, and where a lockout would land. Reversible
     * because both are protective and both are routinely undone.
     */
    SUSPENDED,

    /**
     * Terminal. This identity will never authenticate again.
     *
     * <p>Not deletion. The identity is referenced by audit records and by sessions that existed,
     * and {@code INV-HIST-01} forbids rewriting history that points at it. A closed identity is
     * how "this login is gone" is represented without destroying the evidence that it was used.
     */
    CLOSED;

    public Set<IdentityStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(SUSPENDED, CLOSED);
            case SUSPENDED -> EnumSet.of(ACTIVE, CLOSED);
            case CLOSED -> EnumSet.noneOf(IdentityStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(IdentityStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
