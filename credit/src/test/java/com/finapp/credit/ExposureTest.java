package com.finapp.credit;

import static com.finapp.credit.AffordabilityTest.ABSENT;
import static com.finapp.credit.AffordabilityTest.content;
import static com.finapp.credit.AffordabilityTest.euros;
import static com.finapp.credit.AffordabilityTest.loan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.credit.ExposureAssessment.Assessment;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exposure assessment's worked cases (`P10-TSK-010`; {@code INV-CRD-09}, {@code INV-CRD-12}): every term of the sum
 * counted once, exact to the minor unit, the headroom's sign the limit's verdict - read from the snapshot alone.
 */
@DisplayName("the exposure assessment's worked cases (P10-TSK-010)")
class ExposureTest {

    @Test
    @DisplayName("the bureau's balance, the platform's outstanding and reserved credit and the request, summed exactly")
    void everyTermIsCounted() {
        Assessment.Assessed assessed = assessed(loan(1_000_000, 36, Map.of(
                CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT, euros(50_025),
                CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE, euros(300_000))), euros(2_500_000));
        assertThat(assessed.exposure()).as("4,200.50 + 500.25 + 3,000.00 + 10,000.00").isEqualTo(euros(1_770_075));
        assertThat(assessed.headroom()).isEqualTo(euros(729_925));
        assertThat(assessed.within()).isTrue();
    }

    @Test
    @DisplayName("the reserved exposure is a term: the same request beside an earlier approval has less headroom")
    void theReservedTermCounts() {
        Money maximum = euros(1_500_000);
        Assessment.Assessed alone = assessed(loan(1_000_000, 36, Map.of()), maximum);
        Assessment.Assessed beside = assessed(loan(1_000_000, 36, Map.of(
                CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE, euros(100_000))), maximum);
        assertThat(alone.exposure()).isEqualTo(euros(1_420_050));
        assertThat(beside.exposure()).isEqualTo(euros(1_520_050));
        assertThat(alone.within()).isTrue();
        assertThat(beside.within()).as("the earlier approval takes it over the limit").isFalse();
    }

    @Test
    @DisplayName("the headroom's sign is the verdict: positive within, zero exactly at the limit and within, negative beyond")
    void theHeadroomSign() {
        SnapshotContent request = loan(1_000_000, 36, Map.of());
        assertThat(assessed(request, euros(1_420_050)).headroom()).isEqualTo(Money.zero(CurrencyCode.of("EUR")));
        assertThat(assessed(request, euros(1_420_050)).within()).as("at the limit is within").isTrue();
        Assessment.Assessed beyond = assessed(request, euros(1_200_000));
        assertThat(beyond.headroom()).isEqualTo(euros(-220_050));
        assertThat(beyond.within()).isFalse();
    }

    @Test
    @DisplayName("a credit line's request is its limit")
    void aCreditLineCountsItsLimit() {
        Assessment.Assessed assessed = assessed(
                content(CreditProduct.CREDIT_LINE, 250_000, Optional.empty(), Map.of()), euros(1_000_000));
        assertThat(assessed.exposure()).isEqualTo(euros(420_050 + 250_000));
    }

    @Test
    @DisplayName("the bureau's balance absent is unassessable and named - never a zero")
    void anAbsentBalanceIsNamed() {
        assertThat(ExposureAssessment.assess(
                        loan(1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_TOTAL_BALANCE, ABSENT)), euros(2_000_000)))
                .isEqualTo(new Assessment.Unassessable(Set.of(CreditAttributeCode.BUREAU_TOTAL_BALANCE)));
    }

    @Test
    @DisplayName("a maximum in another currency is refused, never converted (INV-CRD-12)")
    void aForeignMaximumIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> ExposureAssessment.assess(
                loan(1_000_000, 36, Map.of()), Money.ofMinorUnits(2_000_000, CurrencyCode.of("USD"))));
    }

    @Test
    @DisplayName("no figure renders in the assessment's text")
    void noFigureRenders() {
        assertThat(assessed(loan(1_000_000, 36, Map.of()), euros(2_000_000)).toString())
                .doesNotContain("14200").doesNotContain("5799");
    }

    private static Assessment.Assessed assessed(SnapshotContent content, Money maximum) {
        Assessment assessment = ExposureAssessment.assess(content, maximum);
        assertThat(assessment).isInstanceOf(Assessment.Assessed.class);
        return (Assessment.Assessed) assessment;
    }
}
