package com.finapp.app.checkout;

/**
 * No checkout session answers - unknown, malformed, another merchant's, or a token that
 * opens nothing (`P6-TSK-007`).
 *
 * <p><strong>One answer for all four</strong>, and the reasoning is stronger here than the
 * usual one-404 rule: a checkout token is GUESSED at rather than typed, so telling a guesser
 * that a session exists but is not theirs is the only bit they need. For the merchant surface
 * the same answer is {@code INV-MER-01}'s tenancy oracle.
 */
public class UnknownCheckoutSessionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownCheckoutSessionException() {
        super("no checkout session answers");
    }
}
