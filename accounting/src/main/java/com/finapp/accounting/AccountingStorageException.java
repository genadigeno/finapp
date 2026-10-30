package com.finapp.accounting;

/**
 * An accounting store's failure, named by its operation and SQLSTATE only (`P14-TSK-003`; risk's
 * {@code RiskStorageException}): never the driver's message, which can carry a value from the row, and never the driver
 * exception as a cause ({@code StorageFailuresCarryNoRowTest}'s rule).
 */
public final class AccountingStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public AccountingStorageException(String message) {
        super(message);
    }
}
