package com.finapp.merchant;

/**
 * The payout does not fit what the payable can fund: judged inside the payable account's lock
 * with every in-flight payout already held ({@code INV-MER-05}), so a payable left negative by
 * a retained fee (ADR-0054) refuses every amount. {@code merchant.PayoutUnfunded} at the
 * surface, and nothing is written — no row, no hold, no claim.
 */
public class MerchantPayoutUnfundedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantPayoutUnfundedException() {
        super("the payable cannot fund the payout");
    }
}
