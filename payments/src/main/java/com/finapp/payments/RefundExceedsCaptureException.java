package com.finapp.payments;

/**
 * The refund would take the non-{@code FAILED} sum past the captured amount
 * ({@code INV-PAY-05}, `P5-TSK-015`). The caller's 422 — an amount they supplied and can
 * correct — decided under the attempt-row lock with nothing written; the schema trigger holds
 * the same bound for every other writer. Names the currency, never any amount
 * ({@code INV-AUD-02}).
 */
public final class RefundExceedsCaptureException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public RefundExceedsCaptureException(com.finapp.sharedkernel.money.CurrencyCode currency) {
        super("the refund would exceed the captured amount in " + currency.code());
    }
}
