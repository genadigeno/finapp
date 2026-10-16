package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The closed vocabularies lending declares before any behaviour (P11-TSK-002), each pinned to exactly the members
 * {@code LENDING_LIFECYCLES.md} and {@code PHASE_11_PLAN.md} name - so a member added, dropped or renamed is a
 * reviewed change to this test as well as to the enum, never a silent widening of what a later task's {@code CHECK}
 * or engine must accept.
 */
@DisplayName("lending's closed vocabularies (P11-TSK-002)")
class LendingVocabulariesTest {

    @Test
    @DisplayName("only implemented frequencies are nameable: MONTHLY alone (the day count, the engines and their versions"
            + " moved to creditmath with the engines, P12-TSK-002 - CreditmathVocabulariesTest)")
    void onlyMonthlyIsNameable() {
        assertThat(RepaymentFrequency.values()).containsExactly(RepaymentFrequency.MONTHLY);
    }

    @Test
    @DisplayName("the loan application machine has exactly LENDING_LIFECYCLES.md section 4.3's six states")
    void theApplicationStates() {
        assertThat(names(LoanApplicationStatus.values()))
                .containsExactly("SUBMITTED", "AWAITING_DECISION", "OFFERED", "DECLINED", "CLOSED_UNDECIDED", "WITHDRAWN");
    }

    @Test
    @DisplayName("the loan offer machine has exactly section 4.4's four states")
    void theOfferStates() {
        assertThat(names(LoanOfferStatus.values())).containsExactly("OFFERED", "ACCEPTED", "DECLINED", "EXPIRED");
    }

    @Test
    @DisplayName("the loan machine has exactly section 4.5's five states - no condition is a state")
    void theLoanStates() {
        assertThat(names(LoanStatus.values()))
                .containsExactly("PENDING_DISBURSEMENT", "ACTIVE", "CLOSING", "CLOSED", "CANCELLED")
                .as("delinquency, default, draws suspended and awaiting payout are conditions, never states")
                .doesNotContain("DELINQUENT", "DEFAULTED", "SUSPENDED", "AWAITING_PAYOUT", "RESTRUCTURED", "WRITTEN_OFF");
    }

    @Test
    @DisplayName("the payout machine (P11-TSK-015) has exactly section 4.7's six states as built - no RETURNED, which no"
            + " payments record would produce - its edges, three terminals and three NOT_DISPATCHED reasons, generated into"
            + " lending V009's CHECKs")
    void thePayoutMachine() throws java.io.IOException {
        assertThat(names(PayoutStatus.values()))
                .containsExactly("PENDING", "DISPATCHING", "DISPATCHED", "PAID_OUT", "FAILED", "NOT_DISPATCHED")
                .doesNotContain("RETURNED");
        assertThat(PayoutStatus.PENDING.permittedTransitions()).containsExactly(PayoutStatus.DISPATCHING);
        assertThat(PayoutStatus.DISPATCHING.permittedTransitions())
                .containsExactlyInAnyOrder(PayoutStatus.DISPATCHED, PayoutStatus.NOT_DISPATCHED);
        assertThat(PayoutStatus.DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(PayoutStatus.PAID_OUT, PayoutStatus.FAILED);
        assertThat(java.util.Arrays.stream(PayoutStatus.values()).filter(PayoutStatus::isTerminal).map(Enum::name))
                .containsExactly("PAID_OUT", "FAILED", "NOT_DISPATCHED");
        assertThat(names(NotDispatchedReason.values()))
                .containsExactly("NO_ELIGIBLE_RAIL", "DESTINATION_UNAVAILABLE", "CUSTOMER_UNAVAILABLE");
        String v009;
        try (var migration = getClass().getResourceAsStream("/db/migration/lending/V009__the_external_payout.sql")) {
            assertThat(migration).as("lending V009 on the classpath").isNotNull();
            v009 = new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(v009).contains("status IN (" + PayoutStatus.sqlValueList() + ")")
                .contains("not_dispatched_reason IN (" + NotDispatchedReason.sqlValueList() + ")");
    }

    @Test
    @DisplayName("the loan's terminal reasons: three for CLOSED, two for CANCELLED")
    void theTerminalReasons() {
        assertThat(names(ClosureReason.values())).containsExactly("REPAID", "PAID_OFF_EARLY", "CLOSED_BY_CUSTOMER");
        assertThat(names(CancellationReason.values())).containsExactly("STANDING_LOST", "DISBURSEMENT_FAILED");
    }

    @Test
    @DisplayName("two account kinds; four delinquency buckets in ascending order (the allocation components and overpayment"
            + " treatments moved to creditmath with the engines, P12-TSK-002 - CreditmathVocabulariesTest)")
    void theRemainingVocabularies() {
        assertThat(names(LoanKind.values())).containsExactly("INSTALMENT", "REVOLVING");
        assertThat(names(DelinquencyBucket.values())).containsExactly("B1_1_29", "B2_30_59", "B3_60_89", "B4_90_PLUS");
        // P11-TSK-024: the condition kinds; the servicing hold (A9) is designed, not built - no member for it.
        assertThat(names(ConditionKind.values())).containsExactly("DELINQUENCY", "DEFAULT");
    }

    @Test
    @DisplayName("the condition kinds are lending V020's CHECK, member for member (P11-TSK-024)")
    void theConditionKindsAreV020s() throws java.io.IOException {
        String v020;
        try (var migration = getClass().getResourceAsStream(
                "/db/migration/lending/V020__delinquency_and_default_conditions.sql")) {
            assertThat(migration).as("lending V020 on the classpath").isNotNull();
            v020 = new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(v020).contains("kind IN (" + ConditionKind.sqlValueList() + ")");
    }

    @Test
    @DisplayName("each aggregate has its own typed identifier - UUIDv7 only, and never equal across kinds")
    void theTypedIdentifiers() {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        UUID value = ids.next();
        assertThat(LoanId.of(value).value()).isEqualTo(value);
        assertThat(LoanId.of(value)).isEqualTo(LoanId.of(value));
        assertThat((Object) LoanId.of(value))
                .as("a loan and its agreement sharing a value are still two different things")
                .isNotEqualTo(LoanAgreementId.of(value));
        assertThat(LoanApplicationId.next(ids).value().version()).isEqualTo(7);
        assertThat(LoanOfferId.next(ids)).isNotEqualTo(LoanOfferId.next(ids));
        assertThat(LoanTermsVersionId.of(value).value()).isEqualTo(value);
        assertThat(CapitalContributionId.of(value).value()).isEqualTo(value);
        assertThatThrownBy(() -> LoanId.of(UUID.randomUUID()))
                .as("a v4 value carries no creation instant (ADR-0013)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the terms version's vocabularies (P11-TSK-005): its machine, and only implemented conventions - FIXED,"
            + " DEDUCTED (OLDEST_DUE_FIRST moved to creditmath with the engines, P12-TSK-002) - and one registered"
            + " agreement template per product")
    void theTermsVocabularies() {
        assertThat(names(LoanTermsVersionStatus.values())).containsExactly("PROPOSED", "ACTIVE", "RETIRED", "REJECTED");
        assertThat(LoanTermsVersionStatus.PROPOSED.permittedTransitions())
                .containsExactlyInAnyOrder(LoanTermsVersionStatus.ACTIVE, LoanTermsVersionStatus.REJECTED);
        assertThat(LoanTermsVersionStatus.ACTIVE.permittedTransitions()).containsExactly(LoanTermsVersionStatus.RETIRED);
        assertThat(LoanTermsVersionStatus.RETIRED.isTerminal()).isTrue();
        assertThat(LoanTermsVersionStatus.REJECTED.isTerminal()).isTrue();
        assertThat(names(RateType.values())).containsExactly("FIXED");
        assertThat(names(OriginationFeeTreatment.values())).containsExactly("DEDUCTED");
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(Arrays.stream(AgreementTemplate.values()).filter(template -> template.product() == product))
                    .as("%s has its one agreement template at version 1", product).hasSize(1);
        }
        assertThat(AgreementTemplate.of("PERSONAL_LOAN_AGREEMENT", 1)).contains(AgreementTemplate.PERSONAL_LOAN_AGREEMENT_V1);
        assertThat(AgreementTemplate.of("PERSONAL_LOAN_AGREEMENT", 2)).as("an unheld version is empty, never a fallback")
                .isEmpty();
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
