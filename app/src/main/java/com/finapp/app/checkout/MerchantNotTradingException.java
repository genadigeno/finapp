package com.finapp.app.checkout;

/**
 * The merchant cannot open new checkout sessions - it is not {@code ACTIVE}
 * (`P6-TSK-007`).
 *
 * <p>Suspension gates <strong>new dispatches</strong> ({@code CHECKOUT_MERCHANT_LIFECYCLES.md}
 * section 5), and a new session is one. It never touches money already in flight: a session
 * confirmed before the suspension still completes and its capture still credits the payable,
 * because a suspended merchant's money stays theirs and stays explainable.
 */
public class MerchantNotTradingException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantNotTradingException() {
        super("this merchant cannot open new checkout sessions");
    }
}
