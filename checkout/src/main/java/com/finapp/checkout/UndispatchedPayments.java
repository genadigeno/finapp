package com.finapp.checkout;

import java.util.UUID;

/**
 * The payment a session opened, ended when the session ends before it was ever dispatched (the
 * Phase 7 -&gt; 8 transition). Checkout cannot see payments; the composition root answers this
 * over the payment's own cancellation, as the platform.
 *
 * <p>Called INSIDE the expiring transaction and BEFORE the session row is locked: the intent's
 * row is taken first, conditionally, which is the order a wallet payment's confirmation takes
 * them (intent, then session) - the other order would let the two deadlock.
 */
@FunctionalInterface
public interface UndispatchedPayments<T> {

    /** Cancels the intent iff it still awaits confirmation; true when this call did. */
    boolean cancelIfUndispatched(T unitOfWork, UUID intentRef);

    /** For flows and tests that open no payments. */
    static <T> UndispatchedPayments<T> none() {
        return (unitOfWork, intentRef) -> false;
    }
}
