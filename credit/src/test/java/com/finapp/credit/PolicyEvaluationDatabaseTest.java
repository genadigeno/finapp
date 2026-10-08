package com.finapp.credit;

import static com.finapp.credit.ScorecardFixtures.CLOCK;
import static com.finapp.credit.ScorecardFixtures.IDS;
import static com.finapp.credit.ScorecardFixtures.activate;
import static com.finapp.credit.ScorecardFixtures.correlation;
import static com.finapp.credit.ScorecardFixtures.count;
import static com.finapp.credit.ScorecardFixtures.employee;
import static com.finapp.credit.ScorecardFixtures.inOneTransaction;
import static com.finapp.credit.ScorecardFixtures.race;
import static com.finapp.credit.ScorecardFixtures.refused;
import static com.finapp.credit.ScorecardFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The policy evaluation against a real database (`P10-TSK-013`; {@code INV-CRD-06}, {@code INV-CRD-02},
 * {@code INV-CRD-04}, {@code INV-CRD-10}): born once per assessment under every race with every rule's result beside
 * it, the engine version recorded, an adverse outcome without a reason refused by the table itself, never changed by any
 * role.
 */
@Tag("database")
@DisplayName("the policy evaluation (P10-TSK-013)")
class PolicyEvaluationDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CreditAssessments.Terms TERMS =
            CreditAssessments.Terms.of(CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));

    @BeforeAll
    static void v1IsInForce() {
        CreditPolicyFixtures.activateV1(CreditProduct.PERSONAL_LOAN, employee());
    }

    private static PolicyEvaluations evaluations(EngineVersions engines) {
        return new PolicyEvaluations(new JdbcPolicyEvaluationStore(), new JdbcCreditPolicyStore(), engines, IDS, CLOCK);
    }

    private static CreditAssessment assess(DecisionSnapshot snapshot) {
        return inOneTransaction(uow -> CreditAssessmentDatabaseTest.assessments().assess(uow, snapshot, TERMS, correlation()))
                .assessment();
    }

    @Test
    @DisplayName("ten evaluators of one assessment leave one evaluation and one set of rule results, and answer with it - counted")
    void tenEvaluatorsLeaveOneEvaluation() throws Exception {
        DecisionSnapshot snapshot = snapshot(activate(801));
        CreditAssessment assessment = assess(snapshot);
        PolicyEvaluations evaluations = evaluations(EngineVersions.STANDARD);
        List<PolicyEvaluations.Evaluated> outcomes = race(10,
                () -> inOneTransaction(uow -> evaluations.evaluate(uow, snapshot, assessment)));
        assertThat(outcomes.stream().filter(outcome -> !outcome.replayed())).hasSize(1);
        assertThat(outcomes.stream().map(outcome -> outcome.evaluation().id()).distinct()).hasSize(1);
        String id = outcomes.get(0).evaluation().id().value().toString();
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation WHERE assessment_id = '"
                + assessment.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation_rule WHERE evaluation_id = '" + id + "'"))
                .isEqualTo(CreditPolicyV1.rules(CreditProduct.PERSONAL_LOAN).size());
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.evaluation().result())
                .isEqualTo(outcomes.get(0).evaluation().result()));
    }

    @Test
    @DisplayName("the result is recorded exactly - outcome, amount, reasons, engine version, every rule - and read back as computed")
    void theEvaluationIsRecorded() {
        DecisionSnapshot clean = snapshot(activate(802), EvaluationFixtures.clean());
        PolicyEvaluation approved = inOneTransaction(
                uow -> evaluations(EngineVersions.STANDARD).evaluate(uow, clean, assess(clean))).evaluation();
        assertThat(approved.result().outcome()).isEqualTo(EvaluationOutcome.APPROVE);
        assertThat(approved.result().approved()).contains(Money.ofMinorUnits(1_000_000, EUR));
        assertThat(approved.result().engineVersion()).isEqualTo(1);
        assertThat(approved.policyVersion()).isEqualTo(CreditPolicyV1.PERSONAL_LOAN_ID);
        DecisionSnapshot golden = snapshot(activate(803));
        CreditAssessment assessment = assess(golden);
        PolicyEvaluation referred = inOneTransaction(
                uow -> evaluations(EngineVersions.STANDARD).evaluate(uow, golden, assessment)).evaluation();
        assertThat(referred.result().outcome()).isEqualTo(EvaluationOutcome.REFER);
        assertThat(referred.result().reasons()).containsExactly(ReasonCode.INCOME_UNVERIFIED);
        assertThat(referred.result().rules()).extracting(EvaluationResult.RuleResult::ruleCode)
                .containsExactlyElementsOf(CreditPolicyV1.rules(CreditProduct.PERSONAL_LOAN).stream()
                        .map(CreditPolicy.PolicyRule::ruleCode).toList());
        assertThat(referred.result().rules().get(2).state()).as("PRIOR_DEFAULT read an ABSENT count")
                .isEqualTo(EvaluationResult.RuleState.UNASSESSED);
        PolicyEvaluation read = inOneTransaction(uow -> new JdbcPolicyEvaluationStore().byAssessment(uow, assessment.id()))
                .orElseThrow();
        assertThat(read.sameConclusion(referred)).isTrue();
        assertThat(read.evaluatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a disagreeing second computation is a defect, never a replacement")
    void aDisagreeingRecomputationIsADefect() {
        DecisionSnapshot snapshot = snapshot(activate(804), EvaluationFixtures.clean());
        CreditAssessment assessment = assess(snapshot);
        inOneTransaction(uow -> evaluations(EngineVersions.STANDARD).evaluate(uow, snapshot, assessment));
        PolicyEvaluator disagreeing = new PolicyEvaluator() {
            @Override
            public int engineVersion() {
                return 1;
            }

            @Override
            public EvaluationResult evaluate(SnapshotContent content, CreditAssessment assessed, CreditPolicy policy) {
                EvaluationResult honest = new PolicyEvaluatorV1().evaluate(content, assessed, policy);
                return new EvaluationResult(1, EvaluationOutcome.REFER, honest.requested(), Optional.empty(),
                        List.of(ReasonCode.RISK_REFERRAL), false, honest.rules());
            }
        };
        assertThatIllegalStateException().isThrownBy(() -> inOneTransaction(uow -> evaluations(
                new EngineVersions(List.of(disagreeing))).evaluate(uow, snapshot, assessment)))
                .withMessageContaining("born once");
    }

    @Test
    @DisplayName("the table refuses what the domain cannot represent - by raw SQL, an adverse outcome without a reason first")
    void theTableRefusesTheUnrepresentable() throws SQLException {
        DecisionSnapshot snapshot = snapshot(activate(805));
        CreditAssessment assessment = assess(snapshot);
        try (Connection application = DatabaseRoles.application()) {
            String prefix = "INSERT INTO credit.policy_evaluation (id, assessment_id, decision_request_id, policy_version_id,"
                    + " engine_version, outcome, currency, requested_minor, approved_minor, reason_codes, fallback_applied,"
                    + " evaluated_at) VALUES ('" + IDS.next() + "', '" + assessment.id().value() + "', '"
                    + assessment.decisionRequest() + "', '" + CreditPolicyV1.PERSONAL_LOAN_ID.value() + "', 1, ";
            String now = ", now())";
            for (String outcome : List.of("REFER", "DECLINE", "HARD_DECLINE")) {
                refused(application, prefix + "'" + outcome + "', 'EUR', 100000, NULL, '{}'::text[], false" + now, "23514");
            }
            refused(application, prefix + "'APPROVE', 'EUR', 100000, NULL, '{}'::text[], false" + now, "23514");
            refused(application, prefix + "'APPROVE', 'EUR', 100000, 100001, '{}'::text[], false" + now, "23514");
            refused(application, prefix + "'APPROVE', 'EUR', 100000, 50000, '{}'::text[], false" + now, "23514");
            refused(application, prefix + "'DECLINE', 'EUR', 100000, 50000, '{CRD-INSOLVENCY}'::text[], false" + now, "23514");
            refused(application, prefix + "'REFER', 'EUR', 100000, NULL, '{CRD-RISK-REFERRAL}'::text[], true" + now, "23514");
            refused(application, prefix + "'APPROVE', 'EUR', 100000, 100000, '{}'::text[], true" + now, "23514");
            refused(application, prefix + "'DECLINE', 'EUR', 100000, NULL, '{CRD-NOT-A-CODE}'::text[], false" + now, "P0001");
            refused(application, prefix + "'DECLINE', 'EUR', 100000, NULL, '{CRD-INSOLVENCY,CRD-INSOLVENCY}'::text[], false"
                    + now, "P0001");
            refused(application, prefix + "'MAYBE', 'EUR', 100000, NULL, '{CRD-INSOLVENCY}'::text[], false" + now, "23514");
        }
    }

    @Test
    @DisplayName("no role changes an evaluation or its rule results; a rule result joins only in its evaluation's transaction")
    void noRoleChangesAnEvaluation() throws SQLException {
        DecisionSnapshot snapshot = snapshot(activate(806));
        CreditAssessment assessment = assess(snapshot);
        PolicyEvaluation evaluation = inOneTransaction(
                uow -> evaluations(EngineVersions.STANDARD).evaluate(uow, snapshot, assessment)).evaluation();
        String id = evaluation.id().value().toString();
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.policy_evaluation SET outcome = 'APPROVE' WHERE id = '" + id + "'", "P0001");
            refused(owner, "DELETE FROM credit.policy_evaluation WHERE id = '" + id + "'", "P0001");
            // Since V013 (P10-TSK-018) the underwriting case references the evaluation, so a plain TRUNCATE meets its
            // foreign key first (0A000); naming the case's tables too reaches the triggers.
            refused(owner, "TRUNCATE credit.policy_evaluation_rule, credit.policy_evaluation", "0A000");
            refused(owner, "TRUNCATE credit.policy_evaluation_rule, credit.policy_evaluation, credit.underwriting_case_event,"
                    + " credit.underwriting_case", "P0001");
            refused(owner, "UPDATE credit.policy_evaluation_rule SET triggered = false WHERE evaluation_id = '" + id + "'",
                    "P0001");
            refused(owner, "DELETE FROM credit.policy_evaluation_rule WHERE evaluation_id = '" + id + "'", "P0001");
            refused(owner, "TRUNCATE credit.policy_evaluation_rule", "P0001");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE credit.policy_evaluation SET outcome = 'APPROVE' WHERE id = '" + id + "'", "42501");
            refused(application, "INSERT INTO credit.policy_evaluation_rule (evaluation_id, ordinal, rule_code, effect,"
                    + " triggered, assessed) VALUES ('" + id + "', 99, 'LATE_RULE', 'REFER', true, true)", "P0001");
        }
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation_rule WHERE evaluation_id = '" + id + "'"))
                .isEqualTo(CreditPolicyV1.rules(CreditProduct.PERSONAL_LOAN).size());
    }

    @Test
    @DisplayName("an evaluation publishes nothing - the decision's event carries the outcome")
    void anEvaluationPublishesNothing() throws SQLException {
        DecisionSnapshot snapshot = snapshot(activate(807));
        CreditAssessment assessment = assess(snapshot);
        long before = count("SELECT count(*) FROM platform.outbox_event");
        inOneTransaction(uow -> evaluations(EngineVersions.STANDARD).evaluate(uow, snapshot, assessment));
        assertThat(count("SELECT count(*) FROM platform.outbox_event")).isEqualTo(before);
    }
}
