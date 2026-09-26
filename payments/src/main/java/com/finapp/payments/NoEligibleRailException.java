package com.finapp.payments;

/**
 * No declared rail can carry the payment (`P7-TSK-003`, ADR-0060 §3): the matched rule's
 * candidates were all refused, or no rule of the version in force matches the payment's
 * shape. Thrown after the refusal has been durably recorded as a {@link RoutingDecision}
 * with no chosen rail — the record is the point, the exception is only the answer's carrier
 * (the surface maps it to {@code payments.NoEligibleRail}, 422).
 *
 * <p>Carries identifiers only: the refusal's full explanation is the decision row's, and an
 * exception message reaches logs ({@code INV-AUD-02}).
 */
public class NoEligibleRailException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final transient RoutingDecisionId decision;

    public NoEligibleRailException(RoutingDecisionId decision) {
        super("no eligible rail: the refusal is recorded as routing decision " + decision);
        this.decision = decision;
    }

    /** The recorded refusal — what the operator's explanation read renders. */
    public RoutingDecisionId decision() {
        return decision;
    }
}
