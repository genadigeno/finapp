package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credit products as PHASE_10_PLAN.md section 12.1 declares them (P10-TSK-001, ADR-0084 section 6), and the two BNPL
 * products PHASE_12_PLAN.md section 12.1 adds with the term shape (`P12-TSK-001`, ADR-0102 point 6; A16, OQ-18).
 */
@DisplayName("the credit products declare their family, bounds, term shape, threshold, validities and retention"
        + " (P10-TSK-001, P12-TSK-001)")
class CreditProductTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("exactly four products, two per family - a new one is a reviewed code change, never a string")
    void exactlyFourProducts() {
        assertThat(CreditProduct.values()).containsExactly(CreditProduct.PERSONAL_LOAN, CreditProduct.CREDIT_LINE,
                CreditProduct.BNPL_PAY_IN_4, CreditProduct.BNPL_PAY_IN_N);
        assertThat(CreditProduct.of(CreditProduct.Family.LENDING))
                .containsExactly(CreditProduct.PERSONAL_LOAN, CreditProduct.CREDIT_LINE);
        assertThat(CreditProduct.of(CreditProduct.Family.BNPL))
                .containsExactly(CreditProduct.BNPL_PAY_IN_4, CreditProduct.BNPL_PAY_IN_N);
    }

    @Test
    @DisplayName("the personal loan: EUR 500.00 to 25,000.00 over 6 to 60 months")
    void thePersonalLoan() {
        CreditProduct loan = CreditProduct.PERSONAL_LOAN;
        assertThat(loan.currency()).isEqualTo(EUR);
        assertThat(loan.minimumAmount()).isEqualTo(Money.ofMinorUnits(500_00, EUR));
        assertThat(loan.maximumAmount()).isEqualTo(Money.ofMinorUnits(25_000_00, EUR));
        assertThat(loan.termShape()).isEqualTo(CreditProduct.TermShape.MONTHS);
        assertThat(loan.termBounds()).contains(new CreditProduct.TermBounds(6, 60));
        assertThat(loan.planInstalments()).isEmpty();
        assertThat(loan.revolving()).isFalse();
        assertThat(loan.referable()).isTrue();
    }

    @Test
    @DisplayName("the credit line: EUR 250.00 to 5,000.00, revolving, with no term")
    void theCreditLine() {
        CreditProduct line = CreditProduct.CREDIT_LINE;
        assertThat(line.currency()).isEqualTo(EUR);
        assertThat(line.minimumAmount()).isEqualTo(Money.ofMinorUnits(250_00, EUR));
        assertThat(line.maximumAmount()).isEqualTo(Money.ofMinorUnits(5_000_00, EUR));
        assertThat(line.termShape()).isEqualTo(CreditProduct.TermShape.NONE);
        assertThat(line.termBounds()).isEmpty();
        assertThat(line.revolving()).isTrue();
        assertThat(line.referable()).isTrue();
    }

    @Test
    @DisplayName("pay-in-4: EUR 50.00 to 1,000.00, a fixed plan of four instalments that takes no term and refuses any -"
            + " never read as a line")
    void payIn4() {
        CreditProduct p4 = CreditProduct.BNPL_PAY_IN_4;
        assertThat(p4.family()).isEqualTo(CreditProduct.Family.BNPL);
        assertThat(p4.currency()).isEqualTo(EUR);
        assertThat(p4.minimumAmount()).isEqualTo(Money.ofMinorUnits(50_00, EUR));
        assertThat(p4.maximumAmount()).isEqualTo(Money.ofMinorUnits(1_000_00, EUR));
        assertThat(p4.termShape()).isEqualTo(CreditProduct.TermShape.FIXED_PLAN);
        assertThat(p4.termBounds()).isEmpty();
        assertThat(p4.planInstalments()).isEqualTo(OptionalInt.of(4));
        assertThat(p4.revolving()).as("no term, and still never a line").isFalse();
        assertThat(p4.referable()).isFalse();
        assertThatCode(() -> submitted(p4, p4.minimumAmount(), Optional.empty())).doesNotThrowAnyException();
        assertThatCode(() -> submitted(p4, p4.maximumAmount(), Optional.empty())).doesNotThrowAnyException();
        for (int term : List.of(1, 3, 4, 6, 12, 36)) {
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("a term of %d", term)
                    .isThrownBy(() -> submitted(p4, Money.ofMinorUnits(200_00, EUR), Optional.of(term)))
                    .withMessageContaining("fixed plan");
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("as recorded too")
                    .isThrownBy(() -> new DecisionRequest.Application(p4, Money.ofMinorUnits(200_00, EUR),
                            Optional.of(term), Optional.empty(), Optional.empty()));
        }
    }

    @Test
    @DisplayName("pay-in-N: EUR 200.00 to 5,000.00 over exactly 3, 6 or 12 months - 4, and every other term, refused")
    void payInN() {
        CreditProduct pn = CreditProduct.BNPL_PAY_IN_N;
        assertThat(pn.family()).isEqualTo(CreditProduct.Family.BNPL);
        assertThat(pn.minimumAmount()).isEqualTo(Money.ofMinorUnits(200_00, EUR));
        assertThat(pn.maximumAmount()).isEqualTo(Money.ofMinorUnits(5_000_00, EUR));
        assertThat(pn.termShape()).isEqualTo(CreditProduct.TermShape.MONTHS);
        assertThat(pn.termBounds()).contains(CreditProduct.TermBounds.oneOf(3, 6, 12));
        assertThat(pn.termBounds().orElseThrow().admittedMonths()).containsExactly(3, 6, 12);
        assertThat(pn.planInstalments()).isEmpty();
        assertThat(pn.revolving()).isFalse();
        assertThat(pn.referable()).isFalse();
        Money amount = Money.ofMinorUnits(1_000_00, EUR);
        for (int term : List.of(3, 6, 12)) {
            assertThatCode(() -> submitted(pn, amount, Optional.of(term))).as("%d months", term)
                    .doesNotThrowAnyException();
        }
        for (int term = 0; term <= 61; term++) {
            if (term == 3 || term == 6 || term == 12) {
                continue;
            }
            int refused = term;
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("%d months", refused)
                    .isThrownBy(() -> submitted(pn, amount, Optional.of(refused)))
                    .withMessageContaining("3, 6 or 12 months");
        }
        assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("pay-in-N names its term")
                .isThrownBy(() -> submitted(pn, amount, Optional.empty()));
    }

    @Test
    @DisplayName("the lending products: a request validity of 7 days, a decision validity of 30; the BNPL products: an"
            + " hour and a day (OQ-18) - every product keeps evidence 25 months")
    void theValiditiesAndTheRetention() {
        for (CreditProduct product : CreditProduct.of(CreditProduct.Family.LENDING)) {
            assertThat(product.requestValidity()).as(product.name()).isEqualTo(Duration.ofDays(7));
            assertThat(product.decisionValidity()).as(product.name()).isEqualTo(Duration.ofDays(30));
        }
        for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
            assertThat(product.requestValidity()).as(product.name()).isEqualTo(Duration.ofHours(1));
            assertThat(product.decisionValidity()).as(product.name()).isEqualTo(Duration.ofDays(1));
        }
        for (CreditProduct product : CreditProduct.values()) {
            assertThat(product.evidenceRetention()).as(product.name()).isEqualTo(Period.ofMonths(25));
        }
    }

    @Test
    @DisplayName("every product declares every property, in its one currency, with its four-eyes threshold inside"
            + " its bounds and a term shape that agrees with its bounds and plan")
    void everyProductDeclaresEveryProperty() {
        for (CreditProduct product : CreditProduct.values()) {
            assertThat(product.family()).as(product.name()).isNotNull();
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
            assertThat(product.requestValidity()).as(product.name()).isLessThanOrEqualTo(product.decisionValidity());
            assertThat(product.evidenceRetention().isNegative() || product.evidenceRetention().isZero())
                    .as(product.name()).isFalse();
            assertThat(product.revolving()).as(product.name())
                    .isEqualTo(product.termShape() == CreditProduct.TermShape.NONE);
            assertThat(product.termBounds().isPresent()).as(product.name())
                    .isEqualTo(product.termShape() == CreditProduct.TermShape.MONTHS);
            assertThat(product.planInstalments().isPresent()).as(product.name())
                    .isEqualTo(product.termShape() == CreditProduct.TermShape.FIXED_PLAN);
            assertThat(product.referable()).as(product.name())
                    .isEqualTo(product.family() == CreditProduct.Family.LENDING);
        }
        assertThat(CreditProduct.PERSONAL_LOAN.fourEyesThreshold()).isEqualTo(Money.ofMinorUnits(10_000_00, EUR));
        assertThat(CreditProduct.CREDIT_LINE.fourEyesThreshold()).isEqualTo(Money.ofMinorUnits(2_500_00, EUR));
        assertThat(CreditProduct.BNPL_PAY_IN_4.fourEyesThreshold()).isEqualTo(Money.ofMinorUnits(500_00, EUR));
        assertThat(CreditProduct.BNPL_PAY_IN_N.fourEyesThreshold()).isEqualTo(Money.ofMinorUnits(2_500_00, EUR));
    }

    @Test
    @DisplayName("term bounds are at least a month and ordered; an admitted set ascends from the minimum to the maximum")
    void termBoundsAreOrdered() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditProduct.TermBounds(0, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditProduct.TermBounds(12, 6));
        assertThat(new CreditProduct.TermBounds(6, 6).maximumMonths()).isEqualTo(6);
        assertThatIllegalArgumentException().isThrownBy(() -> CreditProduct.TermBounds.oneOf());
        assertThatIllegalArgumentException().isThrownBy(() -> CreditProduct.TermBounds.oneOf(6, 3, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> CreditProduct.TermBounds.oneOf(3, 3, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreditProduct.TermBounds(3, 24, List.of(3, 6, 12)));
        CreditProduct.TermBounds range = CreditProduct.TermBounds.between(6, 60);
        assertThat(range.admits(6)).isTrue();
        assertThat(range.admits(37)).isTrue();
        assertThat(range.admits(5)).isFalse();
        assertThat(range.admits(61)).isFalse();
        assertThat(range.describe()).isEqualTo("6 to 60 months");
        CreditProduct.TermBounds set = CreditProduct.TermBounds.oneOf(3, 6, 12);
        assertThat(set.admits(4)).isFalse();
        assertThat(set.admits(12)).isTrue();
        assertThat(set.describe()).isEqualTo("3, 6 or 12 months");
        assertThat(CreditProduct.TermBounds.oneOf(9).describe()).isEqualTo("9 months");
    }

    @Test
    @DisplayName("the origin and the consumer kind each admit exactly one family (P12-TSK-001)")
    void theOriginAndTheConsumerKindAdmitOneFamily() {
        assertThat(RequestOrigin.values()).containsExactly(RequestOrigin.CUSTOMER_DOOR, RequestOrigin.BNPL_APPLICATION);
        assertThat(ConsumerKind.values()).containsExactly(ConsumerKind.LOAN_AGREEMENT, ConsumerKind.BNPL_AGREEMENT);
        for (CreditProduct product : CreditProduct.values()) {
            boolean bnpl = product.family() == CreditProduct.Family.BNPL;
            assertThat(RequestOrigin.CUSTOMER_DOOR.admits(product)).as(product.name()).isEqualTo(!bnpl);
            assertThat(RequestOrigin.BNPL_APPLICATION.admits(product)).as(product.name()).isEqualTo(bnpl);
            assertThat(RequestOrigin.of(product)).isEqualTo(bnpl ? RequestOrigin.BNPL_APPLICATION : RequestOrigin.CUSTOMER_DOOR);
            assertThat(ConsumerKind.LOAN_AGREEMENT.takesUp(product)).as(product.name()).isEqualTo(!bnpl);
            assertThat(ConsumerKind.BNPL_AGREEMENT.takesUp(product)).as(product.name()).isEqualTo(bnpl);
        }
    }

    private static DecisionRequest.Application submitted(CreditProduct product, Money amount, Optional<Integer> term) {
        return DecisionRequest.Application.submitted(product, amount, term, Optional.empty(), Optional.empty());
    }
}
