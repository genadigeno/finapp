package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the instance, pod, node and edge-controller experiments hold about themselves ({@code P16-TSK-020};
 * {@code OPERATIONS_RUNBOOK.md} section 11.61): E01 at the run's count and E02, E03, E04 and E25, each written for a count and run under
 * a load profile beside them, each behind the safety boundary with its faults in the registry, each refusing an unauthenticated request
 * while its fault is on and walking the availability matrix's row it checks.
 *
 * <p>None of them has run on a cluster: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025), and a
 * run's result is recorded in the resilience register and the runbook when it happens. What a hermetic test holds is the text that makes
 * each run a measurement: every fault guarded and registered and taken off on every way out, every figure read rather than copied, every
 * failure told apart by a window the run measured, and nothing judged on what was never read.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the instance, pod, node and edge-controller experiments hold what they say they hold (P16-TSK-020)")
class InstanceFailuresRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";

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

    private static String experiment(String name) {
        return function(experiments, name);
    }

    @Test
    @DisplayName("each experiment is written for the run's count and runs under the load beside it, the refusals read before anything is"
            + " built")
    void eachIsWrittenForACountAndRunsUnderTheLoad() {
        assertThat(harness).as("written for a count, beside E45").contains("EXPERIMENTS_AT_A_COUNT=\"E45 E01 E02 E03 E04 E25\"");
        assertThat(experiments).contains("EXPERIMENTS_UNDER_LOAD=\"E01 E02 E03 E04 E25\"").contains("EXPERIMENTS_NEED_LOAD=\"E02 E03 E04 E25\"")
                .contains("EXPERIMENTS_LOAD=${REHEARSAL_EXPERIMENTS_LOAD:-}");
        String resolve = experiment("experiments_load_resolve");
        assertThat(resolve).as("an experiment written for the load without one, one not written for it with one, E01 away from three without"
                        + " one, the probe without E02, a probe-pod sender after E04: each refused")
                .contains("runs under a load profile: name one in REHEARSAL_EXPERIMENTS_LOAD")
                .contains("is not written to run under a load: its census is the full one")
                .contains("if [ \"$FLEET_REPLICAS\" != 3 ] && [ -z \"$EXPERIMENTS_LOAD\" ]; then")
                .contains("REHEARSAL_PROBE=pdb-removed takes the disruption budget away before E02's evictions")
                .contains("E04:E01 | E04:E03)");
        int resolved = harness.indexOf("[ \"$SCOPE\" != experiments ] || experiments_load_resolve");
        assertThat(resolved).as("right after the count is resolved, before the build").isGreaterThan(harness.indexOf("\nfleet_resolve\n"))
                .isLessThan(harness.indexOf("step build-images"));
        int preflight = harness.indexOf("if [ \"$SCOPE\" = experiments ] && [ -n \"$EXPERIMENTS_LOAD\" ]; then\n    step load-preflight\n    experiments_load_preflight");
        assertThat(preflight).as("the load's model read from what k6 will run, before the build").isPositive().isLessThan(harness.indexOf("step build-images"));
        assertThat(experiment("experiments_load_preflight")).contains("LOAD_PROFILE_NAME=$EXPERIMENTS_LOAD").contains("LOAD_REPLICAS=$FLEET_REPLICAS")
                .contains("load_preflight").contains("the autoscale profile is the load scope's").contains("has no stages");
        String run = experiment("experiment_run");
        assertThat(run).as("under the load, an experiment's census is the load's through its window; the full one otherwise")
                .contains("if [ -n \"$EXPERIMENTS_LOAD_ON\" ]; then\n        experiments_window_census \"$1\"\n    else\n        census \"$1\"");
        assertThat(experiment("E01_recover")).as("E01 back at the run's count, never three").contains("ready_fleet 400 || fail").doesNotContain("ready_three");
    }

    @Test
    @DisplayName("the load runs beside the experiments as the load scope drives it, its fleet read by set with each fault's victims excused,"
            + " and is judged whole after the last experiment")
    void theLoadRunsBesideTheExperimentsAndIsJudgedWhole() {
        String begin = experiment("experiments_load_begin");
        assertThat(begin.indexOf("EXPERIMENTS_LOAD_ON=1")).as("the fleet read by set before the first sample").isLessThan(begin.indexOf("load_setup"));
        assertThat(begin).as("load.sh's own setup, sampler, statistics, feeder and callback senders")
                .contains("load_setup").contains("load_sampler &").contains("load_statistics &").contains("load_feeder &")
                .contains("java --source 21 /src/Rehearsal.java /work load-callbacks load");
        assertThat(begin).as("the generators under the names load.sh reads, each given an edge node's address in turn, its open model's segments")
                .contains("--name \"$CLUSTER-load-k6-$i\"").contains("address=$(experiments_generator_address \"$i\")")
                .contains("--add-host \"public.finapp.test:$address\"").contains("--execution-segment \"$i/$LOAD_GENERATORS:$((i + 1))/$LOAD_GENERATORS\"")
                .contains("SSL_CERT_FILE=/generator/edge-ca.crt");
        assertThat(experiment("experiments_edge_split")).as("two nodes each running a ready public controller with E25, the workload's first")
                .contains("EXPERIMENTS_EDGE_NODES=$NODE_IP").contains("$1 == \"edge\" && $2 == \"True\" && $4 == \"\"")
                .contains("do not run on two nodes");
        assertThat(experiment("experiments_load_peak")).as("the experiments in the profile's last stage, the samples read until then")
                .contains("for (i = 1; i < NR; i++) s += t[i]").contains("a sample failed under the load before the experiments");
        assertThat(function(load, "load_fleet_moves")).contains("|| [ -n \"${EXPERIMENTS_LOAD_ON:-}\" ]");
        String scraped = function(load, "load_scraped");
        assertThat(scraped).as("a pod a running experiment took, on a node it stopped, or made since, excused: read by the experiment itself")
                .contains("|{.spec.nodeName}{\"\\n\"}{end}'").contains("read -r name address gone ready since born node")
                .contains("if [ -n \"${EXPERIMENTS_LOAD_ON:-}\" ] && experiments_excused \"$name\" \"$node\" \"$born\"; then");
        assertThat(scraped.indexOf("experiments_excused")).as("after a terminating pod is left to neither side, before either bound")
                .isGreaterThan(scraped.indexOf("[ -n \"$name\" ] && [ -z \"$gone\" ] || continue")).isLessThan(scraped.indexOf("if [ \"$ready\" = True ]; then"));
        assertThat(experiment("experiments_excused")).contains("($1 == \"pod\" && $2 == pod) || ($1 == \"node\" && $2 == node) || ($1 == \"after\" && born >= $2)");
        assertThat(experiment("experiments_load_steady")).as("each steady state empties the victims, and refuses a load that ended or a sample that failed")
                .contains(": > \"$WORK/experiments-victims\"").contains("load_generating || cannot").contains("a sample failed under the load");
        String window = experiment("experiments_window_census");
        assertThat(window).as("every sample through the window PASS, none 45 s from the next, one more after the recovery")
                .contains("load_sample \"$1-end\"").contains("-v from=\"$EXP_STARTED_AT\"").contains("first - from > 45").contains("gap > 45")
                .contains("bad > 0").contains("EXP_STOPPED=");
        String end = experiment("experiments_load_end");
        List<String> order = List.of("while load_generating; do", "load_report", "load_judge", "load_shed", "experiments_load_windows", "experiments_load_census");
        int at = -1;
        for (String part : order) {
            int next = end.indexOf(part, at + 1);
            assertThat(next).as("%s after the step before it", part).isGreaterThan(at);
            at = next;
        }
        assertThat(function(load, "load_judge")).as("a load beside faults records a fault's dropped iterations as the stress profile's, and is judged"
                        + " on its money as every load")
                .contains("[ -z \"${EXPERIMENTS_LOAD_ON:-}\" ] || stress=true").contains("[ \"$unresolved\" = 0 ] || fail")
                .contains("|| { [ -n \"${EXPERIMENTS_LOAD_ON:-}\" ] && [ \"$served\" -ge \"$ready\" ]; }");
        String census = experiment("experiments_load_census");
        assertThat(census).as("the full census with the generator's counts, then a last sample")
                .contains("LOAD_TRANSFERS=").contains("LOAD_CHECKOUTS=").contains("LOAD_TOPUPS=$((LOAD_FUNDED + ${topups:-0}))")
                .contains("census load").contains("load_sample final");
        assertThat(experiment("experiments_scope").indexOf("[ -z \"$EXPERIMENTS_LOAD_ON\" ] || experiments_load_end"))
                .as("the load judged after the last experiment").isGreaterThan(experiment("experiments_scope").indexOf("experiment_run \"$id\""));
    }

    @Test
    @DisplayName("the generators' own series are read per window after the load, each rule's breach failing the experiment's standing verdict")
    void eachWindowIsReadFromTheGeneratorsOwnSeries() {
        String windows = experiment("experiments_load_windows");
        assertThat(windows).as("the request timeout read from the scenarios, never copied; a failure is no answer or a 5xx, read by column name")
                .contains("sed -n \"s/.*timeout: '\\([0-9]*\\)s'.*/\\1/p\" \"$LOAD_SRC/scenarios.js\"")
                .contains("$1 == \"metric_name\" {for (f = 1; f <= NF; f++) c[$f] = f; next}")
                .contains("$1 == \"http_reqs\" && ($c[\"status\"] == \"\" || $c[\"status\"] == 0 || $c[\"status\"] >= 500)")
                .contains("-v to=\"$((to + timeout + 5))\"");
        assertThat(windows).as("none: no generator's request failed; half: only the lost controller's node's generators; a 503 apart")
                .contains("none) problem=$(awk -F'\\t' '$3 != 503 {n += $4}").contains("half) problem=$(awk -F'\\t' -v lost=\"$address\" '$2 != lost && $3 != 503");
        assertThat(windows).as("a breach is recorded as the experiment's standing verdict, and fails the run")
                .contains("printf 'verdict\\tFAILED\\nafter the load").contains("[ -z \"$broken\" ] || fail");
        assertThat(experiment("E02_recover")).contains("experiments_window E02 \"$E02_KILLED_AT\" \"$E02_BACK_AT\" record");
        assertThat(experiment("E03_fault")).contains("experiments_window E03 \"$evicted\" \"$gone\" none");
        assertThat(experiment("E04_recover")).contains("experiments_window E04 \"$E04_STOPPED_AT\" \"$E04_BACK_AT\" record");
        assertThat(experiment("E25_recover")).contains("experiments_window E25 \"$E25_KILLED_AT\" \"$E25_BACK_AT\" half \"$E25_LOST_ADDRESS\"");
    }

    @Test
    @DisplayName("every new fault goes through the boundary and the registry, and comes off on every way out: a stopped worker at the address it had,"
            + " a cordon, the disruption budget")
    void everyNewFaultIsGuardedRegisteredAndCleared() {
        String on = experiment("experiments_fault_on");
        assertThat(on).as("the boundary, then the registry, then the fault").startsWith("\n    experiments_guard\n    printf '%s\\n' \"$*\" >> \"$FAULTS\"\n");
        assertThat(on).as("a worker of this cluster, at the address recorded for its restore, stopped as a machine stops")
                .contains("= \"$CLUSTER|worker\" ]").contains("|| fail \"the safety boundary refused to stop $1: it is not a worker of $CLUSTER\"")
                .contains("[ \"$(dock inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' \"$1\" 2>/dev/null)\" = \"$2\" ]")
                .contains("dock kill \"$1\"");
        assertThat(on).contains("|| fail \"the safety boundary refused to cordon $1: it is not a node of $CLUSTER\"").contains("kube cordon \"$1\"")
                .contains("kube -n finapp delete poddisruptionbudget finapp-app");
        String undo = experiment("experiments_undo");
        assertThat(undo).contains("node-stopped) experiments_node_start \"$1\" \"$2\" ;;").contains("cordoned) kube uncordon \"$1\" ;;")
                .contains("pdb-removed) kube apply -f - < \"$A/PodDisruptionBudget.finapp-app.yaml\" ;;");
        assertThat(experiment("experiments_node_start")).as("started again and held to the address it had")
                .contains("dock start \"$1\" || return 1").contains("[ \"$address\" = \"$2\" ] || {");
        String left = experiment("experiments_faults_left");
        assertThat(left.indexOf("--filter status=exited")).as("a stopped node read first and alone, before anything that execs through a kubelet")
                .isPositive().isLessThan(left.indexOf("--filter status=paused"));
        assertThat(left).contains("nodes still cordoned").contains("the application's disruption budget is absent");
        String clear = experiment("experiments_clear_faults");
        assertThat(clear.indexOf("--filter status=exited")).as("on every way out, a stopped node started first").isPositive()
                .isLessThan(clear.indexOf("dock unpause \"$node\""));
        assertThat(clear).contains("kube uncordon \"$node\"").contains("kube apply -f - < \"$A/PodDisruptionBudget.finapp-app.yaml\"");
        String crash = experiment("experiments_crash");
        assertThat(crash).as("the crash probes put the new kinds on too").contains("experiments_fault_on pdb-removed")
                .contains("experiments_fault_on cordoned \"$victim_node\"").contains("experiments_fault_on node-stopped \"$stop_node\"");
        String spare = experiment("experiments_spare");
        assertThat(spare).as("the cordon a fault of the registry, put on and off through it").contains("experiments_fault_on cordoned \"$EXPERIMENTS_SPARE_NODE\"")
                .contains("experiments_fault_off cordoned \"$EXPERIMENTS_SPARE_NODE\"");
        assertThat(experiment("E04_fault")).contains("experiments_fault_on node-stopped \"$E04_NODE\" \"$E04_ADDRESS\"");
        assertThat(experiment("E02_fault")).contains("[ \"$PROBE\" != pdb-removed ] || experiments_fault_on pdb-removed");
    }

    @Test
    @DisplayName("E02 kills half the fleet at once a zone at a time, asks an eviction of every replica once the budget reads the kills, and stops"
            + " at any reading with no replica ready")
    void e02KillsHalfAndTheBudgetRefusesEveryEviction() {
        String steady = experiment("E02_steady");
        assertThat(steady).as("half, a zone at a time").contains("E02_HALF=$((live / 2))")
                .contains("awk '{n[$4]++; print n[$4], $4, $1, $2}' \"$EXP_DIR/live.txt\" | sort -k1,1n -k2,2 | head -\"$E02_HALF\"");
        String fault = experiment("E02_fault");
        assertThat(fault.indexOf("e02_target \"$pod\"")).as("every process found before any signal is sent").isPositive()
                .isLessThan(fault.indexOf("kill -9 \"${rest#*|}\""));
        assertThat(experiment("e02_target")).contains("|| fail \"the safety boundary refused to kill on $TARGET_NODE: it is not a node of $CLUSTER\"");
        assertThat(fault.indexOf("experiments_context_check || fail")).isPositive().isLessThan(fault.indexOf("kill -9"));
        assertThat(fault).as("the evictions asked once the budget reads the kills, and every replica's").contains("case $reading in 0\\|*) break ;; esac")
                .contains("did not read the kills within 30 s").contains("experiments_evict \"$pod\" >> \"$EXP_DIR/evictions.tsv\"")
                .contains("[ -z \"$errors\" ] || cannot");
        assertThat(fault.indexOf("case $reading in 0\\|*) break ;; esac")).isLessThan(fault.indexOf("experiments_evict \"$pod\""));
        assertThat(experiment("experiments_evict")).as("a refusal is the budget's own message, anything else an error")
                .contains("kube create --raw \"/api/v1/namespaces/finapp/pods/$1/eviction\" -f -").contains("*\"disruption budget\"*) ev_outcome=refused")
                .contains("*) ev_outcome=error");
        String observe = experiment("E02_observe");
        assertThat(observe).as("the stop: any reading with no replica ready, read every 2 s")
                .contains("if [ \"$ready\" = 0 ]; then").contains("the fleet's ready count reached zero").contains("sleep 2");
        assertThat(observe).as("with the budget, no eviction admitted; each victim a SIGKILL, back in its pod")
                .contains("[ \"$E02_ADMITTED\" = 0 ] || fail").contains("[ \"$code\" = 137 ] || fail").contains("$3 > before[$1]");
        String recover = experiment("E02_recover");
        assertThat(recover).as("every failed answer in a killed replica's window, the survivors' own 503s apart, the workload's no more")
                .contains("experiments_failures_windows \"$EXP_DIR\" \"$EXP_DIR/windows.txt\"").contains("[ \"$FAILED_OUTSIDE\" = 0 ] || fail")
                .contains("[ \"$failed_requests\" -le \"$FAILED_INSIDE\" ]");
        assertThat(experiment("experiments_failures_windows")).contains("if ($4 == 503 && $5 == 503) { refused++; next }")
                .contains("[ -s \"$2\" ] || cannot");
        assertThat(load).as("the shed requests' census the routed note named (P16-TSK-016)").contains("load_shed() {");
        assertThat(function(experiments, "experiments_load_end")).contains("load_shed");
    }

    @Test
    @DisplayName("E03 evicts gracefully, one a zone, each admitted only when the budget allows and each with work in flight its own drain answers;"
            + " a request failed on a graceful path stops it")
    void e03EvictsGracefullyWithWorkInFlight() {
        String fault = experiment("E03_fault");
        assertThat(fault.indexOf("[ \"$claim\" = IN_PROGRESS ] || cannot")).as("a payout held on the pod, its claim in progress, before its eviction")
                .isPositive().isLessThan(fault.indexOf("line=$(experiments_evict \"$pod\")"));
        assertThat(fault).as("a refusal is the budget serialising, asked again; the pod gone and the fleet ready before the next")
                .contains("[ \"$outcome\" = refused ] || break").contains("while kube -n finapp get pod \"$pod\" -o name").contains("ready_fleet 400 || fail");
        assertThat(fault).as("the stop read after each eviction, before the next: a failed answer at the edge through it, or the held payout unanswered")
                .contains("if [ \"$FAILED_INSIDE\" != 0 ]; then").contains("a request failed on a graceful path").contains("e03_resolve \"$key\" \"$n\"");
        assertThat(experiment("E03_observe")).contains("[ \"$drained\" = \"$E03_EVICTIONS\" ] || fail");
        assertThat(experiment("E03_recover")).contains("if [ \"$failed_requests\" != 0 ]; then");
    }

    @Test
    @DisplayName("E04 stops the spare zone's worker, kept clear of what the run must not lose, and reads the scheduler's answer: capacity not"
            + " restored while it is lost is FAILED, with the reason")
    void e04StopsTheSpareWorkerAndReadsTheSchedulersAnswer() {
        assertThat(experiments).contains("EXPERIMENTS_SPARE_ZONE=zone-c");
        String place = experiment("experiments_place");
        assertThat(place).as("off the spare zone only in the experiments scope with E04 named, into an affinity or before the pod's securityContext")
                .contains("experiments:*\" E04 \"*) ;;").contains("*) cat; return 0 ;;").contains("operator: NotIn")
                .contains("/^      affinity:$/ && !done").contains("/^      securityContext:$/ && !done");
        assertThat(harness).contains("\"$DIR/simulator.yaml\" | experiments_place | kube apply -f -");
        assertThat(experiments).contains("\"$DIR/experiments/toxiproxy.yaml\" | experiments_place | kube apply -f -")
                .contains("\"$DIR/experiments/archive-store.yaml\" | experiments_place | kube apply -f -")
                .contains("| experiments_place | kube apply -f - >> \"$ART/apply.log\" 2>&1 || cannot \"the standby could not be applied\"");
        assertThat(experiment("experiments_spare_render")).as("the primary off it, the brokers one a zone, each read back")
                .contains("experiments_place < \"$A/StatefulSet.finapp-postgres.yaml\"").contains("app.kubernetes.io/name: finapp-kafka")
                .contains("whenUnsatisfiable: DoNotSchedule").contains("|| cannot \"E04: the brokers could not be spread");
        assertThat(experiment("experiments_spare")).as("the cluster's own units moved off it before any load, everything read back off it")
                .contains("kube-system/coredns finapp-observability/finapp-prometheus finapp-edge/finapp-edge-public finapp-edge/finapp-edge-operator")
                .contains("[ -z \"$left\" ] || fail").contains("experiments_edge_addresses");
        String steady = experiment("E04_steady");
        assertThat(steady).as("no client's way in on it, nothing that must not be lost on it, a replica on it")
                .contains("for way in $NODE_IP $OPERATOR_IP $EXPERIMENTS_EDGE_NODES; do").contains("[ -z \"$left\" ] || cannot")
                .contains("no application replica runs on $E04_NODE");
        String observe = experiment("E04_observe");
        assertThat(observe).as("the toleration read from a lost replica, never copied; the stop the control plane unreachable")
                .contains("{.spec.tolerations[?(@.key==\"node.kubernetes.io/unreachable\")].tolerationSeconds}")
                .contains("the control plane did not answer three reads in a row");
        assertThat(observe).as("section 11.8's InstanceDown read, and its absence a stop; replacements Pending past the hold FAILED with the scheduler's"
                        + " reason")
                .contains("ALERTS{alertname=\"InstanceDown\",alertstate=\"firing\"}").contains("InstanceDown never fired")
                .contains("--field-selector reason=FailedScheduling").contains("ready capacity not restored while the worker was lost");
        String recover = experiment("E04_recover");
        assertThat(recover).as("the worker Ready again, InstanceDown cleared, failures told apart to the edge's own wait, read from the edge")
                .contains("{.status.conditions[?(@.type==\"Ready\")].status}").contains("InstanceDown still fired 600 s after")
                .contains("$((E04_STOPPED_AT + out + EXP_EDGE_WAIT + 5))").contains("[ \"$FAILED_OUTSIDE\" = 0 ] || fail");
        assertThat(experiment("experiments_edge_wait")).contains("responseHeaderTimeout").contains("\"$ROOT/deploy/edge/base/public-edge.yaml\"");
    }

    @Test
    @DisplayName("E25 kills the public controller the workload does not reach, its generators split over both nodes: the survivor's answers and the"
            + " workload fail none, the lost half only inside its window")
    void e25LosesOneControllerWithTheGeneratorsSplit() {
        String steady = experiment("E25_steady");
        assertThat(steady).contains("E25_LOST_ADDRESS=$(printf '%s\\n' $EXPERIMENTS_EDGE_NODES | grep -vxF \"$NODE_IP\" | head -1)")
                .contains("cannot \"E25: no controller on each of the generators' two nodes\"");
        assertThat(experiment("E25_fault")).contains("experiments_kill_container finapp-edge \"$E25_VICTIM\" edge");
        assertThat(experiment("experiments_kill_container")).contains("|| fail \"the safety boundary refused to kill on $node: it is not a node of $CLUSTER\"")
                .contains("dock exec \"$node\" kill -9 \"$pid\"");
        String observe = experiment("E25_observe");
        assertThat(observe).as("its stop: both controllers unready, or the lost one not back in 10 min").contains("both controllers of the public edge unready")
                .contains("the lost controller was not replaced in 10 min").contains("[ \"$code\" = 137 ] || fail");
        String recover = experiment("E25_recover");
        assertThat(recover).as("the workload, through the survivor, failed none; the survivor's own lines read and none failed in the window")
                .contains("[ \"$failed_requests\" = 0 ] || fail").contains("kube -n finapp-edge logs \"$E25_SURVIVOR\" -c edge")
                .contains("[ \"$served\" -ge 10 ] || fail").contains("[ \"$failed\" = 0 ] || fail");
    }

    @Test
    @DisplayName("each experiment sends an unauthenticated request through the public edge while its fault is on, which must stay refused and leave"
            + " no record")
    void eachExperimentProbesAnUnauthenticatedRequest() {
        for (String name : List.of("E01_fault", "E02_fault", "E03_fault", "E04_fault", "E25_fault")) {
            assertThat(experiment(name)).as("%s probes while its fault is on", name).contains("experiments_unauthenticated");
        }
        String probe = experiment("experiments_unauthenticated");
        assertThat(probe).as("no bearer, through the public edge by the workload's way in").doesNotContain("Authorization")
                .contains("--add-host \"public.finapp.test:$NODE_IP\"").contains("https://public.finapp.test:30443/v1/transfers");
        assertThat(probe).as("401 or 403 a refusal; a success FAILED; never answered could not show it; no idempotency record under its key")
                .contains("401 | 403) ;;").contains("2[0-9][0-9])\n            EXP_STOPPED=").contains("so its refusal was not shown")
                .contains("SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = '$ua_key'");
    }

    @Test
    @DisplayName("each experiment walks the availability matrix's row it checks, after reading the edge's answers, a minute past its return")
    void eachExperimentWalksItsRow() {
        for (String[] walk : List.of(new String[] {"E01_recover", "A07"}, new String[] {"E02_recover", "A08"}, new String[] {"E03_recover", "A07"},
                new String[] {"E04_recover", "A07"}, new String[] {"E25_recover", "A09"})) {
            String body = experiment(walk[0]);
            assertThat(body.indexOf("experiments_edge_read")).as("%s reads the edge's answers before it walks", walk[0]).isNotNegative()
                    .isLessThan(body.indexOf("availability_check " + walk[1]));
            assertThat(body).contains("+ EXPERIMENTS_WALK_MARGIN))");
        }
        assertThat(experiments).contains("EXPERIMENTS_WALK_MARGIN=60");
    }
}
