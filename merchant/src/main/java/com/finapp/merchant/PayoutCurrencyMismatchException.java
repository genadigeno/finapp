package com.finapp.merchant;

/**
 * A payout asked for in a currency other than the merchant's settlement currency. A merchant has
 * one payable, in that currency, and multi-currency payouts are Phase 9's (ADR-0051
 * consequences). {@code merchant.PayoutCurrencyMismatch} at the surface, nothing written.
 */
public class PayoutCurrencyMismatchException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public PayoutCurrencyMismatchException() {
        super("a payout must be in the merchant's settlement currency");
    }
}
