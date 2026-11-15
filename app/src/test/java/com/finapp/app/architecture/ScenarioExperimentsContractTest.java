package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the fifteen scenario experiments hold about themselves ({@code P16-TSK-027}; {@code PHASE_16_PLAN.md} section 13;
 * {@code RESILIENCE_EXPERIMENTS.md} section 4; {@code OPERATIONS_RUNBOOK.md} section 11.66): F01 to F15, each an experiment run by
 * {@code experiment_run} like every other, each composing the experiments its register row names, and each counting the seven checks.
 *
 * <p>None of them has run on a cluster: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025). What a
 * hermetic test holds is the text that makes each run a verdict on the seven checks: every check recorded and a failed one stopping the
 * scenario, the planted discrepancy delivered before the fault and looked for after it, every recorded key sent again through another
 * replica, each scenario's re-run judged, and each scenario's fault the one its register row names.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the fifteen scenario experiments count the seven checks and compose what their register rows name (P16-TSK-027)")
class ScenarioExperimentsContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String HOLDS = "app/src/test/java/com/finapp/app/domain/HoldDatabaseTest.java";

    private static final List<String> SCENARIOS =
            List.of("F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "F12", "F13", "F14", "F15");

    private static Path root;
    private static String harness;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        experiments = read(EXPERIMENTS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{").matcher(experiments);
        assertThat(start.find()).as("the toolkit defines %s", name).isTrue();
        String rest = experiments.substring(start.end());
        // A one-line body ("F03_fault() { E09_fault; }") ends on its own line; a block ends at the first column-zero brace.
        int newline = rest.indexOf('\n');
        if (rest.substring(0, newline).strip().endsWith("}")) {
            return rest.substring(0, newline);
        }
        Matcher end = Pattern.compile("(?m)^}$").matcher(rest);
        assertThat(end.find()).as("%s ends", name).isTrue();
        return rest.substring(0, end.start());
    }

    @Test
    @DisplayName("every scenario defines the step shape experiment_run runs, and its re-run, and is dispatched by name")
    void everyScenarioHasItsSteps() {
        for (String id : SCENARIOS) {
            for (String step : List.of("steady", "fault", "observe", "recover", "rerun")) {
                function(id + "_" + step);
            }
        }
        assertThat(function("experiments_scope")).as("an experiment is found by its fault step's name, so F01 to F15 are run as E01 is")
                .contains("command -v \"${id}_fault\"").contains("experiment_run \"$id\"");
    }

    @Test
    @DisplayName("every drivable scenario reads before its fault and judges after its recovery, and F06 refuses with its reason")
    void everyScenarioReadsBeforeAndJudgesAfter() {
        for (String id : SCENARIOS) {
            if (id.equals("F06")) {
                continue;
            }
            assertThat(function(id + "_steady")).as("%s reads the journal and plants before its fault", id).contains("scenario_before");
            assertThat(function(id + "_recover")).as("%s judges checks (c) to (g) at the end of its recovery", id).contains("scenario_after");
        }
        assertThat(function("F06_steady")).as("no loan can exist on kind: F06 refuses, naming why, as E20 does")
                .contains("cannot \"F06 cannot be driven on kind").contains("no bureau can be configured")
                .contains("the owner's decision of 2026-11-13, unresolved question 13");
    }

    @Test
    @DisplayName("the seven checks: (a) and (b) from the census, (c) to (g) after the recovery, each recorded and a failed one stopping the scenario")
    void theSevenChecksAreCountedAndAFailureStops() {
        String check = function("scenario_check");
        assertThat(check).as("each check recorded with its evidence, and a FAIL stops the scenario (FAILED, still recovered and censused)")
                .contains(">> \"$EXP_DIR/seven-checks.tsv\"").contains("[ \"$2\" = PASS ] || EXP_STOPPED=");
        assertThat(function("experiment_run")).as("after the census, an F experiment's checks (a) and (b) and the summary")
                .contains("case $1 in F[0-9][0-9]) scenario_census \"$1\" ;; esac");
        String census = function("scenario_census");
        assertThat(census).as("(a) the census's one-per lines, (b) its trial balance and GL equals ledger, or the load's window under a load")
                .contains("scenario_check a").contains("scenario_check b").contains("'^PASS|one '")
                .contains("trial balance zero per currency").contains("GL equals ledger").contains("windows.tsv")
                .contains("[ \"$counted\" = abcdefg ] || EXP_STOPPED=");
        String after = function("scenario_after");
        for (String letter : List.of("c", "d", "e", "g")) {
            assertThat(after).as("check (%s) judged after the recovery", letter).contains("scenario_check " + letter);
        }
        assertThat(after).as("(c) and (d) the journal and the growing tables compared before and after; (e) the scenario's own re-run judged")
                .contains("experiments_db_snapshot \"$EXP_DIR\" after").contains("experiments_db_compare \"$EXP_DIR\" before after")
                .contains("\"${EXP_ID}_rerun\"").contains("experiments_db_compare \"$EXP_DIR\" after rerun").contains("scenario_resend");
        assertThat(function("scenario_resend")).as("(f) every recorded key sent again through another replica, its status and bytes compared")
                .contains("experiments_a_ready_replica \"$SCENARIO_FIRST_POD\"").contains("sha256sum").contains("scenario_check f");
        assertThat(function("scenario_before")).as("the journal read and the discrepancy planted before the fault")
                .contains("experiments_db_snapshot \"$EXP_DIR\" before").contains("scenario_plant");
    }

    @Test
    @DisplayName("check (g): a real capture of the run settled off by the plant's amount, delivered before the fault, looked for as a break after")
    void thePlantedDiscrepancyIsRaisedAsABreak() {
        String plant = function("scenario_plant");
        assertThat(plant).as("a captured card payment's provider reference, its amount plus the plant's, delivered and attested")
                .contains("capture_provider_reference").contains("planted=$((captured + SCENARIO_PLANT_MINOR))")
                .contains("client settle-file \"$day\" \"scenario-$EXP_ID.csv\"").contains("RECONCILIATION_OPERATOR");
        assertThat(experiments).contains("SCENARIO_PLANT_MINOR=${REHEARSAL_SCENARIO_PLANT_MINOR:-100}")
                .contains("SCENARIO_BREAK_SECONDS=${REHEARSAL_SCENARIO_BREAK_SECONDS:-900}");
        assertThat(function("scenario_planted_break")).as("a break on an external item keyed by the planted capture, raised since the scenario began")
                .contains("reconciliation.break").contains("reconciliation.external_item_key").contains("'$SCENARIO_PLANT_REF'")
                .contains("to_timestamp($EXP_STARTED_AT)");
    }

    @Test
    @DisplayName("each scenario's fault is the one its register row names")
    void eachScenarioComposesItsRow() {
        Map<String, List<String>> composed = Map.ofEntries(
                Map.entry("F03_observe", List.of("E09_observe", "E09_recover", "E22_steady", "E22_fault", "E22_observe")),
                Map.entry("F04_fault", List.of("E01_fault")),
                Map.entry("F05_observe", List.of("E22_observe", "experiments_fault_on scaled finapp finapp-app 10")),
                Map.entry("F11_fault", List.of("E05_fault")),
                Map.entry("F12_fault", List.of("E09_fault")),
                Map.entry("F14_observe", List.of("E82_observe", "rollout undo deployment/finapp-app")),
                Map.entry("F15_fault", List.of("E22_fault")));
        composed.forEach((step, calls) -> assertThat(function(step)).as("%s composes its row's experiments", step).contains(calls));
        assertThat(function("F01_fault")).as("the commit slowed past the client's patience, so its answer is given up while it commits")
                .contains("toxic postgres f01-slow-commit latency latency=4000").contains("lost 2");
        assertThat(function("F01_observe")).as("the lost answer's transfer committed, then retried under its key through the other replica, once")
                .contains("transfer-retried").contains("\"$F01_SECOND\"").contains("a second effect");
        assertThat(function("F02_fault")).as("the provider's answers dropped downstream alone: the effect taken, the answer lost")
                .contains("toxic simulator f02-answers-lost timeout timeout=0").doesNotContain("stream=upstream");
        assertThat(function("F07_fault")).as("the confirmation sent to every ready replica at once, the provider's callback racing it")
                .contains("for address in $(experiments_fleet").contains("scenario_confirm \"$address\"").contains("scenario_callback");
        // The gate's finding: a bare wait waits for every child of the harness, the security probe running until the recovery among them,
        // so a scenario that waited for its racers that way would never reach its observation.
        String section = experiments.substring(experiments.indexOf("# THE FIFTEEN SCENARIOS"));
        assertThat(section).as("every wait in the scenarios names the children it waits for")
                .doesNotContainPattern("(?m)^\\s*wait\\s*$");
        assertThat(function("F07_fault")).contains("wait $F07_RACERS");
        assertThat(function("F07_observe")).as("one captured attempt, one capture entry, the payment SUCCEEDED")
                .contains("[ \"$captured\" = 1 ] && [ \"$entries\" = 1 ] && [ \"$state\" = SUCCEEDED ]");
        assertThat(function("F08_fault")).as("a payout's effect held at the provider with its claim open").contains("experiments_fault_on hold hold-payouts");
        assertThat(function("F09_fault")).as("the replica parsing the file killed, the source's next file delivered at once")
                .contains("experiments_kill_jvm \"$victim\"").contains("\"$EXP_ID-2.csv\"");
        assertThat(function("scenario_ingested")).as("no file left unaccepted and no source sequence run twice")
                .contains("status IN ('RECEIVED', 'PARSED')").contains("GROUP BY source_id, source_sequence HAVING count(*) > 1");
        assertThat(function("F10_fault")).as("a run read mid-walk, its replica killed")
                .contains("status = 'IN_PROGRESS' AND cursor > 0 AND cursor < item_count").contains("experiments_kill_jvm \"$victim\"");
        assertThat(function("F10_observe")).as("the run completed elsewhere, nothing matched twice").contains("COMPLETED").contains("HAVING count(*) > 1");
        assertThat(function("F13_fault")).as("the card rail silent past the timeout").contains("experiments_fault_on hold degrade-card-silent");
        assertThat(function("F13_observe")).as("the late approved callbacks after the conclusion: evidence, never a second capture entry")
                .contains("sleep \"${REHEARSAL_F13_LATE_SECONDS:-300}\"").contains("scenario_callback f13late")
                .contains("the late success added a capture entry");
    }

    @Test
    @DisplayName("the three scenarios at ten are written for the run's count and refuse below it; they may run under the engineering workload")
    void theScenariosAtTenAreWrittenForTheCount() {
        assertThat(harness).contains("EXPERIMENTS_AT_A_COUNT=\"$EXPERIMENTS_AT_A_COUNT F07 F08 F14\"");
        assertThat(experiments).contains("EXPERIMENTS_UNDER_LOAD=\"$EXPERIMENTS_UNDER_LOAD F07 F08 F14\"");
        for (String id : List.of("F07", "F08", "F14")) {
            assertThat(function(id + "_steady")).as("%s refuses below ten replicas", id)
                    .contains("[ \"$FLEET_REPLICAS\" -ge 10 ] || cannot \"" + id + " runs at ten replicas");
        }
    }

    @Test
    @DisplayName("the census counts what the scenarios moved: their transfers, payouts and card payments, each of its own amount")
    void theCensusCountsTheScenariosMoney() {
        assertThat(harness).as("the scenarios' card payments in the expected top-ups and wallet credit")
                .contains("+ ${EXPERIMENTS_TOPUPS:-0} ))").contains("+ ${EXPERIMENTS_TOPUP_MINOR:-0} ))");
        assertThat(experiments).contains("EXPERIMENTS_TOPUPS=0").contains("EXPERIMENTS_TOPUP_MINOR=0");
        assertThat(function("F07_observe")).contains("EXPERIMENTS_TOPUP_MINOR=$((EXPERIMENTS_TOPUP_MINOR + 7))");
        assertThat(function("F13_observe")).contains("EXPERIMENTS_TOPUP_MINOR=$((EXPERIMENTS_TOPUP_MINOR + 13))");
        assertThat(function("scenario_keyed_transfer")).as("a scenario's own transfer under a workload's reference, which the census holds to one")
                .contains("\"wl-$EXP_ID-$tag\"").contains("EXPERIMENTS_TRANSFERS=$((EXPERIMENTS_TRANSFERS + 1))");
    }

    @Test
    @DisplayName("scenario 8's ledger hold is released concurrently by a database case, ten connections at a barrier")
    void scenarioEightsLedgerHoldIsReleasedConcurrently() {
        String holds = read(HOLDS);
        assertThat(holds).contains("void tenConcurrentReleasesOfOneHoldReleaseOnce()").contains("new CyclicBarrier(instances)")
                .contains("assertThat(acted).as(\"exactly one release acts");
        assertThat(function("F08_steady").concat(experiments.substring(experiments.indexOf("# F08:"), experiments.indexOf("F08_steady()"))))
                .as("F08 names the database case that closes the ledger hold's half").contains("HoldDatabaseTest#tenConcurrentReleasesOfOneHoldReleaseOnce");
    }
}
