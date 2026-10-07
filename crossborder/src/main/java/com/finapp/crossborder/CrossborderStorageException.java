package com.finapp.crossborder;

import java.io.Serial;

/**
 * A storage failure in the crossborder schema - loud, never a domain outcome.
 *
 * <p>The {@code KycStorageException} shape: <strong>no constructor taking a cause</strong>. This schema holds a
 * beneficiary's provider-attested destination reference and its registration (classified above {@code INTERNAL}),
 * and PostgreSQL puts the entire failing row in a constraint violation's {@code DETAIL} - a cause attached here
 * reached {@code ApiErrorHandler}'s log with the row in it (the Phase 9 to 10 transition gate; INV-RAIL-03,
 * INV-AUD-02). Callers pass {@code DatabaseFailure.describe(...)}, which keeps the SQLState and drops the row;
 * {@code StorageFailuresCarryNoRowTest} refuses a cause coming back.
 */
public final class CrossborderStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public CrossborderStorageException(String message) {
        super(message);
    }
}
