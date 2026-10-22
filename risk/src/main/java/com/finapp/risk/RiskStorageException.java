package com.finapp.risk;

/**
 * A risk persistence failure (`P13-TSK-005`) - the operation and the SQLSTATE, never the driver's exception or a row:
 * a driver message can carry a subject, a code or a reason (the Phase 9 rule, {@code INV-AUD-02}, {@code INV-RSK-08};
 * bnpl's {@code BnplStorageException}).
 */
public final class RiskStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public RiskStorageException(String message) {
        super(message);
    }
}
