package com.finapp.credit;

import static com.finapp.credit.EvaluationFixtures.assessment;
import static com.finapp.credit.EvaluationFixtures.clean;
import static com.finapp.credit.EvaluationFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replay selects the pinned engine (`P10-TSK-013`; {@code INV-CRD-01}, PHASE_10_PLAN.md section 14 row 26): a planted
 * engine 2, differing from engine 1 in one operator, leaves every engine-1 evaluation identical - an engine is added
 * beside the old, never in its place.
 */
@DisplayName("engine versions: replay selects the pinned engine (P10-TSK-013)")
class EngineVersionTest {

    /** Engine 2 as a successor might write it: engine 1's semantics, except that {@code LT} includes its bound. */
    private static final class PlantedEngine2 implements PolicyEvaluator {

        private final PolicyEvaluator engine1 = new PolicyEvaluatorV1();

        @Override
        public int engineVersion() {
            return 2;
        }

        @Override
        public EvaluationResult evaluate(SnapshotContent snapshot, CreditAssessment assessment, CreditPolicy policy) {
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            for (CreditPolicy.PolicyRule rule : policy.rules()) {
                rules.add(rule.operator() != PolicyOperator.LT ? rule : new CreditPolicy.PolicyRule(rule.ruleCode(),
                        rule.subject(), PolicyOperator.LE, rule.operand(), rule.effect(), rule.cap(), rule.reason()));
            }
            CreditPolicy rewritten = new CreditPolicy(policy.product(), policy.assessmentRateBps(),
                    policy.minimumDisposable(), policy.minimumPaymentRatioBps(), policy.maximumExposure(),
                    policy.maximumDataAge(), policy.unavailableFallback(), policy.autoApprovalCeiling(), rules);
            SnapshotContent asEngine1 = pinned(snapshot, 1);
            EvaluationResult result = engine1.evaluate(asEngine1,
                    assessment(asEngine1, assessment.affordability(), assessment.exposure(), assessment.score()), rewritten);
            return new EvaluationResult(2, result.outcome(), result.requested(), result.approved(), result.reasons(),
                    result.fallbackApplied(), result.rules());
        }
    }

    private static SnapshotContent pinned(SnapshotContent snapshot, int engine) {
        PinnedVersions versions = snapshot.versions();
        return new SnapshotContent(snapshot.decisionRequest(), snapshot.party(), snapshot.product(),
                snapshot.requestedAmount(), snapshot.termMonths(),
                new PinnedVersions(versions.policyVersion(), versions.modelVersion(), engine), snapshot.attributes());
    }

    /** A score exactly at v1's floor of 450: engine 1 refers it (not below 450), engine 2 declines it (450 "LT" 450). */
    private static EvaluationResult evaluate(EngineVersions engines, int pinnedEngine) {
        SnapshotContent snapshot = pinned(EvaluationFixtures.snapshot(clean(), eur(12_000_00)), pinnedEngine);
        return engines.engine(snapshot.versions().engineVersion()).evaluate(snapshot, assessment(snapshot, 450),
                CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN));
    }

    @Test
    @DisplayName("with engine 2 planted, an evaluation pinned to engine 1 is identical; one pinned to 2 differs at the bound")
    void aPlantedEngineLeavesEngine1Identical() {
        EngineVersions planted = new EngineVersions(List.of(new PolicyEvaluatorV1(), new PlantedEngine2()));
        EvaluationResult standard = evaluate(EngineVersions.STANDARD, 1);
        assertThat(evaluate(planted, 1)).isEqualTo(standard);
        assertThat(standard.engineVersion()).isEqualTo(1);
        assertThat(standard.outcome()).isEqualTo(EvaluationOutcome.REFER);
        EvaluationResult second = evaluate(planted, 2);
        assertThat(second.engineVersion()).isEqualTo(2);
        assertThat(second.outcome()).isEqualTo(EvaluationOutcome.DECLINE);
    }

    @Test
    @DisplayName("a version the build does not hold is a defect, never a fallback; every build holds engine 1, once")
    void theRegister() {
        assertThatIllegalStateException().isThrownBy(() -> EngineVersions.STANDARD.engine(2));
        assertThatIllegalStateException().isThrownBy(() -> EngineVersions.STANDARD.engine(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new EngineVersions(List.of(new PlantedEngine2())));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new EngineVersions(List.of(new PolicyEvaluatorV1(), new PolicyEvaluatorV1())));
        assertThat(EngineVersions.STANDARD.engine(1)).isInstanceOf(PolicyEvaluatorV1.class);
        assertThat(Optional.of(EngineVersions.STANDARD.engine(1).engineVersion())).contains(PolicyEvaluatorV1.VERSION);
    }
}
