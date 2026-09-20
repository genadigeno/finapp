package com.finapp.payments;

import java.io.Serial;

/**
 * A refund status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable, {@code EntityId}
 * is not ({@code IllegalTransferTransitionException}'s recorded reasoning); the message names
 * the identifier's value instead. No amount anywhere ({@code INV-AUD-02}).
 */
public final class IllegalRefundTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final RefundStatus from;
    private final RefundStatus to;

    public IllegalRefundTransitionException(RefundId refund, RefundStatus from, RefundStatus to) {
        super("refund " + refund + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public RefundStatus from() {
        return from;
    }

    public RefundStatus to() {
        return to;
    }
}
