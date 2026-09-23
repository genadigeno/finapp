package com.finapp.payments;

/**
 * The caller has no live wallet to fund: no live ACTIVE customer, or no live wallet product
 * ({@code PaymentParticipants.walletOwnedBy}'s one empty answer). Nothing was written.
 */
public final class NoWalletForPaymentException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public NoWalletForPaymentException() {
        super("the caller has no live wallet to fund");
    }
}
