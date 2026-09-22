package com.finapp.checkout;

/**
 * The offer's clock has run out, so nothing new may start on it (ADR-0053 §5: <em>expiry gates
 * dispatch</em>).
 *
 * <p><strong>Distinct from {@link IllegalCheckoutSessionTransitionException}, and the difference
 * is the whole design.</strong> That one means the session has already <em>reached</em> a state
 * the move is not permitted from. This one means the row still says {@code OPEN} because the
 * sweeper has not arrived yet, while the deadline has passed. Both refuse; only the second is
 * about a clock, and collapsing them would hide the fact that the platform stops honouring an
 * offer at its deadline rather than at the sweeper's convenience.
 *
 * <p>Names no session and no instant ({@code INV-AUD-02}) — a deadline is a number that, with a
 * timestamp in the same log line, identifies one customer's purchase.
 */
public class CheckoutSessionExpiredException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public CheckoutSessionExpiredException() {
        super("the checkout session's deadline has passed; it accepts no new work");
    }
}
