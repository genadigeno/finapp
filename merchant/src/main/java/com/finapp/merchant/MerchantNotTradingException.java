package com.finapp.merchant;

/**
 * The merchant is suspended or closed, and suspension gates new dispatches
 * ({@code CHECKOUT_MERCHANT_LIFECYCLES.md} §5): judged on the merchant row locked in the
 * payout's dispatch transaction, so a suspension and a payout serialise rather than race.
 * {@code merchant.NotTrading} at the surface — checkout's {@code checkout.NotTrading} vocabulary
 * for the same fact — and nothing is written.
 */
public class MerchantNotTradingException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantNotTradingException() {
        super("the merchant is not trading, so no payout may be dispatched");
    }
}
