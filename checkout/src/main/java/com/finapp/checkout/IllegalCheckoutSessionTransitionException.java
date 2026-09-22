package com.finapp.checkout;

/**
 * The requested move is not on the session's machine ({@code INV-LIFE-02}).
 *
 * <p><strong>The message names STATES and nothing else</strong> ({@code INV-AUD-02}): no session
 * identifier, no merchant, no amount, no token. A refusal is the most likely thing in this
 * module to end up in a log line somebody pastes into a ticket, and "a session cannot move from
 * EXPIRED to COMPLETED" says everything a reader needs while naming nobody's purchase.
 */
public class IllegalCheckoutSessionTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalCheckoutSessionTransitionException(
            CheckoutSessionStatus from, CheckoutSessionStatus to) {
        super("a checkout session cannot move from " + from + " to " + to);
    }
}
