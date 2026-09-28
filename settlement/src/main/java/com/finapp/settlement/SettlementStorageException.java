package com.finapp.settlement;

/**
 * A settlement persistence failure (`P8-TSK-002`) — the {@code PaymentsStorageException}
 * shape: infrastructure and integrity failures surface loudly and fail the transaction; they
 * are never domain refusals, and their messages carry identifiers, never content.
 */
public class SettlementStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public SettlementStorageException(String message) {
        super(message);
    }

    public SettlementStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
