package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.credit.CreditProduct;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each {@link BnplProduct} agrees with the {@link CreditProduct} whose decision it requests and consumes (`P12-TSK-002`;
 * ADR-0101 decision 6, ADR-0102 decision 1, {@code PHASE_12_PLAN.md} section 12.1) - the {@code LendingVocabularyTest}
 * precedent.
 *
 * <p><strong>Checked here because nowhere else can.</strong> {@code bnpl} has no build edge to {@code credit}: the
 * correspondence is by name, and {@code app} - the composition root, the one module that sees both - is the only place
 * it can be held. Without this test the two enums could drift apart silently, and the first sign would be a BNPL
 * application requesting a decision for a plan credit cannot decide, or a purchase financed in a currency the decision
 * never approved.
 *
 * <p><strong>"Shape", precisely.</strong> {@link BnplProduct} declares no amount bounds - credit's are the only ones (A16)
 * - so what must agree is the shape of its plans against credit's term shape: pay-in-4's one fixed plan with credit's
 * {@code FIXED_PLAN} and its declared instalments (no term is sent); pay-in-N's plans, one month per instalment, with
 * credit's {@code MONTHS} and exactly its admitted terms {3, 6, 12}.
 */
@DisplayName("each BNPL product agrees with its credit product (P12-TSK-002)")
class BnplVocabularyTest {

    @Test
    @DisplayName("bnpl's products are exactly credit's BNPL family, by name - and no lending product among them")
    void bnplProductsAreCreditsBnplFamily() {
        assertThat(Arrays.stream(BnplProduct.values()).map(Enum::name))
                .as("bnpl prices and services exactly the BNPL products credit decides")
                .containsExactlyElementsOf(CreditProduct.of(CreditProduct.Family.BNPL).stream().map(Enum::name).toList());
        for (BnplProduct product : BnplProduct.values()) {
            assertThat(credit(product).family()).as(product.name()).isEqualTo(CreditProduct.Family.BNPL);
        }
    }

    @Test
    @DisplayName("each pair shares one currency")
    void oneCurrency() {
        for (BnplProduct product : BnplProduct.values()) {
            assertThat(product.currency())
                    .as("%s: the purchase is in the currency the decision approved", product)
                    .isEqualTo(credit(product).currency());
        }
    }

    @Test
    @DisplayName("each plan shape agrees with credit's term shape: pay-in-4's one fixed plan is credit's FIXED_PLAN of the"
            + " same instalments; pay-in-N's plans are credit's MONTHS with exactly its admitted terms")
    void thePlanShapesAgreeWithTheTermShapes() {
        for (BnplProduct product : BnplProduct.values()) {
            CreditProduct credit = credit(product);
            switch (product.shape()) {
                case FIXED -> {
                    assertThat(credit.termShape()).as(product.name()).isEqualTo(CreditProduct.TermShape.FIXED_PLAN);
                    assertThat(product.plans()).hasSize(1);
                    BnplPlan plan = product.plans().get(0);
                    assertThat(credit.planInstalments()).as("%s: credit decides the plan bnpl schedules", product)
                            .isEqualTo(OptionalInt.of(plan.instalments()));
                    assertThat(plan.termMonths()).as("a fixed plan sends no term").isEmpty();
                    assertThat(credit.termBounds()).isEmpty();
                }
                case MONTHS -> {
                    assertThat(credit.termShape()).as(product.name()).isEqualTo(CreditProduct.TermShape.MONTHS);
                    List<Integer> terms = product.plans().stream().map(plan -> plan.termMonths().orElseThrow()).toList();
                    assertThat(credit.termBounds()).as(product.name()).hasValueSatisfying(bounds -> {
                        assertThat(bounds.admittedMonths()).as("%s: the terms a request may name are the plans", product)
                                .containsExactlyElementsOf(terms);
                        terms.forEach(term -> assertThat(bounds.admits(term)).isTrue());
                    });
                    assertThat(credit.planInstalments()).isEmpty();
                }
            }
            assertThat(credit.revolving()).as("%s: a BNPL product never revolves", product).isFalse();
        }
    }

    private static CreditProduct credit(BnplProduct product) {
        return CreditProduct.valueOf(product.name());
    }
}
