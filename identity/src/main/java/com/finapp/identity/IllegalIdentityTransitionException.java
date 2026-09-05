package com.finapp.identity;

/**
 * An {@link Identity} was asked to make a transition its lifecycle does not permit.
 *
 * <p>A distinct type, so a caller can tell an illegal transition from every other illegal state and
 * an API layer can map it to a conflict rather than a 500.
 *
 * <p>The identifier is in the message rather than in a field, for the reason
 * {@code IllegalCustomerTransitionException} records: an exception is serializable, {@code EntityId}
 * is not, and making it so would oblige every existing identifier type to declare a
 * {@code serialVersionUID} — a change to proven Phase 0 code this task has no business making. The
 * states are enums and survive the round trip.
 */
public final class IllegalIdentityTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final IdentityStatus from;
    private final IdentityStatus to;

    public IllegalIdentityTransitionException(IdentityId identityId, IdentityStatus from, IdentityStatus to) {
        super(
                "Identity "
                        + identityId
                        + " cannot move from "
                        + from
                        + " to "
                        + to
                        + (from.isTerminal() ? " because " + from + " is terminal" : ""));
        this.from = from;
        this.to = to;
    }

    public IdentityStatus from() {
        return from;
    }

    public IdentityStatus to() {
        return to;
    }
}
