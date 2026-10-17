package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL application as recorded (`P12-TSK-010`, {@code bnpl V003}; {@code BNPL_LIFECYCLES.md} section 4.2): one
 * customer's choice of one BNPL product and plan inside one checkout session, the credit decision request opened beside
 * it, and the terms version active at its submission (informational - the offer pins its own, `P12-TSK-011`).
 * <strong>No figure</strong>: the purchase amount is the session's gross and credit's request's ({@code INV-BNPL-01},
 * lending {@code V004}'s rule).
 *
 * @param checkoutSession the checkout session it was made in, by value (ADR-0029) - at most one open application each
 * @param merchant the merchant the session pays, by value - recorded so the offer and purchase need not re-derive it
 * @param decisionRequest credit's decision request, by value
 * @param termsVersionId the product's terms version {@code ACTIVE} at submission, read {@code FOR SHARE}
 * @param termsVersion that version's number
 * @param closureReason why it closed undecided - present exactly for {@link BnplApplicationStatus#CLOSED_UNDECIDED}
 * @param submittedAt the database's clock at the submission, after its locks
 * @param closedAt the database's clock at the terminal edge, once there
 */
public record BnplApplication(
        BnplApplicationId id,
        UUID party,
        BnplProduct product,
        BnplPlan plan,
        BnplApplicationStatus status,
        UUID checkoutSession,
        UUID merchant,
        UUID decisionRequest,
        BnplTermsVersionId termsVersionId,
        int termsVersion,
        Optional<BnplApplicationClosureReason> closureReason,
        Instant submittedAt,
        Optional<Instant> closedAt,
        CorrelationId correlation) {

    public BnplApplication {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(decisionRequest, "decisionRequest must not be null");
        Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
        Objects.requireNonNull(closureReason, "closureReason must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!product.plans().contains(plan)) {
            throw new IllegalArgumentException(plan + " is not a plan of " + product);
        }
        if ((status == BnplApplicationStatus.CLOSED_UNDECIDED) != closureReason.isPresent()) {
            throw new IllegalArgumentException("a closure reason is present exactly for CLOSED_UNDECIDED");
        }
    }

    /** Never the party, the session or the merchant ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplApplication[" + id.value() + ", " + product + ", " + plan + ", " + status + "]";
    }
}
