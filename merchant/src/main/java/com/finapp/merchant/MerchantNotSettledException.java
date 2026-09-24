package com.finapp.merchant;

import java.io.Serial;

/**
 * A close refused because the merchant is still owed money, or money is on its way to or from its
 * payable (the Phase 6 → 7 transition): a non-zero payable, a standing hold, or a payment in
 * flight. A closed merchant can be paid out by nothing, so the platform would be left owing a
 * liability it has no way to settle — Phase 3's account close refuses the same way. Nothing was
 * written.
 */
public final class MerchantNotSettledException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public MerchantNotSettledException() {
        // Names no amount and no account (INV-AUD-02): this message reaches logs.
        super("a merchant with money owed or in flight cannot be closed");
    }
}
