package com.finapp.payments;

/**
 * A dispute was asked to move along an edge its machine does not have, or to be born at a
 * stage the network never opens with (`P7-TSK-012`, {@code INV-LIFE-02}) — every edge out of a
 * terminal stage included ({@code INV-LIFE-04}).
 */
public class IllegalDisputeTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalDisputeTransitionException(DisputeStage from, DisputeStage to) {
        super("a dispute cannot move " + from + " -> " + to);
    }

    public IllegalDisputeTransitionException(DisputeStage birth) {
        super("a dispute cannot be born at " + birth + ": only an entry stage opens one");
    }
}
