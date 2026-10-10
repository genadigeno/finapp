package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What each loan product declares (P11-TSK-002; L3, {@code PHASE_11_PLAN.md} section 12.1). The agreement with
 * credit's products is {@code app}'s {@code LendingVocabularyTest} - the only place both enums are visible.
 */
@DisplayName("the loan products (P11-TSK-002)")
class LoanProductTest {

    @Test
    @DisplayName("two products, PERSONAL_LOAN and CREDIT_LINE (L3) - BNPL is Phase 12's module, never a member")
    void twoProducts() {
        assertThat(LoanProduct.values()).containsExactly(LoanProduct.PERSONAL_LOAN, LoanProduct.CREDIT_LINE);
    }

    @Test
    @DisplayName("the personal loan is an instalment product; the credit line revolves")
    void eachProductsKind() {
        assertThat(LoanProduct.PERSONAL_LOAN.kind()).isEqualTo(LoanKind.INSTALMENT);
        assertThat(LoanProduct.CREDIT_LINE.kind()).isEqualTo(LoanKind.REVOLVING);
    }

    @Test
    @DisplayName("both are EUR (L4's neutral reference jurisdiction) and MONTHLY")
    void currencyAndFrequency() {
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(product.currency()).as("%s", product).isEqualTo(CurrencyCode.of("EUR"));
            assertThat(product.frequency()).as("%s", product).isEqualTo(RepaymentFrequency.MONTHLY);
        }
    }

    @Test
    @DisplayName("an offer is valid 14 days by default and a disbursement waits at most 24 hours (section 5.3)")
    void offerValidityAndDisbursementDeadline() {
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(product.offerValidity()).as("%s", product).isEqualTo(Duration.ofDays(14));
            assertThat(product.disbursementDeadline()).as("%s", product).isEqualTo(Duration.ofHours(24));
        }
    }
}
