package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PayoutReturnFallbacks} over JDBC (the Phase 8 -> 9 transition, ADR-0033: explicit SQL)
 * - the very statement the resolution machine's approval reads, so the worker and the approval
 * judge one fact.
 */
public final class JdbcPayoutReturnFallbacks implements PayoutReturnFallbacks {

    @Override
    public boolean transferStands(Connection unitOfWork, String payoutOperationRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(payoutOperationRef, "payoutOperationRef must not be null");
        return JdbcResolutionStore.transferStands(
                unitOfWork, payoutOperationRef, Optional.empty());
    }
}
