package com.finapp.kyc;

/**
 * A {@link VerificationCheck} was asked to make a transition its lifecycle does not permit.
 *
 * <p>The {@code IllegalKycCaseTransitionException} shape, for its recorded reasons: a distinct
 * type so an API layer can map it to a conflict rather than a 500; the states as fields because
 * they are what a caller acts on and are serializable; the identifier in the message only,
 * because {@code EntityId} is not serializable and a non-serializable field silently empties on
 * a round trip — the requirement this project has now met five times.
 */
public final class IllegalCheckTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final CheckStatus from;
    private final CheckStatus to;

    public IllegalCheckTransitionException(CheckId checkId, CheckStatus from, CheckStatus to) {
        super(
                "Verification check "
                        + checkId
                        + " cannot move from "
                        + from
                        + " to "
                        + to
                        + (from.isTerminal() ? " because " + from + " is terminal" : ""));
        this.from = from;
        this.to = to;
    }

    public CheckStatus from() {
        return from;
    }

    public CheckStatus to() {
        return to;
    }
}
