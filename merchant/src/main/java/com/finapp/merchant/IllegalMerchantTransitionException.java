package com.finapp.merchant;

/**
 * A merchant state move outside the machine's edges ({@code INV-LIFE-02}) — refused by the
 * aggregate before any write, and by `V002`'s trigger for every writer that never ran this
 * code. The message names states only ({@code INV-AUD-02}): identifiers and names travel in
 * audit records, never in exception text that may reach a log.
 */
public class IllegalMerchantTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalMerchantTransitionException(MerchantStatus from, MerchantStatus to) {
        super("a merchant cannot move " + from + " -> " + to);
    }
}
