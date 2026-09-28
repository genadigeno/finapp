package com.finapp.merchant;

/**
 * A payout destination move outside the machine's edges ({@code INV-LIFE-02}) — refused by the
 * aggregate before any write, and by `V006`'s trigger for every writer that never ran this code.
 * At the surface it is {@code merchant.DestinationChangeNotOpen}: the change is no longer open
 * to the decision asked of it. The message names states only ({@code INV-AUD-02}).
 */
public class IllegalPayoutDestinationTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalPayoutDestinationTransitionException(
            PayoutDestinationStatus from, PayoutDestinationStatus to) {
        super("a payout destination cannot move " + from + " -> " + to);
    }
}
