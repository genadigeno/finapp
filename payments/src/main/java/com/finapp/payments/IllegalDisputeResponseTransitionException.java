package com.finapp.payments;

/**
 * A dispute response was asked to move along an edge its machine does not have (`P7-TSK-014`,
 * {@code INV-LIFE-02}) — every edge out of a terminal status included ({@code INV-LIFE-04}).
 */
public class IllegalDisputeResponseTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalDisputeResponseTransitionException(
            DisputeResponseStatus from, DisputeResponseStatus to) {
        super("a dispute response cannot move " + from + " -> " + to);
    }
}
