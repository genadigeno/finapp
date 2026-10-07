package com.finapp.credit;

import java.io.Serial;

/**
 * A storage failure in the credit schema - loud, never a domain outcome (`P10-TSK-004`).
 *
 * <p>The {@code CrossborderStorageException} shape: <strong>no constructor taking a cause</strong>.
 * PostgreSQL puts the entire failing row in a constraint violation's {@code DETAIL}, and this schema
 * holds party references now and credit evidence later; a cause attached here would carry the row
 * into a log. Callers pass {@code DatabaseFailure.describe(...)}, which keeps the SQLState and drops
 * the row; {@code StorageFailuresCarryNoRowTest} refuses a cause coming back.
 */
public final class CreditStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public CreditStorageException(String message) {
        super(message);
    }
}
