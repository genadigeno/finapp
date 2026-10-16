package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.credit.CreditProduct;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanProduct;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each {@link LoanProduct} agrees with the {@link CreditProduct} whose decision it consumes (P11-TSK-002; A16, A20,
 * {@code PHASE_11_PLAN.md} section 12.1) - and, since credit learned the BNPL products (`P12-TSK-001`), lending's products
 * are exactly credit's lending family: a BNPL product is {@code bnpl}'s, never lending's.
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
    @DisplayName("lending's products are exactly credit's lending products, by name - an injection into credit's"
            + " products whose image is the lending family (P12-TSK-001; it was a bijection until the BNPL products)")
    void lendingProductsAreCreditsNonBnplProducts() {
        assertThat(Arrays.stream(LoanProduct.values()).map(Enum::name))
                .as("lending prices and services exactly the lending products credit decides")
                .containsExactlyElementsOf(CreditProduct.of(CreditProduct.Family.LENDING).stream().map(Enum::name).toList());
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(LendingCreditProducts.of(product).family()).as(product.name())
                    .isEqualTo(CreditProduct.Family.LENDING);
        }
    }

    @Test
    @DisplayName("the BNPL products are never lending products: no LoanProduct names one, lending's mapping can never"
            + " yield one, and credit's BNPL products have no LoanProduct to map back to")
    void theBnplProductsAreNeverLendingProducts() {
        for (CreditProduct bnpl : CreditProduct.of(CreditProduct.Family.BNPL)) {
            assertThat(Arrays.stream(LoanProduct.values()).map(Enum::name)).as(bnpl.name()).doesNotContain(bnpl.name());
            assertThatIllegalArgumentException().as("LoanProduct.valueOf(%s)", bnpl)
                    .isThrownBy(() -> LoanProduct.valueOf(bnpl.name()));
        }
        assertThat(Arrays.stream(LoanProduct.values()).map(LendingCreditProducts::of))
                .doesNotContainAnyElementsOf(CreditProduct.of(CreditProduct.Family.BNPL));
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
        return LendingCreditProducts.of(product);
    }
}
