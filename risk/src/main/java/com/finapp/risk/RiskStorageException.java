package com.finapp.risk;

/**
 * A risk store's failure, named by its operation and SQLSTATE only (`P13-TSK-002`; bnpl's
 * {@code BnplStorageException}): never the driver's message, which can carry a value from the row.
 */
public final class RiskStorageException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public RiskStorageException(String message) {
        super(message);
    }
}
