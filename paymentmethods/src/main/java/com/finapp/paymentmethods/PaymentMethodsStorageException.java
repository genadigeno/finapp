package com.finapp.paymentmethods;

import java.io.Serial;

/**
 * A payment-method row could not be written or read.
 *
 * <p>The {@code TransfersStorageException} shape, for its recorded reasons: unchecked and never
 * swallowed, and <strong>no constructor taking a cause</strong> — PostgreSQL puts the entire
 * failing row in a constraint violation's {@code DETAIL}, and on this table that row holds a
 * token reference, which must never reach a log. Callers pass
 * {@code DatabaseFailure.describe(...)}, which keeps the operation and the SQLState and drops
 * everything else (the {@code P1-TSK-008} finding).
 */
public final class PaymentMethodsStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public PaymentMethodsStorageException(String message) {
        super(message);
    }
}
