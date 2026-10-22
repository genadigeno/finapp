package com.finapp.accounts;

import com.finapp.ledger.LedgerAccountId;
import java.util.UUID;

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
 *
 * <p><strong>A source that knows its credits by the customer, not by the account</strong> (the Phase 12 &rarr; 13
 * transition gate's A7-1): a BNPL dispute's refund outcome is credited to the customer's wallet, and bnpl records the
 * customer's party, never the wallet's account. So the close asks {@link #anyFor(Object, LedgerAccountId, UUID)} with the
 * customer whose agreement is closing (the owner its own {@code lockOwnedBy} established); the composition root answers
 * it over bnpl's open disputes of that customer's party - accounts never reads a bnpl table. It defaults to
 * {@link #anyFor(Object, LedgerAccountId)}, so a source keyed by the account alone is unchanged.
 */
@FunctionalInterface
public interface PendingCredits<T> {

    /** True while any payment not yet final will credit {@code account}. */
    boolean anyFor(T unitOfWork, LedgerAccountId account);

    /**
     * True while anything not yet final will credit {@code account}, an account of {@code customerId}'s closing agreement -
     * the account-keyed answer, unless the implementation also knows credits by the customer.
     */
    default boolean anyFor(T unitOfWork, LedgerAccountId account, UUID customerId) {
        return anyFor(unitOfWork, account);
    }
}
