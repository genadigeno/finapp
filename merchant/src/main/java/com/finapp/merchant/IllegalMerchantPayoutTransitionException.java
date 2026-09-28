package com.finapp.merchant;

/**
 * A payout move outside the machine's edges ({@code INV-LIFE-02}) — refused by the aggregate
 * before any write, and by `V007`'s trigger for every writer that never ran this code. The
 * message names states only ({@code INV-AUD-02}).
 */
public class IllegalMerchantPayoutTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalMerchantPayoutTransitionException(
            MerchantPayoutStatus from, MerchantPayoutStatus to) {
        super("a merchant payout cannot move " + from + " -> " + to);
    }
}
