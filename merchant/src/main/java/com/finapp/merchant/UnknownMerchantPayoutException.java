package com.finapp.merchant;

/**
 * No payout answers to the identifier under the authenticated merchant — the surface's one
 * {@code api.NotFound}: unknown, malformed and another merchant's alike, because the pairing is
 * judged in the statement ({@code INV-MER-01}'s oracle discipline).
 */
public class UnknownMerchantPayoutException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownMerchantPayoutException() {
        super("no payout answers to the identifier under the merchant");
    }
}
