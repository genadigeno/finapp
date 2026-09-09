package com.finapp.kyc;

/**
 * A {@link KycCase} was asked to make a transition its lifecycle does not permit.
 *
 * <p>The {@code IllegalCustomerTransitionException} shape, for its recorded reasons: a distinct
 * type so an API layer can map it to a conflict rather than a 500; the states as fields because
 * they are what a caller acts on and are serializable; the identifier in the message only,
 * because {@code EntityId} is not serializable and a non-serializable field silently empties on
 * a round trip — the requirement this project has now met four times.
 */
public final class IllegalKycCaseTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final KycCaseStatus from;
    private final KycCaseStatus to;

    public IllegalKycCaseTransitionException(KycCaseId caseId, KycCaseStatus from, KycCaseStatus to) {
        super(
                "KycCase "
                        + caseId
                        + " cannot move from "
                        + from
                        + " to "
                        + to
                        + (from.isTerminal() ? " because " + from + " is terminal" : ""));
        this.from = from;
        this.to = to;
    }

    public KycCaseStatus from() {
        return from;
    }

    public KycCaseStatus to() {
        return to;
    }
}
