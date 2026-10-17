package com.finapp.merchant;

/**
 * The merchant is {@code CLOSED}, and a closed relationship takes no new payout destination
 * (`P6-TSK-011`). At the surface it is {@code merchant.IllegalTransition} — the merchant's
 * current status does not permit the change. A {@code SUSPENDED} merchant may still change its
 * destination: suspension gates new dispatches, never the arrangements a reinstated merchant
 * will be paid by ({@code CHECKOUT_MERCHANT_LIFECYCLES.md} §5).
 *
 * <p>Since `P12-TSK-006` also a BNPL enablement's proposal or approval for a closed merchant: a
 * relationship that ended takes no new business. The message was generalised then.
 */
public class MerchantClosedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantClosedException() {
        super("a closed merchant takes no new change");
    }
}
