package com.finapp.kyc;

/**
 * An {@link InformationRequest} was asked to make a transition its lifecycle does not permit - the
 * {@link IllegalKycCaseTransitionException} shape: the states as fields, the identifier in the message only.
 */
public final class IllegalInformationRequestTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final InformationRequestStatus from;
    private final InformationRequestStatus to;

    public IllegalInformationRequestTransitionException(
            InformationRequestId requestId, InformationRequestStatus from, InformationRequestStatus to) {
        super("InformationRequest " + requestId + " cannot move from " + from + " to " + to
                + (from.isTerminal() ? " because " + from + " is terminal" : ""));
        this.from = from;
        this.to = to;
    }

    public InformationRequestStatus from() {
        return from;
    }

    public InformationRequestStatus to() {
        return to;
    }
}
