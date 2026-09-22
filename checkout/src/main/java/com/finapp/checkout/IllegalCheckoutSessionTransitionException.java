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

    private final CheckoutSessionStatus from;
    private final CheckoutSessionStatus to;

    public IllegalCheckoutSessionTransitionException(
            CheckoutSessionStatus from, CheckoutSessionStatus to) {
        super("a checkout session cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    /**
     * The state the session was actually in (`P6-TSK-008`).
     *
     * <p>Typed rather than left in the message, because a surface translating this into
     * {@code checkout.NotAbandonable} needs the state to tell the caller <em>which</em> refusal
     * this is, and parsing it back out of a sentence is how a message becomes an accidental
     * contract. The same information, in the form a caller can act on.
     */
    public CheckoutSessionStatus from() {
        return from;
    }

    /** The move that was refused. */
    public CheckoutSessionStatus to() {
        return to;
    }
}
