package com.finapp.app.checkout;

/**
 * The merchant has no effective fee schedule version, so the platform cannot price this
 * offer (`P6-TSK-007`).
 *
 * <p><strong>Refused at creation rather than discovered at capture</strong>, which is the whole
 * reason the check is here: an unpriced session would reach
 * {@code MerchantSettlement} and throw INSIDE the transaction that moves money, after the
 * customer had paid. The difference is between a merchant fixing their configuration and a
 * customer's money needing a refund.
 */
public class MerchantNotPriceableException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantNotPriceableException() {
        super("this merchant has no fee schedule, so a checkout cannot be priced");
    }
}
