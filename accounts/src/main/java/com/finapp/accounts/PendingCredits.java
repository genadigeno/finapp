package com.finapp.accounts;

import com.finapp.ledger.LedgerAccountId;

/**
 * Whether a payment in flight will credit a ledger account (the Phase 6 → 7 transition) — the
 * port the composition root implements over {@code payments}, which {@code accounts} cannot see.
 *
 * <p>{@link AccountClosing} asks it for every ledger account it closes, <strong>under that
 * account's {@code FOR UPDATE}</strong>: a payment awaiting confirmation or being processed is
 * value on its way to the agreement, exactly as a standing hold is value still reserved under it,
 * and closing then would leave the provider capturing money the ledger refuses to book — the
 * customer charged, nothing recorded, and no route to refund it. The confirmation's
 * {@code FOR SHARE} on the same row is the other rank ({@code PaymentParticipants#creditable}): a
 * close either waits for a confirmation and then sees its payment, or commits first and the
 * confirmation dispatches nothing.
 */
@FunctionalInterface
public interface PendingCredits<T> {

    /** True while any payment not yet final will credit {@code account}. */
    boolean anyFor(T unitOfWork, LedgerAccountId account);
}
