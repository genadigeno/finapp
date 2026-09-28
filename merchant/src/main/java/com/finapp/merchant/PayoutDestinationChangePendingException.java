package com.finapp.merchant;

/**
 * The merchant already has an open payout destination change — proposed, or approved and cooling
 * off (`V006`'s one-open index, `P6-TSK-011`). One change at a time keeps the cooling-off's
 * meaning single: there is never a question of which pending change would win. The remedy is to
 * withdraw the open one first.
 */
public class PayoutDestinationChangePendingException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public PayoutDestinationChangePendingException() {
        super("a payout destination change is already open for the merchant");
    }
}
