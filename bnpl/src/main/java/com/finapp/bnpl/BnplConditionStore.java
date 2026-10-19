package com.finapp.bnpl;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for the condition history and the fee assessments (`P12-TSK-020`, {@code bnpl V013}; lending's
 * {@code LoanConditionStore} and {@code FeeAssessmentStore}): the latest recorded change of each kind and the obligation's
 * assessments - read under the obligation's lock (B4) by the servicing step, or in a read's snapshot - and the rows it
 * appends, INSERT-only. The gauges' counts read the latest rows of obligations in servicing.
 */
public interface BnplConditionStore {

    /** The database clock, {@code clock_timestamp()} - read by a condition read, which holds no lock and decides nothing. */
    Instant databaseNow(Connection unitOfWork);

    /** {@code obligation}'s latest recorded change of {@code kind}; empty when none was ever recorded. */
    Optional<BnplConditionEvent> latest(Connection unitOfWork, BnplObligationId obligation, BnplConditionKind kind);

    /** Appends one change; the database stamps the instant and holds it to {@code bnpl V013}'s rules. */
    void insertCondition(Connection unitOfWork, BnplConditionEvent event);

    /** Every fee assessed on {@code obligation}, oldest first. */
    List<BnplFeeAssessment> assessments(Connection unitOfWork, BnplObligationId obligation);

    /** Births one assessment; the database stamps the instant and holds it to {@code bnpl V013}'s rules. */
    void insertAssessment(Connection unitOfWork, BnplFeeAssessment assessment);

    /**
     * {@code ACTIVE} obligations by product and the bucket their latest {@code DELINQUENCY} change recorded - past-due
     * buckets only. Counts, never an obligation.
     */
    Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDueByProductAndBucket(Connection unitOfWork);

    /** {@code ACTIVE} obligations whose latest {@code DEFAULT} change set the flag, by product. Counts only. */
    Map<BnplProduct, Long> defaultedByProduct(Connection unitOfWork);
}
