package com.finapp.reconciliation;

/**
 * A reconciliation persistence failure (`P8-TSK-004`) — thrown loudly, never absorbed: an
 * opener that cannot write rolls its completion back with it, and the redelivery or sweep
 * completes both (ADR-0067 §6, the accepted coupling).
 */
public class ReconciliationStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public ReconciliationStorageException(String message) {
        super(message);
    }

    public ReconciliationStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
