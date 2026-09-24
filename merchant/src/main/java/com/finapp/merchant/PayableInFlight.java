package com.finapp.merchant;

import com.finapp.ledger.LedgerAccountId;

/**
 * Whether a payment not yet final will credit a merchant's payable (the Phase 6 → 7 transition)
 * — the port the composition root implements over {@code payments}, which {@code merchant} cannot
 * see. {@link MerchantAdministration} asks it under the payable's lock before a close: a capture
 * landing on a closed merchant's payable is a liability nothing can pay out.
 */
@FunctionalInterface
public interface PayableInFlight<T> {

    /** True while any payment not yet final will credit {@code payable}. */
    boolean anyCrediting(T unitOfWork, LedgerAccountId payable);
}
