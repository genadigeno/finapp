package com.finapp.fx;

import java.io.Serial;

/**
 * A storage failure in the fx schema - loud, never a domain outcome.
 *
 * <p><strong>It never carries the driver's exception</strong> (the Phase 9 to 10 transition): every fx table holds a
 * column classified above {@code INTERNAL} (DATA_CLASSIFICATION.md - amounts, rates, provider references, operator
 * reasons), and a {@code SQLException}'s message carries PostgreSQL's {@code DETAIL} line naming every column of the
 * refused row. So the module's one rule is {@code DatabaseFailure.describe}'s: the operation and the SQLState, never
 * the cause. There is no constructor taking one, and {@code FxExceptionsCarryNoDriverCauseTest} refuses any fx
 * exception built from a {@code DatabaseFailure} description with a second argument.
 */
public final class FxStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public FxStorageException(String message) {
        super(message);
    }
}
