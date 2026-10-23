package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every rule kind is evaluated (`P13-TSK-003`; ADR-0109 decision 2; {@code INV-RSK-04}): each of the twenty-two has
 * its evaluator and its reason code, and on a one-rule policy each fires on a case built to fire it, stays quiet on
 * a case built not to, and comes out {@code ABSENT} - never fired - when its reading is absent. Over the fixtures,
 * inputs with every reading present leave no rule {@code ABSENT}.
 */
@DisplayName("every rule kind has an evaluator, a reason code, and fires exactly when it should (P13-TSK-003)")
class RuleKindCoverageTest {

    private static final Money EUR_100 = eur(100_00);

    @Test
    @DisplayName("the twenty-two kinds each have an evaluator and a rule reason code of the same name, and no reason"
            + " names a kind that is not one")
    void everyKindHasAnEvaluatorAndAReason() {
        for (RuleKind kind : RuleKind.values()) {
            assertThat(RuleEvaluation.of(kind).name()).isEqualTo(kind.name());
            assertThat(ReasonCode.of(kind).source()).isEqualTo(ReasonCode.Source.RULE);
        }
        assertThat(RuleEvaluation.values()).hasSize(RuleKind.values().length);
        assertThat(ReasonCode.values()).hasSize(RuleKind.values().length + 3);
        assertThat(ReasonCode.NO_POLICY_ACTIVE.source()).isEqualTo(ReasonCode.Source.FAIL_SAFE);
        assertThat(ReasonCode.RISK_UNAVAILABLE.source()).isEqualTo(ReasonCode.Source.FAIL_SAFE);
        assertThat(ReasonCode.CHALLENGE_REQUIRED.source()).isEqualTo(ReasonCode.Source.SEAM);
        assertThat(ReasonCode.AMOUNT_BAND.code()).isEqualTo("AMOUNT_BAND");
    }

    @Test
    @DisplayName("each kind fires on its firing case, stays NOT_FIRED on its quiet case, and is ABSENT - not fired -"
            + " with its reading absent")
    void everyKindFiresAndDoesNot() {
        for (Probe probe : probes()) {
            RiskPolicy policy = oneRule(probe.operation(), probe.kind(), probe.parameters());
            RuleHit fires = RiskEngineV1.evaluate(policy, probe.inputs(probe.fires())).hits().get(0);
            assertThat(fires.outcome()).as("%s fires", probe.kind()).isEqualTo(RuleOutcome.FIRED);
            assertThat(fires.contribution()).isEqualTo(100);
            assertThat(fires.judged()).isNotEqualTo("ABSENT");
            RuleHit quiet = RiskEngineV1.evaluate(policy, probe.inputs(probe.quiet())).hits().get(0);
            assertThat(quiet.outcome()).as("%s stays quiet", probe.kind()).isEqualTo(RuleOutcome.NOT_FIRED);
            assertThat(quiet.contribution()).isZero();
            if (probe.kind() == RuleKind.AMOUNT_BAND) {
                // Written for judged operations only, whose amount is sealed by construction: its reading cannot be
                // absent (RiskEngineV1Test#theModelRefusesTheMalformed holds the refusal).
                continue;
            }
            RuleHit absent = RiskEngineV1.evaluate(policy, probe.inputs(b -> {})).hits().get(0);
            assertThat(absent.outcome()).as("%s over an absent reading", probe.kind()).isEqualTo(RuleOutcome.ABSENT);
            assertThat(absent.judged()).isEqualTo("ABSENT");
            assertThat(absent.contribution()).isZero();
        }
        assertThat(probes()).extracting(Probe::kind).containsExactly(RuleKind.values());
    }

    @Test
    @DisplayName("over every fixture, inputs with every reading present leave no rule ABSENT")
    void filledInputsLeaveNoRuleAbsent() {
        Random random = new Random(EvaluationCorpus.SEED);
        for (RiskOperation operation : RiskPolicyFixtures.operations()) {
            for (int i = 0; i < 20; i++) {
                Verdict verdict = RiskEngineV1.evaluate(RiskPolicyFixtures.v1(operation),
                        EvaluationCorpus.filled(random, operation));
                assertThat(verdict.hits()).as("%s", operation)
                        .noneMatch(hit -> hit.outcome() == RuleOutcome.ABSENT);
            }
        }
    }

    // ------------------------------------------------------------------ the probes, one per kind in the kinds' order

    record Probe(RuleKind kind, RiskOperation operation, Map<String, ParameterValue> parameters,
            Consumer<EvaluationInputs.Builder> fires, Consumer<EvaluationInputs.Builder> quiet) {

        /** Inputs with the operation's amount (the sweep's too, so the amount kinds can be probed) and {@code fill}. */
        EvaluationInputs inputs(Consumer<EvaluationInputs.Builder> fill) {
            EvaluationInputs.Builder builder = EvaluationInputs.builder(operation);
            if (kind != RuleKind.AMOUNT_BAND && kind != RuleKind.ROUND_AMOUNT) {
                builder.amount(EUR_100);
            }
            fill.accept(builder);
            return builder.build();
        }
    }

    static List<Probe> probes() {
        return List.of(
                new Probe(RuleKind.AMOUNT_BAND, RiskOperation.TRANSFER,
                        Map.of("threshold_minor", RiskPolicyFixtures.minor(500_00)),
                        b -> b.amount(eur(500_00)), b -> b.amount(eur(499_99))),
                new Probe(RuleKind.VELOCITY_COUNT, RiskOperation.TRANSFER, velocity(SubjectKind.ACCOUNT, WindowKind.D1,
                        "threshold_count", RiskPolicyFixtures.count(10)),
                        b -> b.velocity(SubjectKind.ACCOUNT, WindowKind.D1, 10, 0),
                        b -> b.velocity(SubjectKind.ACCOUNT, WindowKind.D1, 9, 0)),
                new Probe(RuleKind.VELOCITY_SUM, RiskOperation.TRANSFER, velocity(SubjectKind.PARTY, WindowKind.D1,
                        "threshold_minor", RiskPolicyFixtures.minor(5_000_00)),
                        b -> b.velocity(SubjectKind.PARTY, WindowKind.D1, 0, 5_000_00),
                        b -> b.velocity(SubjectKind.PARTY, WindowKind.D1, 0, 4_999_99)),
                new Probe(RuleKind.NEW_PAYEE, RiskOperation.TRANSFER, Map.of("lookback_days", RiskPolicyFixtures.days(30)),
                        b -> b.payeeFirstSeen(FirstSeen.never()), b -> b.payeeFirstSeen(FirstSeen.daysAgo(30))),
                new Probe(RuleKind.NEW_DEVICE, RiskOperation.TRANSFER, Map.of("lookback_days", RiskPolicyFixtures.days(30)),
                        b -> b.deviceFirstSeen(FirstSeen.daysAgo(29)), b -> b.deviceFirstSeen(FirstSeen.daysAgo(31))),
                new Probe(RuleKind.SESSION_ASSURANCE_BELOW, RiskOperation.TRANSFER,
                        Map.of("assurance", RiskPolicyFixtures.assurance(RiskAssurance.MULTI_FACTOR)),
                        b -> b.sessionAssurance(RiskAssurance.SINGLE_FACTOR),
                        b -> b.sessionAssurance(RiskAssurance.MULTI_FACTOR)),
                new Probe(RuleKind.RECENT_AUTH_FAILURES, RiskOperation.TRANSFER,
                        Map.of("window", RiskPolicyFixtures.window(WindowKind.H1), "threshold_count",
                                RiskPolicyFixtures.count(3)),
                        b -> b.authFailures(WindowKind.H1, 3), b -> b.authFailures(WindowKind.H1, 2)),
                new Probe(RuleKind.RECENT_CREDENTIAL_CHANGE, RiskOperation.WITHDRAWAL,
                        Map.of("lookback_hours", RiskPolicyFixtures.hours(24)),
                        b -> b.credentialChange(LastSeen.hoursAgo(23)), b -> b.credentialChange(LastSeen.never())),
                new Probe(RuleKind.RECENT_RECOVERY, RiskOperation.WITHDRAWAL,
                        Map.of("lookback_hours", RiskPolicyFixtures.hours(72)),
                        b -> b.recovery(LastSeen.hoursAgo(0)), b -> b.recovery(LastSeen.hoursAgo(72))),
                new Probe(RuleKind.ACCOUNT_AGE_BELOW, RiskOperation.WITHDRAWAL, Map.of("days", RiskPolicyFixtures.days(7)),
                        b -> b.accountAgeDays(6), b -> b.accountAgeDays(7)),
                new Probe(RuleKind.KYC_NOT_APPROVED, RiskOperation.WITHDRAWAL, Map.of(),
                        b -> b.kycApproved(false), b -> b.kycApproved(true)),
                new Probe(RuleKind.SCREENING_HIT, RiskOperation.TRANSFER, Map.of(),
                        b -> b.screeningHit(true), b -> b.screeningHit(false)),
                new Probe(RuleKind.PROFILE_RATING, RiskOperation.TRANSFER,
                        Map.of("rating", RiskPolicyFixtures.rating(RiskRating.HIGH)),
                        b -> b.profileRating(RiskRating.PROHIBITED), b -> b.profileRating(RiskRating.MEDIUM)),
                new Probe(RuleKind.MERCHANT_SUSPENDED, RiskOperation.CARD_PAYMENT, Map.of(),
                        b -> b.merchantSuspended(true), b -> b.merchantSuspended(false)),
                new Probe(RuleKind.CORRIDOR_HIGH_RISK, RiskOperation.CROSS_BORDER,
                        Map.of("corridors", RiskPolicyFixtures.corridors()),
                        b -> b.corridor("EUR-USD-PA"), b -> b.corridor("EUR-USD-US")),
                new Probe(RuleKind.DORMANT_THEN_ACTIVE, RiskOperation.MONITORING,
                        Map.of("dormant_days", RiskPolicyFixtures.days(90), "window", RiskPolicyFixtures.window(WindowKind.D1),
                                "threshold_minor", RiskPolicyFixtures.minor(1_000_00)),
                        b -> b.velocity(SubjectKind.PARTY, WindowKind.D1, 1, 1_000_00).monitoring(dormant(90)),
                        b -> b.velocity(SubjectKind.PARTY, WindowKind.D1, 1, 999_99).monitoring(dormant(120))),
                new Probe(RuleKind.RAPID_IN_OUT, RiskOperation.MONITORING,
                        Map.of("window", RiskPolicyFixtures.window(WindowKind.D1), "ratio_bps", RiskPolicyFixtures.bps(8_000)),
                        b -> b.monitoring(flows(new Flow(1_000_00, 800_00))),
                        b -> b.monitoring(flows(new Flow(1_000_00, 799_99)))),
                new Probe(RuleKind.STRUCTURING, RiskOperation.MONITORING,
                        Map.of("window", RiskPolicyFixtures.window(WindowKind.D1), "below_minor", RiskPolicyFixtures.minor(1_000_00),
                                "threshold_count", RiskPolicyFixtures.count(3), "sum_minor", RiskPolicyFixtures.minor(2_500_00)),
                        b -> b.monitoring(amounts(Amounts.of(900_00, 950_00, 800_00, 5_000_00))),
                        b -> b.monitoring(amounts(Amounts.of(900_00, 950_00, 1_000_00, 5_000_00)))),
                new Probe(RuleKind.ROUND_AMOUNT, RiskOperation.MONITORING,
                        Map.of("modulus_minor", RiskPolicyFixtures.minor(1_000_00)),
                        b -> b.amount(eur(3_000_00)), b -> b.amount(eur(3_000_01))),
                new Probe(RuleKind.REFUND_RATIO, RiskOperation.MONITORING,
                        Map.of("window", RiskPolicyFixtures.window(WindowKind.D30), "ratio_bps", RiskPolicyFixtures.bps(2_000)),
                        b -> b.monitoring(refunds(new Ratio(20, 100))), b -> b.monitoring(refunds(new Ratio(19, 100)))),
                new Probe(RuleKind.CHARGEBACK_RATIO, RiskOperation.MONITORING,
                        Map.of("window", RiskPolicyFixtures.window(WindowKind.D30), "ratio_bps", RiskPolicyFixtures.bps(100)),
                        b -> b.monitoring(chargebacks(new Ratio(1, 100))), b -> b.monitoring(chargebacks(new Ratio(0, 100)))),
                new Probe(RuleKind.BNPL_MULTI_APPLICATION, RiskOperation.MONITORING,
                        Map.of("window", RiskPolicyFixtures.window(WindowKind.D7), "threshold_count", RiskPolicyFixtures.count(3)),
                        b -> b.monitoring(bnpl(3)), b -> b.monitoring(bnpl(2))));
    }

    // ------------------------------------------------------------------ the shapes

    /** A policy of one weighted rule (weight 100) for {@code operation}, with a single all-covering band. */
    static RiskPolicy oneRule(RiskOperation operation, RuleKind kind, Map<String, ParameterValue> parameters) {
        List<RiskRule> rules = List.of(RiskRule.weighted(1, kind, RuleParameters.of(kind, parameters), 100));
        if (operation == RiskOperation.MONITORING) {
            return new RiskPolicy(operation, RiskEngine.RISK_ENGINE_V1, RiskPolicyFixtures.EUR, rules, List.of(),
                    List.of(new RatingBand(0, 1000, RiskRating.LOW)), List.of(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }
        return new RiskPolicy(operation, RiskEngine.RISK_ENGINE_V1, RiskPolicyFixtures.EUR, rules,
                List.of(new ScoreBand(0, 1000, RiskDecision.ALLOW)), List.of(),
                List.of(new FailSafeBand(0, OptionalLong.empty(), RiskDecision.DENY)),
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.of(eur(1_000_00)),
                operation == RiskOperation.WITHDRAWAL ? Optional.of(Duration.ofHours(72)) : Optional.empty());
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
    }

    private static Map<String, ParameterValue> velocity(SubjectKind dimension, WindowKind window, String threshold,
            ParameterValue value) {
        return Map.of("dimension", new ParameterValue.Subject(dimension), "window", RiskPolicyFixtures.window(window),
                threshold, value);
    }

    private static MonitoringReadings dormant(int days) {
        return new MonitoringReadings(Reading.of(days), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static MonitoringReadings flows(Flow d1) {
        return new MonitoringReadings(Reading.absent(), window(d1), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static MonitoringReadings amounts(Amounts d1) {
        return new MonitoringReadings(Reading.absent(), Map.of(), window(d1), Map.of(), Map.of(), Map.of());
    }

    private static MonitoringReadings refunds(Ratio d30) {
        Map<WindowKind, Ratio> map = new EnumMap<>(WindowKind.class);
        map.put(WindowKind.D30, d30);
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), map, Map.of(), Map.of());
    }

    private static MonitoringReadings chargebacks(Ratio d30) {
        Map<WindowKind, Ratio> map = new EnumMap<>(WindowKind.class);
        map.put(WindowKind.D30, d30);
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), Map.of(), map, Map.of());
    }

    private static MonitoringReadings bnpl(long d7) {
        Map<WindowKind, Long> map = new EnumMap<>(WindowKind.class);
        map.put(WindowKind.D7, d7);
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), Map.of(), Map.of(), map);
    }

    private static <V> Map<WindowKind, V> window(V d1) {
        Map<WindowKind, V> map = new EnumMap<>(WindowKind.class);
        map.put(WindowKind.D1, d1);
        return map;
    }
}
