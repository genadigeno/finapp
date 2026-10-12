package com.finapp.lending;

/**
 * A refused conservation (`P11-TSK-018`; {@code INV-LND-01}, {@code INV-LND-04}): the rows of a loan do not explain its
 * due accounts' ledger balances, or the allocation engine broke its own assertion. Never an input refusal - a defect, so
 * nothing is allocated over it and the whole transaction rolls back. The repayment door counts it
 * ({@code finapp.lending.allocation.anomaly}, alerting above zero); in the servicing step it fails the step and its failed
 * meter carries it (`P11-TSK-017`'s recorded owner judgement).
 */
public final class AllocationAnomaly extends IllegalStateException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public AllocationAnomaly(String message) {
        super(message);
    }

    public AllocationAnomaly(String message, Throwable cause) {
        super(message, cause);
    }
}
