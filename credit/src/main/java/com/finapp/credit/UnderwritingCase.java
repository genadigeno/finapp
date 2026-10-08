package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A referral's manual review, as recorded (`P10-TSK-018`, {@code credit V013}; ADR-0089; {@code INV-CRD-11},
 * {@code INV-CRD-06}).
 *
 * @param basisEvaluation the request's {@code REFER} evaluation - the system's judgement, kept as the case's basis
 * @param basisAssessment the assessment the basis evaluated
 * @param basisSnapshot the snapshot the basis was made from
 * @param referralReasons the basis evaluation's reason codes, in order - why it was referred
 * @param approvable the referral's ceiling: the request capped by every {@code CAP_AMOUNT} rule the basis triggered - no
 *     person approves more (G7)
 * @param fourEyesThreshold the product's threshold, copied at birth: a person's approval above it waits for a second
 * @param assignee the underwriter who holds it - a taken case's alone
 * @param first the first decision - an {@code AWAITING_SECOND} or {@code DECIDED} case's, and a {@code CLOSED} one's when
 *     its request was abandoned under a recorded first approval
 * @param secondDecidedBy the second underwriter - a four-eyes approval's alone
 * @param closureReason why it closed - the request's {@code EXPIRED}, {@code STANDING_LOST} or {@code CONSENT_WITHDRAWN}
 * @param requestExpiresAt the request's validity, on the database's clock - governing an {@code OPEN} case alone
 */
public record UnderwritingCase(
        UnderwritingCaseId id,
        DecisionRequestId decisionRequest,
        UUID party,
        CreditProduct product,
        PolicyEvaluationId basisEvaluation,
        CreditAssessmentId basisAssessment,
        DecisionSnapshotId basisSnapshot,
        List<ReasonCode> referralReasons,
        Money requested,
        Money approvable,
        Money fourEyesThreshold,
        UnderwritingCaseStatus status,
        Optional<String> assignee,
        Optional<FirstDecision> first,
        Optional<String> secondDecidedBy,
        Optional<String> closureReason,
        Instant openedAt,
        Instant requestExpiresAt) {

    public UnderwritingCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(decisionRequest, "decisionRequest");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(basisEvaluation, "basisEvaluation");
        Objects.requireNonNull(basisAssessment, "basisAssessment");
        Objects.requireNonNull(basisSnapshot, "basisSnapshot");
        Objects.requireNonNull(referralReasons, "referralReasons");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(approvable, "approvable");
        Objects.requireNonNull(fourEyesThreshold, "fourEyesThreshold");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(assignee, "assignee");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(secondDecidedBy, "secondDecidedBy");
        Objects.requireNonNull(closureReason, "closureReason");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(requestExpiresAt, "requestExpiresAt");
        referralReasons = List.copyOf(referralReasons);
    }

    /** Whether {@code actor} holds it. */
    public boolean heldBy(String actor) {
        return assignee.isPresent() && assignee.get().equals(actor);
    }

    /** No amount renders. */
    @Override
    public String toString() {
        return "UnderwritingCase[" + id + ", " + product + ", " + status + "]";
    }

    /**
     * A person's first decision: the outcome, an approval's amount, at least one reason code and a reason, by whom and
     * when.
     */
    public record FirstDecision(
            DecisionOutcome outcome,
            Optional<Money> approved,
            List<ReasonCode> reasons,
            String reason,
            String decidedBy,
            Instant decidedAt) {

        public FirstDecision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(approved, "approved");
            Objects.requireNonNull(reasons, "reasons");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decidedAt, "decidedAt");
            reasons = List.copyOf(reasons);
        }

        @Override
        public String toString() {
            return "FirstDecision[" + outcome + "]";
        }
    }
}
