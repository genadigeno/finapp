package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.EngineVersion;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The closed vocabularies bnpl declares before any behaviour (`P12-TSK-002`), each pinned to exactly the members
 * {@code BNPL_LIFECYCLES.md}, ADR-0102 and {@code PHASE_12_PLAN.md} name - so a member added, dropped or renamed is a
 * reviewed change to this test as well as to the enum, never a silent widening of what a later task's {@code CHECK} or
 * engine must accept. The lending precedent ({@code LendingVocabulariesTest}, `P11-TSK-002`).
 */
@DisplayName("bnpl's closed vocabularies (P12-TSK-002)")
class BnplVocabulariesTest {

    @Test
    @DisplayName("two products, each EUR, each declaring its frequency, its plan shape and its plans: pay-in-4 biweekly on"
            + " one fixed plan of four, pay-in-N monthly on 3, 6 or 12 (B1, A2)")
    void theProducts() {
        assertThat(names(BnplProduct.values())).containsExactly("BNPL_PAY_IN_4", "BNPL_PAY_IN_N");
        for (BnplProduct product : BnplProduct.values()) {
            assertThat(product.currency()).as(product.name()).isEqualTo(CurrencyCode.of("EUR"));
        }
        assertThat(BnplProduct.BNPL_PAY_IN_4.frequency()).isEqualTo(InstalmentFrequency.BIWEEKLY);
        assertThat(BnplProduct.BNPL_PAY_IN_4.shape()).isEqualTo(BnplProduct.PlanShape.FIXED);
        assertThat(BnplProduct.BNPL_PAY_IN_4.plans()).containsExactly(BnplPlan.PAY_IN_4);
        assertThat(BnplProduct.BNPL_PAY_IN_N.frequency()).isEqualTo(InstalmentFrequency.MONTHLY);
        assertThat(BnplProduct.BNPL_PAY_IN_N.shape()).isEqualTo(BnplProduct.PlanShape.MONTHS);
        assertThat(BnplProduct.BNPL_PAY_IN_N.plans())
                .containsExactly(BnplPlan.PAY_IN_3_MONTHS, BnplPlan.PAY_IN_6_MONTHS, BnplPlan.PAY_IN_12_MONTHS);
    }

    @Test
    @DisplayName("four plans, each in exactly one product: pay-in-4's four instalments and no term, pay-in-N's term one"
            + " month per instalment")
    void thePlans() {
        assertThat(names(BnplPlan.values()))
                .containsExactly("PAY_IN_4", "PAY_IN_3_MONTHS", "PAY_IN_6_MONTHS", "PAY_IN_12_MONTHS");
        assertThat(BnplPlan.PAY_IN_4.instalments()).isEqualTo(4);
        assertThat(BnplPlan.PAY_IN_4.termMonths()).as("a fixed plan sends no term").isEqualTo(OptionalInt.empty());
        assertThat(BnplPlan.PAY_IN_4.product()).isEqualTo(BnplProduct.BNPL_PAY_IN_4);
        for (BnplPlan plan : List.of(BnplPlan.PAY_IN_3_MONTHS, BnplPlan.PAY_IN_6_MONTHS, BnplPlan.PAY_IN_12_MONTHS)) {
            assertThat(plan.product()).as(plan.name()).isEqualTo(BnplProduct.BNPL_PAY_IN_N);
            assertThat(plan.termMonths()).as(plan.name()).isEqualTo(OptionalInt.of(plan.instalments()));
        }
        assertThat(Arrays.stream(BnplPlan.values()).mapToInt(BnplPlan::instalments)).containsExactly(4, 3, 6, 12);
        long listed = Arrays.stream(BnplProduct.values()).mapToLong(product -> product.plans().size()).sum();
        assertThat(listed).as("each plan listed once").isEqualTo(BnplPlan.values().length);
    }

    @Test
    @DisplayName("only implemented frequencies are nameable: BIWEEKLY and MONTHLY - bnpl's, not lending's")
    void theFrequencies() {
        assertThat(names(InstalmentFrequency.values())).containsExactly("BIWEEKLY", "MONTHLY");
        assertThat(InstalmentFrequency.class.getPackageName()).isEqualTo("com.finapp.bnpl");
    }

    @Test
    @DisplayName("the application machine has exactly BNPL_LIFECYCLES.md section 4.2's six states, and its undecided"
            + " closure exactly seven reasons")
    void theApplication() {
        assertThat(names(BnplApplicationStatus.values()))
                .containsExactly("SUBMITTED", "AWAITING_DECISION", "OFFERED", "DECLINED", "CLOSED_UNDECIDED", "WITHDRAWN");
        assertThat(names(BnplApplicationClosureReason.values()))
                .containsExactly("CREDIT_REQUEST_CANCELLED", "CREDIT_REQUEST_EXPIRED", "CREDIT_REQUEST_ABANDONED",
                        "SESSION_CLOSED", "APPROVED_BELOW_PURCHASE", "PRODUCT_NOT_OFFERED", "DECISION_LAPSED")
                .as("a decline and a withdrawal are states, never reasons")
                .doesNotContain("DECLINED", "WITHDRAWN");
    }

    @Test
    @DisplayName("the offer machine has exactly section 4.3's four states")
    void theOffer() {
        assertThat(names(BnplOfferStatus.values())).containsExactly("OFFERED", "ACCEPTED", "DECLINED", "EXPIRED");
    }

    @Test
    @DisplayName("the purchase machine has exactly section 4.5's three states - partially refunded is derived, never a"
            + " state")
    void thePurchase() {
        assertThat(names(BnplPurchaseStatus.values()))
                .containsExactly("AUTHORISED", "FINANCED", "FULLY_REFUNDED")
                .doesNotContain("PARTIALLY_REFUNDED", "CANCELLED");
    }

    @Test
    @DisplayName("the obligation machine has exactly section 4.7's two states and four closure reasons - no condition is a"
            + " state, and nothing is disbursed or cancelled")
    void theObligation() {
        assertThat(names(BnplObligationStatus.values()))
                .containsExactly("ACTIVE", "CLOSED")
                .as("delinquency, default and a dispute's hold are conditions; nothing is disbursed; a financed"
                        + " obligation is never cancelled")
                .doesNotContain("DELINQUENT", "DEFAULTED", "DISPUTED", "PENDING_DISBURSEMENT", "CANCELLED", "WRITTEN_OFF");
        assertThat(names(BnplClosureReason.values()))
                .containsExactly("REPAID", "PAID_OFF_EARLY", "REFUNDED", "REFUNDED_PARTIALLY_REPAID");
    }

    @Test
    @DisplayName("the dispute machine has exactly section 4.10's five states")
    void theDispute() {
        assertThat(names(BnplDisputeStatus.values()))
                .containsExactly("OPEN", "MERCHANT_RESPONDED", "RESOLVED_REFUND", "RESOLVED_UPHELD", "WITHDRAWN");
    }

    @Test
    @DisplayName("bnpl's own two engines each stand at version 1, current; an unheld version is empty, never a fallback;"
            + " and no name collides with creditmath's EngineVersion - a stored engine name is one engine's")
    void theEngines() {
        assertThat(names(BnplEngine.Kind.values())).containsExactly("BNPL_SCHEDULE", "REFUND_ALLOCATION");
        assertThat(names(BnplEngine.values())).containsExactly("BNPL_SCHEDULE_ENGINE_V1", "REFUND_ALLOCATION_ENGINE_V1");
        for (BnplEngine.Kind kind : BnplEngine.Kind.values()) {
            BnplEngine current = BnplEngine.current(kind);
            assertThat(current.kind()).isEqualTo(kind);
            assertThat(current.number()).as("%s's current version", kind).isEqualTo(1);
            assertThat(current.name()).isEqualTo(kind.name() + "_ENGINE_V1");
            assertThat(BnplEngine.of(kind, 1)).contains(current);
            assertThat(BnplEngine.of(kind, 2)).as("version 2 of %s is not held", kind).isEmpty();
            assertThat(BnplEngine.of(kind, 0)).isEmpty();
        }
        assertThat(names(BnplEngine.values())).doesNotContainAnyElementsOf(List.of(names(EngineVersion.values())));
    }

    @Test
    @DisplayName("each aggregate has its own typed identifier - UUIDv7 only, and never equal across kinds")
    void theTypedIdentifiers() {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        UUID value = ids.next();
        assertThat(BnplObligationId.of(value).value()).isEqualTo(value);
        assertThat(BnplObligationId.of(value)).isEqualTo(BnplObligationId.of(value));
        assertThat((Object) BnplPurchaseId.of(value))
                .as("a purchase and its obligation sharing a value are still two different things")
                .isNotEqualTo(BnplObligationId.of(value));
        assertThat((Object) BnplAgreementId.of(value)).isNotEqualTo(BnplPurchaseId.of(value));
        assertThat(BnplApplicationId.next(ids).value().version()).isEqualTo(7);
        assertThat(BnplOfferId.next(ids)).isNotEqualTo(BnplOfferId.next(ids));
        assertThat(BnplTermsVersionId.of(value).value()).isEqualTo(value);
        assertThat(BnplRefundId.of(value).value()).isEqualTo(value);
        assertThat(BnplDisputeId.next(ids).value().version()).isEqualTo(7);
        assertThatThrownBy(() -> BnplPurchaseId.of(UUID.randomUUID()))
                .as("a v4 value carries no creation instant (ADR-0013)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
