package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A loan application as recorded (`P11-TSK-010`, {@code lending V004}; {@code LENDING_LIFECYCLES.md} section 4.3): one
 * customer's request for one product, the credit decision request opened beside it, the terms version active at its
 * submission (informational - the offer pins its own) and the customer's choices. <strong>No figure</strong>: the amount
 * or limit applied for, the term and the declared income and expenditure are credit's request's alone.
 *
 * @param decisionRequest credit's decision request, by value (ADR-0029)
 * @param termsVersionId the product's terms version {@code ACTIVE} at submission, read {@code FOR SHARE}
 * @param termsVersion that version's number
 * @param repaymentDay the repayment (loan) or statement (line) day, one the recorded version offers
 * @param submittedAt the database's transaction time at the submission
 * @param closedAt the database's time at the terminal edge, once there
 */
public record LoanApplication(
        LoanApplicationId id,
        UUID party,
        LoanProduct product,
        LoanApplicationStatus status,
        UUID decisionRequest,
        LoanTermsVersionId termsVersionId,
        int termsVersion,
        int repaymentDay,
        RepaymentMethod repaymentMethod,
        PayoutPreference payoutPreference,
        Instant submittedAt,
        Optional<Instant> closedAt,
        CorrelationId correlation) {

    public LoanApplication {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(decisionRequest, "decisionRequest must not be null");
        Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
        Objects.requireNonNull(repaymentMethod, "repaymentMethod must not be null");
        Objects.requireNonNull(payoutPreference, "payoutPreference must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
    }

    /** Never the party ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanApplication[" + id.value() + ", " + product + ", " + status + "]";
    }
}
