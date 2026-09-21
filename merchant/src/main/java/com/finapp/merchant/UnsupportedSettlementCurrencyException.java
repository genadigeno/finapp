package com.finapp.merchant;

/**
 * The settlement currency is not one the chart serves ({@code merchant.UnsupportedCurrency})
 * — refused before any write, the {@code AccountOpening} reasoning: a payable the residual
 * account cannot exist for must not exist for a millisecond ({@code INV-BAL-03}). Names no
 * currency: the caller sent it and needs no echo.
 */
public class UnsupportedSettlementCurrencyException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnsupportedSettlementCurrencyException() {
        super("the settlement currency is not supported");
    }
}
