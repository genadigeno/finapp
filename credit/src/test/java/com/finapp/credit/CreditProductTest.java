package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The credit products as PHASE_10_PLAN.md section 12.1 declares them (P10-TSK-001, ADR-0084 section 6). */
@DisplayName("the credit products declare their bounds, currency, threshold, validities and retention (P10-TSK-001)")
class CreditProductTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("exactly two products - a new one is a reviewed code change, never a string")
    void exactlyTwoProducts() {
        assertThat(CreditProduct.values())
                .containsExactly(CreditProduct.PERSONAL_LOAN, CreditProduct.CREDIT_LINE);
    }

    @Test
    @DisplayName("the personal loan: EUR 500.00 to 25,000.00 over 6 to 60 months")
    void thePersonalLoan() {
        CreditProduct loan = CreditProduct.PERSONAL_LOAN;
        assertThat(loan.currency()).isEqualTo(EUR);
        assertThat(loan.minimumAmount()).isEqualTo(Money.ofMinorUnits(500_00, EUR));
        assertThat(loan.maximumAmount()).isEqualTo(Money.ofMinorUnits(25_000_00, EUR));
        assertThat(loan.termBounds()).contains(new CreditProduct.TermBounds(6, 60));
        assertThat(loan.revolving()).isFalse();
    }

    @Test
    @DisplayName("the credit line: EUR 250.00 to 5,000.00, revolving, with no term")
    void theCreditLine() {
        CreditProduct line = CreditProduct.CREDIT_LINE;
        assertThat(line.currency()).isEqualTo(EUR);
        assertThat(line.minimumAmount()).isEqualTo(Money.ofMinorUnits(250_00, EUR));
        assertThat(line.maximumAmount()).isEqualTo(Money.ofMinorUnits(5_000_00, EUR));
        assertThat(line.termBounds()).isEmpty();
        assertThat(line.revolving()).isTrue();
    }

    @Test
    @DisplayName("every product: a request validity of 7 days, a decision validity of 30, evidence kept 25 months")
    void theValiditiesAndTheRetention() {
        for (CreditProduct product : CreditProduct.values()) {
            assertThat(product.requestValidity()).as(product.name()).isEqualTo(Duration.ofDays(7));
            assertThat(product.decisionValidity()).as(product.name()).isEqualTo(Duration.ofDays(30));
            assertThat(product.evidenceRetention()).as(product.name()).isEqualTo(Period.ofMonths(25));
        }
    }

    @Test
    @DisplayName("every product declares every property, in its one currency, with its four-eyes threshold inside"
            + " its bounds")
    void everyProductDeclaresEveryProperty() {
        for (CreditProduct product : CreditProduct.values()) {
            assertThat(product.currency()).as(product.name()).isNotNull();
            assertThat(product.minimumAmount().currency()).as(product.name()).isEqualTo(product.currency());
            assertThat(product.maximumAmount().currency()).as(product.name()).isEqualTo(product.currency());
            assertThat(product.fourEyesThreshold().currency()).as(product.name()).isEqualTo(product.currency());
            assertThat(product.minimumAmount().isPositive()).as(product.name()).isTrue();
            assertThat(product.minimumAmount()).as(product.name()).isLessThanOrEqualTo(product.maximumAmount());
            assertThat(product.fourEyesThreshold()).as(product.name())
                    .isBetween(product.minimumAmount(), product.maximumAmount());
            assertThat(product.requestValidity()).as(product.name()).isPositive();
            assertThat(product.decisionValidity()).as(product.name()).isPositive();
            assertThat(product.evidenceRetention().isNegative() || product.evidenceRetention().isZero())
                    .as(product.name()).isFalse();
            assertThat(product.revolving()).as(product.name()).isEqualTo(product.termBounds().isEmpty());
        }
        assertThat(CreditProduct.PERSONAL_LOAN.fourEyesThreshold()).isEqualTo(Money.ofMinorUnits(10_000_00, EUR));
        assertThat(CreditProduct.CREDIT_LINE.fourEyesThreshold()).isEqualTo(Money.ofMinorUnits(2_500_00, EUR));
    }

    @Test
    @DisplayName("term bounds are at least a month and ordered")
    void termBoundsAreOrdered() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditProduct.TermBounds(0, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditProduct.TermBounds(12, 6));
        assertThat(new CreditProduct.TermBounds(6, 6).maximumMonths()).isEqualTo(6);
    }
}
