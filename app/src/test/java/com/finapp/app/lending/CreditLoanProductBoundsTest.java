package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditProduct;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanProductBounds;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The amortisation bounds lending's terms door judges against are credit's, read through {@code app} (`P11-TSK-006`;
 * A16, ADR-0092's Follow-up): every instalment product's bounds equal its {@link CreditProduct}'s - the one truth - and
 * a revolving product has none. Lending's own test fixture ({@code LoanTermsFixtures.BOUNDS}) states the personal
 * loan's EUR 500.00 to 25,000.00 over 6 to 60 months; this holds credit to the same figures.
 */
@DisplayName("lending's product bounds are credit's (P11-TSK-006)")
class CreditLoanProductBoundsTest {

    private final LoanProductBounds bounds = new CreditLoanProductBounds();

    @Test
    @DisplayName("each instalment product's bounds are its credit counterpart's; a revolving product has none")
    void creditsBoundsExactly() {
        for (LoanProduct product : LoanProduct.values()) {
            CreditProduct credit = CreditProduct.valueOf(product.name());
            if (product.kind() == LoanKind.REVOLVING) {
                assertThat(bounds.amortisationBounds(product)).as(product.name()).isEmpty();
                continue;
            }
            LoanProductBounds.Bounds read = bounds.amortisationBounds(product).orElseThrow();
            assertThat(read.minimumPrincipal()).isEqualTo(credit.minimumAmount());
            assertThat(read.maximumPrincipal()).isEqualTo(credit.maximumAmount());
            assertThat(read.minimumTermMonths()).isEqualTo(credit.termBounds().orElseThrow().minimumMonths());
            assertThat(read.maximumTermMonths()).isEqualTo(credit.termBounds().orElseThrow().maximumMonths());
        }
    }

    @Test
    @DisplayName("the personal loan: EUR 500.00 to 25,000.00 over 6 to 60 months - the figures lending's fixtures state")
    void thePersonalLoan() {
        CurrencyCode euro = CurrencyCode.of("EUR");
        assertThat(bounds.amortisationBounds(LoanProduct.PERSONAL_LOAN)).contains(new LoanProductBounds.Bounds(
                Money.ofMinorUnits(500_00, euro), Money.ofMinorUnits(25_000_00, euro), 6, 60));
    }
}
