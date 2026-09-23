package com.finapp.payments;

/**
 * A payments store met a database it could not work with ({@code TransfersStorageException}'s
 * shape): infrastructure trouble or an invariant already broken — never a domain outcome.
 * Messages carry identifiers and SQLStates, never amounts ({@code INV-AUD-02}).
 */
public final class PaymentsStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public PaymentsStorageException(String message) {
        super(message);
    }
}
