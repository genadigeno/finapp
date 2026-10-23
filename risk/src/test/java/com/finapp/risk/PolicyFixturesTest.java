package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.testing.RiskPolicyFixtures;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy v1 fixtures, hermetic (`P13-TSK-002`; owner decisions C1 and C8, {@code PHASE_13_PLAN.md} section 12.2):
 * every fixture validates at the door's rank (its construction IS the door's judgement), carries C1's fail-safe bands
 * exactly, uses every one of the twenty-two kinds across the set, and names the plan's thresholds.
 */
@DisplayName("the policy v1 fixtures validate and carry the owner's bands (P13-TSK-002, C1, C8)")
class PolicyFixturesTest {

    @Test
    @DisplayName("every fixture builds - the five judged operations and the sweep's - and the credit signal has none")
    void everyFixtureValidates() {
        Map<RiskOperation, RiskPolicy> all = RiskPolicyFixtures.all();
        assertThat(all.keySet()).containsExactlyInAnyOrder(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL,
                RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER,
                RiskOperation.MONITORING);
        for (Map.Entry<RiskOperation, RiskPolicy> fixture : all.entrySet()) {
            assertThat(fixture.getValue().operation()).isEqualTo(fixture.getKey());
            assertThat(fixture.getValue().engine()).isEqualTo(RiskEngine.RISK_ENGINE_V1);
            assertThat(fixture.getValue().currency()).isEqualTo(RiskPolicyFixtures.EUR);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RiskPolicyFixtures.v1(RiskOperation.CREDIT_SIGNAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("C1 exactly: transfers ALLOW below EUR 1,000 and DENY from it; withdrawals and cross-border DENY at every"
            + " band; checkout payments ALLOW below EUR 250 and DENY from it")
    void theFailSafeBandsAreTheOwners() {
        assertThat(RiskPolicyFixtures.v1(RiskOperation.TRANSFER).failSafeBands()).containsExactly(
                new FailSafeBand(0, OptionalLong.of(1_000_00), RiskDecision.ALLOW),
                new FailSafeBand(1_000_00, OptionalLong.empty(), RiskDecision.DENY));
        for (RiskOperation denied : List.of(RiskOperation.WITHDRAWAL, RiskOperation.CROSS_BORDER)) {
            assertThat(RiskPolicyFixtures.v1(denied).failSafeBands()).as(denied.name())
                    .containsExactly(new FailSafeBand(0, OptionalLong.empty(), RiskDecision.DENY));
        }
        for (RiskOperation payment : List.of(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT)) {
            assertThat(RiskPolicyFixtures.v1(payment).failSafeBands()).as(payment.name()).containsExactly(
                    new FailSafeBand(0, OptionalLong.of(250_00), RiskDecision.ALLOW),
                    new FailSafeBand(250_00, OptionalLong.empty(), RiskDecision.DENY));
        }
    }

    @Test
    @DisplayName("the plan's thresholds: challenge at EUR 500 and deny at EUR 10,000 for transfers, hold at EUR 2,500 for"
            + " withdrawals, 10 per 24 h per account, EUR 5,000 per 24 h per party, credential change -> HOLD, screening"
            + " hit -> DENY, PROHIBITED -> DENY, the override threshold EUR 1,000, the hold validity 72 h")
    void thePlansParameters() {
        RiskPolicy transfer = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
        assertThat(hardDecisions(transfer, RuleKind.AMOUNT_BAND)).containsExactly(
                Map.entry(500_00L, RiskDecision.CHALLENGE), Map.entry(10_000_00L, RiskDecision.DENY));
        RiskRule count = first(transfer, RuleKind.VELOCITY_COUNT);
        assertThat(count.parameters().subjectKind("dimension")).isEqualTo(SubjectKind.ACCOUNT);
        assertThat(count.parameters().window("window")).isEqualTo(WindowKind.D1);
        assertThat(count.parameters().count("threshold_count")).isEqualTo(10);
        RiskRule sum = first(transfer, RuleKind.VELOCITY_SUM);
        assertThat(sum.parameters().subjectKind("dimension")).isEqualTo(SubjectKind.PARTY);
        assertThat(sum.parameters().minorAmount("threshold_minor")).isEqualTo(5_000_00);
        assertThat(first(transfer, RuleKind.SCREENING_HIT).hardDecision()).contains(RiskDecision.DENY);
        RiskRule prohibited = first(transfer, RuleKind.PROFILE_RATING);
        assertThat(prohibited.parameters().rating("rating")).isEqualTo(RiskRating.PROHIBITED);
        assertThat(prohibited.hardDecision()).contains(RiskDecision.DENY);
        assertThat(transfer.overrideThreshold().orElseThrow().minorUnits()).isEqualTo(1_000_00);
        assertThat(transfer.challengeAssurance()).contains(RiskAssurance.MULTI_FACTOR);
        // The composite: a new payee alone and a mid amount alone stay below CHALLENGE's band; together they reach it.
        int newPayee = first(transfer, RuleKind.NEW_PAYEE).weight().orElseThrow();
        int midAmount = transfer.rules().stream().filter(r -> r.kind() == RuleKind.AMOUNT_BAND && r.weight().isPresent())
                .findFirst().orElseThrow().weight().orElseThrow();
        ScoreBand challenge = transfer.scoreBands().stream().filter(b -> b.decision() == RiskDecision.CHALLENGE)
                .findFirst().orElseThrow();
        assertThat(challenge.covers(newPayee)).isFalse();
        assertThat(challenge.covers(midAmount)).isFalse();
        assertThat(challenge.covers(newPayee + midAmount)).isTrue();

        RiskPolicy withdrawal = RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL);
        assertThat(hardDecisions(withdrawal, RuleKind.AMOUNT_BAND)).containsExactly(Map.entry(2_500_00L, RiskDecision.HOLD));
        assertThat(first(withdrawal, RuleKind.RECENT_CREDENTIAL_CHANGE).hardDecision()).contains(RiskDecision.HOLD);
        assertThat(withdrawal.holdValidity()).contains(RiskPolicy.DEFAULT_HOLD_VALIDITY);

        RiskPolicy monitoring = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        RiskRule structuring = first(monitoring, RuleKind.STRUCTURING);
        assertThat(structuring.parameters().count("threshold_count")).isEqualTo(3);
        assertThat(structuring.parameters().minorAmount("below_minor")).isEqualTo(1_000_00);
        assertThat(structuring.parameters().minorAmount("sum_minor")).isEqualTo(2_500_00);
        assertThat(structuring.parameters().window("window")).isEqualTo(WindowKind.D1);
        assertThat(first(monitoring, RuleKind.RAPID_IN_OUT).parameters().basisPoints("ratio_bps")).isEqualTo(8_000);
        RiskRule dormant = first(monitoring, RuleKind.DORMANT_THEN_ACTIVE);
        assertThat(dormant.parameters().days("dormant_days")).isEqualTo(90);
        assertThat(dormant.parameters().minorAmount("threshold_minor")).isEqualTo(1_000_00);
        assertThat(monitoring.ratingBands()).containsExactly(new RatingBand(0, 299, RiskRating.LOW),
                new RatingBand(300, 699, RiskRating.MEDIUM), new RatingBand(700, 1000, RiskRating.HIGH));
        assertThat(monitoring.rules()).allMatch(rule -> rule.hardDecision().isEmpty());
    }

    @Test
    @DisplayName("every one of the twenty-two kinds is used by some fixture, so the engine and the battery have a"
            + " parameterised row of each")
    void everyKindIsUsed() {
        Set<RuleKind> used = EnumSet.noneOf(RuleKind.class);
        for (RiskPolicy policy : RiskPolicyFixtures.all().values()) {
            for (RiskRule rule : policy.rules()) {
                used.add(rule.kind());
            }
        }
        assertThat(used).containsExactlyInAnyOrder(RuleKind.values());
    }

    // -----------------------------------------------------------------

    private static RiskRule first(RiskPolicy policy, RuleKind kind) {
        return policy.rules().stream().filter(rule -> rule.kind() == kind).findFirst().orElseThrow();
    }

    private static List<Map.Entry<Long, RiskDecision>> hardDecisions(RiskPolicy policy, RuleKind kind) {
        return policy.rules().stream()
                .filter(rule -> rule.kind() == kind && rule.hardDecision().isPresent())
                .map(rule -> Map.entry(rule.parameters().minorAmount("threshold_minor"), rule.hardDecision().orElseThrow()))
                .toList();
    }
}
