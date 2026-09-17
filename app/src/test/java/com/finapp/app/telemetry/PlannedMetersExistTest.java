package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every meter the current phase's plan names is actually registered (`P1-TSK-029`).
 *
 * <h2>This is criterion 6, converted from a review opinion into a build failure</h2>
 *
 * <p>{@code PHASE_GATES.md} criterion 6 asks for metrics on the phase's critical flows, and
 * {@code P1-DOC-001} failed it by <strong>reading the plan and the code side by side</strong> —
 * four of six meters did not exist. That is a check somebody performs once, at a gate, having
 * remembered to. This performs it on every build.
 *
 * <p>It is bidirectional in the way that matters:
 *
 * <ul>
 *   <li>A meter deleted, renamed, or never wired fails the build.
 *   <li>A plan naming a meter nobody built fails the build — which is exactly the state Phase 1
 *       was in for two days and which no guard could see.
 * </ul>
 *
 * <h2>The phase is derived, never written down</h2>
 *
 * <p>{@code MutationDemonstrationTest}'s helper shape: the current phase comes from
 * {@code CURRENT_STATE.md}, whose stated role is to be <em>the canonical description of where the
 * project is</em>, so Phase 2 needs no edit here. A constant would be the stale-list defect this
 * repository closes by derivation everywhere else.
 *
 * <h2>What it deliberately does not check</h2>
 *
 * <p>Tags. {@code MetricConventionTest} owns those, and it checks something stronger than a
 * declared list: that no meter carries a tag key a request could influence. A second, weaker copy
 * of that here would be duplication that drifts ({@code P1-TSK-012}).
 */
@Tag("slice")
// Pointed at a database that is not there, exactly as MetricConventionTest is. The context starts
// regardless (P0-TSK-027), so the test would pass either way on a machine where compose happens to
// be running - and "happens to be running" is what the hermetic tier exists to exclude. Making it
// explicit is the difference between a test that IS hermetic and one that has not been asked.
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every planned meter exists (P1-TSK-029)")
class PlannedMetersExistTest {

    /**
     * A row of the plan's §10 table: the meter name is the first backticked token.
     *
     * <p>The row also carries the instrument and a rationale — {@code | `finapp.identity.recovery`
     * — counter by stage | Recovery is the ATO vector … |} — so anchoring on the leading backtick
     * is what stops "counter" and "gauge" being read as meter names.
     */
    private static final Pattern PLANNED_METER =
            Pattern.compile("^\\|\\s*`(finapp\\.[a-z][a-z0-9.]*)`");

    /** The highest phase {@code CURRENT_STATE.md} §Current Phase names. */
    private static final Pattern PHASE_HEADING = Pattern.compile("^\\*\\*Phase (\\d+) —");

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("every meter the plan names is registered")
    void everyPlannedMeterIsRegistered() {
        Set<String> planned = plannedMeters();
        Set<String> registered = registeredMeters();

        assertThat(planned)
                .as("the plan must name some meters, or this guard checks nothing - the vacuity"
                        + " this repository has met repeatedly")
                .isNotEmpty();

        assertThat(registered)
                .as("a meter the plan names but nothing registers is criterion 6 failing, and it"
                        + " is invisible to every other check: the plan parses, the code compiles,"
                        + " and an operator finds out during an incident")
                .containsAll(planned);
    }

    @Test
    @DisplayName("every meter the plan names satisfies the naming convention")
    void everyPlannedMeterIsWellFormed() {
        // The finding this task began with: three of the four planned names carried UNDERSCORES,
        // which MetricNames.NAME forbids, so they could not have been registered at all. Both
        // forms collapse to the same Prometheus series, so the plan was corrected rather than the
        // convention widened - and this is what stops the next plan reintroducing them.
        assertThat(plannedMeters())
                .allSatisfy(
                        name ->
                                assertThat(MetricNames.isWellFormed(name))
                                        .as(
                                                "%s cannot be registered: the convention is dots,"
                                                        + " not underscores",
                                                name)
                                        .isTrue());
    }

    /**
     * `P2-TSK-020`'s acceptance, performed ahead of the flip: every meter Phase 2's plan names
     * is registered in this context — which boots with <strong>nothing</strong> configured, no
     * database and no provider endpoint, so it is exactly the "freshly started instance" the
     * criterion names.
     *
     * <p>Pinned to {@code PHASE_2_PLAN.md} deliberately, where the guard above derives: the
     * derived rule keys on phases recorded {@code COMPLETE} — an <em>exit</em> criterion, so a
     * guard red for weeks at every phase start is one somebody turns off — and this task's job
     * is precisely to make the flip a non-event for that rule. Between now and the flip, this
     * is the only thing holding the six series; after it, a harmless second reading of the same
     * table. The two conditions this proves that no per-class test can: the provider-fed series
     * exist <strong>without</strong> {@code finapp.kyc.provider.url} (the unconditional
     * registration in {@code KycMetrics}), and the case counter's decorator is actually the
     * wired bean.
     */
    @Test
    @DisplayName("Phase 2's planned meters are already published, ahead of the phase flip")
    void phase2PlannedMetersAreAlreadyPublished() {
        Set<String> planned = new TreeSet<>();
        for (String line : read(repositoryFile("docs/project/PHASE_2_PLAN.md"))) {
            Matcher row = PLANNED_METER.matcher(line);
            if (row.find()) {
                planned.add(row.group(1));
            }
        }
        assertThat(planned)
                .as("the Phase 2 plan's §10 table must parse, or this checks nothing")
                .hasSizeGreaterThanOrEqualTo(6);

        assertThat(registeredMeters()).containsAll(planned);
    }

    /**
     * `P3-TSK-020`'s acceptance, performed ahead of the flip — the same shape one phase on:
     * every meter Phase 3's plan §15 names is registered in this context, which boots with
     * nothing configured and no reachable database, so it is exactly the "freshly started
     * instance" the milestone acceptance names. Between now and the flip this is the only
     * thing holding the six series; after it, a harmless second reading of the same table.
     * What this proves that no per-class test can: the write-path observer, the hold gauge,
     * the trial-balance series and the account counters are all the WIRED beans' eager
     * registrations, not a test's own.
     */
    @Test
    @DisplayName("Phase 3's planned meters are already published, ahead of the phase flip")
    void phase3PlannedMetersAreAlreadyPublished() {
        Set<String> planned = new TreeSet<>();
        for (String line : read(repositoryFile("docs/project/PHASE_3_PLAN.md"))) {
            Matcher row = PLANNED_METER.matcher(line);
            if (row.find()) {
                planned.add(row.group(1));
            }
        }
        assertThat(planned)
                .as("the Phase 3 plan's §15 table must parse, or this checks nothing")
                .hasSizeGreaterThanOrEqualTo(6);

        assertThat(registeredMeters()).containsAll(planned);
    }

    @Test
    @DisplayName("the guard is not vacuous: it reads a real plan and a real registry")
    void theGuardHasTeeth() {
        assertThat(registeredMeters())
                .as("a real registry with real meters")
                .isNotEmpty();

        // And the parser must actually find the identity meters rather than matching nothing and
        // passing over an empty set - the failure mode of every document-backed guard here.
        assertThat(plannedMeters())
                .as("the parser reads the table rather than matching nothing")
                .anyMatch(name -> name.startsWith("finapp.identity."));
    }

    // -----------------------------------------------------------------

    private Set<String> registeredMeters() {
        Set<String> ours = new TreeSet<>();
        registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(MetricNames::isOurs)
                .forEach(ours::add);
        return ours;
    }

    private static Set<String> plannedMeters() {
        Set<String> planned = new TreeSet<>();
        for (Path plan : planPaths()) {
            for (String line : read(plan)) {
                Matcher row = PLANNED_METER.matcher(line);
                if (row.find()) {
                    planned.add(row.group(1));
                }
            }
        }
        return planned;
    }

    /**
     * The plans of every phase recorded {@code COMPLETE} — because criterion 6 is an <em>exit</em>
     * criterion, and the Phase 1 &rarr; 2 transition found what enforcing the <em>named</em> phase
     * does at a boundary in <strong>both</strong> directions.
     *
     * <p>Forwards: naming Phase 2 as {@code READY} would demand Phase 2's planned meters before
     * any Phase 2 flow exists — a guard that is red for weeks at every phase start is one somebody
     * turns off. Backwards, and worse: reading only the <em>highest</em> plan meant that the
     * moment Phase 2 was named, <strong>Phase 1's meters silently stopped being checked at
     * all</strong> — a deleted `finapp.identity.lockout` would have passed this guard while its
     * alert evaluated nothing, which is the exact defect the guard was built to catch.
     *
     * <p>So: the union of every {@code COMPLETE} phase's plan. A phase's meters become enforced by
     * the act of recording it {@code COMPLETE} — criterion 6 checked at exactly the moment it
     * applies — and stay enforced for ever after. {@code COMPLETE} is `PHASE_GATES.md` §1's closed
     * status vocabulary, matched in its backticked form on the Status line; a reworded line
     * degrades enforcement of the newest phase only, and the teeth test still requires Phase 1's
     * meters to be found.
     */
    private static List<Path> planPaths() {
        List<Path> plans = new ArrayList<>();
        int pending = -1;
        boolean inSection = false;
        for (String line : read(repositoryFile("docs/project/CURRENT_STATE.md"))) {
            if (line.startsWith("## ")) {
                inSection = line.startsWith("## Current Phase");
            }
            if (!inSection) {
                continue;
            }
            Matcher heading = PHASE_HEADING.matcher(line);
            if (heading.find()) {
                pending = Integer.parseInt(heading.group(1));
                continue;
            }
            if (pending >= 1 && line.startsWith("Status:") && line.contains("`COMPLETE`")) {
                Path plan = repositoryFile("docs/project/PHASE_" + pending + "_PLAN.md");
                assertThat(plan)
                        .as("a phase recorded COMPLETE must have a plan, and its Observability"
                                + " table is what criterion 6 is assessed against")
                        .exists();
                plans.add(plan);
            }
        }
        assertThat(plans)
                .as("at least one phase is COMPLETE - Phase 1 is, so an empty list means the"
                        + " status parse broke, not that the project moved backwards")
                .isNotEmpty();
        return plans;
    }

    private static Path repositoryFile(String relative) {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve(relative))) {
            here = here.getParent();
        }
        assertThat(here).as("could not locate %s from %s", relative, Path.of("").toAbsolutePath()).isNotNull();
        return here.resolve(relative);
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
