package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the ten-instance runs hold about themselves ({@code P16-TSK-014}; {@code OPERATIONS_RUNBOOK.md} section 11.58): the release's walk
 * at the run's count (E26), the autoscaler deciding the fleet between its floor and ten under load (E70) with every connection sample held
 * to the budget's formula at the count asked for then, and the entry's probe; the sustained profile at ten (E69) and the pipeline's scale at
 * the overlay's ceiling (E71); a count for the experiments scope (E45 at ten); a moving fleet read by set.
 *
 * <p>None of them has run on a cluster: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025), and a run's
 * result is recorded in the resilience register and the runbook when it happens. What a hermetic test holds is that the text keeps what
 * makes each run a measurement: the count is the autoscaler's and never the harness's where the run says so, every figure is read from the
 * run's own copy rather than copied, every wait and floor reads the run's count, and nothing the run judges can pass on what it never read.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the ten-instance runs hold what they say they hold (P16-TSK-014)")
class TenInstancesRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String PIPELINE = "infra/scripts/kind-rehearsal/pipeline.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String MODEL = "infra/scripts/kind-rehearsal/load/model.js";
    private static final String STAGES = "infra/scripts/kind-rehearsal/load/stages.awk";
    private static final String PROFILE = "infra/scripts/kind-rehearsal/load/autoscale.json";

    private static Path root;
    private static String harness;
    private static String load;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
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

    /** The harness's main sequence from one step to the next step after it. */
    private static String step(String name) {
        int start = harness.indexOf("\nstep " + name + "\n");
        assertThat(start).as("the harness has a step %s", name).isPositive();
        int end = harness.indexOf("\nstep ", start + 1);
        return harness.substring(start, end < 0 ? harness.length() : end);
    }

    @Test
    @DisplayName("each scope runs at its own count, three by default and the overlay's ceiling at most, refused before anything is built"
            + " wherever its steps are written for three")
    void eachScopeRunsAtItsCountOrRefusesIt() {
        assertThat(harness).as("the release's and the experiments scope's own counts, after the load scope's")
                .contains("FLEET_REPLICAS=3\n[ \"$SCOPE\" != load ] || FLEET_REPLICAS=$LOAD_REPLICAS\n")
                .contains("[ \"$SCOPE\" != release ] || FLEET_REPLICAS=${REHEARSAL_RELEASE_REPLICAS:-3}")
                .contains("[ \"$SCOPE\" != experiments ] || FLEET_REPLICAS=${REHEARSAL_EXPERIMENTS_REPLICAS:-3}")
                .contains("FLEET_START=$FLEET_REPLICAS").contains("EXPERIMENTS_AT_A_COUNT=\"E45\"");
        String resolve = function(harness, "fleet_resolve");
        assertThat(resolve).as("three to the ceiling the overlay states, read from it")
                .contains("path: \\/spec\\/maxReplicas$/{n;s/^        value: \\([0-9]*\\)$/\\1/p;}")
                .contains("\"$ROOT/deploy/overlays/kind/kustomization.yaml\"")
                .contains("[ \"$FLEET_REPLICAS\" -ge 3 ] && [ \"$FLEET_REPLICAS\" -le \"$fr_ceiling\" ]")
                .contains("[ \"$FLEET_REPLICAS\" != 3 ] || return 0");
        assertThat(resolve).as("a count other than three refused where the steps are three's: the release's probes, the experiments not written"
                        + " for a count, every other scope")
                .contains("release)\n            [ -z \"$PROBE\" ] || {").contains("for fr_id in $EXPERIMENTS; do")
                .contains("case \" $EXPERIMENTS_AT_A_COUNT \" in").contains("*) echo \"?? the $SCOPE scope is written for three replicas");
        assertThat(harness.indexOf("\nfleet_resolve\n")).as("resolved at the start, before the build")
                .isPositive().isGreaterThan(harness.indexOf("fleet_resolve() {")).isLessThan(harness.indexOf("step build-images"));
    }

    @Test
    @DisplayName("the release walks at its count: every wait, floor, eviction and reporting figure reads it, and a rollout of ten is bounded"
            + " by ten of the Deployment's own steps (E26)")
    void theReleaseWalksAtItsCount() {
        String ready = function(harness, "ready_fleet");
        assertThat(ready).as("ready at the count the fleet starts at, never at three").contains("[ \"${ready:-0}\" -ge \"$FLEET_START\" ]");
        assertThat(step("app-nminus1")).contains("if ! ready_fleet 900; then").doesNotContain("if ! ready_three");
        assertThat(step("hold")).contains("ready_fleet 240 || fail").doesNotContain("ready_three");
        assertThat(step("nminus1-on-n-schema")).contains("ready_fleet 120 || fail")
                .contains("pass \"$FLEET_REPLICAS replicas of N - 1 are ready on N's schema\"").doesNotContain("ready_three");
        assertThat(step("kill")).contains("ready_fleet 400 || fail").doesNotContain("ready_three");
        assertThat(function(harness, "assert_floor")).contains("[ \"$floor\" -ge \"$FLEET_REPLICAS\" ] || fail");
        String reporting = function(harness, "assert_reporting_open");
        assertThat(reporting).contains("[ \"$reported\" -ge \"$FLEET_REPLICAS\" ] || fail")
                .contains("want=$((FLEET_REPLICAS * (WRITER_POOL + REPORTING_POOL)))").doesNotContain("want=$((3 *");
        assertThat(function(harness, "evict_during_rollout")).as("mid-rollout: one terminating and the count plus its surge running")
                .contains("want_running=${4:-$((FLEET_REPLICAS + 1))}");
        assertThat(harness).as("the bound: the count times the startup probe's budget and minReadySeconds, read from the run's copy")
                .contains("if [ -z \"${REHEARSAL_ROLLOUT_TIMEOUT:-}\" ] && [ \"$FLEET_REPLICAS\" -gt 3 ]; then")
                .contains("ROLL_START=$(probe_budget \"$A/Deployment.finapp-app.yaml\" startupProbe)")
                .contains("ROLLOUT_TIMEOUT=$((FLEET_REPLICAS * (ROLL_START + ROLL_READY)))");
        assertThat(function(harness, "probe_budget")).as("the probe's own period times its own threshold, inside its own block")
                .contains("$1 == key && NF == 1").contains("s && NF && match($0, /[^ ]/) <= ind {s = 0}").contains("END {print p * f}");
    }

    @Test
    @DisplayName("an autoscaled run leaves the autoscaler to decide between its floor and the run's count, and the harness never writes the"
            + " count there (E70)")
    void theAutoscalerDecidesBetweenTheFloorAndTheCount() {
        String patch = function(harness, "fleet_patch");
        int verified = patch.indexOf("|| cannot \"the rendered kind overlay could not be set to $FLEET_REPLICAS replicas");
        int autoscaled = patch.indexOf("if [ -n \"$FLEET_AUTOSCALE\" ]; then");
        assertThat(autoscaled).as("after the held copy is set and verified").isGreaterThan(verified).isPositive();
        assertThat(patch.substring(autoscaled)).as("the minimum given back to the overlay's floor, read back, the count the most it may choose")
                .contains("[ \"$FLEET_REPLICAS\" -gt \"$fp_floor\" ] || cannot")
                .contains("sed -i \"s/^\\(  \\)minReplicas: $FLEET_REPLICAS$/\\1minReplicas: $fp_floor/\" \"$fp_hpa\" \"$fp_all\"")
                .contains("grep -q -F -x -e \"  minReplicas: $fp_floor\" \"$fp_hpa\"").contains("FLEET_START=$fp_floor");
        String preflight = function(load, "load_preflight");
        assertThat(preflight).as("only the autoscale profile asks it, never beside a scale the harness holds")
                .contains("LOAD_AUTOSCALE=$(load_profile_value autoscale)").contains("FLEET_AUTOSCALE=1")
                .contains("[ -z \"$PIPELINE_SCALE\" ] || cannot");
        assertThat(function(load, "load_target")).as("the load starts at the floor").contains("if [ \"$LOAD_AUTOSCALE\" = true ]; then\n        load_autoscale_floor");
        String floor = function(load, "load_autoscale_floor");
        assertThat(floor).as("the autoscaler read back from the cluster as the copy wrote it, the fleet settled at the floor")
                .contains("[ \"$held\" = \"$LOAD_FLOOR/$LOAD_REPLICAS\" ]").contains("load_autoscale_settled \"before the load's setup\"")
                .contains("\"$A/HorizontalPodAutoscaler.finapp-app.yaml\"");
        assertThat(function(load, "load_autoscale_settled")).as("the count asked and the ready replicas both the floor, nothing terminating or unready")
                .contains("'{.spec.replicas}/{.status.readyReplicas}' 2>/dev/null)\" = \"$LOAD_FLOOR/$LOAD_FLOOR\" ]")
                .contains("NF >= 3 && ($2 != \"\" || $3 != \"True\") {n++}");
        String settledAgain = function(load, "load_autoscale_start");
        assertThat(settledAgain.indexOf("load_autoscale_settled \"as the load starts\"")).as("the floor again as the load starts, before the probe and the reading")
                .isPositive().isLessThan(settledAgain.indexOf("if [ \"$PROBE\" = scale-down-unbounded ]; then"));
        for (String name : new String[] {"load_autoscale_floor", "load_autoscale_settled", "load_autoscale_sampler", "load_autoscale_judge"}) {
            assertThat(function(load, name)).as("%s never writes the count or the autoscaler: the metric decides it", name)
                    .doesNotContain("scale deployment").doesNotContain("patch hpa").doesNotContain("--replicas");
        }
        String start = function(load, "load_autoscale_start");
        assertThat(start).as("the one write before the load is the probe's").doesNotContain("scale deployment").doesNotContain("--replicas");
        assertThat(start.split("patch hpa", -1)).as("one patch of the autoscaler, the probe's removal of its scale-down policy").hasSize(2);
        String judge = function(load, "load_autoscale_judge");
        assertThat(judge).as("read every 10 s; the count reached with every replica ready, after a reading above the target")
                .contains("(LOAD_ENDED - LOAD_STARTED) / 10 * 9 / 10").contains("$2 == n && $3 == n {print $1; exit}")
                .contains("$1 <= r && $6 ~ /^[0-9]+$/ && $6 + 0 > t");
        assertThat(judge).as("back at the floor with every replica ready within the load, after a reading below the target")
                .contains("$1 > u && $2 == f && $3 == f").contains("$1 >= u && $1 <= d && $6 ~ /^[0-9]+$/ && $6 + 0 < t");
        assertThat(judge).as("the census sampled at the count and again at the floor, then the formula at every count")
                .contains("[ \"$at_count\" -ge 1 ] || fail").contains("[ \"$at_floor\" -ge 1 ] || fail").contains("assert_within_count load");
        String scope = function(load, "load_scope");
        assertThat(scope.indexOf("load_autoscale_judge")).as("judged after the census and before the budget")
                .isGreaterThan(scope.indexOf("load_census")).isLessThan(scope.indexOf("load_budget"));
        assertThat(function(load, "load_judge")).as("ten replicas served each route, whatever is ready at the end")
                .contains("{ [ \"$LOAD_AUTOSCALE\" = true ] && [ \"$served\" -ge \"$LOAD_REPLICAS\" ]; }");
    }

    @Test
    @DisplayName("every connection sample reads the count it was asked for, and is held to the budget's formula at that count, the margin read"
            + " from the run's copy")
    void eachSampleIsHeldToTheFormulaAtItsCount() {
        String sampler = function(harness, "start_sampler");
        assertThat(sampler).as("the count read before the pods, written as the eighth column")
                .contains("\\toperator\\tcount\\n'").contains("count=$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.replicas}'");
        assertThat(sampler.indexOf("count=$(kube")).isLessThan(sampler.indexOf("fleet=$(sample_fleet"));
        assertThat(sampler).contains("\"$operator\" \"$count\" >> \"$file\"");
        assertThat(harness).as("the margin: the copy's instances less the count it holds")
                .contains("BUDGET_MARGIN=$((FLEET_INSTANCES - FLEET_REPLICAS))");
        String within = function(harness, "assert_within_count");
        // The count of samples over it is what fails the run, so that line is held whole: the same comparison elsewhere in the function
        // (the first sample named in the message) cannot stand in for it (P16-TSK-014's own probe P2 found the looser pin passing).
        assertThat(within).as("each sample against (its count + the margin) x the pool, never the run's whole budget, and that count fails the run")
                .contains("over=$(awk -F'\\t' -v pool=\"$pool\" -v m=\"$BUDGET_MARGIN\" 'NR > 1 && $8 ~ /^[0-9]+$/ && $5 + 0 > ($8 + m) * pool {n++} END {print n + 0}'")
                .contains("[ \"$over\" = 0 ] || fail").doesNotContain("$BUDGET\"").contains("pool=$((WRITER_POOL + REPORTING_POOL))");
        assertThat(within).as("a run whose counts were not read is not a run that held them")
                .contains("[ \"$counted\" -ge 1 ] && [ \"$counted\" -ge $((total * 9 / 10)) ]").contains("counts-$1.tsv");
    }

    @Test
    @DisplayName("the probe removes the autoscaler's scale-down policy before the load, only with the autoscale profile, and the formula at"
            + " each count must go red")
    void theProbeRemovesTheScaleDownPolicy() {
        String start = function(load, "load_autoscale_start");
        assertThat(start).contains("if [ \"$PROBE\" = scale-down-unbounded ]; then")
                .contains("[{\"op\":\"remove\",\"path\":\"/spec/behavior/scaleDown\"}]").contains("load_autoscale_sampler &");
        String run = function(load, "load_run");
        assertThat(run.indexOf("    load_autoscale_start\n")).as("planted before the load is timed").isPositive()
                .isLessThan(run.indexOf("LOAD_STARTED=$(date +%s)"));
        assertThat(function(load, "load_preflight")).contains("[ \"$PROBE\" != scale-down-unbounded ] || [ \"$LOAD_AUTOSCALE\" = true ]");
        String judge = function(load, "load_autoscale_judge");
        assertThat(judge.indexOf("[ \"$PROBE\" != scale-down-unbounded ] || fail")).as("the probe fails the run if the formula held")
                .isGreaterThan(judge.indexOf("assert_within_count load"));
        assertThat(read("deploy/base/horizontalpodautoscaler.yaml")).as("the policy the probe removes: one pod per period no shorter than the grace")
                .contains("    scaleDown:\n      stabilizationWindowSeconds: 300\n      selectPolicy: Max\n      policies:\n        - type: Pods\n          value: 1\n");
    }

    @Test
    @DisplayName("the autoscale profile moves the CPU metric with sign-ins at nine times their peak while the money mix runs, enough by the"
            + " capacity model's own cost to hold ten replicas above the autoscaler's target")
    void theAutoscaleProfileMovesTheMetricWhileMoneyMoves() {
        String profile = read(PROFILE);
        assertThat(profile).contains("  \"stages\": [[300, 0.25], [1200, 0.25, \"signin\", 9.0], [1800, 0.25]],")
                .contains("  \"autoscale\": true,").contains("  \"generators\": 8,").doesNotContain("\"stress\"");
        for (String money : new String[] {"transfer", "checkout", "topup"}) {
            assertThat(profile).as("%s driven by k6 throughout", money).containsPattern("\\{\"name\": \"" + money + "\", \"generator\": \"k6\"");
        }
        assertThat(read(MODEL)).as("a stage of four fields: every operation at the factor, the one named at its own")
                .contains("if (stage.length > 3) {\n        return Math.round(operation.rate * (stage[2] === operation.name ? stage[3] : stage[1]));");
        assertThat(function(load, "load_stages")).contains("x[NR] = (NF > 3) ? \" \" $4 : \"\"").contains("print t, f[i], o[i] x[i]");
        assertThat(function(load, "load_expected")).as("judged as model.js rounds it")
                .contains("NF > 3 { target = int(rate * (($3 == op) ? $4 : $2) + 0.5); n += target * $1 / unit; next }");
        assertThat(read(STAGES)).contains("own[n] = (k > 3) ? f[4] : \"\"").contains("(own[i] != \"\") ? ((only[i] == op) ? own[i] : factor[i])");
        // Read, never copied: the sign-in's cost on the replicas (CAPACITY_MODEL.md section 3.4's isolation run), the profile's sign-in rate and
        // its surge, the pods' CPU request and the autoscaler's target. The surge alone must hold ten pods above the target.
        Matcher cost = Pattern.compile("\\| Sign-in \\|[^\\n]*\\| replicas (\\d+) \\(the credential hash\\)").matcher(read("docs/architecture/CAPACITY_MODEL.md"));
        assertThat(cost.find()).as("the sign-in's measured CPU on the replicas").isTrue();
        Matcher rate = Pattern.compile("\\{\"name\": \"signin\", \"generator\": \"k6\", \"exec\": \"signin\", \"rate\": (\\d+), \"per\": \"1000s\"").matcher(profile);
        assertThat(rate.find()).as("the profile's sign-in rate").isTrue();
        Matcher request = Pattern.compile("requests:\\n\\s+cpu: (\\d+)m").matcher(read("deploy/base/deployment.yaml"));
        assertThat(request.find()).as("the pods' CPU request").isTrue();
        Matcher target = Pattern.compile("averageUtilization: (\\d+)").matcher(read("deploy/base/horizontalpodautoscaler.yaml"));
        assertThat(target.find()).as("the autoscaler's target").isTrue();
        Matcher surge = Pattern.compile("\\[\\d+, [0-9.]+, \"signin\", ([0-9.]+)]").matcher(profile);
        assertThat(surge.find()).as("the surge's own factor").isTrue();
        double surgeCores = Integer.parseInt(rate.group(1)) / 1000.0 * Double.parseDouble(surge.group(1)) * Integer.parseInt(cost.group(1)) / 1000.0;
        double tenAtTarget = 10 * Integer.parseInt(request.group(1)) / 1000.0 * Integer.parseInt(target.group(1)) / 100.0;
        assertThat(surgeCores).as("the surge's CPU against ten pods at the target (derived from measured costs, not measured at ten)")
                .isGreaterThan(tenAtTarget);
    }

    @Test
    @DisplayName("a fleet that moves is read by set: a replica ready past the scrape's settling time must be scraped, none may stand unready"
            + " past its start's bound, each bound read; a held fleet keeps the exact count")
    void aMovingFleetIsReadBySet() {
        String sample = function(load, "load_sample");
        assertThat(sample).contains("elif load_fleet_moves && ! load_scraped \"$s_out\"; then")
                .contains("elif ! load_fleet_moves && { [ -z \"$s_up\" ] || [ -z \"$s_ready\" ] || [ \"$s_up\" != \"$s_ready\" ]; }; then");
        assertThat(sample.indexOf("load_scraped")).as("a branch before the gauges and the pages, which every sample still reads").isPositive()
                .isLessThan(sample.indexOf("elif ! grep -q '\"result\":\\[\\]' \"$s_out.gauges.json\"; then"));
        assertThat(function(load, "load_fleet_moves")).contains("[ -n \"$PIPELINE_SCALE\" ] || [ \"$LOAD_AUTOSCALE\" = true ]");
        String scraped = function(load, "load_scraped");
        assertThat(scraped).as("the targets Prometheus scraped up, by address; the pods by readiness, its time and their creation")
                .contains("load_prom 'up{job=\"finapp\"} == 1'").contains("grep -q '\"status\":\"success\"'")
                .contains("{.status.conditions[?(@.type==\"Ready\")].lastTransitionTime}").contains("{.metadata.creationTimestamp}")
                .contains("[ -n \"$name\" ] && [ -z \"$gone\" ] || continue")
                .contains("[ $((now - at)) -ge \"$LOAD_SETTLE\" ] || continue").contains("grep -qxF \"$address\" \"$1.scraped.txt\"")
                .contains("[ $((now - at)) -lt \"$LOAD_UNREADY_BOUND\" ]").doesNotContain("fail ");
        String bounds = function(load, "load_moving_bounds");
        assertThat(bounds).as("twice the scrape interval and the listing's refresh, from the kind Prometheus's own configuration")
                .contains("deploy/observability/overlays/kind/prometheus.yml").contains("LOAD_SETTLE=$((2 * (scrape + refresh)))")
                .as("the startup probe's budget and the readiness window, from the run's own Deployment")
                .contains("probe_budget \"$A/Deployment.finapp-app.yaml\" startupProbe").contains("probe_budget \"$A/Deployment.finapp-app.yaml\" readinessProbe")
                .contains("LOAD_UNREADY_BOUND=$((starting + readying))");
        String setup = function(load, "load_setup");
        assertThat(setup.indexOf("! load_fleet_moves || load_moving_bounds")).as("its bounds read before the first sample").isPositive()
                .isLessThan(setup.indexOf("load_sample 0"));
    }

    @Test
    @DisplayName("the experiments scope runs at its count only what is written for a count (E45 at ten), its topology and its recovery at it")
    void theExperimentsScopeRunsAtItsCountOnlyWhatIsWrittenForIt() {
        String experiments = read(EXPERIMENTS);
        assertThat(function(experiments, "experiments_topology_check")).contains("[ \"$ready\" = \"$FLEET_REPLICAS\" ] || fail");
        assertThat(function(experiments, "E45_recover")).contains("ready_fleet 400 || fail").doesNotContain("ready_three");
        assertThat(harness).contains("EXPERIMENTS_AT_A_COUNT=\"E45\"");
    }

    @Test
    @DisplayName("the pipeline's scale reaches the overlay's ceiling (E71), its budget judged at each count it was asked for")
    void thePipelinesScaleReachesTheOverlaysCeiling() {
        String pipeline = read(PIPELINE);
        String preflight = function(pipeline, "pipeline_preflight");
        assertThat(preflight).as("three to the ceiling the overlay states, read from it")
                .contains("path: \\/spec\\/maxReplicas$/{n;s/^        value: \\([0-9]*\\)$/\\1/p;}")
                .contains("[ \"$count\" -ge 3 ] && [ \"$count\" -le \"$ceiling\" ] || cannot").doesNotContain("3 | 4 | 5 | 6 | 7");
        assertThat(function(pipeline, "pipeline_scale_judge")).as("each count's peak from the samples asked for it, within the run's budget")
                .contains("NR > 1 && $8 == c && $5 + 0 > m").contains("[ \"$peak\" -le \"$BUDGET\" ] || fail").contains("[ \"$peak\" -gt 0 ] || fail");
    }
}
