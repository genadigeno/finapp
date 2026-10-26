package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The explanation door's content (`P13-TSK-023`; ADR-0108 decisions 3-4; {@code INV-RSK-04}; {@code DESIGN-023} D6):
 * built from the sealed rows and the pinned version's band list only - the rules in their order with each reading and
 * contribution, the band reached by position and decision (never its bounds), the step that decided (a score band, a
 * hard rule, the fail-safe) and the fail-safe's band; and <strong>never a re-evaluation</strong> - rows planted unlike
 * anything the engine would answer are explained as planted. Over HTTP the read is {@code RiskAssessmentApiTest}'s.
 */
@DisplayName("the explanation: from the sealed rows only - rules, readings, contributions, the band by position, the step"
        + " that decided, the fail-safe (P13-TSK-023)")
class ExplanationTest {

    private static final CurrencyCode EUR = RiskPolicyFixtures.EUR;

    /** One weighted amount rule (300 from EUR 100) and a rule that never fires; ALLOW to 299, REVIEW from 300. */
    private static final RiskPolicy BANDED = policy(new RiskPolicyFixtures.Rules()
            .weighted(RuleKind.AMOUNT_BAND, java.util.Map.of("threshold_minor", RiskPolicyFixtures.minor(100_00)), 300)
            .weighted(RuleKind.AMOUNT_BAND, java.util.Map.of("threshold_minor", RiskPolicyFixtures.minor(1_000_000_00)),
                    100),
            List.of(new ScoreBand(0, 299, RiskDecision.ALLOW), new ScoreBand(300, 1000, RiskDecision.REVIEW)));

    /** A hard DENY from EUR 100 over a single ALLOW band - the hard rule beats the band. */
    private static final RiskPolicy HARD = policy(new RiskPolicyFixtures.Rules()
            .deciding(RuleKind.AMOUNT_BAND, java.util.Map.of("threshold_minor", RiskPolicyFixtures.minor(100_00)),
                    RiskDecision.DENY),
            List.of(new ScoreBand(0, 1000, RiskDecision.ALLOW)));

    @Test
    @DisplayName("a score band decided: every rule in declared order with its outcome, its reading and its contribution;"
            + " the score; the band reached named 2 of 2, REVIEW; no hard rule named")
    void aScoreBandDecided() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(BANDED);
        RiskAssessment sealed = seal(world, 150_00);

        Explanation explanation = Explanation.of(sealed, Optional.of(BANDED));
        assertThat(explanation.decision()).isEqualTo(RiskDecision.REVIEW).isEqualTo(sealed.decision());
        assertThat(explanation.step()).isEqualTo(Explanation.Step.SCORE_BAND);
        assertThat(explanation.decidingRules()).isEmpty();
        assertThat(explanation.score()).isEqualTo(300);
        assertThat(explanation.band()).contains(new Explanation.Band(2, 2, RiskDecision.REVIEW));
        assertThat(explanation.rules()).extracting(Explanation.Rule::ordinal).containsExactly(1, 2);
        assertThat(explanation.rules()).extracting(Explanation.Rule::outcome)
                .containsExactly(RuleOutcome.FIRED, RuleOutcome.NOT_FIRED);
        assertThat(explanation.rules()).extracting(Explanation.Rule::contribution).containsExactly(300, 0);
        assertThat(explanation.rules()).extracting(Explanation.Rule::judged)
                .containsExactlyElementsOf(sealed.hits().stream().map(RuleHit::judged).toList());
        assertThat(explanation.failSafe()).isEmpty();
        assertThat(explanation.permits()).isFalse();
    }

    @Test
    @DisplayName("a hard rule decided: the fired rule's DENY beats the band's ALLOW - the step HARD_RULE, the rule that"
            + " carried it named, the band reached still named (1 of 1, ALLOW)")
    void aHardRuleDecided() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(HARD);
        RiskAssessment sealed = seal(world, 150_00);

        Explanation explanation = Explanation.of(sealed, Optional.of(HARD));
        assertThat(explanation.decision()).isEqualTo(RiskDecision.DENY);
        assertThat(explanation.step()).isEqualTo(Explanation.Step.HARD_RULE);
        assertThat(explanation.decidingRules()).containsExactly(1);
        assertThat(explanation.band()).contains(new Explanation.Band(1, 1, RiskDecision.ALLOW));
        assertThat(explanation.rules().get(0).hardDecision()).contains(RiskDecision.DENY);
    }

    @Test
    @DisplayName("the fail-safe decided: a timeout names its cause, its reason and the pinned version's fail-safe band by"
            + " position; no policy at all names the cause and no band - the compiled ALLOW")
    void theFailSafeDecided() {
        EvaluationWorld world = new EvaluationWorld();
        RiskAssessment unpoliced = seal(world, 50_00);
        Explanation none = Explanation.of(unpoliced, Optional.empty());
        assertThat(none.step()).isEqualTo(Explanation.Step.FAIL_SAFE);
        assertThat(none.failSafe()).hasValueSatisfying(failSafe -> {
            assertThat(failSafe.cause()).isEqualTo(UnavailableCause.NO_POLICY);
            assertThat(failSafe.reason()).isEqualTo(ReasonCode.NO_POLICY_ACTIVE);
            assertThat(failSafe.band()).isEmpty();
        });
        assertThat(none.rules()).isEmpty();

        world.activate(BANDED);
        world.failSerialise.push(EvaluationWorld.timeout());
        RiskAssessment timedOut = seal(world, 2_000_00);
        Explanation timeout = Explanation.of(timedOut, Optional.of(BANDED));
        assertThat(timeout.step()).isEqualTo(Explanation.Step.FAIL_SAFE);
        assertThat(timeout.decision()).isEqualTo(timedOut.decision());
        assertThat(timeout.failSafe()).hasValueSatisfying(failSafe -> {
            assertThat(failSafe.cause()).isEqualTo(timedOut.unavailable().orElseThrow());
            assertThat(failSafe.reason()).isEqualTo(ReasonCode.RISK_UNAVAILABLE);
            assertThat(failSafe.band()).contains(new Explanation.Band(2, 2, RiskDecision.DENY));
        });
        assertThat(timeout.band()).as("no score band answered a fail-safe").isEmpty();
    }

    @Test
    @DisplayName("from the sealed rows only: rows planted unlike anything the engine would answer are explained as planted"
            + " - a planted re-evaluation is refused, the explanation is what was decided, not what the engine says now")
    void aPlantedReEvaluationIsRefused() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(BANDED);
        RiskAssessment sealed = seal(world, 150_00);
        List<RuleHit> planted = new ArrayList<>();
        planted.add(new RuleHit(1, RuleKind.AMOUNT_BAND, RuleOutcome.FIRED, 300, Optional.empty(), "amount=99999"));
        planted.add(new RuleHit(2, RuleKind.AMOUNT_BAND, RuleOutcome.FIRED, 0, Optional.empty(), "amount=88888"));
        RiskAssessment tampered = withHits(sealed, planted);

        Explanation explanation = Explanation.of(tampered, Optional.of(BANDED));
        assertThat(explanation.rules()).extracting(Explanation.Rule::judged)
                .as("the sealed readings, never the engine's re-reading of the sealed inputs")
                .containsExactly("amount=99999", "amount=88888");
        assertThat(explanation.rules()).extracting(Explanation.Rule::outcome)
                .containsExactly(RuleOutcome.FIRED, RuleOutcome.FIRED);
        assertThat(RiskEngineV1.evaluate(BANDED, CanonicalEvaluation.parseInputs(tampered.inputsCanonical())).hits())
                .as("the engine would have answered otherwise - which is the point")
                .isNotEqualTo(planted);
        assertThat(AssessmentReplay.replay(tampered, Optional.of(BANDED)).verdict())
                .as("finding the difference is the replay's work, not the explanation's")
                .isEqualTo(AssessmentReplay.Outcome.DIVERGED);
    }

    @Test
    @DisplayName("a band is named by its position and its decision, never its bounds; a pinned version that cannot be read"
            + " leaves the band unnamed, never guessed")
    void aBandHasNoBounds() {
        assertThat(Arrays.stream(Explanation.Band.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("position", "of", "decision");
        EvaluationWorld world = new EvaluationWorld();
        world.activate(BANDED);
        Explanation unread = Explanation.of(seal(world, 150_00), Optional.empty());
        assertThat(unread.band()).isEmpty();
        assertThat(unread.step()).isEqualTo(Explanation.Step.SCORE_BAND);
    }

    // ------------------------------------------------------------------

    private static RiskAssessment seal(EvaluationWorld world, long minor) {
        return RiskEvaluationTest.sealed(world,
                world.evaluation.evaluate(UOW, EvaluationWorld.transfer(Money.ofMinorUnits(minor, EUR))));
    }

    private static RiskAssessment withHits(RiskAssessment sealed, List<RuleHit> hits) {
        return new RiskAssessment(sealed.id(), sealed.operation(), sealed.commandRef(), sealed.subjects(),
                sealed.amount(), sealed.pin(), sealed.engine(), sealed.score(), sealed.decision(), sealed.unavailable(),
                sealed.challengeSatisfied(), sealed.velocity(), sealed.cutAt(), sealed.inputsCanonical(),
                sealed.inputsSha256(), sealed.verdictSha256(), hits, sealed.reasons(), sealed.latencyMicros(),
                sealed.correlationId(), sealed.causationId(), sealed.assessedAt());
    }

    private static RiskPolicy policy(RiskPolicyFixtures.Rules rules, List<ScoreBand> bands) {
        return new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, EUR, rules.list(), bands, List.of(),
                RiskPolicyFixtures.failSafe(1_000_00, RiskDecision.ALLOW, RiskDecision.DENY),
                Optional.of(RiskAssurance.MULTI_FACTOR),
                Optional.of(Money.ofMinorUnits(RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR, EUR)), Optional.empty());
    }
}
