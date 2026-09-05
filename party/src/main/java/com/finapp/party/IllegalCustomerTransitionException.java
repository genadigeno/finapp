package com.finapp.party;

/**
 * A {@link Customer} was asked to make a transition its lifecycle does not permit.
 *
 * <p>A distinct type rather than {@code IllegalStateException}, so a caller can tell "this move is
 * not legal from here" apart from every other illegal state, and so an API layer can map it to a
 * conflict rather than to a 500. The states are kept as fields rather than only formatted into the
 * message, because a caller that wants to report the current state should not have to parse it back
 * out of a sentence.
 *
 * <p><strong>The identifier is in the message and not in a field</strong>, which is deliberate and
 * is the third time this project has met the underlying problem. An exception is serializable, so a
 * non-serializable field silently empties on a round trip — {@code P0-TSK-009} found the monetary
 * exceptions returning {@code null} for their operands, and {@code P0-TSK-016} found the same for
 * {@code IdempotencyKey}; both were fixed by making the value serializable. {@code EntityId} is not,
 * and making it so would oblige every existing identifier type to declare a
 * {@code serialVersionUID} — a change to proven Phase 0 code that this task has no business making
 * ({@code EXECUTION_PROTOCOL.md} rule 4). The states, which are enums and therefore serializable,
 * are the part a caller acts on; the identifier is diagnostic and the message carries it.
 */
public final class IllegalCustomerTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final CustomerStatus from;
    private final CustomerStatus to;

    public IllegalCustomerTransitionException(CustomerId customerId, CustomerStatus from, CustomerStatus to) {
        super(
                "Customer "
                        + customerId
                        + " cannot move from "
                        + from
                        + " to "
                        + to
                        + (from.isTerminal() ? " because " + from + " is terminal" : ""));
        this.from = from;
        this.to = to;
    }

    public CustomerStatus from() {
        return from;
    }

    public CustomerStatus to() {
        return to;
    }
}
