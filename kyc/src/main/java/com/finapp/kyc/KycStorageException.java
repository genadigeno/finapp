package com.finapp.kyc;

import java.io.Serial;

/**
 * A KYC row could not be written or read.
 *
 * <p>The {@code PartyStorageException} shape, for its recorded reasons: unchecked and never
 * swallowed, and <strong>no constructor taking a cause</strong> — PostgreSQL puts the entire
 * failing row in a constraint violation's {@code DETAIL}, and this module's tables will hold the
 * most sensitive bytes the platform has before card data ({@code INV-KYC-06}). Callers pass
 * {@code DatabaseFailure.describe(...)}, which keeps the SQLState and drops everything else.
 */
public final class KycStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public KycStorageException(String message) {
        super(message);
    }
}
