package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProductBounds;
import com.finapp.credit.CreditProduct;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounds bnpl's terms door judges against are credit's, read through {@code app} (`P12-TSK-004`; A16, lending's
 * {@code CreditLoanProductBoundsTest} precedent): every BNPL product's purchase bounds and admitted terms equal its
 * {@link CreditProduct}'s - the one truth - and bnpl's own test fixture ({@code BnplTermsFixtures.BOUNDS}) states the
 * same figures this holds credit to.
 */
@DisplayName("bnpl's product bounds are credit's (P12-TSK-004)")
class CreditBnplProductBoundsTest {

    private final BnplProductBounds bounds = new CreditBnplProductBounds();

    @Test
    @DisplayName("each BNPL product's bounds are its credit counterpart's: amounts, and the admitted terms of a months-shaped"
            + " product; a fixed plan admits no term")
    void creditsBoundsExactly() {
        for (BnplProduct product : BnplProduct.values()) {
            CreditProduct credit = CreditProduct.valueOf(product.name());
            BnplProductBounds.Bounds read = bounds.of(product);
            assertThat(read.minimumAmount()).as(product.name()).isEqualTo(credit.minimumAmount());
            assertThat(read.maximumAmount()).as(product.name()).isEqualTo(credit.maximumAmount());
            assertThat(read.admittedTermMonths()).as(product.name()).isEqualTo(credit.termBounds()
                    .map(CreditProduct.TermBounds::admittedMonths).orElse(List.of()));
        }
    }

    @Test
    @DisplayName("pay-in-4 EUR 50.00 to 1,000.00 with no term; pay-in-N EUR 200.00 to 5,000.00 over 3, 6 or 12 months -"
            + " the figures bnpl's fixtures state")
    void theFiguresTheFixturesState() {
        CurrencyCode euro = CurrencyCode.of("EUR");
        assertThat(bounds.of(BnplProduct.BNPL_PAY_IN_4)).isEqualTo(new BnplProductBounds.Bounds(
                Money.ofMinorUnits(50_00, euro), Money.ofMinorUnits(1_000_00, euro), List.of()));
        assertThat(bounds.of(BnplProduct.BNPL_PAY_IN_N)).isEqualTo(new BnplProductBounds.Bounds(
                Money.ofMinorUnits(200_00, euro), Money.ofMinorUnits(5_000_00, euro), List.of(3, 6, 12)));
    }
}
