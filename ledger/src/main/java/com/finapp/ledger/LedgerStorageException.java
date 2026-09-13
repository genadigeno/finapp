package com.finapp.ledger;

import java.io.Serial;

/**
 * A ledger row could not be written or read.
 *
 * <p>The {@code KycStorageException} shape, for its recorded reasons: unchecked and never
 * swallowed, and <strong>no constructor taking a cause</strong> — PostgreSQL puts the entire
 * failing row in a constraint violation's {@code DETAIL}, and from `P3-TSK-005` on this module's
 * tables hold the platform's financial records ({@code RESTRICTED-FINANCIAL}). Callers pass
 * {@code DatabaseFailure.describe(...)}, which keeps the SQLState and drops everything else.
 */
public final class LedgerStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public LedgerStorageException(String message) {
        super(message);
    }
}
