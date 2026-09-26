package com.finapp.payments;

/**
 * A withdrawal was asked to move along an edge its machine does not have
 * (`P7-TSK-008`, {@code INV-LIFE-02}) — including every edge out of {@code COMPLETED},
 * which is {@code INV-REV-03}'s refusal wearing the machine's own shape.
 */
public class IllegalWithdrawalTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalWithdrawalTransitionException(WithdrawalStatus from, WithdrawalStatus to) {
        super("a withdrawal cannot move " + from + " -> " + to);
    }
}
