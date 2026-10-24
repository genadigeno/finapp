package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The risk policy version, hermetic (`P13-TSK-002`; ADR-0109; {@code INV-RSK-05}): the machine's edges, the content
 * judged whole at construction - every invalid edge refused {@code PolicyInvalid} before anything is locked - the
 * admitted decisions per operation, the bands' coverage, the operation's knobs, and immutability by construction.
 */
@DisplayName("the risk policy version: the machine and the content judged whole (P13-TSK-002)")
class RiskPolicyVersionTest {

    private static final CurrencyCode EUR = RiskPolicyFixtures.EUR;

    @Test
    @DisplayName("PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; RETIRED and REJECTED terminal; decided once not PROPOSED")
    void theMachine() {
        assertThat(RiskPolicyVersionStatus.PROPOSED.permittedTransitions())
                .containsExactlyInAnyOrder(RiskPolicyVersionStatus.ACTIVE, RiskPolicyVersionStatus.REJECTED);
        assertThat(RiskPolicyVersionStatus.ACTIVE.permittedTransitions()).containsExactly(RiskPolicyVersionStatus.RETIRED);
        assertThat(RiskPolicyVersionStatus.RETIRED.isTerminal()).isTrue();
        assertThat(RiskPolicyVersionStatus.REJECTED.isTerminal()).isTrue();
        assertThat(RiskPolicyVersionStatus.PROPOSED.isDecided()).isFalse();
        assertThat(RiskPolicyVersionStatus.ACTIVE.isDecided()).isTrue();
        assertThat(RiskPolicyVersionStatus.sqlValueList()).isEqualTo("'PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED'");
    }

    @Test
    @DisplayName("a kind not written for the operation, a hard decision or a score band the operation does not admit,"
            + " and a rating band to PROHIBITED are each PolicyInvalid")
    void unadmittedContentIsRefused() {
        assertThatThrownBy(() -> transferWith(List.of(
                RiskRule.weighted(1, RuleKind.STRUCTURING, monitoringStructuring(), 100))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("not written for TRANSFER");
        assertThatThrownBy(() -> transferWith(List.of(
                RiskRule.deciding(1, RuleKind.AMOUNT_BAND, amount(500_00), RiskDecision.HOLD))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("TRANSFER does not admit");
        assertThatThrownBy(() -> judged(RiskOperation.CARD_PAYMENT, defaultRules(), List.of(
                new ScoreBand(0, 999, RiskDecision.ALLOW), new ScoreBand(1000, 1000, RiskDecision.REVIEW)),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("CARD_PAYMENT does not admit");
        assertThatThrownBy(() -> new RatingBand(0, 1000, RiskRating.PROHIBITED))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("only a four-eyes disposition");
        assertThatThrownBy(() -> new FailSafeBand(0, OptionalLong.empty(), RiskDecision.HOLD))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("ALLOW or DENY");
    }

    @Test
    @DisplayName("the bands cover their range contiguously: a gap, an overlap, a start above 0, an end below 1000, an"
            + " open-ended band before the last and no band at all are each refused")
    void theBandsCoverTheirRange() {
        List<List<ScoreBand>> badScores = List.of(
                List.of(new ScoreBand(0, 299, RiskDecision.ALLOW), new ScoreBand(301, 1000, RiskDecision.DENY)),
                List.of(new ScoreBand(0, 300, RiskDecision.ALLOW), new ScoreBand(300, 1000, RiskDecision.DENY)),
                List.of(new ScoreBand(1, 1000, RiskDecision.DENY)),
                List.of(new ScoreBand(0, 999, RiskDecision.ALLOW)),
                List.of());
        for (List<ScoreBand> bands : badScores) {
            assertThatThrownBy(() -> judged(RiskOperation.TRANSFER, defaultRules(), bands,
                    RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.empty()))
                    .as(bands.toString()).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        }
        assertThatThrownBy(() -> new ScoreBand(500, 400, RiskDecision.ALLOW)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ScoreBand(0, 1001, RiskDecision.ALLOW)).isInstanceOf(RiskPolicy.PolicyInvalid.class);

        List<List<FailSafeBand>> badFailSafes = List.of(
                List.of(new FailSafeBand(0, OptionalLong.of(1_000_00), RiskDecision.ALLOW)),
                List.of(new FailSafeBand(0, OptionalLong.empty(), RiskDecision.ALLOW),
                        new FailSafeBand(1_000_00, OptionalLong.empty(), RiskDecision.DENY)),
                List.of(new FailSafeBand(0, OptionalLong.of(1_000_00), RiskDecision.ALLOW),
                        new FailSafeBand(2_000_00, OptionalLong.empty(), RiskDecision.DENY)),
                List.of(new FailSafeBand(1, OptionalLong.empty(), RiskDecision.DENY)),
                List.of());
        for (List<FailSafeBand> bands : badFailSafes) {
            assertThatThrownBy(() -> judged(RiskOperation.TRANSFER, defaultRules(), fourBands(), bands, Optional.empty()))
                    .as(bands.toString()).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        }
        assertThatThrownBy(() -> new FailSafeBand(5, OptionalLong.of(5), RiskDecision.DENY))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class);

        assertThatThrownBy(() -> monitoringWith(List.of(new RatingBand(0, 500, RiskRating.LOW))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("end at 1000");
        RiskPolicy transfer = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
        assertThat(transfer.failSafeFor(999_99)).isEqualTo(RiskDecision.ALLOW);
        assertThat(transfer.failSafeFor(1_000_00)).isEqualTo(RiskDecision.DENY);
        assertThat(transfer.failSafeFor(Long.MAX_VALUE)).isEqualTo(RiskDecision.DENY);
        assertThat(transfer.scoreBands().get(1).covers(300)).isTrue();
        assertThat(transfer.scoreBands().get(1).covers(600)).isFalse();
    }

    @Test
    @DisplayName("the rules are numbered 1..n in declared order, at least one, each with a weight and/or a hard decision"
            + " and its own kind's parameters")
    void theRulesAreOrdered() {
        assertThatThrownBy(() -> transferWith(List.of())).isInstanceOf(RiskPolicy.PolicyInvalid.class)
                .hasMessageContaining("at least one rule");
        assertThatThrownBy(() -> transferWith(List.of(
                RiskRule.deciding(2, RuleKind.AMOUNT_BAND, amount(500_00), RiskDecision.DENY))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("numbered 1..1");
        assertThatThrownBy(() -> transferWith(List.of(
                RiskRule.deciding(1, RuleKind.AMOUNT_BAND, amount(500_00), RiskDecision.DENY),
                RiskRule.deciding(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT),
                        RiskDecision.DENY))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new RiskRule(1, RuleKind.AMOUNT_BAND, amount(500_00), OptionalInt.empty(),
                Optional.empty())).isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("weight and/or");
        assertThatThrownBy(() -> new RiskRule(1, RuleKind.AMOUNT_BAND, amount(500_00), OptionalInt.of(0),
                Optional.empty())).isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("1 to 1000");
        assertThatThrownBy(() -> new RiskRule(1, RuleKind.AMOUNT_BAND, amount(500_00), OptionalInt.of(1001),
                Optional.empty())).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new RiskRule(0, RuleKind.AMOUNT_BAND, amount(500_00), OptionalInt.of(1),
                Optional.empty())).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new RiskRule(1, RuleKind.SCREENING_HIT, amount(500_00), OptionalInt.of(1),
                Optional.empty())).isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("own kind's");
        // Both a weight and a hard decision is admitted: the weight counts toward the score, the decision beats it.
        RiskRule both = new RiskRule(1, RuleKind.AMOUNT_BAND, amount(500_00), OptionalInt.of(100),
                Optional.of(RiskDecision.CHALLENGE));
        assertThat(transferWith(List.of(both)).rules()).hasSize(1);
    }

    @Test
    @DisplayName("each operation's knobs: a judged version names MULTI_FACTOR and the override threshold, the withdrawal"
            + " alone a hold validity (1 h to 30 d, whole hours); the monitoring version names none and carries rating"
            + " bands only; the credit signal carries no policy; the currency is the reference's")
    void theKnobsAreTheOperations() {
        assertThatThrownBy(() -> judged(RiskOperation.TRANSFER, defaultRules(), fourBands(),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(Duration.ofHours(72))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("admits no HOLD");
        assertThatThrownBy(() -> judged(RiskOperation.WITHDRAWAL, withdrawalRules(), fourBands(),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("names the hold validity");
        for (Duration bad : List.of(Duration.ofMinutes(30), Duration.ofHours(721), Duration.ofMinutes(90))) {
            assertThatThrownBy(() -> judged(RiskOperation.WITHDRAWAL, withdrawalRules(), fourBands(),
                    RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(bad)))
                    .as(bad.toString()).isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("whole number of hours");
        }
        assertThat(judged(RiskOperation.WITHDRAWAL, withdrawalRules(), fourBands(),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(Duration.ofHours(720))).holdValidity())
                .contains(Duration.ofHours(720));

        // The card payment (P13-TSK-010's second slice): a version names a hold validity exactly when it can decide
        // HOLD - a band or a rule - so the C8 fixture, which holds nothing, stays valid as written.
        List<ScoreBand> holding = List.of(new ScoreBand(0, 299, RiskDecision.ALLOW),
                new ScoreBand(300, 699, RiskDecision.HOLD), new ScoreBand(700, 1000, RiskDecision.DENY));
        assertThatThrownBy(() -> judged(RiskOperation.CARD_PAYMENT, defaultRules(), holding,
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("CARD_PAYMENT can decide HOLD");
        assertThat(judged(RiskOperation.CARD_PAYMENT, defaultRules(), holding,
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(Duration.ofHours(72))).holdValidity())
                .contains(Duration.ofHours(72));
        assertThat(judged(RiskOperation.CARD_PAYMENT, List.of(
                RiskRule.deciding(1, RuleKind.AMOUNT_BAND, amount(2_500_00), RiskDecision.HOLD)), fourBands(),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(Duration.ofHours(24))).holdValidity())
                .as("a rule's hard HOLD is a HOLD the version can decide").contains(Duration.ofHours(24));
        assertThatThrownBy(() -> judged(RiskOperation.CARD_PAYMENT, defaultRules(), List.of(
                new ScoreBand(0, 1000, RiskDecision.ALLOW)), RiskPolicyFixtures.failSafe(RiskDecision.DENY),
                Optional.of(Duration.ofHours(72))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("decides no HOLD in this version");
        assertThat(RiskPolicyFixtures.v1(RiskOperation.CARD_PAYMENT).holdValidity())
                .as("the C8 card fixture holds nothing and names nothing").isEmpty();
        assertThatThrownBy(() -> judged(RiskOperation.WALLET_PAYMENT, defaultRules(), holding,
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(Duration.ofHours(72))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("WALLET_PAYMENT does not admit");

        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, EUR, defaultRules(),
                fourBands(), List.of(), RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.empty(),
                Optional.of(Money.ofMinorUnits(1_000_00, EUR)), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("challenge assurance");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, EUR, defaultRules(),
                fourBands(), List.of(), RiskPolicyFixtures.failSafe(RiskDecision.DENY),
                Optional.of(RiskAssurance.SINGLE_FACTOR), Optional.of(Money.ofMinorUnits(1_000_00, EUR)),
                Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("MULTI_FACTOR alone");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, EUR, defaultRules(),
                fourBands(), List.of(), RiskPolicyFixtures.failSafe(RiskDecision.DENY),
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.empty(), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("override threshold");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, EUR, defaultRules(),
                fourBands(), List.of(), RiskPolicyFixtures.failSafe(RiskDecision.DENY),
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.of(Money.ofMinorUnits(1_000_00, CurrencyCode.of("USD"))),
                Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("version's currency");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1,
                CurrencyCode.of("USD"), defaultRules(), fourBands(), List.of(),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.of(RiskAssurance.MULTI_FACTOR),
                Optional.of(Money.ofMinorUnits(1_000_00, CurrencyCode.of("USD"))), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("amounts are in EUR");
        assertThatThrownBy(() -> judged(RiskOperation.TRANSFER, defaultRules(), fourBands(),
                RiskPolicyFixtures.failSafe(RiskDecision.DENY), Optional.empty(),
                List.of(new RatingBand(0, 1000, RiskRating.LOW))))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("monitoring version alone");

        RiskPolicy monitoring = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        assertThat(monitoring.isMonitoring()).isTrue();
        assertThat(monitoring.scoreBands()).isEmpty();
        assertThat(monitoring.failSafeBands()).isEmpty();
        assertThat(monitoring.challengeAssurance()).isEmpty();
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR,
                monitoring.rules(), fourBands(), monitoring.ratingBands(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty())).isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("rating bands, not");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR,
                monitoring.rules(), List.of(), monitoring.ratingBands(), List.of(),
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.empty(), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("no challenge assurance");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR,
                List.of(RiskRule.deciding(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT),
                        RiskDecision.DENY)),
                List.of(), monitoring.ratingBands(), List.of(), Optional.empty(), Optional.empty(), Optional.empty()))
                .as("the sweep admits no decision: a monitoring rule carries a weight alone")
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("MONITORING does not admit");
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.CREDIT_SIGNAL, RiskEngine.RISK_ENGINE_V1, EUR,
                defaultRules(), List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("credit signal carries no policy");
    }

    @Test
    @DisplayName("the content is immutable by construction - every list a copy - and names no threshold in its toString")
    void immutableByConstruction() {
        List<RiskRule> rules = new ArrayList<>(defaultRules());
        RiskPolicy policy = transferWith(rules);
        rules.clear();
        assertThat(policy.rules()).hasSize(1);
        assertThatThrownBy(() -> policy.rules().add(policy.rules().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.scoreBands().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(policy.toString()).doesNotContain("50000").doesNotContain("500");
        assertThat(policy.rules().get(0).parameters().toString()).doesNotContain("50000");
        assertThat(policy.admittedDecisions()).containsExactlyInAnyOrder(RiskDecision.ALLOW, RiskDecision.CHALLENGE,
                RiskDecision.DENY, RiskDecision.REVIEW);
    }

    @Test
    @DisplayName("a version pairs a machine row with its own operation's content and nothing else's")
    void aVersionIsItsOwnOperations() {
        RiskPolicyStore.VersionRow row = new RiskPolicyStore.VersionRow(
                RiskPolicyVersionId.of(UUID.fromString("01900000-0000-7000-8000-000000000001")), RiskOperation.TRANSFER,
                1, "RISK_ENGINE_V1", RiskPolicyVersionStatus.PROPOSED, "alice", Instant.EPOCH, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        RiskPolicyVersion version = new RiskPolicyVersion(row, RiskPolicyFixtures.v1(RiskOperation.TRANSFER));
        assertThat(version.operation()).isEqualTo(RiskOperation.TRANSFER);
        assertThat(version.version()).isEqualTo(1);
        assertThatThrownBy(() -> new RiskPolicyVersion(row, RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL)))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------

    static RuleParameters amount(long minor) {
        return RuleParameters.of(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", new ParameterValue.MinorAmount(minor)));
    }

    static RuleParameters monitoringStructuring() {
        return RuleParameters.of(RuleKind.STRUCTURING, Map.of(
                "window", new ParameterValue.Window(WindowKind.D1),
                "below_minor", new ParameterValue.MinorAmount(1_000_00),
                "threshold_count", new ParameterValue.Count(3),
                "sum_minor", new ParameterValue.MinorAmount(2_500_00)));
    }

    static List<RiskRule> defaultRules() {
        return List.of(RiskRule.deciding(1, RuleKind.AMOUNT_BAND, amount(500_00), RiskDecision.DENY));
    }

    static List<RiskRule> withdrawalRules() {
        return List.of(RiskRule.deciding(1, RuleKind.AMOUNT_BAND, amount(2_500_00), RiskDecision.HOLD));
    }

    static List<ScoreBand> fourBands() {
        return List.of(new ScoreBand(0, 299, RiskDecision.ALLOW), new ScoreBand(300, 599, RiskDecision.CHALLENGE),
                new ScoreBand(600, 799, RiskDecision.DENY), new ScoreBand(800, 1000, RiskDecision.DENY));
    }

    static RiskPolicy transferWith(List<RiskRule> rules) {
        return judged(RiskOperation.TRANSFER, rules, fourBands(), RiskPolicyFixtures.failSafe(RiskDecision.DENY),
                Optional.empty());
    }

    static RiskPolicy monitoringWith(List<RatingBand> ratingBands) {
        return new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR,
                RiskPolicyFixtures.v1(RiskOperation.MONITORING).rules(), List.of(), ratingBands, List.of(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    static RiskPolicy judged(RiskOperation operation, List<RiskRule> rules, List<ScoreBand> scoreBands,
            List<FailSafeBand> failSafe, Optional<Duration> hold) {
        return judged(operation, rules, scoreBands, failSafe, hold, List.of());
    }

    static RiskPolicy judged(RiskOperation operation, List<RiskRule> rules, List<ScoreBand> scoreBands,
            List<FailSafeBand> failSafe, Optional<Duration> hold, List<RatingBand> ratingBands) {
        return new RiskPolicy(operation, RiskEngine.RISK_ENGINE_V1, EUR, rules, scoreBands, ratingBands, failSafe,
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.of(Money.ofMinorUnits(1_000_00, EUR)), hold);
    }
}
