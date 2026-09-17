package com.finapp.transfers;

import java.io.Serial;

/**
 * A transfers row could not be written or read.
 *
 * <p>The {@code LedgerStorageException} shape, for its recorded reasons: unchecked and never
 * swallowed, and <strong>no constructor taking a cause</strong> — PostgreSQL puts the entire
 * failing row in a constraint violation's {@code DETAIL}, and a wrapped {@code SQLException}
 * carries it into logs. Callers pass {@code DatabaseFailure.describe(...)}, which keeps the
 * operation and the SQLState and drops everything else (the {@code P1-TSK-008} finding).
 */
public final class TransfersStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public TransfersStorageException(String message) {
        super(message);
    }
}
