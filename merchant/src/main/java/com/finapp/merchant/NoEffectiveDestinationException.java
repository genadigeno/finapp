package com.finapp.merchant;

/**
 * The merchant has no {@code EFFECTIVE} payout destination, so there is nowhere a payout may go
 * (ADR-0056 §9): a proposal or a cooling-off changes nothing until the platform effects it.
 * {@code merchant.NoEffectiveDestination} at the surface, nothing written.
 */
public class NoEffectiveDestinationException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public NoEffectiveDestinationException() {
        super("the merchant has no effective payout destination");
    }
}
