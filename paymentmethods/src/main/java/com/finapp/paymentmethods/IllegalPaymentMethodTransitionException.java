package com.finapp.paymentmethods;

import java.io.Serial;

/**
 * A payment-method status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable,
 * {@code EntityId} is not (the standing reasoning); the message names the identifier's value
 * instead. Never the token ({@code INV-AUD-02}): an exception message reaches logs.
 */
public final class IllegalPaymentMethodTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final PaymentMethodStatus from;
    private final PaymentMethodStatus to;

    public IllegalPaymentMethodTransitionException(
            PaymentMethodId method, PaymentMethodStatus from, PaymentMethodStatus to) {
        super("payment method " + method + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentMethodStatus from() {
        return from;
    }

    public PaymentMethodStatus to() {
        return to;
    }
}
