package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.payments.DisputeStage;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.InternalReferenceLookup;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lookup's classification over every payments and merchant state it names
 * (`P8-TSK-010`, ADR-0069 §§1–2; the gate's find made the mappings pure): exhaustive over
 * each status enum, so a NEW member fails here loudly instead of sliding into a default —
 * and the aggregation rule, the strongest knowledge winning.
 */
@DisplayName("the internal reference lookup's classification (P8-TSK-010)")
class JdbcInternalReferenceLookupTest {

    @Test
    @DisplayName("every attempt status: CAPTURED and EXECUTED completed; VOIDED and FAILED"
            + " terminal; every dispatched, unknown or waiting state in flight - a capture"
            + " UNKNOWN is known-and-not-completed, never unknown")
    void everyAttemptStatusIsClassified() {
        Set<PaymentAttemptStatus> completed =
                Set.of(PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.EXECUTED);
        Set<PaymentAttemptStatus> terminal =
                Set.of(PaymentAttemptStatus.VOIDED, PaymentAttemptStatus.FAILED);
        for (PaymentAttemptStatus status : PaymentAttemptStatus.values()) {
            InternalClassification expected =
                    completed.contains(status)
                            ? InternalClassification.COMPLETED
                            : terminal.contains(status)
                                    ? InternalClassification.TERMINAL
                                    : InternalClassification.IN_FLIGHT;
            assertThat(JdbcInternalReferenceLookup.classifyAttempt(status))
                    .as("%s", status)
                    .isEqualTo(expected);
        }
        assertThat(JdbcInternalReferenceLookup.classifyAttempt(
                        PaymentAttemptStatus.CAPTURE_UNKNOWN))
                .isEqualTo(InternalClassification.IN_FLIGHT);
    }

    @Test
    @DisplayName("every refund, withdrawal and payout status: COMPLETED completed, FAILED"
            + " terminal - a failed refund is the terminal fact REFUND_MISMATCH types"
            + " against - the rest in flight")
    void everyOperationStatusIsClassified() {
        for (RefundStatus status : RefundStatus.values()) {
            assertThat(JdbcInternalReferenceLookup.classifyRefund(status))
                    .as("refund %s", status)
                    .isEqualTo(operationExpectation(status.name()));
        }
        for (WithdrawalStatus status : WithdrawalStatus.values()) {
            assertThat(JdbcInternalReferenceLookup.classifyWithdrawal(status))
                    .as("withdrawal %s", status)
                    .isEqualTo(operationExpectation(status.name()));
        }
        for (MerchantPayoutStatus status : MerchantPayoutStatus.values()) {
            assertThat(JdbcInternalReferenceLookup.classifyPayout(status))
                    .as("payout %s", status)
                    .isEqualTo(operationExpectation(status.name()));
        }
    }

    private static InternalClassification operationExpectation(String name) {
        return switch (name) {
            case "COMPLETED" -> InternalClassification.COMPLETED;
            case "FAILED" -> InternalClassification.TERMINAL;
            default -> InternalClassification.IN_FLIGHT;
        };
    }

    @Test
    @DisplayName("every dispute stage: LOST and ACCEPTED terminal; a charged-back stage is"
            + " the applied fact; an inquiry is a stage not yet applied - in flight")
    void everyDisputeStageIsClassified() {
        for (DisputeStage stage : DisputeStage.values()) {
            InternalClassification expected;
            if (stage == DisputeStage.LOST || stage == DisputeStage.ACCEPTED) {
                expected = InternalClassification.TERMINAL;
            } else if (stage.isChargedBack()) {
                expected = InternalClassification.COMPLETED;
            } else {
                expected = InternalClassification.IN_FLIGHT;
            }
            assertThat(JdbcInternalReferenceLookup.classifyDispute(stage))
                    .as("%s", stage)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the aggregation rule: the strongest knowledge wins, ties keep the first,"
            + " and the empty answer is UNKNOWN")
    void theStrongestKnowledgeWins() {
        InternalReferenceLookup.InternalReference unknown =
                InternalReferenceLookup.InternalReference.unknown();
        InternalReferenceLookup.InternalReference inFlight =
                new InternalReferenceLookup.InternalReference(
                        InternalClassification.IN_FLIGHT,
                        Optional.of("op-1"),
                        Optional.of("DISPATCHED"));
        InternalReferenceLookup.InternalReference completed =
                new InternalReferenceLookup.InternalReference(
                        InternalClassification.COMPLETED,
                        Optional.of("op-2"),
                        Optional.of("CAPTURED"));
        assertThat(unknown.strongest(inFlight)).isEqualTo(inFlight);
        assertThat(inFlight.strongest(unknown)).isEqualTo(inFlight);
        assertThat(inFlight.strongest(completed)).isEqualTo(completed);
        assertThat(completed.strongest(inFlight))
                .as("the stronger answer's own identifiers ride with it")
                .isEqualTo(completed);
        InternalReferenceLookup.InternalReference alsoCompleted =
                new InternalReferenceLookup.InternalReference(
                        InternalClassification.COMPLETED,
                        Optional.of("op-3"),
                        Optional.of("EXECUTED"));
        assertThat(completed.strongest(alsoCompleted))
                .as("a tie keeps the first")
                .isEqualTo(completed);
        assertThat(unknown.classification()).isEqualTo(InternalClassification.UNKNOWN);
    }

    @Test
    @DisplayName("every claim subject names its operation in reconciliation's words (P8-TSK-017):"
            + " a claimed RETURN is a refund - the subject the terminal typing reads")
    void everyClaimSubjectIsNamed() {
        java.util.Map<com.finapp.payments.SchemeExecutionClaim.Subject,
                        com.finapp.reconciliation.InternalSubject>
                expected =
                        java.util.Map.of(
                                com.finapp.payments.SchemeExecutionClaim.Subject.PAY_IN,
                                com.finapp.reconciliation.InternalSubject.PAYMENT_ATTEMPT,
                                com.finapp.payments.SchemeExecutionClaim.Subject.WITHDRAWAL,
                                com.finapp.reconciliation.InternalSubject.WITHDRAWAL,
                                com.finapp.payments.SchemeExecutionClaim.Subject.RETURN,
                                com.finapp.reconciliation.InternalSubject.REFUND,
                                com.finapp.payments.SchemeExecutionClaim.Subject.UNMATCHED,
                                com.finapp.reconciliation.InternalSubject.PARKING);
        assertThat(expected.keySet())
                .as("exhaustive: a new claim subject fails here")
                .containsExactlyInAnyOrder(
                        com.finapp.payments.SchemeExecutionClaim.Subject.values());
        expected.forEach(
                (subject, named) ->
                        assertThat(JdbcInternalReferenceLookup.subjectOf(subject))
                                .as("%s", subject)
                                .isEqualTo(named));
    }
}
