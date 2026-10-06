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

    @Test
    @DisplayName("every cover status (P9-TSK-011): EXECUTED completed, REJECTED or VOIDED terminal, DISPATCHED or"
            + " UNKNOWN in flight - an FX provider line naming a cover still in flight is MISSING_INTERNAL's case")
    void everyCoverStatusIsClassified() {
        for (com.finapp.fx.CoverStatus status : com.finapp.fx.CoverStatus.values()) {
            InternalClassification expected = switch (status) {
                case EXECUTED -> InternalClassification.COMPLETED;
                case REJECTED, VOIDED -> InternalClassification.TERMINAL;
                case DISPATCHED, UNKNOWN -> InternalClassification.IN_FLIGHT;
            };
            assertThat(JdbcInternalReferenceLookup.classifyCover(status)).as("cover %s", status).isEqualTo(expected);
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
                                com.finapp.reconciliation.InternalSubject.PARKING,
                                com.finapp.payments.SchemeExecutionClaim.Subject.OUTBOUND_CREDIT,
                                com.finapp.reconciliation.InternalSubject.OUTBOUND_CREDIT);
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
    @Test
    @DisplayName("every operation key resolves within its source family (P9-TSK-014): an end-to-end"
            + " reference names an attempt or a withdrawal only for an instant source, a payout provider"
            + " reference a merchant payout only for the payout source - a counterparty's source (a"
            + " corridor's, an FX provider's) names neither; an unscoped subject keeps the unscoped reading")
    void operationKeysResolveWithinTheirFamily() {
        for (com.finapp.ledger.AccountPurpose position : com.finapp.ledger.AccountPurpose.values()) {
            JdbcInternalReferenceLookup.Family family =
                    JdbcInternalReferenceLookup.Family.of(Optional.of(position), true);
            assertThat(family.namesInstantOperations())
                    .as("%s names instant operations", position)
                    .isEqualTo(position == com.finapp.ledger.AccountPurpose.INSTANT_CLEARING);
            assertThat(family.namesMerchantPayouts())
                    .as("%s names merchant payouts", position)
                    .isEqualTo(position == com.finapp.ledger.AccountPurpose.PAYOUT_CLEARING);
            if (position.ownerKind() == com.finapp.ledger.OwnerKind.COUNTERPARTY) {
                assertThat(family).as("a counterparty's source is its own family").isEqualTo(
                        JdbcInternalReferenceLookup.Family.OTHER);
            }
        }
        JdbcInternalReferenceLookup.Family positionless = JdbcInternalReferenceLookup.Family.of(Optional.empty(), true);
        assertThat(positionless.namesInstantOperations()).as("a source settling no position").isFalse();
        assertThat(positionless.namesMerchantPayouts()).isFalse();
        JdbcInternalReferenceLookup.Family unscoped = JdbcInternalReferenceLookup.Family.of(Optional.empty(), false);
        assertThat(unscoped.namesInstantOperations()).isTrue();
        assertThat(unscoped.namesMerchantPayouts()).isTrue();
    }
    @Test
    @DisplayName("identical references from the merchant payout source and the corridor source are typed in"
            + " their own families (P9-TSK-014): the corridor's PAYOUT_PROVIDER_REF and END_TO_END_REF never"
            + " reach the merchant payouts, the withdrawals or the attempts - the merchant's and the scheme's do")
    void identicalReferencesStayInTheirFamilies() {
        java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.UUID merchantSource = java.util.UUID.randomUUID();
        java.util.UUID corridorSource = java.util.UUID.randomUUID();
        java.util.UUID schemeSource = java.util.UUID.randomUUID();
        java.util.Map<java.util.UUID, com.finapp.ledger.AccountPurpose> positions = java.util.Map.of(
                merchantSource, com.finapp.ledger.AccountPurpose.PAYOUT_CLEARING,
                corridorSource, com.finapp.payments.SimulatedCorridorAdapter.RAIL.capabilities().clearingPurpose().orElseThrow(),
                schemeSource, com.finapp.ledger.AccountPurpose.INSTANT_CLEARING);
        JdbcInternalReferenceLookup lookup = new JdbcInternalReferenceLookup(
                recording(com.finapp.payments.PaymentAttemptStore.class, "attempts", asked),
                recording(com.finapp.payments.RefundStore.class, "refunds", asked),
                recording(com.finapp.payments.DisputeStore.class, "disputes", asked),
                recording(com.finapp.payments.WithdrawalStore.class, "withdrawals", asked),
                recording(com.finapp.merchant.MerchantPayoutStore.class, "payouts", asked),
                recording(com.finapp.payments.SchemeExecutionClaimStore.class, "claims", asked),
                (uow, source) -> Optional.empty(),
                recording(com.finapp.fx.TradeStore.class, "covers", asked),
                (uow, source) -> Optional.ofNullable(positions.get(source)));
        java.util.Map<com.finapp.reconciliation.KeyKind, String> references = java.util.Map.of(
                com.finapp.reconciliation.KeyKind.PAYOUT_PROVIDER_REF, "xp_same",
                com.finapp.reconciliation.KeyKind.END_TO_END_REF, "XB-same-1");

        assertThat(lookup.classify(null, new InternalReferenceLookup.LookupSubject(
                        Optional.empty(), references, Optional.of(corridorSource))).classification())
                .isEqualTo(InternalClassification.UNKNOWN);
        assertThat(asked).as("the corridor's references reach no other family's store").isEmpty();

        lookup.classify(null, new InternalReferenceLookup.LookupSubject(
                Optional.empty(), references, Optional.of(merchantSource)));
        assertThat(asked).as("the merchant payout source's reference reaches the merchant payouts alone")
                .containsExactly("payouts.findByProviderReference");

        asked.clear();
        lookup.classify(null, new InternalReferenceLookup.LookupSubject(
                Optional.empty(), references, Optional.of(schemeSource)));
        assertThat(asked).as("the scheme's end-to-end reference reaches the attempts and the withdrawals")
                .containsExactly("attempts.findByEndToEndReference", "withdrawals.findByEndToEndReference");
    }

    /** A store that answers nothing and records what it was asked. */
    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<?> port, String name, java.util.List<String> asked) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? name : null;
                    }
                    asked.add(name + "." + method.getName());
                    return Optional.empty();
                });
    }
}
