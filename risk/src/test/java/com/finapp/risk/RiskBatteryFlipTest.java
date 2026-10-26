package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.RiskBattery.Flip;
import com.finapp.risk.RiskBattery.Outcome;
import com.finapp.risk.RiskBattery.Perturbation;
import com.finapp.risk.RiskBatteryWorld.Cohort;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy battery's perturbations (`P13-TST-002`; {@code PHASE_13_PLAN.md} section 13.3; {@code INV-RSK-01},
 * {@code INV-RSK-05}): one per subject, its kind and its target drawn by a mix of the index and never by the generator's
 * walk ({@link RiskBattery#mix}) - a sealed reading moved across the first rule that reads it, a rule's parameter moved
 * across the subject's reading, a band moved under the score (or a fail-safe band's decision flipped), the version swapped
 * for the battery's successor - each replayed through `-004`'s path (or the sweep's seal re-evaluated) and required to
 * flip and to <strong>name its row</strong> ({@link Flip#namesItsRow}: the first diverged rule is the perturbed one; a band
 * diverges on the decision alone and the explanation names the band moved), the engine's perturbed verdict the oracle's.
 * Beside them the <strong>control</strong> - every seal replayed under the battery's own statement, unperturbed - flips
 * nothing, and the <strong>swapped slice</strong> - one subject in ten replayed whole under the successor versions -
 * diverges exactly where the oracle predicts and nowhere else: a version's content swapped under its pin is seen
 * ({@code INV-RSK-05}). A perturbation that flips nothing fails here, naming the perturbation, the subject and the version.
 */
@DisplayName("P13-TST-002 - the perturbations: a reading, a parameter, a band, the version - each flips and names its row")
class RiskBatteryFlipTest {

    /** At least this many subjects per perturbation kind (the BNPL battery's precedent). */
    static final int PER_KIND = 500;

    private static RiskBattery.Run run;

    @BeforeAll
    static void battery() throws Exception {
        run = RiskBattery.run();
    }

    @Test
    @DisplayName("every subject is perturbed once, every kind on at least 500 subjects, across every cohort and every v1"
            + " version - the selections the mix's, never the walk's")
    void everySubjectIsPerturbedAcrossThePopulation() {
        List<Outcome> unperturbed = run.outcomes().stream().filter(outcome -> outcome.flip().isEmpty()).toList();
        assertThat(unperturbed.stream().map(Outcome::index).limit(20).toList()).as("subjects no perturbation applied to")
                .isEmpty();
        Map<Perturbation, Long> kinds = new EnumMap<>(Perturbation.class);
        Map<Perturbation, Set<Cohort>> cohorts = new EnumMap<>(Perturbation.class);
        Map<Perturbation, Set<String>> versions = new EnumMap<>(Perturbation.class);
        for (Outcome outcome : run.outcomes()) {
            outcome.flip().ifPresent(flip -> {
                kinds.merge(flip.kind(), 1L, Long::sum);
                cohorts.computeIfAbsent(flip.kind(), ignored -> EnumSet.noneOf(Cohort.class)).add(outcome.cohort());
                versions.computeIfAbsent(flip.kind(), ignored -> new java.util.TreeSet<>()).add(flip.version());
            });
        }
        System.out.println("P13-TST-002 FLIPS: " + kinds + " versions=" + versions);
        for (Perturbation kind : Perturbation.values()) {
            assertThat(kinds.getOrDefault(kind, 0L)).as("subjects perturbed by " + kind).isGreaterThanOrEqualTo(PER_KIND);
            assertThat(cohorts.get(kind)).as(kind + " across every cohort").containsExactlyInAnyOrder(Cohort.values());
            assertThat(versions.get(kind)).as(kind + " across every v1 version").hasSize(
                    RiskBatteryOracles.OPERATIONS.size());
        }
    }

    @Test
    @DisplayName("a sealed reading moved across the first rule reading it, re-sealed as a forger would: DIVERGED, the"
            + " first diverged rule that rule - every rule kind named by a reading or a parameter")
    void aPerturbedReadingFlipsAndNamesItsRule() {
        assertFlipped(Perturbation.READING);
        Set<RuleKind> named = EnumSet.noneOf(RuleKind.class);
        for (Outcome outcome : run.outcomes()) {
            outcome.flip().filter(flip -> flip.kind() == Perturbation.READING || flip.kind() == Perturbation.PARAMETER)
                    .flatMap(Flip::rule).ifPresent(named::add);
        }
        assertThat(named).as("rule kinds named by a reading or a parameter").containsExactlyInAnyOrder(RuleKind.values());
    }

    @Test
    @DisplayName("a rule's parameter moved across the subject's reading, the seal replayed under that version: DIVERGED,"
            + " the first diverged rule the one changed")
    void aPerturbedParameterFlipsAndNamesItsRule() {
        assertFlipped(Perturbation.PARAMETER);
    }

    @Test
    @DisplayName("a band moved under the score - or a fail-safe band's decision flipped: DIVERGED on the decision alone,"
            + " the hits and the score equal, the explanation naming the band the score now falls in")
    void aMovedBandFlipsTheDecisionAlone() {
        assertFlipped(Perturbation.BAND);
        long failSafes = flips(Perturbation.BAND).stream().filter(flip -> flip.what().startsWith("fail-safe")).count();
        System.out.println("P13-TST-002 BAND on fail-safe bands: " + failSafes);
        assertThat(failSafes).as("fail-safe bands flipped").isPositive();
    }

    @Test
    @DisplayName("the version swapped for its successor under the pin: DIVERGED, naming the first rule the two versions"
            + " judge otherwise, or the decision when only a band differs")
    void aSwappedVersionFlipsNamingWhereTheVersionsDiffer() {
        assertFlipped(Perturbation.VERSION);
    }

    @Test
    @DisplayName("the control: every seal replayed under the battery's own statement of its version, unperturbed, is"
            + " IDENTICAL - a fixture edited away from what the battery states is red here, naming the version")
    void theControlFlipsNothing() {
        List<String> flipped = run.outcomes().stream().flatMap(outcome -> outcome.controls().stream()).toList();
        System.out.println("P13-TST-002 CONTROL: " + flipped.size() + " seals diverging under the battery's statement"
                + (flipped.isEmpty() ? "" : " - " + flipped.stream().map(line -> line.replaceAll("^subject \\d+ #\\d+ ",
                        "").replaceAll(":.*$", "")).collect(Collectors.groupingBy(version -> version, TreeMap::new,
                                Collectors.counting()))));
        assertThat(flipped.stream().limit(25).toList()).as(flipped.size() + " seals the battery's statement does not"
                + " reproduce").isEmpty();
    }

    @Test
    @DisplayName("one subject in ten replayed whole under the successor versions: DIVERGED exactly for the evaluations the"
            + " oracle predicts, naming the first rule judged otherwise, IDENTICAL for every other (INV-RSK-05)")
    void theSwappedSliceDivergesExactlyWherePredicted() {
        Map<RiskOperation, long[]> totals = new EnumMap<>(RiskOperation.class);
        List<String> wrong = new java.util.ArrayList<>();
        Set<Cohort> cohorts = EnumSet.noneOf(Cohort.class);
        for (Outcome outcome : run.outcomes()) {
            outcome.swap().ifPresent(swap -> {
                cohorts.add(outcome.cohort());
                swap.counts().forEach((operation, count) -> {
                    long[] total = totals.computeIfAbsent(operation, ignored -> new long[3]);
                    for (int i = 0; i < 3; i++) {
                        total[i] += count[i];
                    }
                });
                swap.wrong().forEach(line -> wrong.add("subject " + outcome.index() + " " + line));
            });
        }
        StringBuilder text = new StringBuilder();
        totals.forEach((operation, total) -> text.append(operation).append("{replayed=").append(total[0])
                .append(", predicted=").append(total[1]).append(", diverged=").append(total[2]).append("} "));
        System.out.println("P13-TST-002 SWAPPED: " + text.toString().strip());
        assertThat(wrong.stream().limit(25).toList()).as(wrong.size() + " swapped seals not as predicted").isEmpty();
        assertThat(cohorts).as("the slice across every cohort").containsExactlyInAnyOrder(Cohort.values());
        assertThat(totals.keySet()).as("every v1 version swapped").containsExactlyInAnyOrderElementsOf(
                RiskBatteryOracles.OPERATIONS);
        totals.forEach((operation, total) -> {
            assertThat(total[1]).as(operation + ": seals predicted to diverge").isPositive();
            assertThat(total[0] - total[1]).as(operation + ": seals predicted IDENTICAL").isPositive();
        });
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertFlipped(Perturbation kind) {
        List<Flip> flips = flips(kind);
        assertThat(flips).as("subjects perturbed by " + kind).hasSizeGreaterThanOrEqualTo(PER_KIND);
        List<String> unflipped = run.outcomes().stream()
                .filter(outcome -> outcome.flip().filter(flip -> flip.kind() == kind && !flip.holds()).isPresent())
                .map(outcome -> "subject " + outcome.index() + " " + outcome.flip().get()).toList();
        Map<String, Long> perVersion = flips.stream().collect(Collectors.groupingBy(Flip::version, TreeMap::new,
                Collectors.counting()));
        System.out.println("P13-TST-002 PERTURBED " + kind + ": " + flips.size() + " subjects, "
                + (flips.size() - unflipped.size()) + " flipped naming their row " + perVersion);
        assertThat(unflipped.stream().limit(25).toList()).as(unflipped.size() + " perturbations by " + kind
                + " that did not flip, name their row or agree with the oracle").isEmpty();
    }

    private static List<Flip> flips(Perturbation kind) {
        return run.outcomes().stream().flatMap(outcome -> outcome.flip().stream()).filter(flip -> flip.kind() == kind)
                .toList();
    }
}
