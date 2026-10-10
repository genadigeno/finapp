package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditProduct;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanProduct;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each {@link LoanProduct} agrees with the {@link CreditProduct} whose decision it consumes (P11-TSK-002; A16, A20,
 * {@code PHASE_11_PLAN.md} section 12.1).
 *
 * <p><strong>Checked here because nowhere else can.</strong> {@code lending} has no build edge to {@code credit}
 * (ADR-0090 point 5): the correspondence is by name, and {@code app} - the composition root, the one module that sees
 * both - is the only place it can be held. Without this test the two enums could drift apart silently, and the first
 * sign would be a loan offered in a currency, or with a shape, that the decision it consumes never approved.
 *
 * <p><strong>"Bounds", precisely.</strong> {@link LoanProduct} declares no amount or term bounds - the offer is exactly
 * the approved decision (A20), and credit's bounds are the only ones - so what must agree is the shape those bounds
 * imply: an {@link LoanKind#INSTALMENT} product's credit counterpart has term bounds, a {@link LoanKind#REVOLVING} one
 * has none; and an offer's default validity never outlives the decision it would consume, or the default would be a
 * figure no offer could ever reach ({@code expires_at = LEAST(valid_until, offered_at + validity)}).
 */
@DisplayName("each loan product agrees with its credit product (P11-TSK-002)")
class LendingVocabularyTest {

    @Test
    @DisplayName("the two enums are one bijection by name - no loan product without a credit product, and the reverse")
    void oneBijectionByName() {
        assertThat(Arrays.stream(LoanProduct.values()).map(Enum::name))
                .as("lending prices and services exactly the products credit decides")
                .containsExactlyElementsOf(Arrays.stream(CreditProduct.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("each pair shares one currency")
    void oneCurrency() {
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(product.currency())
                    .as("%s: the agreement is in the currency the decision approved", product)
                    .isEqualTo(credit(product).currency());
        }
    }

    @Test
    @DisplayName("each kind agrees with credit's bounds: an instalment product has a term, a revolving one has none")
    void kindAgreesWithTheTermBounds() {
        for (LoanProduct product : LoanProduct.values()) {
            CreditProduct credit = credit(product);
            assertThat(product.kind() == LoanKind.REVOLVING)
                    .as("%s is %s, so credit's product must %s", product, product.kind(),
                            product.kind() == LoanKind.REVOLVING ? "revolve (no term)" : "carry term bounds")
                    .isEqualTo(credit.revolving());
            assertThat(credit.termBounds().isPresent()).isEqualTo(product.kind() == LoanKind.INSTALMENT);
        }
    }

    @Test
    @DisplayName("an offer's default validity never outlives the decision it would consume")
    void theOfferValidityIsWithinTheDecisionValidity() {
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(product.offerValidity())
                    .as("%s: offered_at + validity must be able to come before the decision's valid_until", product)
                    .isLessThanOrEqualTo(credit(product).decisionValidity());
        }
    }

    private static CreditProduct credit(LoanProduct product) {
        return CreditProduct.valueOf(product.name());
    }
}
