package com.finapp.payments;

/**
 * A withdrawal must be priced in its wallet's currency (`P7-TSK-008`): the hold, the
 * posting and the scheme dispatch all carry one {@code Money}, and FX is no part of this
 * flow. Refused before any transaction or wire call; names currencies, never amounts.
 */
public class WithdrawalCurrencyMismatchedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public WithdrawalCurrencyMismatchedException(String walletCurrency, String requested) {
        super(
                "a withdrawal is priced in its wallet's currency "
                        + walletCurrency
                        + "; the request carried "
                        + requested);
    }
}
