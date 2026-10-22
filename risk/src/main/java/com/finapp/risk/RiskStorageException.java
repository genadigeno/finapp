package com.finapp.risk;

/**
 * A risk store's statement failed (`P13-TSK-019`; bnpl's {@code BnplStorageException}): the operation and the SQLSTATE,
 * never a parameter - no subject, amount or key reaches a log through this message ({@code INV-RSK-08}).
 */
public final class RiskStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public RiskStorageException(String message) {
        super(message);
    }
}
