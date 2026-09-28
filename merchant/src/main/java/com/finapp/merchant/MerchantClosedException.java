package com.finapp.merchant;

/**
 * The merchant is {@code CLOSED}, and a closed relationship takes no new payout destination
 * (`P6-TSK-011`). At the surface it is {@code merchant.IllegalTransition} — the merchant's
 * current status does not permit the change. A {@code SUSPENDED} merchant may still change its
 * destination: suspension gates new dispatches, never the arrangements a reinstated merchant
 * will be paid by ({@code CHECKOUT_MERCHANT_LIFECYCLES.md} §5).
 */
public class MerchantClosedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantClosedException() {
        super("a closed merchant takes no new payout destination");
    }
}
