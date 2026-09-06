package com.finapp.identity;

import java.io.Serial;

/**
 * A credential transition the state machine does not permit.
 *
 * <p>Carries the states and not the identifier, for the reason `P1-TSK-005` recorded for its two
 * siblings: an exception is serializable and {@code EntityId} is not, and making it so would oblige
 * every existing identifier type to declare a {@code serialVersionUID} - a change to proven code
 * with no correctness benefit ({@code EXECUTION_PROTOCOL.md} rule 4). The identifier is in the log
 * line the caller writes; the states are what a caller cannot otherwise recover.
 */
public final class IllegalCredentialTransitionException extends IllegalStateException {

    @Serial private static final long serialVersionUID = 1L;

    private final CredentialStatus from;
    private final CredentialStatus to;

    IllegalCredentialTransitionException(CredentialStatus from, CredentialStatus to) {
        super("A credential cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public CredentialStatus from() {
        return from;
    }

    public CredentialStatus to() {
        return to;
    }
}
