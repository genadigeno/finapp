package com.finapp.bnpl;

/**
 * A bnpl persistence failure (`P12-TSK-004`) - the operation and the SQLSTATE, never the driver's exception or a row: a
 * driver message can carry an amount, a rate or a reason (the Phase 9 rule, {@code INV-AUD-02}; lending's
 * {@code LendingStorageException}).
 */
public final class BnplStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public BnplStorageException(String message) {
        super(message);
    }
}
