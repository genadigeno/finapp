package com.finapp.bnpl;

/**
 * A refused conservation (`P12-TSK-018`; lending's {@code AllocationAnomaly}; {@code INV-BNPL-01}, {@code INV-LND-04} by
 * analogy): an obligation's rows do not explain its due accounts' ledger balances ({@link BnplReceivables.Anomaly}), or
 * the allocation engine refused its own inputs or broke its own assertion. Never an input refusal - a defect, so nothing
 * is allocated over it and the whole transaction rolls back. The repayment door counts it
 * ({@code finapp.bnpl.allocation.anomaly}, alerting above zero); in the servicing step it fails the step and its failed
 * meter carries it.
 */
public class AllocationAnomaly extends IllegalStateException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public AllocationAnomaly(String message) {
        super(message);
    }

    public AllocationAnomaly(String message, Throwable cause) {
        super(message, cause);
    }
}
