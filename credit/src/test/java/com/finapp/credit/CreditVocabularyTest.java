package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The attribute vocabulary and the decision outcome (P10-TSK-001; PHASE_10_PLAN.md section 12.2,
 * {@code INV-CRD-04}).
 */
@DisplayName("the attribute codes and the decision outcome are closed and distinct (P10-TSK-001)")
class CreditVocabularyTest {

    @Test
    @DisplayName("a decision is APPROVED or DECLINED and never REFER - a referral is an evaluation's outcome"
            + " (INV-CRD-04)")
    void aDecisionIsNeverAReferral() {
        assertThat(DecisionOutcome.values()).containsExactly(DecisionOutcome.APPROVED, DecisionOutcome.DECLINED);
        assertThatIllegalArgumentException().isThrownBy(() -> DecisionOutcome.valueOf("REFER"));
    }

    @Test
    @DisplayName("an attribute, a reason and a decision outcome share no type - score is not decision"
            + " (INV-CRD-04)")
    void theVocabulariesAreDistinctTypes() {
        List<Class<?>> vocabularies =
                List.of(CreditAttributeCode.class, ReasonCode.class, DecisionOutcome.class, CreditProduct.class);
        for (Class<?> one : vocabularies) {
            for (Class<?> other : vocabularies) {
                if (one != other) {
                    assertThat(one.isAssignableFrom(other)).as("%s from %s", one.getSimpleName(), other.getSimpleName())
                            .isFalse();
                }
            }
            assertThat(one.getInterfaces()).as("%s implements nothing it could be confused through",
                    one.getSimpleName()).isEmpty();
        }
    }

    @Test
    @DisplayName("the attribute codes are the plan's list plus the two markers, each with its value type")
    void theAttributeCodesAreThePlans() {
        Map<CreditAttributeCode, AttributeValueType> expected = Map.ofEntries(
                Map.entry(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, AttributeValueType.INTEGER),
                Map.entry(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, AttributeValueType.INTEGER),
                Map.entry(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, AttributeValueType.INTEGER),
                Map.entry(CreditAttributeCode.BUREAU_DEFAULTS_72M, AttributeValueType.INTEGER),
                Map.entry(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, AttributeValueType.BOOLEAN),
                Map.entry(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.BUREAU_TOTAL_BALANCE, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.FINDATA_MONTHLY_INCOME, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.DECLARED_MONTHLY_INCOME, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.DECLARED_MONTHLY_EXPENDITURE, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.PARTY_AGE_YEARS, AttributeValueType.INTEGER),
                Map.entry(CreditAttributeCode.PARTY_RESIDENCY_COUNTRY, AttributeValueType.CODE),
                Map.entry(CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE, AttributeValueType.MONEY),
                Map.entry(CreditAttributeCode.RISK_SIGNAL, AttributeValueType.CODE),
                Map.entry(CreditAttributeCode.SOURCE_UNAVAILABLE, AttributeValueType.CODE),
                Map.entry(CreditAttributeCode.CURRENCY_NOT_SUPPORTED, AttributeValueType.CODE));
        assertThat(CreditAttributeCode.values()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (CreditAttributeCode code : CreditAttributeCode.values()) {
            assertThat(code.valueType()).as(code.name()).isEqualTo(expected.get(code));
        }
    }

    @Test
    @DisplayName("exactly the two markers record an absence, and no attribute is floating point (INV-CRD-12)")
    void theMarkersAndTheValueTypes() {
        assertThat(Arrays.stream(CreditAttributeCode.values()).filter(CreditAttributeCode::marker))
                .containsExactlyInAnyOrder(CreditAttributeCode.SOURCE_UNAVAILABLE, CreditAttributeCode.CURRENCY_NOT_SUPPORTED);
        assertThat(Arrays.stream(AttributeValueType.values()).map(Enum::name))
                .containsExactlyInAnyOrder("INTEGER", "MONEY", "BOOLEAN", "CODE");
    }
}
