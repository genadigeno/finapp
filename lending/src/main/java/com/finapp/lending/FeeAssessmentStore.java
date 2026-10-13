package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for fee assessments (`P11-TSK-025`, {@code lending V021}'s {@code fee_assessment}): INSERT-only, every write
 * reached under the loan's row lock (L3) - which the table's trigger takes again for every writer, so the per-account cap is
 * judged under it.
 */
public interface FeeAssessmentStore {

    /** Every assessment of {@code loan}, oldest first. */
    List<FeeAssessment> ofLoan(Connection unitOfWork, LoanId loan);

    /** The assessment {@code id} of {@code loan} - empty when it is another loan's or none. */
    Optional<FeeAssessment> find(Connection unitOfWork, LoanId loan, UUID id);

    /** Births one assessment; the database stamps the instant. */
    void insert(Connection unitOfWork, FeeAssessment assessment, CorrelationId correlation);
}
