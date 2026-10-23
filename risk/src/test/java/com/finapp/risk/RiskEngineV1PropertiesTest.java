package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RISK_ENGINE_V1}'s properties over the seeded corpus (`P13-TSK-003`; ADR-0109 decision 3;
 * {@code INV-RSK-01}, {@code INV-RSK-04}): determinism, independence of the inputs' presentation order, the score
 * within bounds and equal to the fired contributions clamped, the decision admitted and never softer than a fired
 * hard decision, the reasons the fired rules' codes in order, monotonicity where the kind implies it, the
 * {@code MONITORING} verdict a rating, and the canonical form's strict round trip. Every figure is re-derived by the
 * test from the hits, never read back from the engine's own total.
 */
@DisplayName("risk engine v1: properties over a seeded corpus (P13-TSK-003)")
class RiskEngineV1PropertiesTest {

    private static final int PER_OPERATION = 80;
    private static final List<EvaluationCorpus.Case> CORPUS = EvaluationCorpus.generate(EvaluationCorpus.SEED, PER_OPERATION);

    @Test
    @DisplayName("re-evaluating the same inputs gives an equal verdict, the same canonical bytes and the same SHA-256")
    void determinism() {
        for (EvaluationCorpus.Case c : CORPUS) {
            Verdict first = RiskEngineV1.evaluate(c.policy(), c.inputs());
            Verdict second = RiskEngineV1.evaluate(c.policy(), c.inputs());
            assertThat(second).isEqualTo(first);
            String rendered = CanonicalEvaluation.render(first);
            assertThat(CanonicalEvaluation.render(second)).isEqualTo(rendered);
            assertThat(CanonicalEvaluation.sha256(rendered)).isEqualTo(CanonicalEvaluation.sha256(CanonicalEvaluation.render(second)));
        }
        assertThat(CORPUS).hasSize(PER_OPERATION * 6);
    }

    @Test
    @DisplayName("the readings presented in another order are the same value, the same bytes and the same verdict")
    void presentationOrderDoesNotMatter() {
        for (EvaluationCorpus.Case c : CORPUS) {
            EvaluationInputs inputs = c.inputs();
            EvaluationInputs reversed = new EvaluationInputs(inputs.operation(), reversed(inputs.subjects()),
                    inputs.amount(), reversed(inputs.velocity()), inputs.payeeFirstSeen(), inputs.deviceFirstSeen(),
                    inputs.sessionAssurance(), reversed(inputs.authFailures()), inputs.credentialChange(), inputs.recovery(),
                    inputs.accountAgeDays(), inputs.kycApproved(), inputs.screeningHit(), inputs.profileRating(),
                    inputs.merchantSuspended(), inputs.corridor(), new MonitoringReadings(
                            inputs.monitoring().dormantDays(), reversed(inputs.monitoring().flows()),
                            reversed(inputs.monitoring().amounts()), reversed(inputs.monitoring().refunds()),
                            reversed(inputs.monitoring().chargebacks()), reversed(inputs.monitoring().bnplApplications())));
            assertThat(reversed).isEqualTo(inputs);
            assertThat(CanonicalEvaluation.render(reversed)).isEqualTo(CanonicalEvaluation.render(inputs));
            assertThat(RiskEngineV1.evaluate(c.policy(), reversed)).isEqualTo(RiskEngineV1.evaluate(c.policy(), inputs));
        }
    }

    @Test
    @DisplayName("the score is within [0, 1000] and equals the fired contributions summed and clamped; every rule has"
            + " its hit in declared order; the reasons are the fired rules' codes in that order")
    void theScoreAndTheHits() {
        int clamped = 0;
        for (EvaluationCorpus.Case c : CORPUS) {
            Verdict verdict = RiskEngineV1.evaluate(c.policy(), c.inputs());
            assertThat(verdict.hits()).hasSize(c.policy().rules().size());
            long sum = 0;
            List<ReasonCode> reasons = new ArrayList<>();
            for (int i = 0; i < verdict.hits().size(); i++) {
                RuleHit hit = verdict.hits().get(i);
                RiskRule rule = c.policy().rules().get(i);
                assertThat(hit.ordinal()).isEqualTo(i + 1);
                assertThat(hit.kind()).isEqualTo(rule.kind());
                if (hit.fired()) {
                    assertThat(hit.contribution()).isEqualTo(rule.weight().orElse(0));
                    assertThat(hit.hardDecision()).isEqualTo(rule.hardDecision());
                    sum += hit.contribution();
                    reasons.add(ReasonCode.valueOf(rule.kind().name()));
                } else {
                    assertThat(hit.contribution()).isZero();
                    assertThat(hit.hardDecision()).isEmpty();
                }
            }
            assertThat(verdict.score().value()).isBetween(0, 1000);
            assertThat(verdict.score().value()).isEqualTo((int) Math.min(sum, 1000));
            if (sum > 1000) {
                clamped++;
            }
            assertThat(verdict.reasons()).isEqualTo(reasons);
            assertThat(verdict.failSafeApplied()).isFalse();
            assertThat(verdict.engine()).isEqualTo(RiskEngine.RISK_ENGINE_V1);
        }
        assertThat(clamped).as("the corpus reaches the clamp").isPositive();
    }

    @Test
    @DisplayName("a judged operation's decision is one it admits and never softer than any fired hard decision; the"
            + " sweep's verdict is a rating and no decision")
    void theDecision() {
        int hardened = 0;
        for (EvaluationCorpus.Case c : CORPUS) {
            Verdict verdict = RiskEngineV1.evaluate(c.policy(), c.inputs());
            if (c.inputs().operation() == RiskOperation.MONITORING) {
                assertThat(verdict.decision()).isEmpty();
                assertThat(verdict.rating()).isPresent();
                assertThat(verdict.rating().get()).isNotEqualTo(RiskRating.PROHIBITED);
                continue;
            }
            assertThat(verdict.rating()).isEmpty();
            RiskDecision decision = verdict.decision().orElseThrow();
            assertThat(decision.isAdmittedBy(c.inputs().operation())).isTrue();
            RiskDecision band = c.policy().scoreBands().stream()
                    .filter(b -> b.covers(verdict.score().value())).findFirst().orElseThrow().decision();
            RiskDecision expected = band;
            for (RuleHit hit : verdict.hits()) {
                if (hit.hardDecision().isPresent()) {
                    assertThat(RiskDecision.strictest(decision, hit.hardDecision().get())).isEqualTo(decision);
                    expected = RiskDecision.strictest(expected, hit.hardDecision().get());
                }
            }
            assertThat(decision).isEqualTo(expected);
            if (expected != band) {
                hardened++;
            }
        }
        assertThat(hardened).as("the corpus has hard decisions beating the band").isPositive();
    }

    @Test
    @DisplayName("monotone where the kind implies it: a larger amount never un-fires an AMOUNT_BAND hit, more velocity"
            + " never un-fires a VELOCITY_COUNT or VELOCITY_SUM hit, and on a judged operation neither lowers the score"
            + " - the score is monotone in the contributions")
    void monotonicity() {
        Random random = new Random(EvaluationCorpus.SEED + 1);
        int amountChecks = 0;
        int velocityChecks = 0;
        for (EvaluationCorpus.Case c : CORPUS) {
            if (c.inputs().amount().value().isEmpty()) {
                continue;
            }
            Verdict before = RiskEngineV1.evaluate(c.policy(), c.inputs());
            Money amount = c.inputs().amount().value().get();
            Money larger = Money.ofMinorUnits(amount.minorUnits() + random.nextInt(20_000_00), amount.currency());
            Verdict withLarger = RiskEngineV1.evaluate(c.policy(), with(c.inputs(), larger, 0));
            Verdict withMoreVelocity = RiskEngineV1.evaluate(c.policy(), with(c.inputs(), amount, 1 + random.nextInt(50)));
            if (c.inputs().operation().judgesAMovement()) {
                // Only AMOUNT_BAND reads the amount on a judged fixture (ROUND_AMOUNT is the sweep's), and only the
                // velocity kinds read the figures: more of either adds contributions and never removes one.
                assertThat(withLarger.score().value()).isGreaterThanOrEqualTo(before.score().value());
                assertThat(withMoreVelocity.score().value()).isGreaterThanOrEqualTo(before.score().value());
                assertThat(RiskDecision.strictest(withLarger.decision().orElseThrow(), before.decision().orElseThrow()))
                        .as("a larger amount never softens the decision").isEqualTo(withLarger.decision().orElseThrow());
            }
            for (int i = 0; i < before.hits().size(); i++) {
                RuleHit hit = before.hits().get(i);
                if (hit.fired() && hit.kind() == RuleKind.AMOUNT_BAND) {
                    assertThat(withLarger.hits().get(i).outcome()).isEqualTo(RuleOutcome.FIRED);
                    amountChecks++;
                }
                if (hit.fired() && (hit.kind() == RuleKind.VELOCITY_COUNT || hit.kind() == RuleKind.VELOCITY_SUM)) {
                    assertThat(withMoreVelocity.hits().get(i).outcome()).isEqualTo(RuleOutcome.FIRED);
                    velocityChecks++;
                }
            }
        }
        assertThat(amountChecks).isPositive();
        assertThat(velocityChecks).isPositive();
    }

    @Test
    @DisplayName("the canonical inputs form round-trips strictly: parsed back to an equal value that renders the same"
            + " bytes, and a text the form would not write is refused")
    void theCanonicalFormRoundTrips() {
        for (EvaluationCorpus.Case c : CORPUS) {
            String canonical = CanonicalEvaluation.render(c.inputs());
            EvaluationInputs parsed = CanonicalEvaluation.parseInputs(canonical);
            assertThat(parsed).isEqualTo(c.inputs());
            assertThat(CanonicalEvaluation.render(parsed)).isEqualTo(canonical);
            assertThat(RiskEngineV1.evaluate(c.policy(), parsed)).isEqualTo(RiskEngineV1.evaluate(c.policy(), c.inputs()));
        }
        String canonical = CanonicalEvaluation.render(CORPUS.get(0).inputs());
        assertThatThrownBy(() -> CanonicalEvaluation.parseInputs(canonical.replace("\"format\":\"1\"", "\"format\":\"2\"")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalEvaluation.parseInputs(canonical.replace(",", ", ")))
                .as("whitespace is not canonical").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalEvaluation.parseInputs(canonical.replace("\"scale\":\"2\"", "\"scale\":\"02\"")))
                .as("a non-canonical number is refused by the re-render").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalEvaluation.parseInputs(canonical + " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ the shapes

    private static EvaluationInputs with(EvaluationInputs inputs, Money amount, int moreVelocity) {
        Map<VelocityKey, VelocityReading> velocity = new LinkedHashMap<>();
        for (Map.Entry<VelocityKey, VelocityReading> entry : inputs.velocity().entrySet()) {
            velocity.put(entry.getKey(), new VelocityReading(entry.getValue().count() + moreVelocity,
                    entry.getValue().sumMinor() + moreVelocity * 100_00L));
        }
        return new EvaluationInputs(inputs.operation(), inputs.subjects(), Reading.of(amount), velocity,
                inputs.payeeFirstSeen(), inputs.deviceFirstSeen(), inputs.sessionAssurance(), inputs.authFailures(),
                inputs.credentialChange(), inputs.recovery(), inputs.accountAgeDays(), inputs.kycApproved(),
                inputs.screeningHit(), inputs.profileRating(), inputs.merchantSuspended(), inputs.corridor(),
                inputs.monitoring());
    }

    private static <K, V> Map<K, V> reversed(Map<K, V> map) {
        List<Map.Entry<K, V>> entries = new ArrayList<>(map.entrySet());
        Map<K, V> reversed = new LinkedHashMap<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            reversed.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return reversed;
    }
}
