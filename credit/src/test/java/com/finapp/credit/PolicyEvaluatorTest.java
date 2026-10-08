package com.finapp.credit;

import static com.finapp.credit.EvaluationFixtures.always;
import static com.finapp.credit.EvaluationFixtures.assessment;
import static com.finapp.credit.EvaluationFixtures.bureauUnavailable;
import static com.finapp.credit.EvaluationFixtures.cap;
import static com.finapp.credit.EvaluationFixtures.clean;
import static com.finapp.credit.EvaluationFixtures.eur;
import static com.finapp.credit.EvaluationFixtures.policy;
import static com.finapp.credit.EvaluationFixtures.rule;
import static com.finapp.credit.EvaluationFixtures.snapshot;
import static com.finapp.credit.EvaluationFixtures.with;
import static com.finapp.credit.EvaluationFixtures.without;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.credit.EvaluationResult.RuleState;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Engine 1 (`P10-TSK-013`; PHASE_10_PLAN.md section 12.6, {@code INV-CRD-01}, {@code INV-CRD-02}, {@code INV-CRD-10}):
 * every operator against every value type, every effect, every severity combination, the reasons' order and dedup, the
 * caps and the ceiling, the unavailable fallback, a partial answer - and an adverse result without a reason
 * unrepresentable.
 */
@DisplayName("the policy evaluator, engine 1 (P10-TSK-013)")
class PolicyEvaluatorTest {

    private static final PolicyEvaluator ENGINE = new PolicyEvaluatorV1();
    private static final Money REQUESTED = eur(10_000_00);

    private static CreditPolicy.Subject attribute(CreditAttributeCode code) {
        return CreditPolicyV1.attribute(code);
    }

    private static CreditPolicy.Subject figure(PolicyFigure figure) {
        return CreditPolicyV1.figure(figure);
    }

    /** The state of one REFER rule over the clean snapshot (with {@code attributes}) and {@code assessment}'s figures. */
    private static RuleState state(List<CreditAttribute> attributes, CreditAssessment assessment, CreditPolicy.Subject subject,
            PolicyOperator operator, CreditPolicy.Operand operand) {
        SnapshotContent snapshot = snapshot(attributes, REQUESTED);
        CreditPolicy policy = policy(List.of(
                rule("UNDER_TEST", subject, operator, operand, PolicyEffect.REFER, ReasonCode.RISK_REFERRAL)));
        EvaluationResult result = ENGINE.evaluate(snapshot, rebind(assessment, snapshot), policy);
        RuleState state = result.rules().get(0).state();
        assertThat(result.outcome()).isEqualTo(state.triggered() ? EvaluationOutcome.REFER
                : state.assessed() ? EvaluationOutcome.APPROVE : EvaluationOutcome.REFER);
        return state;
    }

    private static RuleState state(CreditPolicy.Subject subject, PolicyOperator operator, CreditPolicy.Operand operand) {
        return state(clean(), assessment(snapshot(REQUESTED), 700), subject, operator, operand);
    }

    private static CreditAssessment rebind(CreditAssessment assessment, SnapshotContent snapshot) {
        return assessment(snapshot, assessment.affordability(), assessment.exposure(), assessment.score());
    }

    private static CreditPolicy.Operand.IntegerOperand integer(long value) {
        return new CreditPolicy.Operand.IntegerOperand(value);
    }

    private static CreditPolicy.Operand.MoneyOperand moneyOperand(long minor) {
        return new CreditPolicy.Operand.MoneyOperand(eur(minor));
    }

    private static CreditPolicy.Operand.BooleanOperand bool(boolean value) {
        return new CreditPolicy.Operand.BooleanOperand(value);
    }

    private static CreditPolicy.Operand.CodesOperand codeSet(String... codes) {
        return new CreditPolicy.Operand.CodesOperand(List.of(codes));
    }

    // ------------------------------------------------------------------ every operator against every value type

    @Test
    @DisplayName("integers order: an attribute of 3 (BUREAU_DELINQUENCIES_24M) and the SCORE figure of 700")
    void integers() {
        List<CreditAttribute> three = with(clean(), CreditAttributeCode.BUREAU_DELINQUENCIES_24M,
                new AttributeValue.IntegerValue(3));
        CreditAssessment assessed = assessment(snapshot(REQUESTED), 700);
        CreditPolicy.Subject delinquencies = attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M);
        Object[][] cases = {
            {PolicyOperator.LT, 3L, false}, {PolicyOperator.LT, 4L, true},
            {PolicyOperator.LE, 3L, true}, {PolicyOperator.LE, 2L, false},
            {PolicyOperator.GT, 3L, false}, {PolicyOperator.GT, 2L, true},
            {PolicyOperator.GE, 3L, true}, {PolicyOperator.GE, 4L, false},
            {PolicyOperator.EQ, 3L, true}, {PolicyOperator.EQ, 4L, false},
            {PolicyOperator.NE, 3L, false}, {PolicyOperator.NE, 4L, true},
        };
        for (Object[] c : cases) {
            PolicyOperator operator = (PolicyOperator) c[0];
            long operand = (long) c[1];
            RuleState expected = (boolean) c[2] ? RuleState.TRIGGERED : RuleState.NOT_TRIGGERED;
            assertThat(state(three, assessed, delinquencies, operator, integer(operand)))
                    .as("3 %s %d", operator, operand).isEqualTo(expected);
            assertThat(state(clean(), assessed, figure(PolicyFigure.SCORE), operator, integer(operand + 697)))
                    .as("700 %s %d", operator, operand + 697).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("money orders: an attribute (BUREAU_TOTAL_BALANCE 4,200.50) and the three money figures")
    void money() {
        CreditAssessment assessed = assessment(snapshot(REQUESTED), 700);
        Object[][] cases = {
            {PolicyOperator.LT, 0L, false}, {PolicyOperator.LT, 1L, true},
            {PolicyOperator.LE, 0L, true}, {PolicyOperator.LE, -1L, false},
            {PolicyOperator.GT, 0L, false}, {PolicyOperator.GT, -1L, true},
            {PolicyOperator.GE, 0L, true}, {PolicyOperator.GE, 1L, false},
            {PolicyOperator.EQ, 0L, true}, {PolicyOperator.EQ, 1L, false},
            {PolicyOperator.NE, 0L, false}, {PolicyOperator.NE, 1L, true},
        };
        for (Object[] c : cases) {
            PolicyOperator operator = (PolicyOperator) c[0];
            long offset = (long) c[1];
            RuleState expected = (boolean) c[2] ? RuleState.TRIGGERED : RuleState.NOT_TRIGGERED;
            assertThat(state(clean(), assessed, attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE), operator,
                    moneyOperand(420_050 + offset))).as("balance %s", operator).isEqualTo(expected);
            assertThat(state(clean(), assessed, figure(PolicyFigure.DISPOSABLE_INCOME), operator,
                    moneyOperand(125_000 + offset))).as("disposable %s", operator).isEqualTo(expected);
            assertThat(state(clean(), assessed, figure(PolicyFigure.EXPOSURE), operator,
                    moneyOperand(2_000_000 + offset))).as("exposure %s", operator).isEqualTo(expected);
            assertThat(state(clean(), assessed, figure(PolicyFigure.EXPOSURE_HEADROOM), operator,
                    moneyOperand(2_000_000 + offset))).as("headroom %s", operator).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("booleans are equal or not: an attribute (BUREAU_INSOLVENCY_FLAG false) and the AFFORDABLE figure")
    void booleans() {
        CreditAssessment affordable = assessment(snapshot(REQUESTED), 700);
        for (boolean operand : new boolean[] {true, false}) {
            RuleState equal = operand ? RuleState.NOT_TRIGGERED : RuleState.TRIGGERED;
            RuleState notEqual = operand ? RuleState.TRIGGERED : RuleState.NOT_TRIGGERED;
            CreditPolicy.Subject flag = attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG);
            assertThat(state(clean(), affordable, flag, PolicyOperator.EQ, bool(operand))).isEqualTo(equal);
            assertThat(state(clean(), affordable, flag, PolicyOperator.NE, bool(operand))).isEqualTo(notEqual);
            assertThat(state(clean(), affordable, figure(PolicyFigure.AFFORDABLE), PolicyOperator.EQ, bool(!operand)))
                    .isEqualTo(equal);
            assertThat(state(clean(), affordable, figure(PolicyFigure.AFFORDABLE), PolicyOperator.NE, bool(!operand)))
                    .isEqualTo(notEqual);
        }
    }

    @Test
    @DisplayName("codes are equal or not, in or out of a set: RISK_SIGNAL NOT_ASSESSED")
    void codes() {
        CreditPolicy.Subject signal = attribute(CreditAttributeCode.RISK_SIGNAL);
        assertThat(state(signal, PolicyOperator.EQ, codeSet("NOT_ASSESSED"))).isEqualTo(RuleState.TRIGGERED);
        assertThat(state(signal, PolicyOperator.EQ, codeSet("HIGH"))).isEqualTo(RuleState.NOT_TRIGGERED);
        assertThat(state(signal, PolicyOperator.NE, codeSet("NOT_ASSESSED"))).isEqualTo(RuleState.NOT_TRIGGERED);
        assertThat(state(signal, PolicyOperator.NE, codeSet("HIGH"))).isEqualTo(RuleState.TRIGGERED);
        assertThat(state(signal, PolicyOperator.IN, codeSet("HIGH", "NOT_ASSESSED"))).isEqualTo(RuleState.TRIGGERED);
        assertThat(state(signal, PolicyOperator.IN, codeSet("HIGH", "LOW"))).isEqualTo(RuleState.NOT_TRIGGERED);
        assertThat(state(signal, PolicyOperator.NOT_IN, codeSet("HIGH", "NOT_ASSESSED")))
                .isEqualTo(RuleState.NOT_TRIGGERED);
        assertThat(state(signal, PolicyOperator.NOT_IN, codeSet("HIGH", "LOW"))).isEqualTo(RuleState.TRIGGERED);
    }

    @Test
    @DisplayName("presence is always decided - of every value type, absent and present")
    void presence() {
        CreditPolicy.Operand none = new CreditPolicy.Operand.None();
        for (CreditAttributeCode code : List.of(CreditAttributeCode.BUREAU_DELINQUENCIES_24M,
                CreditAttributeCode.BUREAU_TOTAL_BALANCE, CreditAttributeCode.BUREAU_INSOLVENCY_FLAG,
                CreditAttributeCode.RISK_SIGNAL)) {
            List<CreditAttribute> absent = with(clean(), code, new AttributeValue.Absent());
            CreditAssessment assessed = assessment(snapshot(REQUESTED), 700);
            assertThat(state(clean(), assessed, attribute(code), PolicyOperator.IS_PRESENT, none)).as("%s present", code)
                    .isEqualTo(RuleState.TRIGGERED);
            assertThat(state(clean(), assessed, attribute(code), PolicyOperator.IS_ABSENT, none)).as("%s present", code)
                    .isEqualTo(RuleState.NOT_TRIGGERED);
            assertThat(state(absent, assessed, attribute(code), PolicyOperator.IS_ABSENT, none)).as("%s absent", code)
                    .isEqualTo(RuleState.TRIGGERED);
            assertThat(state(absent, assessed, attribute(code), PolicyOperator.IS_PRESENT, none)).as("%s absent", code)
                    .isEqualTo(RuleState.NOT_TRIGGERED);
        }
    }

    @Test
    @DisplayName("a comparison of an ABSENT attribute or an unassessable figure is UNASSESSED - it never triggers")
    void absentIsUnassessed() {
        CreditAssessment unassessable = assessment(snapshot(REQUESTED), EvaluationFixtures.unaffordable(),
                EvaluationFixtures.unexposed(), 700);
        List<CreditAttribute> absentDefaults = with(clean(), CreditAttributeCode.BUREAU_DEFAULTS_72M,
                new AttributeValue.Absent());
        for (PolicyOperator operator : List.of(PolicyOperator.LT, PolicyOperator.LE, PolicyOperator.GT,
                PolicyOperator.GE, PolicyOperator.EQ, PolicyOperator.NE)) {
            assertThat(state(absentDefaults, unassessable, attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), operator,
                    integer(0))).isEqualTo(RuleState.UNASSESSED);
            assertThat(state(clean(), unassessable, figure(PolicyFigure.DISPOSABLE_INCOME), operator, moneyOperand(0)))
                    .isEqualTo(RuleState.UNASSESSED);
            assertThat(state(clean(), unassessable, figure(PolicyFigure.EXPOSURE), operator, moneyOperand(0)))
                    .isEqualTo(RuleState.UNASSESSED);
            assertThat(state(clean(), unassessable, figure(PolicyFigure.EXPOSURE_HEADROOM), operator, moneyOperand(0)))
                    .isEqualTo(RuleState.UNASSESSED);
        }
        for (PolicyOperator operator : List.of(PolicyOperator.EQ, PolicyOperator.NE)) {
            assertThat(state(clean(), unassessable, figure(PolicyFigure.AFFORDABLE), operator, bool(false)))
                    .isEqualTo(RuleState.UNASSESSED);
        }
        List<CreditAttribute> absentSignal = with(clean(), CreditAttributeCode.RISK_SIGNAL, new AttributeValue.Absent());
        for (PolicyOperator operator : List.of(PolicyOperator.IN, PolicyOperator.NOT_IN)) {
            assertThat(state(absentSignal, unassessable, attribute(CreditAttributeCode.RISK_SIGNAL), operator,
                    codeSet("HIGH"))).isEqualTo(RuleState.UNASSESSED);
        }
    }

    // ------------------------------------------------------------------ effects and their severity

    @Test
    @DisplayName("each effect alone: HARD_DECLINE, DECLINE and REFER conclude themselves; a cap approves less")
    void eachEffect() {
        SnapshotContent snapshot = snapshot(REQUESTED);
        CreditAssessment assessment = assessment(snapshot, 700);
        for (PolicyEffect effect : List.of(PolicyEffect.HARD_DECLINE, PolicyEffect.DECLINE, PolicyEffect.REFER)) {
            EvaluationResult result = ENGINE.evaluate(snapshot, assessment,
                    policy(List.of(always("ONLY", effect, ReasonCode.RISK_REFERRAL))));
            assertThat(result.outcome()).isEqualTo(EvaluationOutcome.valueOf(effect.name()));
            assertThat(result.reasons()).containsExactly(ReasonCode.RISK_REFERRAL);
            assertThat(result.approved()).isEmpty();
        }
        EvaluationResult capped = ENGINE.evaluate(snapshot, assessment,
                policy(List.of(cap("ONLY", eur(4_000_00), ReasonCode.SCORE_INSUFFICIENT))));
        assertThat(capped.outcome()).isEqualTo(EvaluationOutcome.APPROVE);
        assertThat(capped.approved()).contains(eur(4_000_00));
        assertThat(capped.reasons()).containsExactly(ReasonCode.SCORE_INSUFFICIENT);
    }

    @Test
    @DisplayName("every combination of triggered effects concludes the most severe: HARD_DECLINE > DECLINE > REFER > APPROVE")
    void everySeverityCombination() {
        SnapshotContent snapshot = snapshot(REQUESTED);
        CreditAssessment assessment = assessment(snapshot, 700);
        PolicyEffect[] effects = PolicyEffect.values();
        for (int mask = 1; mask < (1 << effects.length); mask++) {
            Set<PolicyEffect> present = EnumSet.noneOf(PolicyEffect.class);
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            for (int i = 0; i < effects.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    present.add(effects[i]);
                    rules.add(always("RULE_" + i, effects[i], ReasonCode.RISK_REFERRAL));
                }
            }
            // Each order of the same effects, too: the severity is the rule, not the position.
            for (List<CreditPolicy.PolicyRule> order : List.of(rules, rules.reversed())) {
                EvaluationOutcome expected = present.contains(PolicyEffect.HARD_DECLINE) ? EvaluationOutcome.HARD_DECLINE
                        : present.contains(PolicyEffect.DECLINE) ? EvaluationOutcome.DECLINE
                        : present.contains(PolicyEffect.REFER) ? EvaluationOutcome.REFER
                        : EvaluationOutcome.APPROVE;
                assertThat(ENGINE.evaluate(snapshot, assessment, policy(order)).outcome())
                        .as("triggered %s", present).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("an adverse outcome gives the triggered rules' codes in ordinal order, deduplicated keeping the first")
    void reasonOrderAndDedup() {
        SnapshotContent snapshot = snapshot(REQUESTED);
        CreditAssessment assessment = assessment(snapshot, 700);
        EvaluationResult result = ENGINE.evaluate(snapshot, assessment, policy(List.of(
                always("FIRST", PolicyEffect.REFER, ReasonCode.RISK_REFERRAL),
                rule("SILENT", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(0), PolicyEffect.DECLINE,
                        ReasonCode.INSOLVENCY),
                always("SECOND", PolicyEffect.DECLINE, ReasonCode.SCORE_INSUFFICIENT),
                always("REPEAT", PolicyEffect.REFER, ReasonCode.RISK_REFERRAL),
                cap("CAPPED", eur(1_000_00), ReasonCode.EXPOSURE_LIMIT),
                always("THIRD", PolicyEffect.REFER, ReasonCode.INCOME_UNVERIFIED))));
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.DECLINE);
        assertThat(result.reasons()).containsExactly(ReasonCode.RISK_REFERRAL, ReasonCode.SCORE_INSUFFICIENT,
                ReasonCode.EXPOSURE_LIMIT, ReasonCode.INCOME_UNVERIFIED);
        assertThat(result.rules()).extracting(EvaluationResult.RuleResult::state).containsExactly(RuleState.TRIGGERED,
                RuleState.NOT_TRIGGERED, RuleState.TRIGGERED, RuleState.TRIGGERED, RuleState.TRIGGERED,
                RuleState.TRIGGERED);
        assertThat(result.rules()).extracting(EvaluationResult.RuleResult::ordinal).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(result.engineVersion()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the approved amount

    private EvaluationResult approve(Money requested, Money ceiling, List<CreditPolicy.PolicyRule> rules) {
        SnapshotContent snapshot = snapshot(requested);
        return ENGINE.evaluate(snapshot, assessment(snapshot, 700), policy(ceiling, UnavailableFallback.REFER, rules));
    }

    @Test
    @DisplayName("nothing binds: the request in full, with no reason")
    void inFull() {
        EvaluationResult result = approve(REQUESTED, eur(20_000_00), List.of(
                rule("SILENT", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(0), PolicyEffect.DECLINE,
                        ReasonCode.INSOLVENCY)));
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.APPROVE);
        assertThat(result.approved()).contains(REQUESTED);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.fallbackApplied()).isFalse();
    }

    @Test
    @DisplayName("several caps: the least binds and carries its rule's code")
    void severalCaps() {
        EvaluationResult result = approve(REQUESTED, eur(20_000_00), List.of(
                cap("HIGH_CAP", eur(8_000_00), ReasonCode.EXPOSURE_LIMIT),
                cap("LOW_CAP", eur(3_000_00), ReasonCode.SCORE_INSUFFICIENT),
                cap("MIDDLE_CAP", eur(5_000_00), ReasonCode.AFFORDABILITY_INSUFFICIENT)));
        assertThat(result.approved()).contains(eur(3_000_00));
        assertThat(result.reasons()).containsExactly(ReasonCode.SCORE_INSUFFICIENT);
    }

    @Test
    @DisplayName("a cap above the request does not bind: the request in full, with no reason")
    void aCapAboveTheRequest() {
        EvaluationResult result = approve(REQUESTED, eur(20_000_00),
                List.of(cap("LOOSE_CAP", eur(15_000_00), ReasonCode.SCORE_INSUFFICIENT)));
        assertThat(result.approved()).contains(REQUESTED);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    @DisplayName("the ceiling binds alone: CRD-AUTO-APPROVAL-CEILING, the one non-adverse code")
    void theCeilingBinds() {
        EvaluationResult result = approve(REQUESTED, eur(6_000_00),
                List.of(cap("LOOSE_CAP", eur(8_000_00), ReasonCode.SCORE_INSUFFICIENT)));
        assertThat(result.approved()).contains(eur(6_000_00));
        assertThat(result.reasons()).containsExactly(ReasonCode.AUTO_APPROVAL_CEILING);
    }

    @Test
    @DisplayName("a rule cap below the ceiling carries its own code; a rule cap equal to it, too")
    void aRuleCapBelowTheCeiling() {
        EvaluationResult below = approve(REQUESTED, eur(6_000_00),
                List.of(cap("TIGHT_CAP", eur(4_000_00), ReasonCode.SCORE_INSUFFICIENT)));
        assertThat(below.approved()).contains(eur(4_000_00));
        assertThat(below.reasons()).containsExactly(ReasonCode.SCORE_INSUFFICIENT);
        EvaluationResult equal = approve(REQUESTED, eur(6_000_00),
                List.of(cap("EQUAL_CAP", eur(6_000_00), ReasonCode.EXPOSURE_LIMIT)));
        assertThat(equal.approved()).contains(eur(6_000_00));
        assertThat(equal.reasons()).containsExactly(ReasonCode.EXPOSURE_LIMIT);
    }

    @Test
    @DisplayName("v1 over a clean request: in full at the ceiling, the ceiling above it, the low-score cap below 600")
    void policyV1() {
        CreditPolicy v1 = CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN);
        SnapshotContent atCeiling = snapshot(eur(10_000_00));
        EvaluationResult full = ENGINE.evaluate(atCeiling, assessment(atCeiling, 700), v1);
        assertThat(full.outcome()).isEqualTo(EvaluationOutcome.APPROVE);
        assertThat(full.approved()).contains(eur(10_000_00));
        assertThat(full.rules()).extracting(EvaluationResult.RuleResult::state).doesNotContain(RuleState.UNASSESSED);
        SnapshotContent above = snapshot(eur(12_000_00));
        EvaluationResult ceiling = ENGINE.evaluate(above, assessment(above, 700), v1);
        assertThat(ceiling.approved()).contains(eur(10_000_00));
        assertThat(ceiling.reasons()).containsExactly(ReasonCode.AUTO_APPROVAL_CEILING);
        EvaluationResult capped = ENGINE.evaluate(above, assessment(above, 550), v1);
        assertThat(capped.approved()).contains(eur(5_000_00));
        assertThat(capped.reasons()).containsExactly(ReasonCode.SCORE_INSUFFICIENT);
        EvaluationResult referred = ENGINE.evaluate(above, assessment(above, 500), v1);
        assertThat(referred.outcome()).isEqualTo(EvaluationOutcome.REFER);
        assertThat(referred.reasons()).containsExactly(ReasonCode.RISK_REFERRAL, ReasonCode.SCORE_INSUFFICIENT);
        EvaluationResult declined = ENGINE.evaluate(above, assessment(above, 400), v1);
        assertThat(declined.outcome()).isEqualTo(EvaluationOutcome.DECLINE);
        assertThat(declined.reasons()).containsExactly(ReasonCode.SCORE_INSUFFICIENT, ReasonCode.RISK_REFERRAL);
    }

    // ------------------------------------------------------------------ missing data never approves (INV-CRD-10)

    @Test
    @DisplayName("a bureau outage: v1's fallback rule REFERs with CRD-SOURCE-UNAVAILABLE, whatever the figures")
    void theFallbackRefers() {
        SnapshotContent snapshot = snapshot(bureauUnavailable(clean()), REQUESTED);
        EvaluationResult result = ENGINE.evaluate(snapshot, assessment(snapshot, EvaluationFixtures.unaffordable(),
                EvaluationFixtures.unexposed(), 700), CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.REFER);
        assertThat(result.reasons()).containsExactly(ReasonCode.SOURCE_UNAVAILABLE);
        assertThat(result.rules().get(0).state()).isEqualTo(RuleState.TRIGGERED);
        assertThat(result.fallbackApplied()).as("the policy's own rule decided").isFalse();
    }

    @Test
    @DisplayName("the DECLINE variant: a policy declaring DECLINE declines with CRD-SOURCE-UNAVAILABLE")
    void theFallbackDeclines() {
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(CreditProduct.PERSONAL_LOAN);
        rules.set(0, rule("SOURCE_UNAVAILABLE_FALLBACK", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE),
                PolicyOperator.IS_PRESENT, new CreditPolicy.Operand.None(), PolicyEffect.DECLINE,
                ReasonCode.SOURCE_UNAVAILABLE));
        CreditPolicy declining = new CreditPolicy(CreditProduct.PERSONAL_LOAN, 900, eur(100_00), 300, eur(40_000_00),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN).maximumDataAge(), UnavailableFallback.DECLINE,
                eur(10_000_00), rules);
        SnapshotContent snapshot = snapshot(bureauUnavailable(clean()), REQUESTED);
        EvaluationResult result = ENGINE.evaluate(snapshot, assessment(snapshot, EvaluationFixtures.unaffordable(),
                EvaluationFixtures.unexposed(), 700), declining);
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.DECLINE);
        assertThat(result.reasons()).containsExactly(ReasonCode.SOURCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a partial answer decided explicitly: an ABSENT verified income REFERs by v1's own rule")
    void aPartialAnswerDecidedExplicitly() {
        SnapshotContent snapshot = snapshot(with(clean(), CreditAttributeCode.FINDATA_MONTHLY_INCOME,
                new AttributeValue.Absent()), REQUESTED);
        EvaluationResult result = ENGINE.evaluate(snapshot, assessment(snapshot, 700),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.REFER);
        assertThat(result.reasons()).containsExactly(ReasonCode.INCOME_UNVERIFIED);
        assertThat(result.fallbackApplied()).isFalse();
    }

    @Test
    @DisplayName("a partial answer no rule decides: an approval resting on it becomes the declared fallback, recorded")
    void aPartialAnswerNoRuleDecides() {
        SnapshotContent snapshot = snapshot(with(clean(), CreditAttributeCode.BUREAU_DEFAULTS_72M,
                new AttributeValue.Absent()), REQUESTED);
        EvaluationResult result = ENGINE.evaluate(snapshot, assessment(snapshot, 700),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.REFER);
        assertThat(result.reasons()).containsExactly(ReasonCode.SOURCE_UNAVAILABLE);
        assertThat(result.fallbackApplied()).isTrue();
        assertThat(result.approved()).isEmpty();
        assertThat(result.rules().get(2).ruleCode()).isEqualTo("PRIOR_DEFAULT");
        assertThat(result.rules().get(2).state()).isEqualTo(RuleState.UNASSESSED);
        SnapshotContent capped = snapshot(with(clean(), CreditAttributeCode.BUREAU_DEFAULTS_72M,
                new AttributeValue.Absent()), REQUESTED);
        EvaluationResult withCap = ENGINE.evaluate(capped, assessment(capped, 550),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));
        assertThat(withCap.reasons()).as("a triggered cap's code first, then the fallback's")
                .containsExactly(ReasonCode.SCORE_INSUFFICIENT, ReasonCode.SOURCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("an adverse outcome is never softened by missing data: it stands, the fallback unused")
    void anAdverseOutcomeStands() {
        SnapshotContent snapshot = snapshot(with(clean(), CreditAttributeCode.BUREAU_DEFAULTS_72M,
                new AttributeValue.Absent()), REQUESTED);
        EvaluationResult result = ENGINE.evaluate(snapshot, assessment(snapshot, 400),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));
        assertThat(result.outcome()).isEqualTo(EvaluationOutcome.DECLINE);
        assertThat(result.fallbackApplied()).isFalse();
        assertThat(result.reasons()).doesNotContain(ReasonCode.SOURCE_UNAVAILABLE);
    }

    @Test
    @DisplayName("an attribute the snapshot was never given is an evaluation error, never a default (INV-CRD-07)")
    void aMissingAttributeIsAnError() {
        SnapshotContent snapshot = snapshot(without(clean(), CreditAttributeCode.BUREAU_DEFAULTS_72M), REQUESTED);
        assertThatThrownBy(() -> ENGINE.evaluate(snapshot, assessment(snapshot, 700),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN)))
                .isInstanceOf(MissingAttributeException.class);
    }

    // ------------------------------------------------------------------ what cannot be represented

    private final List<EvaluationResult.RuleResult> rules = List.of(
            new EvaluationResult.RuleResult(1, "ONLY", PolicyEffect.DECLINE, RuleState.TRIGGERED));

    @Test
    @DisplayName("an adverse result without a reason (INV-CRD-02), or with a non-adverse one")
    void adverseWithoutAReason() {
        for (EvaluationOutcome outcome : List.of(EvaluationOutcome.REFER, EvaluationOutcome.DECLINE,
                EvaluationOutcome.HARD_DECLINE)) {
            assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, outcome, REQUESTED,
                    Optional.empty(), List.of(), false, rules)).withMessageContaining("INV-CRD-02");
            assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, outcome, REQUESTED,
                    Optional.empty(), List.of(ReasonCode.AUTO_APPROVAL_CEILING), false, rules));
        }
    }

    @Test
    @DisplayName("an approval without an amount, above the request, reduced without its reason, or by the fallback")
    void incoherentApprovals() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, EvaluationOutcome.APPROVE,
                REQUESTED, Optional.empty(), List.of(), false, rules));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, EvaluationOutcome.APPROVE,
                REQUESTED, Optional.of(eur(10_000_01)), List.of(), false, rules));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, EvaluationOutcome.APPROVE,
                REQUESTED, Optional.of(eur(5_000_00)), List.of(), false, rules));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, EvaluationOutcome.APPROVE,
                REQUESTED, Optional.of(REQUESTED), List.of(ReasonCode.SOURCE_UNAVAILABLE), true, rules));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, EvaluationOutcome.DECLINE,
                REQUESTED, Optional.of(REQUESTED), List.of(ReasonCode.SCORE_INSUFFICIENT), false, rules));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationResult(1, EvaluationOutcome.DECLINE,
                REQUESTED, Optional.empty(), List.of(ReasonCode.SCORE_INSUFFICIENT, ReasonCode.SCORE_INSUFFICIENT),
                false, rules));
    }

    @Test
    @DisplayName("inputs that do not belong together: another product's policy, another request's assessment")
    void inputsThatDoNotBelong() {
        SnapshotContent snapshot = snapshot(REQUESTED);
        CreditAssessment assessment = assessment(snapshot, 700);
        assertThatIllegalArgumentException().isThrownBy(() -> ENGINE.evaluate(snapshot, assessment,
                CreditPolicyV1.policy(CreditProduct.CREDIT_LINE)));
        CreditAssessment another = new CreditAssessment(assessment.id(), assessment.snapshot(),
                java.util.UUID.randomUUID(), new byte[32], assessment.versions(), assessment.currency(),
                assessment.affordability(), assessment.exposure(), 700, assessment.assessedAt());
        assertThatIllegalArgumentException().isThrownBy(() -> ENGINE.evaluate(snapshot, another,
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN)));
    }
}
