package com.finapp.payments;

/**
 * The refund would take the non-{@code FAILED} sum past the captured amount
 * ({@code INV-PAY-05}, `P5-TSK-015`) — or, since `P7-TSK-013`, the non-failed refunds and the
 * chargebacks standing on the payment together past it ({@code INV-DSP-01}, ADR-0061 §3: a
 * refund after a chargeback has taken the value back would give the same money away twice).
 * The caller's 422 — an amount they supplied and can correct — decided under the attempt-row
 * lock with nothing written; the schema trigger holds the same bound for every other writer.
 * Names the currency, never any amount ({@code INV-AUD-02}).
 */
public final class RefundExceedsCaptureException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public RefundExceedsCaptureException(com.finapp.sharedkernel.money.CurrencyCode currency) {
        super("the refund would take more than the capture credited, refunds and standing"
                + " chargebacks together, in " + currency.code());
    }
}
