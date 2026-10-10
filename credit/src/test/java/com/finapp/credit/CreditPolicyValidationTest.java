package com.finapp.credit;

import static com.finapp.credit.CreditPolicyV1.attribute;
import static com.finapp.credit.CreditPolicyV1.eur;
import static com.finapp.credit.CreditPolicyV1.figure;
import static com.finapp.credit.CreditPolicyV1.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A credit policy is judged whole at proposal (`P10-TSK-012`; ADR-0086 sections 1 and 6; {@code INV-CRD-10},
 * {@code INV-CRD-02}): every refusal is {@code PolicyIncomplete}, raised before anything is written - never at decision
 * time.
 */
@DisplayName("a credit policy is complete and well typed, or refused at proposal (P10-TSK-012)")
class CreditPolicyValidationTest {

    private static final CreditProduct LOAN = CreditProduct.PERSONAL_LOAN;

    @Test
    @DisplayName("each product's seeded v1 is complete - it would be accepted at proposal")
    void theSeedsAreComplete() {
        for (CreditProduct product : CreditProduct.values()) {
            assertThatCode(() -> CreditPolicyV1.policy(product)).as(product.name()).doesNotThrowAnyException();
            assertThat(CreditPolicyV1.policy(product).sourceKinds()).containsExactlyInAnyOrder(CreditSourceKind.values());
        }
    }

    @Test
    @DisplayName("a cap or the auto-approval ceiling below the product's minimum amount is refused at construction - an"
            + " approval capped there is no offer the product can make (the Phase 10 to 11 transition); at the minimum is"
            + " accepted")
    void aCeilingBelowTheProductMinimumIsRefused() {
        for (CreditProduct product : CreditProduct.values()) {
            Money minimum = product.minimumAmount();
            Money belowMinimum = minimum.minus(Money.ofMinorUnits(1, minimum.currency()));
            List<CreditPolicy.PolicyRule> capped = CreditPolicyV1.rules(product);
            capped.add(new CreditPolicy.PolicyRule("LOW_CAP", figure(PolicyFigure.EXPOSURE_HEADROOM), PolicyOperator.LT,
                    new CreditPolicy.Operand.MoneyOperand(eur(0)), PolicyEffect.CAP_AMOUNT, Optional.of(belowMinimum),
                    ReasonCode.EXPOSURE_LIMIT));
            assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).as(product + "'s cap")
                    .isThrownBy(() -> CreditPolicyV1.policy(product, capped))
                    .withMessageContaining("at least the product's minimum amount");
            CreditPolicy v1 = CreditPolicyV1.policy(product);
            assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).as(product + "'s ceiling")
                    .isThrownBy(() -> new CreditPolicy(product, v1.assessmentRateBps(), v1.minimumDisposable(),
                            v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), v1.unavailableFallback(),
                            belowMinimum, v1.rules()))
                    .withMessageContaining("at least the product's minimum amount");
            List<CreditPolicy.PolicyRule> atTheMinimum = CreditPolicyV1.rules(product);
            atTheMinimum.add(new CreditPolicy.PolicyRule("MIN_CAP", figure(PolicyFigure.EXPOSURE_HEADROOM),
                    PolicyOperator.LT, new CreditPolicy.Operand.MoneyOperand(eur(0)), PolicyEffect.CAP_AMOUNT,
                    Optional.of(minimum), ReasonCode.EXPOSURE_LIMIT));
            assertThatCode(() -> CreditPolicyV1.policy(product, atTheMinimum)).doesNotThrowAnyException();
            assertThatCode(() -> new CreditPolicy(product, v1.assessmentRateBps(), v1.minimumDisposable(),
                    v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), v1.unavailableFallback(),
                    minimum, v1.rules())).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("INV-CRD-09 (P10-DOC-001): only a rule guaranteed to stop an approval past the limit bounds the exposure")
    void onlyAGuaranteedRuleBoundsTheExposure() {
        for (CreditProduct product : CreditProduct.values()) {
            assertThat(CreditPolicyV1.policy(product).boundsExposure()).as(product + "'s seeded v1").isTrue();
        }
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(LOAN);
        rules.removeIf(rule -> rule.ruleCode().equals("EXPOSURE_LIMIT"));
        CreditPolicy unbounded = CreditPolicyV1.policy(LOAN, rules);
        assertThat(unbounded.boundsExposure()).as("v1 without its exposure rule - constructible, refused at proposal")
                .isFalse();
        Money limit = unbounded.maximumExposure();
        Money oneOver = limit.plus(Money.ofMinorUnits(1, limit.currency()));
        Object[][] shapes = {
                {PolicyFigure.EXPOSURE_HEADROOM, PolicyOperator.LT, eur(0), PolicyEffect.DECLINE, true},
                {PolicyFigure.EXPOSURE_HEADROOM, PolicyOperator.LE, eur(0), PolicyEffect.HARD_DECLINE, true},
                {PolicyFigure.EXPOSURE_HEADROOM, PolicyOperator.LT, eur(500_00), PolicyEffect.REFER, true},
                {PolicyFigure.EXPOSURE_HEADROOM, PolicyOperator.LT, Money.ofMinorUnits(-1, limit.currency()),
                        PolicyEffect.DECLINE, false},
                {PolicyFigure.EXPOSURE_HEADROOM, PolicyOperator.GT, eur(0), PolicyEffect.DECLINE, false},
                {PolicyFigure.EXPOSURE, PolicyOperator.GT, limit, PolicyEffect.DECLINE, true},
                {PolicyFigure.EXPOSURE, PolicyOperator.GE, limit, PolicyEffect.REFER, true},
                {PolicyFigure.EXPOSURE, PolicyOperator.GT, oneOver, PolicyEffect.DECLINE, false},
                {PolicyFigure.EXPOSURE, PolicyOperator.LT, limit, PolicyEffect.DECLINE, false},
                {PolicyFigure.DISPOSABLE_INCOME, PolicyOperator.LT, eur(0), PolicyEffect.DECLINE, false}};
        for (Object[] shape : shapes) {
            List<CreditPolicy.PolicyRule> with = CreditPolicyV1.rules(LOAN);
            with.removeIf(rule -> rule.ruleCode().equals("EXPOSURE_LIMIT"));
            with.add(rule("CANDIDATE", figure((PolicyFigure) shape[0]), (PolicyOperator) shape[1],
                    new CreditPolicy.Operand.MoneyOperand((Money) shape[2]), (PolicyEffect) shape[3],
                    ReasonCode.EXPOSURE_LIMIT));
            assertThat(CreditPolicyV1.policy(LOAN, with).boundsExposure()).as(Arrays.toString(shape))
                    .isEqualTo(shape[4]);
        }
        List<CreditPolicy.PolicyRule> capped = CreditPolicyV1.rules(LOAN);
        capped.removeIf(rule -> rule.ruleCode().equals("EXPOSURE_LIMIT"));
        capped.add(new CreditPolicy.PolicyRule("CAPPED", figure(PolicyFigure.EXPOSURE_HEADROOM), PolicyOperator.LT,
                new CreditPolicy.Operand.MoneyOperand(eur(0)), PolicyEffect.CAP_AMOUNT, Optional.of(eur(1_000_00)),
                ReasonCode.EXPOSURE_LIMIT));
        assertThat(CreditPolicyV1.policy(LOAN, capped).boundsExposure()).as("a cap still approves past the limit")
                .isFalse();
    }

    @Test
    @DisplayName("a policy without a fallback for a source kind it reads is incomplete - whatever else it holds")
    void aPolicyWithoutAFallbackForASourceItReadsIsIncomplete() {
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(LOAN);
        rules.removeIf(rule -> rule.reason() == ReasonCode.SOURCE_UNAVAILABLE);
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class)
                .isThrownBy(() -> CreditPolicyV1.policy(LOAN, rules))
                .withMessageContaining("BUREAU")
                .withMessageContaining(ReasonCode.SOURCE_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("a fallback must carry the DECLARED effect: a DECLINE rule does not cover a policy that refers")
    void aFallbackOfAnotherEffectDoesNotCount() {
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(LOAN);
        rules.set(0, rule("SOURCE_UNAVAILABLE_FALLBACK", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE),
                PolicyOperator.IS_PRESENT, new CreditPolicy.Operand.None(), PolicyEffect.DECLINE,
                ReasonCode.SOURCE_UNAVAILABLE));
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).isThrownBy(() -> CreditPolicyV1.policy(LOAN, rules));
    }

    @Test
    @DisplayName("a fallback must be GUARANTEED for the kind: an IN set missing a marker value naming it does not cover it")
    void aPartialMarkerSetDoesNotCover() {
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(LOAN);
        rules.set(0, rule("BUREAU_ALONE", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE), PolicyOperator.IN,
                new CreditPolicy.Operand.CodesOperand(List.of("BUREAU")), PolicyEffect.REFER, ReasonCode.SOURCE_UNAVAILABLE));
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class)
                .as("BUREAU_AND_FINANCIAL_DATA also names the bureau, and FINANCIAL_DATA is not covered at all")
                .isThrownBy(() -> CreditPolicyV1.policy(LOAN, rules));
    }

    @Test
    @DisplayName("the three guaranteed shapes each cover their kind: the marker present, the marker in a full set, an own attribute absent")
    void theGuaranteedShapesCover() {
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(LOAN);
        rules.set(0, rule("BUREAU_FALLBACK", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE), PolicyOperator.IN,
                new CreditPolicy.Operand.CodesOperand(List.of("BUREAU", "BUREAU_AND_FINANCIAL_DATA")), PolicyEffect.REFER,
                ReasonCode.SOURCE_UNAVAILABLE));
        rules.add(rule("FINDATA_FALLBACK", attribute(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE),
                PolicyOperator.IS_ABSENT, new CreditPolicy.Operand.None(), PolicyEffect.REFER, ReasonCode.SOURCE_UNAVAILABLE));
        assertThatCode(() -> CreditPolicyV1.policy(LOAN, rules)).doesNotThrowAnyException();
        assertThat(CreditPolicy.PolicyRule.markerValuesNaming(CreditSourceKind.FINANCIAL_DATA))
                .containsExactlyInAnyOrder("FINANCIAL_DATA", "BUREAU_AND_FINANCIAL_DATA");
    }

    @Test
    @DisplayName("a fallback of APPROVE is unrepresentable: refer and decline are the only fallbacks, and no effect approves")
    void aFallbackOfApproveIsUnrepresentable() {
        assertThat(Arrays.stream(UnavailableFallback.values()).map(Enum::name)).containsExactly("REFER", "DECLINE");
        assertThat(Arrays.stream(PolicyEffect.values()).map(Enum::name)).doesNotContain("APPROVE");
        assertThat(Arrays.stream(UnavailableFallback.values()).map(UnavailableFallback::effect))
                .containsExactly(PolicyEffect.REFER, PolicyEffect.DECLINE);
    }

    @Test
    @DisplayName("an unknown reason code, attribute or figure is outside the vocabulary")
    void anUnknownNameIsIncomplete() {
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).isThrownBy(() -> CreditPolicy.reasonCode("CRD-NO-SUCH"));
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class)
                .isThrownBy(() -> CreditPolicy.subject("ATTRIBUTE", "BUREAU_SHOE_SIZE"));
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).isThrownBy(() -> CreditPolicy.subject("FIGURE", "LUCK"));
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).isThrownBy(() -> CreditPolicy.subject("OTHER", "SCORE"));
        assertThat(CreditPolicy.reasonCode("CRD-INSOLVENCY")).isEqualTo(ReasonCode.INSOLVENCY);
        assertThat(CreditPolicy.subject("FIGURE", "SCORE")).isEqualTo(figure(PolicyFigure.SCORE));
    }

    @Test
    @DisplayName("an operand of the wrong type is refused - ordering a boolean, a set over an integer, money for a score, a figure absent")
    void anOperandOfTheWrongTypeIsIncomplete() {
        assertIncomplete(() -> rule("A", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                new CreditPolicy.Operand.MoneyOperand(eur(1)), PolicyEffect.DECLINE, ReasonCode.SCORE_INSUFFICIENT));
        assertIncomplete(() -> rule("A", attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG), PolicyOperator.GT,
                new CreditPolicy.Operand.BooleanOperand(true), PolicyEffect.DECLINE, ReasonCode.INSOLVENCY));
        assertIncomplete(() -> rule("A", attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), PolicyOperator.IN,
                new CreditPolicy.Operand.CodesOperand(List.of("ONE")), PolicyEffect.DECLINE, ReasonCode.PRIOR_DEFAULT));
        assertIncomplete(() -> rule("A", figure(PolicyFigure.SCORE), PolicyOperator.IS_ABSENT,
                new CreditPolicy.Operand.None(), PolicyEffect.REFER, ReasonCode.RISK_REFERRAL));
        assertIncomplete(() -> rule("A", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.EQ,
                new CreditPolicy.Operand.CodesOperand(List.of("HIGH", "LOW")), PolicyEffect.REFER, ReasonCode.RISK_REFERRAL));
        assertIncomplete(() -> rule("A", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE), PolicyOperator.IS_PRESENT,
                new CreditPolicy.Operand.IntegerOperand(1), PolicyEffect.REFER, ReasonCode.SOURCE_UNAVAILABLE));
    }

    @Test
    @DisplayName("an adverse effect without an adverse code is refused - CRD-AUTO-APPROVAL-CEILING is the ceiling's, never a rule's")
    void anAdverseRuleWithoutAnAdverseCodeIsIncomplete() {
        assertIncomplete(() -> rule("A", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                new CreditPolicy.Operand.IntegerOperand(500), PolicyEffect.REFER, ReasonCode.AUTO_APPROVAL_CEILING));
    }

    @Test
    @DisplayName("a cap is a CAP_AMOUNT's alone, positive, and in the product's currency - as is every money operand")
    void capsAndMoneyAreInTheProductsCurrency() {
        assertIncomplete(() -> new CreditPolicy.PolicyRule("A", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                new CreditPolicy.Operand.IntegerOperand(500), PolicyEffect.DECLINE, Optional.of(eur(100)),
                ReasonCode.SCORE_INSUFFICIENT));
        assertIncomplete(() -> new CreditPolicy.PolicyRule("A", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                new CreditPolicy.Operand.IntegerOperand(500), PolicyEffect.CAP_AMOUNT, Optional.empty(),
                ReasonCode.SCORE_INSUFFICIENT));
        Money dollars = Money.ofMinorUnits(100_00, CurrencyCode.of("USD"));
        List<CreditPolicy.PolicyRule> foreignCap = CreditPolicyV1.rules(LOAN);
        foreignCap.add(new CreditPolicy.PolicyRule("USD_CAP", figure(PolicyFigure.SCORE), PolicyOperator.LT,
                new CreditPolicy.Operand.IntegerOperand(500), PolicyEffect.CAP_AMOUNT, Optional.of(dollars),
                ReasonCode.SCORE_INSUFFICIENT));
        assertIncomplete(() -> CreditPolicyV1.policy(LOAN, foreignCap));
        List<CreditPolicy.PolicyRule> foreignOperand = CreditPolicyV1.rules(LOAN);
        foreignOperand.add(rule("USD_EXPOSURE", figure(PolicyFigure.EXPOSURE), PolicyOperator.GT,
                new CreditPolicy.Operand.MoneyOperand(dollars), PolicyEffect.DECLINE, ReasonCode.EXPOSURE_LIMIT));
        assertIncomplete(() -> CreditPolicyV1.policy(LOAN, foreignOperand));
    }

    @Test
    @DisplayName("the parameters are bounded: rates in basis points, amounts in the product's currency, ages within a year")
    void theParametersAreBounded() {
        CreditPolicy v1 = CreditPolicyV1.policy(LOAN);
        assertIncomplete(() -> new CreditPolicy(LOAN, 10_001, v1.minimumDisposable(), v1.minimumPaymentRatioBps(),
                v1.maximumExposure(), v1.maximumDataAge(), v1.unavailableFallback(), v1.autoApprovalCeiling(), v1.rules()));
        assertIncomplete(() -> new CreditPolicy(LOAN, 900, v1.minimumDisposable(), 0,
                v1.maximumExposure(), v1.maximumDataAge(), v1.unavailableFallback(), v1.autoApprovalCeiling(), v1.rules()));
        assertIncomplete(() -> new CreditPolicy(LOAN, 900, Money.ofMinorUnits(1, CurrencyCode.of("USD")),
                v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), v1.unavailableFallback(),
                v1.autoApprovalCeiling(), v1.rules()));
        assertIncomplete(() -> new CreditPolicy(LOAN, 900, v1.minimumDisposable(), v1.minimumPaymentRatioBps(),
                eur(0), v1.maximumDataAge(), v1.unavailableFallback(), v1.autoApprovalCeiling(), v1.rules()));
        assertIncomplete(() -> new CreditPolicy(LOAN, 900, v1.minimumDisposable(), v1.minimumPaymentRatioBps(),
                v1.maximumExposure(), java.util.Map.of(CreditSourceKind.BUREAU, java.time.Duration.ofDays(400)),
                v1.unavailableFallback(), v1.autoApprovalCeiling(), v1.rules()));
        List<CreditPolicy.PolicyRule> twice = CreditPolicyV1.rules(LOAN);
        twice.add(twice.get(1));
        assertIncomplete(() -> CreditPolicyV1.policy(LOAN, twice));
    }

    private static void assertIncomplete(org.assertj.core.api.ThrowableAssert.ThrowingCallable construction) {
        assertThatExceptionOfType(CreditPolicy.PolicyIncomplete.class).isThrownBy(construction);
    }
}
