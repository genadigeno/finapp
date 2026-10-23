package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.IDS;
import static com.finapp.risk.EvaluationWorld.UOW;
import static com.finapp.risk.EvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counted assessments as the velocity proof's evidence (`P13-TSK-004`, closing `P13-TSK-006`'s finding;
 * {@code INV-RSK-03}): every dimension provable - the party, the device and the merchant included, which the journal
 * cannot name; only {@code COUNTED} assessments are evidence, at their cut instant; the windows T-eval counted equal
 * them ({@code HELD}), and a count no assessment explains is a divergence.
 */
@DisplayName("the counted assessments are the velocity windows' evidence of record (P13-TSK-004)")
class AssessmentVelocityEvidenceTest {

    @Test
    @DisplayName("every dimension is provable; a refused movement and an uncounted one are no evidence; the windows"
            + " T-eval counted HOLD against the assessments - party, device and merchant windows included")
    void theWindowsEqualTheirAssessments() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CARD_PAYMENT);
        UUID party = IDS.next();
        UUID merchant = IDS.next();
        DeviceHash device = DeviceHash.of("a phone");
        for (int i = 0; i < 3; i++) {
            world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.CARD_PAYMENT, eur(20_00), party)
                    .sourceAccount(IDS.next()).merchant(merchant).device(device).build());
        }
        world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.CARD_PAYMENT, eur(10_000_00), party)
                .sourceAccount(IDS.next()).merchant(merchant).device(device).build());
        world.failSerialise.push(EvaluationWorld.timeout());
        world.failSerialise.push(EvaluationWorld.timeout());
        RiskEvaluation.Assessed uncounted = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.CARD_PAYMENT, eur(20_00), party).sourceAccount(IDS.next()).merchant(merchant)
                .device(device).build());
        assertThat(RiskEvaluationTest.sealed(world, uncounted).velocity()).isEqualTo(VelocityOutcome.UNCOUNTED);

        AssessmentVelocityEvidence evidence = new AssessmentVelocityEvidence(world.assessmentStore, world.windowStore);
        assertThat(evidence.provable()).isEqualTo(EnumSet.allOf(VelocityDimension.class));
        Instant later = EvaluationWorld.CLOCK.instant().plus(Duration.ofMinutes(1));
        world.windowStore.delegate.now = later;
        VelocityEvidence.Record record = evidence.read(UOW, later.minus(Duration.ofDays(31)));
        assertThat(record.movements()).as("three permitted and counted; the DENY and the UNCOUNTED are not").hasSize(3);
        assertThat(record.completeThrough()).isEqualTo(later);

        VelocityProof.Result proof = VelocityProof.judge(later, world.windowStore.live(UOW), record, evidence.provable(),
                VelocityProof.SKEW);
        assertThat(proof.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(proof.unprovable()).as("the party, device and merchant windows are judged now").isZero();
        assertThat(proof.judged()).isEqualTo(world.windowStore.live(UOW).size());
    }

    @Test
    @DisplayName("a window counted with no assessment behind it - a lost seal, a stray increment - diverges")
    void aCountNoAssessmentExplainsDiverges() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskEvaluationRequest request = EvaluationWorld.transfer(eur(10_00));
        world.evaluation.evaluate(UOW, request);
        VelocityWindows stray = new VelocityWindows(world.windowStore);
        stray.increment(UOW, stray.cut(UOW, request.movement()), RiskDecision.ALLOW, false);

        AssessmentVelocityEvidence evidence = new AssessmentVelocityEvidence(world.assessmentStore, world.windowStore);
        Instant later = EvaluationWorld.CLOCK.instant().plus(Duration.ofMinutes(1));
        world.windowStore.delegate.now = later;
        VelocityProof.Result proof = VelocityProof.judge(later, world.windowStore.live(UOW),
                evidence.read(UOW, later.minus(Duration.ofDays(31))), evidence.provable(), VelocityProof.SKEW);
        assertThat(proof.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
    }
}
