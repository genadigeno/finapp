package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the integrated soak holds about itself ({@code P16-TST-001}; {@code PHASE_16_PLAN.md} section 2.2's C1;
 * {@code RESILIENCE_EXPERIMENTS.md} E90; {@code OPERATIONS_RUNBOOK.md} section 11.67): ten replicas, the engineering workload eight hours
 * beside a schedule of faults drawn from the register, the census sampled every 30 s, a database failover inside a declared fault window,
 * and the growth, the leaks and the lag judged against their bounds at its end.
 *
 * <p>It has not run on a cluster: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025). What a hermetic
 * test holds is the text that makes the run a verdict: the schedule obeying the soak's own rules, the window excusing only a census that
 * could not be read, every bound read from the soak's own samples and a bound with nothing to read failing it, and the probe's plant.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the integrated soak holds its schedule, its fault window and its bounds (P16-TST-001)")
class SoakContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String SOAK_PROFILE = "infra/scripts/kind-rehearsal/load/soak.json";
    private static final String INTEGRATED = "infra/scripts/kind-rehearsal/load/integrated-soak.json";

    private static Path root;
    private static String harness;
    private static String experiments;
    private static String load;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        experiments = read(EXPERIMENTS);
        load = read(LOAD);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String text, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(text);
        assertThat(start.find()).as("the script defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(text);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return text.substring(start.end(), end.start());
    }

    private static String value(String name) {
        Matcher line = Pattern.compile("(?m)^" + Pattern.quote(name) + "=\"?([^\"\\n]*)\"?$").matcher(experiments);
        assertThat(line.find()).as("experiments.sh sets %s", name).isTrue();
        return line.group(1);
    }

    @Test
    @DisplayName("the default schedule obeys the soak's own rules: ascending minutes, admitted experiments, E05 last, the plant's minute free,"
            + " the last fault with half an hour of load to recover in; a pod kill each hour, a broker, a deploy, a provider")
    void theDefaultScheduleObeysTheRules() {
        List<String> admitted = List.of(value("SOAK_EXPERIMENTS").split(" "));
        assertThat(admitted).containsExactlyInAnyOrder("E01", "E05", "E08", "E17", "E82");
        int plant = Integer.parseInt(value("SOAK_PLANT_MINUTE"));
        int last = -1;
        List<String> ids = new ArrayList<>();
        for (String entry : value("SOAK_SCHEDULE_DEFAULT").split(" ")) {
            String[] parts = entry.split(":");
            int minute = Integer.parseInt(parts[0]);
            assertThat(minute).as("ascending: %s", entry).isGreaterThan(last);
            assertThat(admitted).as("admitted: %s", entry).contains(parts[1]);
            assertThat(minute).as("the probe's minute is free: %s", entry).isNotEqualTo(plant);
            assertThat(ids).as("nothing after E05, which leaves one server and no standby").doesNotContain("E05");
            ids.add(parts[1]);
            last = minute;
        }
        assertThat(last).as("the last fault leaves the eight-hour load half an hour").isLessThanOrEqualTo(450);
        assertThat(ids.get(ids.size() - 1)).isEqualTo("E05");
        assertThat(ids.stream().filter("E01"::equals).count()).as("a replica killed each hour of the eight").isGreaterThanOrEqualTo(7);
        assertThat(ids).as("a broker lost, a rolling deploy, a provider outage, a database failover").contains("E08", "E82", "E17", "E05");
        assertThat(plant).as("the probe plants at hour five").isEqualTo(300);
    }

    @Test
    @DisplayName("what does not go together is refused before anything is built, and the soak's experiments are admitted at ten under the load")
    void theSoakIsResolvedBeforeAnythingIsBuilt() {
        assertThat(function(experiments, "soak_resolve"))
                .contains("the soak runs in the experiments scope").contains("[ \"$FLEET_REPLICAS\" = 10 ]")
                .contains("[ \"$EXPERIMENTS_LOAD\" = integrated-soak ]").contains("set REHEARSAL_SOAK_SCHEDULE, not REHEARSAL_EXPERIMENTS")
                .contains("is in ascending minutes").contains("the soak admits $SOAK_EXPERIMENTS")
                .contains("it is the soak's last fault").contains("[ \"$sr_last\" -le 450 ]")
                .contains("PROBE soak-unbalanced plants into the soak: set REHEARSAL_SOAK=1");
        assertThat(function(experiments, "experiments_load_resolve")).contains("soak_resolve")
                .contains("case \" $EXPERIMENTS_UNDER_LOAD ${SOAK:+$SOAK_EXPERIMENTS} \" in");
        assertThat(harness).as("the soak's five admitted at the run's count with the soak alone")
                .contains("[ -z \"$SOAK\" ] || EXPERIMENTS_AT_A_COUNT=\"$EXPERIMENTS_AT_A_COUNT $SOAK_EXPERIMENTS\"");
        assertThat(experiments).as("the soak's experiments are its schedule's, beside its own profile")
                .contains("EXPERIMENTS=$(printf '%s\\n' $SOAK_SCHEDULE | cut -d: -f2")
                .contains("EXPERIMENTS_LOAD=${REHEARSAL_EXPERIMENTS_LOAD:-integrated-soak}");
    }

    @Test
    @DisplayName("the integrated profile is soak.json's mix, stages and length without the load scope's own database readings")
    void theIntegratedProfileIsTheSoaksMix() {
        String soak = read(SOAK_PROFILE);
        String integrated = read(INTEGRATED);
        assertThat(integrated).contains("\"name\": \"integrated-soak\"").doesNotContain("\"soak\": true");
        assertThat(soak).contains("\"soak\": true");
        assertThat(withoutHeader(integrated)).as("every line but the name, the workload and the soak flag is soak.json's")
                .isEqualTo(withoutHeader(soak));
        assertThat(function(experiments, "experiments_load_preflight")).as("a soak flag would refuse the profile beside the experiments")
                .contains("[ \"$LOAD_SOAK\" != true ]");
    }

    private static String withoutHeader(String profile) {
        return profile.lines().filter(line -> !line.startsWith("  \"name\":") && !line.startsWith("  \"workload\":") && !line.startsWith("  \"soak\":"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    @Test
    @DisplayName("the declared fault window excuses only a sample whose census could not be read; a census FAIL stays FAIL; a PASS after it is required")
    void theFaultWindowExcusesOnlyAnUnreadableCensus() {
        String sample = function(load, "load_sample");
        assertThat(sample).contains("[ -s \"$WORK/load-fault-window\" ] && [ \"$s_verdicts\" != \"$LOAD_CLAUSES\" ]").contains("s_verdict=OUTAGE")
                .contains("if [ \"$s_verdict\" != PASS ] && [ \"$s_verdict\" != OUTAGE ]; then");
        assertThat(function(experiments, "soak_run")).as("E05 inside the window, the fleet back at its ten after every fault")
                .contains("E05) soak_window_open E05; experiment_run E05; soak_window_close E05 ;;")
                .contains("ready_fleet 900 || fail \"the soak: the fleet did not return");
        assertThat(function(experiments, "soak_window_close")).as("the window closed and the first sample after it required to PASS")
                .contains("rm -f \"$WORK/load-fault-window\"").contains("[ \"$after\" = PASS ] || fail");
        assertThat(function(experiments, "soak_wait_until")).as("a failed sample between faults fails the soak")
                .contains("[ ! -s \"$WORK/load-sample-failed\" ] || fail").contains("experiments_load_tick");
        // The gate's finding: the load's census sampler and the soak's sampler are forked before E05 promotes the standby, so a read that
        // named PG_POD as it stood would ask the fenced server for the rest of the soak, and no sample after the window could ever pass.
        assertThat(function(harness, "psql_query")).as("every census read follows the promoted primary").contains("\"$(pg_primary)\"");
        assertThat(function(load, "pg_admin")).as("and the load's statistics reads").contains("\"$(pg_primary)\"");
    }

    @Test
    @DisplayName("the leaks, the growth and the lag are read every 15 minutes by the soak's own sampler and judged at its end; nothing read fails it")
    void theBoundsAreReadAndJudged() {
        assertThat(experiments).contains("SOAK_HEAP_GROWTH=1.25").contains("SOAK_HEAP_SHARE=0.8").contains("SOAK_TABLE_GROWTH=1.5")
                .contains("SOAK_LAG_BOUND=1000");
        assertThat(read("infra/prometheus/rules/platform.yml")).as("the lag bound is the platform's own InboxLagHigh threshold")
                .contains("sum by (module) (finapp_inbox_lag == finapp_inbox_lag) > 1000");
        assertThat(function(experiments, "soak_sampler")).as("every 15 minutes, because the kind Prometheus keeps six hours and the soak runs eight")
                .contains("jvm_gc_live_data_size_bytes").contains("jvm_gc_max_data_size_bytes").contains("max(finapp_inbox_lag)")
                .contains("pg_total_relation_size").contains("$((at + 900))");
        String end = function(experiments, "soak_end");
        assertThat(end).as("each bound read from the soak's samples, and a bound with nothing to read a failure")
                .contains("the heap after collection was not read in its first or last hour")
                .contains("l <= f * g && l <= m * s").contains("the inbox's lag was not read after the last fault")
                .contains("[ \"$lag\" -le \"$SOAK_LAG_BOUND\" ]").contains("late <= early * bound").contains("\"unread\"");
        assertThat(function(experiments, "experiments_scope")).as("the soak begun with the load, its schedule run, its end after the load's census")
                .contains("[ -z \"$SOAK\" ] || soak_begin").contains("soak_run").contains("[ -z \"$SOAK\" ] || soak_end");
    }

    @Test
    @DisplayName("the probe: one journal line raised by one minor unit at hour five, behind the safety boundary, which the next sample must refuse")
    void theProbePlantsAnUnbalancedEntry() {
        String plant = function(experiments, "soak_plant");
        assertThat(plant).contains("experiments_context_check").contains("SET session_replication_role = replica")
                .contains("UPDATE ledger.journal_line SET amount_minor = amount_minor + 1");
        assertThat(function(experiments, "soak_run")).as("the plant only under the probe").contains("[ \"$PROBE\" != soak-unbalanced ] ||")
                .contains("PLANT) soak_plant ;;");
        assertThat(read("infra/scripts/kind-rehearsal/census.sql")).as("the trial balance is among the clauses sampled every 30 s")
                .containsPattern("(?s)-- \\[sampled during load\\]\\nSELECT CASE WHEN count\\(\\*\\) > 0 AND bool_and\\(net = 0\\)");
    }
}
