package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for the condition history (`P11-TSK-024`, {@code lending V020}'s {@code loan_condition_event}): the latest
 * recorded change of each kind, read under the loan's lock (L3) by the servicing step and the draw - or in a read's
 * snapshot - and the rows it appends, INSERT-only. The gauges' counts read the latest rows of accounts in servicing.
 */
public interface LoanConditionStore {

    /** The database clock - read by a condition read, which holds no lock and decides nothing. */
    Instant databaseNow(Connection unitOfWork);

    /** {@code loan}'s latest recorded change of {@code kind}; empty when none was ever recorded. */
    Optional<LoanConditionEvent> latest(Connection unitOfWork, LoanId loan, ConditionKind kind);

    /** Appends one change; the database stamps the instant and holds it to {@code lending V020}'s rules. */
    void insert(Connection unitOfWork, LoanConditionEvent event, CorrelationId correlation);

    /**
     * Accounts in servicing ({@code ACTIVE} or {@code CLOSING}) by product and the bucket their latest {@code DELINQUENCY}
     * change recorded - past-due buckets only; an account never recorded, or last recorded {@code CURRENT}, is counted in
     * none. Counts, never an account.
     */
    Map<LoanProduct, Map<DelinquencyBucket, Long>> pastDueByProductAndBucket(Connection unitOfWork);

    /** Accounts in servicing whose latest {@code DEFAULT} change set the flag, by product. Counts, never an account. */
    Map<LoanProduct, Long> defaultedByProduct(Connection unitOfWork);
}
