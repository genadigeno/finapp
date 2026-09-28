package com.finapp.merchant;

/**
 * No payout destination answers to the identifier under the named merchant — the surface's one
 * {@code api.NotFound}: unknown, malformed and another merchant's alike, because the pairing is
 * judged in the statement ({@code INV-MER-01}'s oracle discipline).
 */
public class UnknownPayoutDestinationException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownPayoutDestinationException() {
        super("no payout destination answers to the identifier under the merchant");
    }
}
