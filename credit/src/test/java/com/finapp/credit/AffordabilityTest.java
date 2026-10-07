package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.credit.AffordabilityAssessment.Assessment;
import com.finapp.credit.AffordabilityAssessment.Parameters;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The affordability assessment's worked cases (`P10-TSK-009`; {@code INV-CRD-12}, {@code INV-MON-01},
 * {@code INV-MON-02}): every figure exact to the minor unit, each expected value computed independently at 60 digits
 * and rounded once - never by the engine under test.
 */
@DisplayName("the affordability assessment's worked cases (P10-TSK-009)")
class AffordabilityTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Parameters PARAMETERS =
            new Parameters(new BigDecimal("0.0899"), new BigDecimal("0.03"), euros(500_00));

    @Test
    @DisplayName("a personal loan on declared figures alone: 10,000.00 over 36 months at 8.99% repays 317.95")
    void aPersonalLoanOnDeclaredFigures() {
        Assessment.Assessed assessed = assessed(loan(1_000_000, 36, Map.of()), PARAMETERS);
        assertThat(assessed.income()).isEqualTo(euros(320_000));
        assertThat(assessed.expenditure()).isEqualTo(euros(140_000));
        assertThat(assessed.obligations()).isEqualTo(euros(35_000));
        assertThat(assessed.repayment()).isEqualTo(euros(31_795));
        assertThat(assessed.disposable()).isEqualTo(euros(113_205));
        assertThat(assessed.affordable()).isTrue();
    }

    @Test
    @DisplayName("verified data lowers the income and raises the expenditure - the prudent figure of each")
    void verifiedDataIsReadPrudently() {
        Assessment.Assessed assessed = assessed(loan(1_000_000, 36, Map.of(
                CreditAttributeCode.FINDATA_MONTHLY_INCOME, euros(290_000),
                CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE, euros(160_000))),
                new Parameters(new BigDecimal("0.0899"), new BigDecimal("0.03"), euros(70_000)));
        assertThat(assessed.income()).isEqualTo(euros(290_000));
        assertThat(assessed.expenditure()).isEqualTo(euros(160_000));
        assertThat(assessed.disposable()).isEqualTo(euros(63_205));
        assertThat(assessed.affordable()).as("632.05 is short of 700.00").isFalse();

        Assessment.Assessed higher = assessed(loan(1_000_000, 36, Map.of(
                CreditAttributeCode.FINDATA_MONTHLY_INCOME, euros(400_000),
                CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE, euros(90_000))), PARAMETERS);
        assertThat(higher.income()).as("a higher verified income never raises the declared").isEqualTo(euros(320_000));
        assertThat(higher.expenditure()).as("a lower verified spend never lowers the declared").isEqualTo(euros(140_000));
    }

    @Test
    @DisplayName("the annuity, to the minor unit, across the product's range")
    void theAnnuityToTheMinorUnit() {
        assertThat(repayment(25_000_00, 60, "0.12")).isEqualTo(euros(556_11));
        assertThat(repayment(500_00, 6, "0.0899")).isEqualTo(euros(85_53));
        assertThat(repayment(7_333_33, 13, "0.065")).isEqualTo(euros(585_72));
        assertThat(repayment(10_000_00, 36, "0.15")).isEqualTo(euros(346_65));
    }

    @Test
    @DisplayName("a zero rate degenerates to the amount divided by the term")
    void aZeroRateIsTheAmountOverTheTerm() {
        assertThat(repayment(1_000_00, 7, "0")).isEqualTo(euros(142_86));
        assertThat(AffordabilityAssessment.annuity(new BigDecimal("1000.00"), 7, BigDecimal.ZERO))
                .isEqualByComparingTo("142.8571428571");
    }

    @Test
    @DisplayName("the one rounding point is the end, to minor units, half up - 125.025 is 125.03")
    void theRoundingPointIsTheEndHalfUp() {
        assertThat(AffordabilityAssessment.annuity(new BigDecimal("1000.20"), 8, BigDecimal.ZERO))
                .isEqualByComparingTo("125.025");
        assertThat(repayment(1_000_20, 8, "0")).as("HALF_UP, not the working HALF_EVEN").isEqualTo(euros(125_03));
        assertThat(AffordabilityAssessment.annuity(new BigDecimal("10000.00"), 36, new BigDecimal("0.0899")))
                .as("carried unrounded at scale 10 until the end").isEqualByComparingTo("317.9507875784");
    }

    @Test
    @DisplayName("a credit line repays its limit times the minimum payment ratio, rounded once")
    void aCreditLineRepaysTheRatio() {
        SnapshotContent line = content(CreditProduct.CREDIT_LINE, 2_345_67, Optional.empty(), Map.of());
        Parameters ratio = new Parameters(new BigDecimal("0.0899"), new BigDecimal("0.035"), euros(500_00));
        assertThat(AffordabilityAssessment.repayment(line, ratio)).as("82.09845").isEqualTo(euros(82_10));
        Assessment.Assessed assessed = assessed(line, ratio);
        assertThat(assessed.disposable()).isEqualTo(euros(320_000 - 140_000 - 35_000 - 82_10));
    }

    @Test
    @DisplayName("an absent obligation or income is unassessable and named - never a silent zero")
    void absenceIsNamedNeverZero() {
        Assessment noObligations = AffordabilityAssessment.assess(
                loan(1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS, ABSENT)), PARAMETERS);
        assertThat(noObligations).isEqualTo(
                new Assessment.Unassessable(java.util.Set.of(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS)));

        Assessment noIncome = AffordabilityAssessment.assess(
                loan(1_000_000, 36, Map.of(CreditAttributeCode.DECLARED_MONTHLY_INCOME, ABSENT)), PARAMETERS);
        assertThat(noIncome).isEqualTo(
                new Assessment.Unassessable(java.util.Set.of(CreditAttributeCode.DECLARED_MONTHLY_INCOME)));

        Assessment verifiedOnly = AffordabilityAssessment.assess(loan(1_000_000, 36, Map.of(
                CreditAttributeCode.DECLARED_MONTHLY_INCOME, ABSENT,
                CreditAttributeCode.FINDATA_MONTHLY_INCOME, euros(250_000))), PARAMETERS);
        assertThat(((Assessment.Assessed) verifiedOnly).income()).as("one figure present is that figure").isEqualTo(euros(250_000));
    }

    @Test
    @DisplayName("a money input in another currency is refused, never converted (INV-CRD-12)")
    void aForeignCurrencyInputIsRefused() {
        Parameters dollars = new Parameters(new BigDecimal("0.0899"), new BigDecimal("0.03"),
                Money.ofMinorUnits(500_00, CurrencyCode.of("USD")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AffordabilityAssessment.assess(loan(1_000_000, 36, Map.of()), dollars))
                .withMessageNotContaining("500");
        assertThatIllegalArgumentException().as("the snapshot itself holds no foreign money").isThrownBy(() -> loan(
                1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(35_000, CurrencyCode.of("USD"))))));
    }

    @Test
    @DisplayName("no figure renders in the assessment's text")
    void noFigureRenders() {
        assertThat(assessed(loan(1_000_000, 36, Map.of()), PARAMETERS).toString())
                .doesNotContain("3200").doesNotContain("317").doesNotContain("1132");
    }

    // -----------------------------------------------------------------

    static final AttributeValue ABSENT = new AttributeValue.Absent();

    static Money euros(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    static Assessment.Assessed assessed(SnapshotContent content, Parameters parameters) {
        Assessment assessment = AffordabilityAssessment.assess(content, parameters);
        assertThat(assessment).isInstanceOf(Assessment.Assessed.class);
        return (Assessment.Assessed) assessment;
    }

    private static Money repayment(long minor, int term, String rate) {
        return AffordabilityAssessment.repayment(loan(minor, term, Map.of()),
                new Parameters(new BigDecimal(rate), new BigDecimal("0.03"), euros(500_00)));
    }

    static SnapshotContent loan(long minor, int term, Map<CreditAttributeCode, ?> overrides) {
        return content(CreditProduct.PERSONAL_LOAN, minor, Optional.of(term), overrides);
    }

    /** The golden snapshot's attributes, with {@code overrides} (a {@link Money} or an {@link AttributeValue}). */
    static SnapshotContent content(
            CreditProduct product, long minor, Optional<Integer> term, Map<CreditAttributeCode, ?> overrides) {
        List<CreditAttribute> attributes = new ArrayList<>();
        for (CreditAttribute attribute : CanonicalSnapshotTest.attributes()) {
            Object override = overrides.get(attribute.code());
            if (override == null) {
                attributes.add(attribute);
                continue;
            }
            AttributeValue value = override instanceof Money money ? new AttributeValue.MoneyValue(money) : (AttributeValue) override;
            attributes.add(new CreditAttribute(attribute.code(), value, attribute.provenance()));
        }
        return new SnapshotContent(UUID.fromString("0190a1b2-0000-7000-8000-00000000000a"),
                UUID.fromString("0190a1b2-0000-7000-8000-00000000000b"), product, euros(minor), term,
                new PinnedVersions(UUID.fromString("0190a1b2-0000-7000-8000-00000000000c"),
                        UUID.fromString("0190a1b2-0000-7000-8000-00000000000d"), AffordabilityAssessment.ENGINE_VERSION),
                attributes);
    }
}
