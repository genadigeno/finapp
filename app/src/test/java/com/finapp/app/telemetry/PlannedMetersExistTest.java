package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        for (String line : read(planPath())) {
            Matcher row = PLANNED_METER.matcher(line);
            if (row.find()) {
                planned.add(row.group(1));
            }
        }
        return planned;
    }

    /**
     * The plan for the phase this project has reached.
     *
     * <p>The <strong>highest</strong> phase the section names rather than the one marked
     * {@code IN_PROGRESS}: a status word is prose that changes shape between phases, and a phase
     * that has been reached does not stop having been reached when it completes
     * ({@code P1-TSK-024}).
     */
    private static Path planPath() {
        int phase = 0;
        boolean inSection = false;
        for (String line : read(repositoryFile("docs/project/CURRENT_STATE.md"))) {
            if (line.startsWith("## ")) {
                inSection = line.startsWith("## Current Phase");
            }
            Matcher heading = PHASE_HEADING.matcher(line);
            if (inSection && heading.find()) {
                phase = Math.max(phase, Integer.parseInt(heading.group(1)));
            }
        }
        assertThat(phase).as("CURRENT_STATE.md must name the phase this project has reached").isPositive();

        Path plan = repositoryFile("docs/project/PHASE_" + phase + "_PLAN.md");
        assertThat(plan)
                .as("a phase that has been reached must have a plan, and its §Observability table"
                        + " is what criterion 6 is assessed against")
                .exists();
        return plan;
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
