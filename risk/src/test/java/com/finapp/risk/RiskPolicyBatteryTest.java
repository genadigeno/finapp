package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.RiskBattery.Outcome;
import com.finapp.risk.RiskBatteryWorld.Cohort;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy battery (`P13-TST-002`; {@code PHASE_13_PLAN.md} section 13.3; ADR-0109 decisions 3-4; {@code INV-RSK-01},
 * {@code INV-RSK-04}, {@code INV-RSK-05}, {@code INV-MON-01}): at least 10,000 generated subjects and their movement
 * sequences ({@link RiskBatteryWorld}) judged through {@code RISK_ENGINE_V1} under every v1 policy version, every verdict
 * the battery's own ({@link RiskBatteryOracles} - never the engine's code, never the fixture object), every seal replayed
 * {@code IDENTICAL} through `-004`'s path and explained by `-023`'s, the whole population written to the same lines by a
 * second JVM, and the fingerprint the activation gate records ({@code OPERATIONS_RUNBOOK.md} section 9.1 step 1).
 *
 * <ul>
 *   <li>every v1 fixture is exactly the version the battery states - a band edge moved in a fixture is red here, naming the
 *       version;
 *   <li>every engine verdict is the oracle's, rule by rule: outcome, contribution, hard decision; the score; the decision or
 *       rating; the reasons; the fail-safe by cause and band; the refusal of an amount the version cannot place;
 *   <li>every judged seal replays {@code IDENTICAL} under its pinned version, every sweep's seal re-evaluates to its digest,
 *       every explanation names the oracle's step, deciding rules and band;
 *   <li>the battery's window arithmetic is production's ({@code WindowStarts}) on every movement;
 *   <li>a second JVM's lines equal this one's, every line; the fingerprints equal the committed ones.
 * </ul>
 *
 * The perturbations are {@code RiskBatteryFlipTest}'s, the floors {@code RiskBatteryCoverageTest}'s - one run per JVM.
 * Hermetic: nothing but the JVM. {@code FINAPP_RISK_BATTERY_SUBJECTS} shrinks it for a probe only - and fails the census.
 */
@DisplayName("P13-TST-002 - the policy battery: exact, replayed IDENTICAL in two JVMs, fingerprinted")
class RiskPolicyBatteryTest {

    private static RiskBattery.Run run;

    @BeforeAll
    static void battery() throws Exception {
        run = RiskBattery.run();
    }

    @Test
    @DisplayName("the census: at least 10,000 subjects over every cohort, every v1 version judged and replayed, the"
            + " population's, every version's and the battery's fingerprints equal to the committed ones")
    void theCensus() {
        assertThat(run.subjects()).as("subjects").isGreaterThanOrEqualTo(10_000);
        assertThat(run.outcomes()).as("one outcome per subject").hasSize(run.subjects());
        Map<Cohort, Long> cohorts = new EnumMap<>(Cohort.class);
        run.outcomes().forEach(outcome -> cohorts.merge(outcome.cohort(), 1L, Long::sum));
        assertThat(cohorts.keySet()).as("every cohort").containsExactlyInAnyOrder(Cohort.values());
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            String version = RiskBatteryOracles.v1(operation).name();
            assertThat(run.coverage().getOrDefault("evaluated|" + version, 0L)).as("evaluations under " + version)
                    .isGreaterThanOrEqualTo(10_000L);
        }
        long evaluations = run.outcomes().stream().mapToLong(Outcome::evaluations).sum();
        assertThat(evaluations).as("evaluations - at least twenty per subject").isGreaterThanOrEqualTo(20L * run.subjects());
        assertThat(run.population()).as("the population's fingerprint (seed " + RiskBatteryWorld.SEED + ")")
                .isEqualTo(RiskBattery.POPULATION);
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            assertThat(run.versions().get(operation)).as("the fingerprint of " + RiskBatteryOracles.v1(operation).name())
                    .isEqualTo(RiskBattery.VERSIONS.get(operation));
        }
        assertThat(run.fingerprint()).as("the battery's fingerprint").isEqualTo(RiskBattery.FINGERPRINT);
    }

    @Test
    @DisplayName("every v1 fixture is exactly the version the battery states - its rules, parameters, weights, hard"
            + " decisions, bands, fail-safe bands and knobs (the version is the data it says)")
    void everyV1FixtureIsTheVersionTheBatteryStates() {
        List<String> differing = new ArrayList<>();
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            String version = RiskBatteryOracles.v1(operation).name();
            Optional<RiskPolicy> stated = RiskBattery.stated(operation);
            RiskPolicy fixture = RiskBattery.FIXTURES.get(operation);
            if (stated.isEmpty()) {
                differing.add(version + ": the battery's statement is no valid version");
            } else if (!stated.get().equals(fixture)) {
                differing.add(version + ": the fixture is not the version the battery states - fixture "
                        + RiskBattery.content(fixture) + " / stated " + RiskBattery.content(stated.get()));
            }
        }
        assertThat(differing).as("versions that are not the data they say").isEmpty();
    }

    @Test
    @DisplayName("every verdict is the oracle's: every rule's outcome, contribution and hard decision, the score clamped,"
            + " the precedence, the decision or rating, the reasons, the fail-safe by cause and band, the refusal of a"
            + " currency the version cannot place")
    void everyVerdictIsTheOraclesOwn() {
        List<String> differing = run.outcomes().stream().flatMap(outcome -> outcome.oracle().stream()).toList();
        System.out.println("P13-TST-002 ORACLE: " + differing.size() + " differences"
                + (differing.isEmpty() ? "" : " - " + byVersion(differing)));
        assertThat(differing.stream().limit(25).toList()).as(differing.size() + " verdicts not the oracle's").isEmpty();
        long agreed = run.outcomes().stream().mapToLong(outcome -> outcome.summary().agreed()).sum();
        long evaluations = run.outcomes().stream().mapToLong(Outcome::evaluations).sum();
        assertThat(agreed).as("verdicts agreeing with the oracle").isEqualTo(evaluations);
    }

    @Test
    @DisplayName("every judged seal replays IDENTICAL under its pinned version and engine from its sealed text, every"
            + " fail-safe among them; every sweep's seal re-evaluates to its digest")
    void everySealReplaysIdentical() {
        List<String> diverged = run.outcomes().stream().flatMap(outcome -> outcome.replays().stream()).toList();
        long judged = run.outcomes().stream().mapToLong(outcome -> outcome.summary().judged()).sum();
        long monitored = run.outcomes().stream().mapToLong(outcome -> outcome.summary().monitored()).sum();
        long failSafes = run.outcomes().stream().mapToLong(outcome -> outcome.summary().failSafes()).sum();
        long identical = run.outcomes().stream().mapToLong(outcome -> outcome.summary().identical()).sum();
        System.out.println("P13-TST-002 REPLAY: judged=" + judged + " (failSafe=" + failSafes + ") monitoring="
                + monitored + " identical=" + identical + " diverged=" + diverged.size());
        assertThat(diverged.stream().limit(25).toList()).as(diverged.size() + " seals not replaying IDENTICAL").isEmpty();
        assertThat(identical).as("every seal IDENTICAL").isEqualTo(judged + monitored);
    }

    @Test
    @DisplayName("every explanation names the oracle's step (hard rule, score band or fail-safe), the rules that decided"
            + " and the band reached, by position")
    void everyExplanationNamesTheOraclesStepAndBand() {
        List<String> wrong = run.outcomes().stream().flatMap(outcome -> outcome.explanations().stream()).toList();
        assertThat(wrong.stream().limit(25).toList()).as(wrong.size() + " explanations not the oracle's").isEmpty();
    }

    @Test
    @DisplayName("the battery's window arithmetic - tumbling buckets anchored at Monday 1970-01-05 UTC - is production's"
            + " WindowStarts on every evaluation, every window kind")
    void theBatterysWindowsAreProductions() {
        List<String> wrong = run.outcomes().stream().flatMap(outcome -> outcome.windowStarts().stream()).toList();
        assertThat(wrong.stream().limit(25).toList()).as(wrong.size() + " window starts differing").isEmpty();
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - generates,"
            + " judges, seals and replays every subject to the same line, byte for byte")
    void aSecondJvmReplaysIdentically() {
        System.out.println("P13-TST-002 TWO-JVM: ours=" + run.ours().size() + " theirs=" + run.theirs().size()
                + " totals(ours)=" + run.ours().get(run.ours().size() - 1));
        assertThat(run.ours()).as("one line per subject and the totals").hasSize(run.subjects() + 1);
        assertThat(run.theirs()).as("the second JVM's lines, compared line for line").isEqualTo(run.ours());
    }

    private static String byVersion(List<String> differing) {
        return differing.stream().collect(Collectors.groupingBy(line -> line.replaceAll("^subject \\d+ #\\d+ ", "")
                .replaceAll(":.*$", ""), java.util.TreeMap::new, Collectors.counting())).toString();
    }
}
