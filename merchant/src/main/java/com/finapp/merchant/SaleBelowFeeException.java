package com.finapp.merchant;

/**
 * A sale whose fee meets or exceeds it: priced under its version, the net the merchant would be
 * credited is not positive (`P6-TST-001`, ADR-0058).
 *
 * <p>Such a sale would leave the merchant paying to sell — a payable driven below zero at
 * capture, a debt no refund could fund — so it is refused <strong>at the price</strong>: when a
 * checkout is opened, and again when its fee is pinned, both before any money moves. A capture
 * is never refused for it: money that landed is recorded as it landed. {@code
 * checkout.SaleBelowFee} at the surface, and nothing is written.
 *
 * <p>The message names no amount and no merchant: an exception message reaches logs.
 */
public class SaleBelowFeeException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public SaleBelowFeeException() {
        super("the sale does not cover its fee");
    }
}
