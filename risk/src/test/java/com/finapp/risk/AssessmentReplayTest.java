package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.UOW;
import static com.finapp.risk.EvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.testing.RiskPolicyFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replay from the seal (`P13-TSK-004`; ADR-0109 decision 4; {@code INV-RSK-01}): a judged assessment and every kind of
 * fail-safe replay {@code IDENTICAL} under their pinned version and engine from the sealed inputs alone; a perturbed
 * sealed input, a perturbed rule-hit row and a missing pinned version each replay {@code DIVERGED}, naming the kinds and
 * the first rule that diverged - never a figure.
 */
@DisplayName("replay: IDENTICAL from the seal, DIVERGED naming what diverged (P13-TSK-004)")
class AssessmentReplayTest {

    private static final RiskPolicy TRANSFER = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);

    @Test
    @DisplayName("a judged assessment and every fail-safe - no policy, an unread pin, a timeout, another currency -"
            + " replay IDENTICAL")
    void everySealReplaysIdentical() {
        EvaluationWorld world = new EvaluationWorld();
        RiskEvaluation.Assessed noPolicy = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(10_00)));
        world.activate(TRANSFER);
        RiskEvaluation.Assessed judged = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(1_200_00)));
        world.failPin.push(EvaluationWorld.broken());
        RiskEvaluation.Assessed unpinned = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(10_00)));
        world.failSerialise.push(EvaluationWorld.timeout());
        RiskEvaluation.Assessed timedOut = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(2_000_00)));
        RiskEvaluation.Assessed dollars = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(
                com.finapp.sharedkernel.money.Money.ofMinorUnits(5_00,
                        com.finapp.sharedkernel.money.CurrencyCode.of("USD"))));

        for (RiskEvaluation.Assessed assessed : List.of(noPolicy, judged, unpinned, timedOut, dollars)) {
            RiskAssessment sealed = RiskEvaluationTest.sealed(world, assessed);
            Optional<RiskPolicy> pinned = sealed.pin().map(pin -> TRANSFER);
            AssessmentReplay.Replay replay = AssessmentReplay.replay(sealed, pinned);
            assertThat(replay.verdict()).as(sealed.toString()).isEqualTo(AssessmentReplay.Outcome.IDENTICAL);
            assertThat(replay.diverged()).isEmpty();
        }
    }

    @Test
    @DisplayName("a perturbed sealed input - the amount re-sealed at EUR 10,000 - replays DIVERGED: the decision, the score"
            + " and the hits, the first diverged rule named")
    void aPerturbedInputDiverges() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(TRANSFER);
        RiskAssessment sealed = RiskEvaluationTest.sealed(world,
                world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(1_200_00))));
        EvaluationInputs read = CanonicalEvaluation.parseInputs(sealed.inputsCanonical());
        EvaluationInputs perturbed = new EvaluationInputs(read.operation(), read.subjects(), Reading.of(eur(10_000_00)),
                read.velocity(), read.payeeFirstSeen(), read.deviceFirstSeen(), read.sessionAssurance(),
                read.authFailures(), read.credentialChange(), read.recovery(), read.accountAgeDays(), read.kycApproved(),
                read.screeningHit(), read.profileRating(), read.merchantSuspended(), read.corridor(), read.monitoring());
        String text = CanonicalEvaluation.render(perturbed);
        RiskAssessment tampered = withInputs(sealed, text);

        AssessmentReplay.Replay replay = AssessmentReplay.replay(tampered, Optional.of(TRANSFER));
        assertThat(replay.verdict()).isEqualTo(AssessmentReplay.Outcome.DIVERGED);
        assertThat(replay.diverged()).contains(AssessmentReplay.Divergence.DECISION, AssessmentReplay.Divergence.HITS)
                .doesNotContain(AssessmentReplay.Divergence.SEAL);
        assertThat(replay.firstDivergedRule()).hasValue(1);
        assertThat(replay.toString()).doesNotContain("1000000");
    }

    @Test
    @DisplayName("a perturbed rule-hit row replays DIVERGED: the rows no longer render to the sealed digest (SEAL) and"
            + " differ from the replay (HITS) at the rule named; a pinned version that cannot be read is POLICY")
    void aPerturbedRowAndAMissingPolicyDiverge() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(TRANSFER);
        RiskAssessment sealed = RiskEvaluationTest.sealed(world,
                world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(1_200_00))));
        List<RuleHit> hits = new ArrayList<>(sealed.hits());
        RuleHit third = hits.get(2);
        hits.set(2, new RuleHit(third.ordinal(), third.kind(), third.outcome(), third.contribution(),
                third.hardDecision(), "velocity:SOURCE_ACCOUNT:D1:count=7"));
        RiskAssessment tampered = new RiskAssessment(sealed.id(), sealed.operation(), sealed.commandRef(),
                sealed.subjects(), sealed.amount(), sealed.pin(), sealed.engine(), sealed.score(), sealed.decision(),
                sealed.unavailable(), sealed.challengeSatisfied(), sealed.velocity(), sealed.cutAt(),
                sealed.inputsCanonical(), sealed.inputsSha256(), sealed.verdictSha256(), hits, sealed.reasons(),
                sealed.latencyMicros(), sealed.correlationId(), sealed.causationId(), sealed.assessedAt());
        assertThat(tampered.sealHolds()).isFalse();

        AssessmentReplay.Replay replay = AssessmentReplay.replay(tampered, Optional.of(TRANSFER));
        assertThat(replay.diverged()).containsExactly(AssessmentReplay.Divergence.SEAL, AssessmentReplay.Divergence.HITS);
        assertThat(replay.firstDivergedRule()).hasValue(3);

        assertThat(AssessmentReplay.replay(sealed, Optional.empty()).diverged())
                .containsExactly(AssessmentReplay.Divergence.POLICY);
        assertThat(AssessmentReplay.replay(withInputs(sealed, "{\"format\":\"1\"}"), Optional.of(TRANSFER)).diverged())
                .containsExactly(AssessmentReplay.Divergence.INPUTS);
    }

    private static RiskAssessment withInputs(RiskAssessment sealed, String text) {
        return new RiskAssessment(sealed.id(), sealed.operation(), sealed.commandRef(), sealed.subjects(),
                sealed.amount(), sealed.pin(), sealed.engine(), sealed.score(), sealed.decision(), sealed.unavailable(),
                sealed.challengeSatisfied(), sealed.velocity(), sealed.cutAt(), text, CanonicalEvaluation.sha256(text),
                sealed.verdictSha256(), sealed.hits(), sealed.reasons(), sealed.latencyMicros(), sealed.correlationId(),
                sealed.causationId(), sealed.assessedAt());
    }
}
