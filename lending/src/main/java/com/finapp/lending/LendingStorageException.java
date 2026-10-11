package com.finapp.lending;

/**
 * A lending persistence failure (`P11-TSK-004`) - the operation and the SQLSTATE, never the driver's exception or a
 * row: a driver message can carry an amount, a reference or a reason (the Phase 9 rule, {@code INV-AUD-02}).
 */
public final class LendingStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public LendingStorageException(String message) {
        super(message);
    }
}
