package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RISK_ENGINE_V1}, hermetic (`P13-TSK-003`; {@code PHASE_13_PLAN.md} sections 12.2-12.3; ADR-0108,
 * ADR-0109; {@code INV-RSK-04}): the golden cases over the policy v1 fixtures - every rule's hit in declared order,
 * the composite, absent readings, the precedence table, a hard rule beating a band both ways, the clamp, the band
 * at 0, the sweep's rating - the fail-safe per band and with no policy, the refusals, and the canonical bytes of
 * format 1 pinned.
 */
@DisplayName("risk engine v1: the golden cases, the precedence, the fail-safe, the refusals (P13-TSK-003)")
class RiskEngineV1Test {

    private static final CurrencyCode EUR = RiskPolicyFixtures.EUR;
    private static final RiskPolicy TRANSFER = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
    private static final RiskPolicy WITHDRAWAL = RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL);
    private static final RiskPolicy MONITORING = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
    private static final UUID PARTY = UUID.fromString("00000000-0000-7000-8000-000000000001");

    // ------------------------------------------------------------------ the golden cases

    @Test
    @DisplayName("a quiet EUR 100 transfer: eleven hits in declared order, none fired, score 0, the band at 0 - ALLOW,"
            + " no reason")
    void aQuietTransfer() {
        Verdict verdict = RiskEngineV1.evaluate(TRANSFER, quietTransfer(100_00).build());
        assertThat(verdict.hits()).hasSize(11);
        assertThat(verdict.hits()).extracting(RuleHit::ordinal).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(verdict.hits()).extracting(RuleHit::kind).containsExactly(
                TRANSFER.rules().stream().map(RiskRule::kind).toArray(RuleKind[]::new));
        assertThat(verdict.hits()).allMatch(hit -> hit.outcome() == RuleOutcome.NOT_FIRED);
        assertThat(verdict.score()).isEqualTo(Score.ZERO);
        assertThat(verdict.decision()).contains(RiskDecision.ALLOW);
        assertThat(verdict.rating()).isEmpty();
        assertThat(verdict.reasons()).isEmpty();
        assertThat(verdict.failSafeApplied()).isFalse();
        assertThat(verdict.engine()).isEqualTo(RiskEngine.RISK_ENGINE_V1);
    }

    @Test
    @DisplayName("the composite: EUR 1,200 to a never-seen payee - the hard CHALLENGE at 500, the payee's 250 and the"
            + " mid-amount's 200 fire, score 450 in the CHALLENGE band, three reasons in evaluation order")
    void theComposite() {
        Verdict verdict = RiskEngineV1.evaluate(TRANSFER, quietTransfer(1_200_00).payeeFirstSeen(FirstSeen.never()).build());
        assertThat(verdict.fired()).extracting(RuleHit::ordinal).containsExactly(1, 5, 6);
        assertThat(verdict.hits().get(0).hardDecision()).contains(RiskDecision.CHALLENGE);
        assertThat(verdict.hits().get(0).contribution()).isZero();
        assertThat(verdict.hits().get(4).contribution()).isEqualTo(250);
        assertThat(verdict.hits().get(5).contribution()).isEqualTo(200);
        assertThat(verdict.score().value()).isEqualTo(450);
        assertThat(verdict.decision()).contains(RiskDecision.CHALLENGE);
        assertThat(verdict.reasons()).containsExactly(ReasonCode.AMOUNT_BAND, ReasonCode.NEW_PAYEE, ReasonCode.AMOUNT_BAND);
        assertThat(verdict.hits().get(4).judged()).isEqualTo("payee:daysSinceFirstSeen=NEVER");
        assertThat(verdict.hits().get(0).judged()).isEqualTo("amount=120000");
    }

    @Test
    @DisplayName("a hard rule beats the band: a screening hit on a EUR 100 transfer is DENY at score 0, the ALLOW band"
            + " never softening it")
    void aHardDecisionIsNeverSoftenedByTheBand() {
        Verdict verdict = RiskEngineV1.evaluate(TRANSFER, quietTransfer(100_00).screeningHit(true).build());
        assertThat(verdict.score()).isEqualTo(Score.ZERO);
        assertThat(verdict.decision()).contains(RiskDecision.DENY);
        assertThat(verdict.reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(verdict.hits().get(8).judged()).isEqualTo("screeningHit=true");
    }

    @Test
    @DisplayName("the band raises a hard decision and the sum is clamped: EUR 2,000 to a new payee from a new device,"
            + " single factor, HIGH-rated - the hard CHALLENGE, weights 1,050 clamped to 1,000, the DENY band wins")
    void theBandRaisesAHardDecisionAndTheClamp() {
        Verdict verdict = RiskEngineV1.evaluate(TRANSFER, quietTransfer(2_000_00)
                .payeeFirstSeen(FirstSeen.never()).deviceFirstSeen(FirstSeen.daysAgo(0))
                .sessionAssurance(RiskAssurance.SINGLE_FACTOR).profileRating(RiskRating.HIGH).build());
        assertThat(verdict.fired()).extracting(RuleHit::ordinal).containsExactly(1, 5, 6, 7, 8, 11);
        assertThat(verdict.fired()).extracting(RuleHit::contribution).containsExactly(0, 250, 200, 200, 100, 300);
        assertThat(verdict.score().value()).isEqualTo(1000);
        assertThat(verdict.decision()).contains(RiskDecision.DENY);
        assertThat(verdict.reasons()).containsExactly(ReasonCode.AMOUNT_BAND, ReasonCode.NEW_PAYEE, ReasonCode.AMOUNT_BAND,
                ReasonCode.NEW_DEVICE, ReasonCode.SESSION_ASSURANCE_BELOW, ReasonCode.PROFILE_RATING);
    }

    @Test
    @DisplayName("absent readings never fire and say so: EUR 100 with only the amount sealed - the amount rules judged,"
            + " every other rule ABSENT, score 0, ALLOW")
    void absentReadingsNeverFire() {
        Verdict verdict = RiskEngineV1.evaluate(TRANSFER,
                EvaluationInputs.builder(RiskOperation.TRANSFER).amount(eur(100_00)).build());
        for (RuleHit hit : verdict.hits()) {
            if (hit.kind() == RuleKind.AMOUNT_BAND) {
                assertThat(hit.outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
            } else {
                assertThat(hit.outcome()).as("%s", hit).isEqualTo(RuleOutcome.ABSENT);
                assertThat(hit.judged()).isEqualTo("ABSENT");
            }
        }
        assertThat(verdict.decision()).contains(RiskDecision.ALLOW);
        assertThat(verdict.reasons()).isEmpty();
    }

    /** The precedence as the plan writes it (section 12.3) - spelled out, never derived from the enum's order. */
    private static final List<RiskDecision> PRECEDENCE = List.of(RiskDecision.DENY, RiskDecision.HOLD,
            RiskDecision.REVIEW, RiskDecision.CHALLENGE, RiskDecision.ALLOW);

    @Test
    @DisplayName("the precedence table: two always-firing hard rules on a withdrawal, every ordered pair - the stricter"
            + " decides, DENY > HOLD > REVIEW > CHALLENGE > ALLOW, the table spelled out here and not read from the enum")
    void thePrecedenceTable() {
        assertThat(PRECEDENCE).containsExactlyInAnyOrder(RiskDecision.values());
        for (RiskDecision a : PRECEDENCE) {
            for (RiskDecision b : PRECEDENCE) {
                RiskPolicy policy = withdrawal(List.of(
                        RiskRule.deciding(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT), a),
                        RiskRule.deciding(2, RuleKind.KYC_NOT_APPROVED, RuleParameters.none(RuleKind.KYC_NOT_APPROVED), b)),
                        List.of(new ScoreBand(0, 1000, RiskDecision.ALLOW)));
                Verdict verdict = RiskEngineV1.evaluate(policy, EvaluationInputs.builder(RiskOperation.WITHDRAWAL)
                        .amount(eur(10_00)).screeningHit(true).kycApproved(false).build());
                RiskDecision expected = PRECEDENCE.indexOf(a) <= PRECEDENCE.indexOf(b) ? a : b;
                assertThat(verdict.decision()).as("%s with %s", a, b).contains(expected);
                assertThat(verdict.reasons()).containsExactly(ReasonCode.SCREENING_HIT, ReasonCode.KYC_NOT_APPROVED);
            }
        }
        // And the band's place in the same table: a hard REVIEW over a CHALLENGE band is REVIEW, over a DENY band DENY.
        RiskPolicy review = withdrawal(
                List.of(RiskRule.deciding(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT),
                        RiskDecision.REVIEW)),
                List.of(new ScoreBand(0, 1000, RiskDecision.CHALLENGE)));
        assertThat(RiskEngineV1.evaluate(review, EvaluationInputs.builder(RiskOperation.WITHDRAWAL).amount(eur(10_00))
                .screeningHit(true).build()).decision()).contains(RiskDecision.REVIEW);
        RiskPolicy deny = withdrawal(
                List.of(RiskRule.deciding(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT),
                        RiskDecision.REVIEW)),
                List.of(new ScoreBand(0, 1000, RiskDecision.DENY)));
        assertThat(RiskEngineV1.evaluate(deny, EvaluationInputs.builder(RiskOperation.WITHDRAWAL).amount(eur(10_00))
                .screeningHit(true).build()).decision()).contains(RiskDecision.DENY);
    }

    @Test
    @DisplayName("the band at 0 decides when nothing fires: a challenge-everything policy answers CHALLENGE with no"
            + " fired rule and no reason")
    void theBandAtZeroDecides() {
        RiskPolicy policy = withdrawal(
                List.of(RiskRule.weighted(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT), 100)),
                List.of(new ScoreBand(0, 1000, RiskDecision.CHALLENGE)));
        Verdict verdict = RiskEngineV1.evaluate(policy,
                EvaluationInputs.builder(RiskOperation.WITHDRAWAL).amount(eur(10_00)).screeningHit(false).build());
        assertThat(verdict.fired()).isEmpty();
        assertThat(verdict.decision()).contains(RiskDecision.CHALLENGE);
        assertThat(verdict.reasons()).isEmpty();
    }

    @Test
    @DisplayName("the sweep: structuring (3 below EUR 1,000 summing EUR 2,650) and 85% out within the day score 900 -"
            + " the rating HIGH, no decision; nothing fired is LOW")
    void theMonitoringRating() {
        Map<WindowKind, Amounts> amounts = new EnumMap<>(WindowKind.class);
        amounts.put(WindowKind.D1, Amounts.of(950_00, 800_00, 900_00));
        Map<WindowKind, Flow> flows = new EnumMap<>(WindowKind.class);
        flows.put(WindowKind.D1, new Flow(1_000_00, 850_00));
        Verdict high = RiskEngineV1.evaluate(MONITORING, EvaluationInputs.builder(RiskOperation.MONITORING)
                .subject(SignalSubject.of(SubjectKind.PARTY, PARTY))
                .monitoring(new MonitoringReadings(Reading.absent(), flows, amounts, Map.of(), Map.of(), Map.of()))
                .build());
        assertThat(high.hits()).hasSize(11);
        assertThat(high.fired()).extracting(RuleHit::kind).containsExactly(RuleKind.STRUCTURING, RuleKind.RAPID_IN_OUT);
        assertThat(high.fired().get(0).judged()).isEqualTo("amounts:D1:n=3,below=3,belowSum=265000");
        assertThat(high.fired().get(1).judged()).isEqualTo("flow:D1:in=100000,out=85000");
        assertThat(high.score().value()).isEqualTo(900);
        assertThat(high.rating()).contains(RiskRating.HIGH);
        assertThat(high.decision()).isEmpty();
        assertThat(high.reasons()).containsExactly(ReasonCode.STRUCTURING, ReasonCode.RAPID_IN_OUT);

        Verdict low = RiskEngineV1.evaluate(MONITORING, EvaluationInputs.builder(RiskOperation.MONITORING).build());
        assertThat(low.hits()).allMatch(hit -> hit.outcome() == RuleOutcome.ABSENT);
        assertThat(low.rating()).contains(RiskRating.LOW);
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @DisplayName("the fail-safe with a policy is its band for the amount (C1): transfers ALLOW below EUR 1,000 and DENY"
            + " from it, withdrawals and cross-border DENY everywhere, payments ALLOW below EUR 250 - recorded"
            + " RISK_UNAVAILABLE with no hit and score 0")
    void theFailSafePerBand() {
        assertFailSafe(TRANSFER, 999_99, RiskDecision.ALLOW);
        assertFailSafe(TRANSFER, 1_000_00, RiskDecision.DENY);
        assertFailSafe(WITHDRAWAL, 0, RiskDecision.DENY);
        assertFailSafe(WITHDRAWAL, 50_000_00, RiskDecision.DENY);
        assertFailSafe(RiskPolicyFixtures.v1(RiskOperation.CROSS_BORDER), 1_00, RiskDecision.DENY);
        assertFailSafe(RiskPolicyFixtures.v1(RiskOperation.CARD_PAYMENT), 249_99, RiskDecision.ALLOW);
        assertFailSafe(RiskPolicyFixtures.v1(RiskOperation.WALLET_PAYMENT), 250_00, RiskDecision.DENY);
    }

    @Test
    @DisplayName("the compiled fail-safe with no policy is ALLOW under NO_POLICY_ACTIVE for every judged operation,"
            + " and nothing for the credit signal or the sweep")
    void theFailSafeWithNoPolicy() {
        for (RiskOperation operation : RiskOperation.values()) {
            if (!operation.judgesAMovement()) {
                assertThatThrownBy(() -> RiskEngineV1.failSafe(operation)).isInstanceOf(IllegalArgumentException.class);
                continue;
            }
            Verdict verdict = RiskEngineV1.failSafe(operation);
            assertThat(verdict.operation()).isEqualTo(operation);
            assertThat(verdict.decision()).contains(RiskDecision.ALLOW);
            assertThat(verdict.reasons()).containsExactly(ReasonCode.NO_POLICY_ACTIVE);
            assertThat(verdict.failSafeApplied()).isTrue();
            assertThat(verdict.hits()).isEmpty();
            assertThat(verdict.score()).isEqualTo(Score.ZERO);
        }
        assertThatThrownBy(() -> RiskEngineV1.failSafe(MONITORING, eur(1_00))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("P13-TSK-004's two fail-safes: an unread pin answers the compiled ALLOW under RISK_UNAVAILABLE (never"
            + " NO_POLICY_ACTIVE); an amount the bands cannot place answers the open-ended last band - DENY in every C1"
            + " band set")
    void theUnpinnedAndTheUnplacedFailSafes() {
        for (RiskOperation operation : RiskOperation.values()) {
            if (!operation.judgesAMovement()) {
                assertThatThrownBy(() -> RiskEngineV1.failSafeUnpinned(operation))
                        .isInstanceOf(IllegalArgumentException.class);
                continue;
            }
            Verdict unpinned = RiskEngineV1.failSafeUnpinned(operation);
            assertThat(unpinned.decision()).contains(RiskDecision.ALLOW);
            assertThat(unpinned.reasons()).containsExactly(ReasonCode.RISK_UNAVAILABLE);
            assertThat(unpinned.failSafeApplied()).isTrue();
            Verdict unplaced = RiskEngineV1.failSafeUnplaced(RiskPolicyFixtures.v1(operation));
            assertThat(unplaced.decision()).as(operation.name()).contains(RiskDecision.DENY);
            assertThat(unplaced.reasons()).containsExactly(ReasonCode.RISK_UNAVAILABLE);
        }
        assertThatThrownBy(() -> RiskEngineV1.failSafeUnplaced(MONITORING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("refused, never guessed: inputs of another operation, an amount in another currency, a rule that"
            + " throws - each EvaluationFailed naming its cause, the throwing rule its ordinal and kind")
    void theRefusals() {
        assertThatThrownBy(() -> RiskEngineV1.evaluate(TRANSFER,
                EvaluationInputs.builder(RiskOperation.WITHDRAWAL).amount(eur(1_00)).build()))
                .isInstanceOf(EvaluationFailed.class)
                .satisfies(e -> assertThat(((EvaluationFailed) e).failure()).isEqualTo(EvaluationFailed.Cause.WRONG_OPERATION));
        assertThatThrownBy(() -> RiskEngineV1.evaluate(TRANSFER, EvaluationInputs.builder(RiskOperation.TRANSFER)
                .amount(Money.ofMinorUnits(1_00, CurrencyCode.of("USD"))).build()))
                .isInstanceOf(EvaluationFailed.class)
                .satisfies(e -> assertThat(((EvaluationFailed) e).failure()).isEqualTo(EvaluationFailed.Cause.CURRENCY_MISMATCH));
        assertThatThrownBy(() -> RiskEngineV1.failSafe(TRANSFER, Money.ofMinorUnits(1_00, CurrencyCode.of("USD"))))
                .isInstanceOf(EvaluationFailed.class);
        // P13-TSK-004: a modulus of zero once passed the policy door and made ROUND_AMOUNT throw - the engine's only
        // throwing fixture. The door now refuses it (a modulus of at least one minor unit), so no valid policy carries a
        // rule that throws on admitted inputs; the engine's wrapping of a throwing rule - its defence in depth - is
        // proven through the judge seam: rule 2 throws as a rule's arithmetic would, and the engine wraps it, names the
        // rule, and gives no partial verdict.
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR,
                List.of(RiskRule.weighted(1, RuleKind.ROUND_AMOUNT, RuleParameters.of(RuleKind.ROUND_AMOUNT,
                        Map.of("modulus_minor", RiskPolicyFixtures.minor(0))), 100)),
                List.of(), List.of(new RatingBand(0, 1000, RiskRating.LOW)), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty()))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("modulus of at least one");
        RiskPolicy rounding = new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR,
                List.of(RiskRule.weighted(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT), 100),
                        RiskRule.weighted(2, RuleKind.ROUND_AMOUNT, RuleParameters.of(RuleKind.ROUND_AMOUNT,
                                Map.of("modulus_minor", RiskPolicyFixtures.minor(1))), 100)),
                List.of(), List.of(new RatingBand(0, 1000, RiskRating.LOW)), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty());
        RiskEngineV1.RuleJudge throwsOnTheSecond = (rule, in) -> {
            if (rule.ordinal() == 2) {
                throw new ArithmeticException("/ by zero");
            }
            return RuleEvaluation.of(rule.kind()).judge(rule, in);
        };
        assertThatThrownBy(() -> RiskEngineV1.evaluate(rounding,
                EvaluationInputs.builder(RiskOperation.MONITORING).amount(eur(5_00)).screeningHit(true).build(),
                throwsOnTheSecond))
                .isInstanceOf(EvaluationFailed.class)
                .satisfies(e -> {
                    EvaluationFailed failed = (EvaluationFailed) e;
                    assertThat(failed.failure()).isEqualTo(EvaluationFailed.Cause.RULE_THREW);
                    assertThat(failed.ruleOrdinal()).hasValue(2);
                    assertThat(failed.ruleKind()).contains(RuleKind.ROUND_AMOUNT);
                    assertThat(failed.getMessage()).doesNotContain("500");
                });
    }

    @Test
    @DisplayName("the model holds itself: a judged operation's inputs carry an amount, a hit that did not fire"
            + " contributes nothing, the score is 0 to 1000, a verdict's reasons are its fired hits' codes")
    void theModelRefusesTheMalformed() {
        assertThatThrownBy(() -> EvaluationInputs.builder(RiskOperation.TRANSFER).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationInputs.builder(RiskOperation.CREDIT_SIGNAL).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationInputs.builder(RiskOperation.TRANSFER).amount(eur(1_00)).corridor("EURUSD").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleHit(1, RuleKind.SCREENING_HIT, RuleOutcome.NOT_FIRED, 10, Optional.empty(), "x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleHit(1, RuleKind.SCREENING_HIT, RuleOutcome.ABSENT, 0, Optional.empty(), "x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Score(1001)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Score.clamped(5_000)).isEqualTo(new Score(1000));
        assertThat(Score.clamped(-1)).isEqualTo(Score.ZERO);
        assertThatThrownBy(() -> new VelocityReading(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Amounts(java.util.Collections.nCopies(EvaluationInputs.MAX_AMOUNTS + 1, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        RuleHit fired = new RuleHit(1, RuleKind.SCREENING_HIT, RuleOutcome.FIRED, 100, Optional.empty(), "screeningHit=true");
        assertThatThrownBy(() -> new Verdict(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, List.of(fired),
                new Score(100), Optional.of(RiskDecision.ALLOW), Optional.empty(), List.of(), false))
                .as("the reasons are the fired hits' codes").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Verdict(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, List.of(fired),
                new Score(100), Optional.of(RiskDecision.HOLD), Optional.empty(), List.of(ReasonCode.SCREENING_HIT), false))
                .as("a transfer admits no HOLD").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Verdict(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, List.of(), Score.ZERO,
                Optional.of(RiskDecision.ALLOW), Optional.empty(), List.of(ReasonCode.CHALLENGE_REQUIRED), true))
                .as("the seam's reason is never a verdict's").isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ the bytes

    @Test
    @DisplayName("format 1, pinned: the canonical bytes of one inputs value and of its verdict are exactly these")
    void theCanonicalBytesArePinned() {
        EvaluationInputs inputs = EvaluationInputs.builder(RiskOperation.TRANSFER)
                .subject(SignalSubject.of(SubjectKind.PARTY, PARTY))
                .amount(eur(1_200_00))
                .velocity(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1, 2, 300_00)
                .payeeFirstSeen(FirstSeen.never())
                .deviceFirstSeen(FirstSeen.daysAgo(45))
                .sessionAssurance(RiskAssurance.MULTI_FACTOR)
                .credentialChange(LastSeen.never())
                .accountAgeDays(400)
                .screeningHit(false)
                .profileRating(RiskRating.LOW)
                .build();
        String canonicalInputs = "{\"format\":\"1\",\"kind\":\"inputs\",\"operation\":\"TRANSFER\","
                + "\"subjects\":[{\"kind\":\"PARTY\",\"ref\":\"00000000-0000-7000-8000-000000000001\"}],"
                + "\"amount\":{\"minor\":\"120000\",\"currency\":\"EUR\",\"scale\":\"2\"},"
                + "\"velocity\":[{\"dimension\":\"SOURCE_ACCOUNT\",\"window\":\"D1\",\"count\":\"2\",\"sum\":\"30000\"}],"
                + "\"payeeFirstSeen\":{\"days\":\"NEVER\"},\"deviceFirstSeen\":{\"days\":\"45\"},"
                + "\"sessionAssurance\":{\"value\":\"MULTI_FACTOR\"},\"authFailures\":[],"
                + "\"credentialChange\":{\"hours\":\"NEVER\"},\"recovery\":{\"absent\":\"true\"},"
                + "\"accountAgeDays\":{\"value\":\"400\"},\"kycApproved\":{\"absent\":\"true\"},"
                + "\"screeningHit\":{\"value\":\"false\"},\"profileRating\":{\"value\":\"LOW\"},"
                + "\"merchantSuspended\":{\"absent\":\"true\"},\"corridor\":{\"absent\":\"true\"},"
                + "\"monitoring\":{\"dormantDays\":{\"absent\":\"true\"},\"flows\":[],\"amounts\":[],\"refunds\":[],"
                + "\"chargebacks\":[],\"bnplApplications\":[]}}";
        assertThat(CanonicalEvaluation.render(inputs)).isEqualTo(canonicalInputs);
        assertThat(CanonicalEvaluation.parseInputs(canonicalInputs)).isEqualTo(inputs);

        Verdict verdict = RiskEngineV1.evaluate(TRANSFER, inputs);
        String canonicalVerdict = "{\"format\":\"1\",\"kind\":\"verdict\",\"engine\":\"RISK_ENGINE_V1\",\"operation\":\"TRANSFER\","
                + "\"hits\":["
                + hit(1, "AMOUNT_BAND", "FIRED", 0, "CHALLENGE", "amount=120000") + ","
                + hit(2, "AMOUNT_BAND", "NOT_FIRED", 0, "NONE", "amount=120000") + ","
                + hit(3, "VELOCITY_COUNT", "NOT_FIRED", 0, "NONE", "velocity:SOURCE_ACCOUNT:D1:count=2") + ","
                + hit(4, "VELOCITY_SUM", "ABSENT", 0, "NONE", "ABSENT") + ","
                + hit(5, "NEW_PAYEE", "FIRED", 250, "NONE", "payee:daysSinceFirstSeen=NEVER") + ","
                + hit(6, "AMOUNT_BAND", "FIRED", 200, "NONE", "amount=120000") + ","
                + hit(7, "NEW_DEVICE", "NOT_FIRED", 0, "NONE", "device:daysSinceFirstSeen=45") + ","
                + hit(8, "SESSION_ASSURANCE_BELOW", "NOT_FIRED", 0, "NONE", "assurance=MULTI_FACTOR") + ","
                + hit(9, "SCREENING_HIT", "NOT_FIRED", 0, "NONE", "screeningHit=false") + ","
                + hit(10, "PROFILE_RATING", "NOT_FIRED", 0, "NONE", "rating=LOW") + ","
                + hit(11, "PROFILE_RATING", "NOT_FIRED", 0, "NONE", "rating=LOW")
                + "],\"score\":\"450\",\"decision\":\"CHALLENGE\",\"rating\":\"NONE\","
                + "\"reasons\":[\"AMOUNT_BAND\",\"NEW_PAYEE\",\"AMOUNT_BAND\"],\"failSafeApplied\":\"false\"}";
        assertThat(CanonicalEvaluation.render(verdict)).isEqualTo(canonicalVerdict);
        assertThat(CanonicalEvaluation.sha256(canonicalVerdict)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(CanonicalEvaluation.render(RiskEngineV1.failSafe(RiskOperation.TRANSFER))).isEqualTo(
                "{\"format\":\"1\",\"kind\":\"verdict\",\"engine\":\"RISK_ENGINE_V1\",\"operation\":\"TRANSFER\",\"hits\":[],"
                        + "\"score\":\"0\",\"decision\":\"ALLOW\",\"rating\":\"NONE\",\"reasons\":[\"NO_POLICY_ACTIVE\"],"
                        + "\"failSafeApplied\":\"true\"}");
    }

    // ------------------------------------------------------------------ the shapes

    /** A transfer with every reading present and benign: an old payee and device, MFA, approved, clear, LOW. */
    private static EvaluationInputs.Builder quietTransfer(long amountMinor) {
        return EvaluationInputs.builder(RiskOperation.TRANSFER)
                .subject(SignalSubject.of(SubjectKind.PARTY, PARTY))
                .amount(eur(amountMinor))
                .velocity(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1, 2, 300_00)
                .velocity(VelocityDimension.PARTY, WindowKind.D1, 3, 400_00)
                .payeeFirstSeen(FirstSeen.daysAgo(200))
                .deviceFirstSeen(FirstSeen.daysAgo(100))
                .sessionAssurance(RiskAssurance.MULTI_FACTOR)
                .screeningHit(false)
                .profileRating(RiskRating.LOW);
    }

    private static RiskPolicy withdrawal(List<RiskRule> rules, List<ScoreBand> bands) {
        return new RiskPolicy(RiskOperation.WITHDRAWAL, RiskEngine.RISK_ENGINE_V1, EUR, rules, bands, List.of(),
                List.of(new FailSafeBand(0, OptionalLong.empty(), RiskDecision.DENY)), Optional.of(RiskAssurance.MULTI_FACTOR),
                Optional.of(eur(1_000_00)), Optional.of(Duration.ofHours(72)));
    }

    private static void assertFailSafe(RiskPolicy policy, long amountMinor, RiskDecision expected) {
        Verdict verdict = RiskEngineV1.failSafe(policy, eur(amountMinor));
        assertThat(verdict.operation()).isEqualTo(policy.operation());
        assertThat(verdict.decision()).as("%s at %d", policy.operation(), amountMinor).contains(expected);
        assertThat(verdict.reasons()).containsExactly(ReasonCode.RISK_UNAVAILABLE);
        assertThat(verdict.failSafeApplied()).isTrue();
        assertThat(verdict.hits()).isEmpty();
        assertThat(verdict.score()).isEqualTo(Score.ZERO);
        assertThat(verdict.rating()).isEmpty();
    }

    private static String hit(int ordinal, String kind, String outcome, int contribution, String hard, String judged) {
        return "{\"ordinal\":\"" + ordinal + "\",\"kind\":\"" + kind + "\",\"outcome\":\"" + outcome
                + "\",\"contribution\":\"" + contribution + "\",\"hardDecision\":\"" + hard + "\",\"judged\":\"" + judged + "\"}";
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
