package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.LockoutPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the calibration's runs hold about themselves ({@code P16-TSK-015}; ADR-0145; {@code CAPACITY_MODEL.md} section 4a;
 * {@code OPERATIONS_RUNBOOK.md} section 11.60): the burst profile with the autoscaler left free (E75), judged as a fleet that must not move
 * after a reading above its target, with no sign-in refused; the entry's probe (E76), the sign-in limit below one person's need in the run's
 * own copy; and the role split's reading (E77), the run's day replayed in the background profile's second stage and each stage read side by
 * side, never judged on its numbers.
 *
 * <p>None of them has run on a cluster: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025). What a hermetic
 * test holds is that the text keeps what makes each run a measurement: the bursts reach the target by the capacity model's own costs, the
 * judge has a positive control before its verdict, the probe's plant reaches the run's copy and nothing else, and the reading is read by
 * column name from the tables the run writes.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the calibration's runs hold what they say they hold (P16-TSK-015)")
class CapacityCalibrationRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String BURST = "infra/scripts/kind-rehearsal/load/burst.json";
    private static final String BACKGROUND = "infra/scripts/kind-rehearsal/load/background.json";
    private static final Pattern OPERATION = Pattern.compile("\\{\"name\": \"([a-z]+)\", \"generator\": \"k6\", \"exec\": \"[a-z]+\", \"rate\": ([0-9]+), \"per\": \"1000s\","
            + " \"perSecond\": ([0-9.]+), \"preAllocatedVUs\": ([0-9]+), \"maxVUs\": ([0-9]+),");
    // Each operation's row in CAPACITY_MODEL.md section 3.4, whose cost on the replicas the derivation reads.
    private static final Map<String, String> COST_ROWS = Map.of(
            "transfer", "Internal transfer",
            "checkout", "Card or checkout payment",
            "topup", "Wallet top-up or withdrawal (top-ups by card)",
            "read", "Account or balance read",
            "signin", "Sign-in",
            "merchant", "Merchant API");

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

    @Test
    @DisplayName("the burst profile is W2's shape at the scale kind carries, each burst taking three replicas above the autoscaler's target and"
            + " every gap below it, by the capacity model's measured costs")
    void theBurstProfileReachesTheTargetOnlyInItsBursts() {
        String profile = read(BURST);
        assertThat(profile).contains("  \"autoscale\": true,").contains("  \"bursts\": true,").doesNotContain("\"stress\"");
        Matcher stagesLine = Pattern.compile("(?m)^  \"stages\": \\[\\[(.*)]],$").matcher(profile);
        assertThat(stagesLine.find()).isTrue();
        List<String[]> stages = new ArrayList<>();
        for (String stage : stagesLine.group(1).split("], \\[")) {
            stages.add(stage.replace("\"", "").split(", "));
        }
        assertThat(stages).as("six cycles of a gap and a burst, an hour").hasSize(12);
        String model = read("docs/architecture/CAPACITY_MODEL.md");
        Matcher w2 = Pattern.compile("A \\*\\*burst\\*\\* is three times the busiest hour's rate for ([0-9]+) seconds").matcher(model);
        assertThat(w2.find()).as("W2's burst, three times for its seconds, read from the capacity model").isTrue();
        int seconds = 0;
        for (int i = 0; i < stages.size(); i++) {
            String[] stage = stages.get(i);
            seconds += Integer.parseInt(stage[0]);
            if (i % 2 == 0) {
                assertThat(stage).as("gap %d: every operation at the gap's factor", i).hasSize(2);
            } else {
                assertThat(stage).as("burst %d: the mix at three times the gap, sign-ins at three times their target peak", i).hasSize(4);
                assertThat(Integer.parseInt(stage[0])).as("W2's burst length").isEqualTo(Integer.parseInt(w2.group(1)));
                assertThat(Double.parseDouble(stage[1]) / Double.parseDouble(stages.get(i - 1)[1])).as("W2's three times").isCloseTo(3.0,
                        org.assertj.core.data.Offset.offset(0.01));
                assertThat(stage[2]).isEqualTo("signin");
                assertThat(Double.parseDouble(stage[3])).isEqualTo(3.0);
            }
        }
        assertThat(seconds).as("an hour, a burst every ten minutes").isEqualTo(3600);
        String stress = read("infra/scripts/kind-rehearsal/load/stress.json");
        assertThat(stress).as("each burst's mix is E30's step 3, carried within every objective at three replicas (CAPACITY_MODEL.md section 3.4)")
                .contains("[300, " + stages.get(1)[1] + "]");

        // Read, never copied: each operation's cost on the replicas (section 3.4's isolation run), its rate here, the pods' request, the target.
        Map<String, Double> rate = new LinkedHashMap<>();
        Matcher operation = OPERATION.matcher(profile);
        while (operation.find()) {
            rate.put(operation.group(1), Double.parseDouble(operation.group(2)) / 1000);
            assertThat(operation.group(4)).as("%s's users all allocated before the load", operation.group(1)).isEqualTo(operation.group(5));
        }
        assertThat(rate.keySet()).as("the mix the demonstration drives").containsExactlyInAnyOrder("transfer", "checkout", "topup", "read", "signin", "merchant");
        double gap = 0;
        double burst = 0;
        for (Map.Entry<String, Double> entry : rate.entrySet()) {
            Matcher cost = Pattern.compile("\\| " + Pattern.quote(COST_ROWS.get(entry.getKey())) + " \\|[^\\n]*?\\| replicas ([0-9.]+)").matcher(model);
            assertThat(cost.find()).as("%s's measured cost on the replicas", entry.getKey()).isTrue();
            double coresPerUnit = entry.getValue() * Double.parseDouble(cost.group(1)) / 1000;
            gap += coresPerUnit * Double.parseDouble(stages.get(0)[1]);
            burst += coresPerUnit * ("signin".equals(entry.getKey()) ? Double.parseDouble(stages.get(1)[3]) : Double.parseDouble(stages.get(1)[1]));
        }
        Matcher request = Pattern.compile("requests:\\n\\s+cpu: (\\d+)m").matcher(read("deploy/base/deployment.yaml"));
        assertThat(request.find()).isTrue();
        Matcher target = Pattern.compile("averageUtilization: (\\d+)").matcher(read("deploy/base/horizontalpodautoscaler.yaml"));
        assertThat(target.find()).isTrue();
        double threeAtTarget = 3 * Integer.parseInt(request.group(1)) / 1000.0 * Integer.parseInt(target.group(1)) / 100.0;
        assertThat(burst).as("each burst's CPU on three replicas against their target (derived from measured costs, not measured)")
                .isGreaterThan(threeAtTarget);
        assertThat(gap).as("each gap's CPU on three replicas against their target: the floor holds between the bursts").isLessThan(threeAtTarget);
    }

    @Test
    @DisplayName("the burst judge reads a burst above the target before it judges, then holds the floor and refuses any sign-in answered 429;"
            + " the probe's red is that last assertion")
    void theBurstJudgeHasItsPositiveControlFirst() {
        String preflight = function(load, "load_preflight");
        assertThat(preflight).as("the burst profile only with the autoscaler free, its probe only with it")
                .contains("LOAD_BURSTS=$(load_profile_value bursts)").contains("[ \"$LOAD_BURSTS\" != true ] || [ \"$LOAD_AUTOSCALE\" = true ]")
                .contains("[ \"$PROBE\" != abuse-below-need ] || [ \"$LOAD_BURSTS\" = true ]");
        String judge = function(load, "load_burst_judge");
        assertThat(judge).as("read every 10 s; each burst found from the profile's own stages, above its first")
                .contains("(LOAD_ENDED - LOAD_STARTED) / 10 * 9 / 10").contains("load_stages | awk -v t0=\"$LOAD_STARTED\" 'NR == 1 {base = $2} $2 + 0 > base + 0");
        int control = judge.indexOf("[ \"$above\" -ge 1 ] || cannot");
        int floor = judge.indexOf("[ \"$most\" -le \"$LOAD_FLOOR\" ] && [ \"$desired\" -le \"$LOAD_FLOOR\" ]");
        int doors = judge.indexOf("[ \"$refused\" = 0 ] || fail");
        int probe = judge.indexOf("[ \"$PROBE\" != abuse-below-need ] || fail");
        assertThat(control).as("the positive control: a reading above the target inside a burst or its tail, or the run could not measure").isPositive();
        assertThat(floor).as("then the floor: the count asked and the autoscaler's desired count").isGreaterThan(control);
        assertThat(doors).as("then the doors: no sign-in refused").isGreaterThan(floor);
        assertThat(probe).as("the probe fails the run if the doors' assertion held").isGreaterThan(doors);
        assertThat(judge).contains("$1 == \"signin\" && $2 == 429 {n += $3}").contains("$1 >= from[i] && $1 <= to[i] + tail")
                .doesNotContain("scale deployment").doesNotContain("patch hpa");
        String scope = function(load, "load_scope");
        assertThat(scope).as("the burst profile's judge in place of the autoscale profile's")
                .contains("if [ \"$LOAD_BURSTS\" = true ]; then\n        step load-bursts\n        load_burst_judge\n    elif [ \"$LOAD_AUTOSCALE\" = true ]; then");
        assertThat(scope.indexOf("load_burst_judge")).as("judged after the census and before the budget")
                .isGreaterThan(scope.indexOf("load_census")).isLessThan(scope.indexOf("load_budget"));
        assertThat(function(load, "load_autoscale_start")).as("the autoscaler read from the load's start in any autoscaled run, the burst's included")
                .contains("[ \"$LOAD_AUTOSCALE\" = true ] || return 0").contains("load_autoscale_sampler &");
    }

    @Test
    @DisplayName("the probe sets the run's own sign-in limit to the lockout's threshold, below one person's need, after the setup that signs in"
            + " every customer from one address and before the load, and never an overlay")
    void theProbeLowersTheRunsOwnLimitBelowOnePerson() {
        String plant = function(load, "load_plant_abuse_below_need");
        assertThat(plant).as("the code's threshold, set on the run's own Deployment, rolled, and read back")
                .contains("\"$ROOT/identity/src/main/java/com/finapp/identity/LockoutPolicy.java\"")
                .contains("kube -n finapp set env deployment/finapp-app \"FINAPP_ABUSE_SIGN_IN_LIMIT=$threshold\"")
                .contains("kube -n finapp rollout status deployment/finapp-app --timeout=\"${ROLLOUT_TIMEOUT}s\"")
                .contains("env[?(@.name==\"FINAPP_ABUSE_SIGN_IN_LIMIT\")].value").contains("[ \"$set_to\" = \"$threshold\" ] || cannot");
        // What the sed reads is the code's own threshold, in the form it reads.
        Matcher policy = Pattern.compile("return new LockoutPolicy\\(([0-9]+), ").matcher(read("identity/src/main/java/com/finapp/identity/LockoutPolicy.java"));
        assertThat(policy.find()).isTrue();
        assertThat(Integer.parseInt(policy.group(1))).as("the threshold the probe reads is the lockout's").isEqualTo(LockoutPolicy.current().threshold());
        String run = function(load, "load_run");
        int planted = run.indexOf("[ \"$PROBE\" != abuse-below-need ] || load_plant_abuse_below_need\n");
        assertThat(planted).as("after the setup (load_scope runs load_setup first), before the fleet is settled at its floor and the load is timed")
                .isPositive().isLessThan(run.indexOf("    load_autoscale_start\n")).isLessThan(run.indexOf("LOAD_STARTED=$(date +%s)"));
        String scope = function(load, "load_scope");
        assertThat(scope.indexOf("load_setup")).isPositive().isLessThan(scope.indexOf("load_run"));
        assertThat(harness).as("never at the render, where the setup's sign-ins from one address would meet it").doesNotContain("FINAPP_ABUSE");
        assertThat(load.split("FINAPP_ABUSE_SIGN_IN_LIMIT=", -1)).as("set in one place, the probe's").hasSize(2);
    }

    @Test
    @DisplayName("the background profile holds the engineering workload at one count in three stages, and replays the run's own day once, at the"
            + " second stage, through the audited operator door")
    void theBackgroundIsTheRunsOwnDayReplayedInTheSecondStage() {
        String profile = read(BACKGROUND);
        // E30's step 3, the stress profile's third 5-minute step.
        String step3 = read("infra/scripts/kind-rehearsal/load/stress.json").split("\\[300, ")[3].split("]")[0];
        assertThat(profile).as("E30's step 3 in three stages, ten, fifteen and ten minutes")
                .contains("  \"stages\": [[600, " + step3 + "], [900, " + step3 + "], [600, " + step3 + "]],")
                .contains("  \"background\": true,").doesNotContain("\"autoscale\"").doesNotContain("\"stress\"");
        assertThat(function(load, "load_preflight")).as("compared at one count")
                .contains("[ \"$LOAD_AUTOSCALE\" != true ] && [ -z \"$PIPELINE_SCALE\" ]").contains("[ \"$(load_stages | grep -c .)\" = 3 ] || cannot");
        assertThat(function(load, "load_run")).contains("[ \"$LOAD_BACKGROUND\" != true ] || load_background_tick \"$elapsed\"");
        String tick = function(load, "load_background_tick");
        assertThat(tick).as("once, at the first stage's end").contains("[ -z \"$LOAD_REPLAY_AT\" ] || return 0")
                .contains("[ \"$1\" -ge \"$(load_stages | awk 'NR == 1 {print $1}')\" ] || return 0");
        assertThat(tick).as("the run's own published events of every handled type, in windows of at most 900, through the operator's door")
                .contains("AND coalesce(e.first_published_at, e.published_at) >= to_timestamp(:from)")
                .contains("(VALUES :handlers) AS h (consumer, event_type)").contains("/ 900 AS chunk")
                .contains("-v \"from=$LOAD_FROM\"").contains("answer=$(client replay \"$type\" \"$from\" \"$to\"");
    }

    @Test
    @DisplayName("the role split's reading needs its background to have run, then writes each stage's latency and resources by column name,"
            + " and judges none of the numbers")
    void theRoleSplitIsReadAndNeverJudged() {
        String judge = function(load, "load_background_judge");
        assertThat(judge.indexOf("|| cannot \"E77: no event of a handled type was replayed")).as("the positive control first").isPositive()
                .isLessThan(judge.indexOf("printf 'stage\\tmeasure\\tvalue\\n' > \"$LOAD_ART/role-split.tsv\""));
        assertThat(judge).contains("[ \"$short\" = 0 ] || fail").contains("drained 900 || fail").contains("lag_settled background || fail")
                .contains("FNR == 1 {for (i = 1; i <= NF; i++) col[FILENAME, $i] = i; next}")
                .contains("$col[lat, \"op\"] \"_p99_ms\\t\" $col[lat, \"p99_ms\"]");
        assertThat(judge.split("fail \"", -1)).as("four failures, none of them on a number the reading records: no replay, a short window, the"
                + " rows not published, a group not settled").hasSize(5);
        Matcher resources = Pattern.compile("LOAD_ROLE_SPLIT_RESOURCES=\"([a-z_ ]+)\"").matcher(load);
        assertThat(resources.find()).isTrue();
        String writer = function(load, "load_resources") + read("infra/scripts/kind-rehearsal/load/resources.awk");
        for (String column : resources.group(1).split(" ")) {
            String signal = column.replaceAll("_(per_s|max)$", "");
            assertThat(signal.startsWith("fleet_") ? load.contains("\"" + signal + "|") : writer.contains(signal.replaceAll("_(app|postgres)_", "_\" p[2] \"_")))
                    .as("%s is a column the run's resources table writes", column).isTrue();
        }
        assertThat(read("infra/scripts/kind-rehearsal/load/stages.awk")).as("the latency columns the reading takes").contains("p50_ms").contains("p99_ms");
        String scope = function(load, "load_scope");
        assertThat(scope.indexOf("load_background_judge")).as("read after the census").isGreaterThan(scope.indexOf("load_census"));
    }
}
