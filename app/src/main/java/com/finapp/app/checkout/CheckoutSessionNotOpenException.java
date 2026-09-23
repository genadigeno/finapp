package com.finapp.app.checkout;

/**
 * The session is not awaiting a confirmation - already confirmed, completed or abandoned
 * (`P6-TSK-007`).
 *
 * <p>Distinct from an EXPIRED session, because the two say different things to the customer
 * looking at the page: one means <em>too late</em>, the other means <em>already done</em>.
 * Carries the state and nothing else ({@code INV-AUD-02}).
 */
public class CheckoutSessionNotOpenException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final com.finapp.checkout.CheckoutSessionStatus status;

    public CheckoutSessionNotOpenException(com.finapp.checkout.CheckoutSessionStatus status) {
        super("a checkout session in " + status + " is not awaiting confirmation");
        this.status = status;
    }

    /** The state that refused, for the surface to render. Never an identifier. */
    public com.finapp.checkout.CheckoutSessionStatus status() {
        return status;
    }
}
