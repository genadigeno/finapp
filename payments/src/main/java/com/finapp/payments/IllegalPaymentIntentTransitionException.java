package com.finapp.payments;

import java.io.Serial;

/**
 * A payment-intent status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable, {@code EntityId}
 * is not ({@code IllegalTransferTransitionException}'s recorded reasoning); the message names
 * the identifier's value instead. No amount anywhere ({@code INV-AUD-02}): an exception message
 * reaches logs, and payment amounts are {@code RESTRICTED-FINANCIAL}.
 */
public final class IllegalPaymentIntentTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final PaymentIntentStatus from;
    private final PaymentIntentStatus to;

    public IllegalPaymentIntentTransitionException(
            PaymentIntentId intent, PaymentIntentStatus from, PaymentIntentStatus to) {
        super("payment intent " + intent + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentIntentStatus from() {
        return from;
    }

    public PaymentIntentStatus to() {
        return to;
    }
}
