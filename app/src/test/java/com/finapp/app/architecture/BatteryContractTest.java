package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.RoleConflicts;
import com.finapp.identity.RoleName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the end-to-end battery holds about itself ({@code P17-TST-001}; {@code PHASE_GATES.md} section 5 Phase 17, criteria 2-4;
 * {@code OPERATIONS_RUNBOOK.md} section 11.69): the battery scope of the kind rehearsal, its seven scenarios each with its subject, its
 * people, its console spec, its journal's judgement and its reading of the records, and the census with its own money in it.
 *
 * <p>It has not run on a cluster: the owner postponed the end-to-end runs (2026-11-15) and the standing rule postpones cluster runs; the
 * console's seven specs are finapp-admin-panel's, written when the run can happen. What a hermetic test holds is the text that makes the
 * run a verdict: nothing judged from anything but the run's records, no scenario passed without its report, its journal and its reading,
 * no door a scenario names off the operator edge, and no secret where a log or a commit can take it. {@link BatteryProxyTest} holds the
 * tools themselves.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the end-to-end battery's seven scenarios are wired, judged from the run's records, and keep their secrets (P17-TST-001)")
class BatteryContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String BATTERY = "infra/scripts/kind-rehearsal/battery.sh";
    private static final String SEED = "infra/scripts/e2e-seed.sh";
    private static final String READINGS = "infra/scripts/kind-rehearsal/battery/";
    private static final String OPERATOR_ROUTES = "deploy/edge/base/operator-routes.yaml";
    private static final String THRESHOLD = "payments/src/main/resources/db/migration/payments/V043__refund_four_eyes_above_a_threshold.sql";

    /** Each scenario: its journal rule, the e2e-seed scenario that makes its subject (or none), and its reading (or none). */
    private record Scenario(String rule, String seed, String reading) {}

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("s1", new Scenario("answered|POST|^/v1/ledger/adjustments/[^/]+/approval$|403", null, "s1-unauthorised.sql"));
        SCENARIOS.put("s2", new Scenario("revoked|ANY|^/v1/operator/events/.+$", null, "s2-revoked.sql"));
        SCENARIOS.put("s3", new Scenario("conflict|POST|^/v1/operator/risk/cases/[^/]+/actions$", "investigated-case", "s3-conflict.sql"));
        SCENARIOS.put("s4", new Scenario("drop|POST|^/v1/operator/refund-proposals/[^/]+/approval$", "refundable-payment", "s4-refund.sql"));
        SCENARIOS.put("s5", new Scenario("stale|POST|^/v1/operator/reconciliation/resolutions/[^/]+/approval$", "fee-break", "s5-stale.sql"));
        SCENARIOS.put("s6", new Scenario("duplicate|POST|^/v1/operator/events/discards/[^/]+/approval$", "parked-record", "s6-duplicate.sql"));
        SCENARIOS.put("s7", new Scenario("outage", null, null));
    }

    private static Path root;
    private static String harness;
    private static String battery;
    private static String seed;

    @BeforeAll
    static void readTheBattery() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        battery = read(BATTERY);
        seed = read(SEED);
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

    /** The branch of battery_scenario's reading case for one scenario: from its label to the next scenario's. */
    private static String readingBranch(String id) {
        String scenario = function(battery, "battery_scenario");
        int readings = scenario.indexOf("    battery_rule \"$bsc_id\"");
        assertThat(readings).as("the readings follow the journal's judgement").isPositive();
        Matcher start = Pattern.compile("(?m)^        " + id + "\\)").matcher(scenario);
        assertThat(start.find(readings)).as("battery_scenario reads %s", id).isTrue();
        Matcher next = Pattern.compile("(?m)^(        s[1-7]\\)|    esac)").matcher(scenario);
        assertThat(next.find(start.end())).isTrue();
        return scenario.substring(start.start(), next.start());
    }

    // ------------------------------------------------------------------------------------------- the scope

    @Test
    @DisplayName("battery is a scope of the kind rehearsal: release N alone, at three, its functions sourced, resolved before anything is"
            + " built, dispatched after the common prefix, and its fleet started again on every way out")
    void theScopeIsWired() {
        assertThat(harness).contains("[ \"$SCOPE\" = battery ] || case $SCOPE in release | rotations | signals | incidents) ;;")
                .contains("[ \"$SCOPE\" != battery ] || N_ONLY=1").contains(". \"$DIR/battery.sh\"");
        assertThat(harness.indexOf("\nbattery_resolve\n")).as("resolved before the build").isPositive()
                .isLessThan(harness.indexOf("\nstep build-images\n")).isGreaterThan(harness.indexOf("\ndpath() {\n"));
        assertThat(function(harness, "fleet_resolve")).as("no exception for the battery: it runs at three, as every scope written for three")
                .doesNotContain("battery");
        String finish = function(harness, "finish");
        assertThat(finish.indexOf("    battery_clear\n")).as("the fleet started again before the cluster may go").isPositive()
                .isLessThan(finish.indexOf("delete cluster"));
        assertThat(function(battery, "battery_clear")).contains("[ -n \"$BATTERY_FLEET_STOPPED\" ] || return 0")
                .contains("scale deployment/finapp-app --replicas=\"$FLEET_REPLICAS\"");
        int dispatch = harness.indexOf("if [ \"$SCOPE\" = battery ]; then\n    battery_scope\n    step end\n    finish\nfi");
        assertThat(dispatch).as("dispatched after the common prefix, never into the release's walk").isPositive()
                .isGreaterThan(harness.indexOf("\nstep console\n")).isGreaterThan(harness.indexOf("\nstep telemetry\n"))
                .isLessThan(harness.indexOf("\nstep hold\n"));
    }

    @Test
    @DisplayName("the console's specs come from the checkout its image is built from - CONSOLE_COMMIT, unchanged, installed - and fewer than"
            + " seven scenarios, or another probe, are refused but in the development mode")
    void theConsoleIsTheImagesCommit() {
        String resolve = function(battery, "battery_resolve");
        assertThat(resolve).contains("[ \"$CONSOLE_FROM\" = repository ]").contains("rev-parse HEAD 2>/dev/null)\" = \"$CONSOLE_COMMIT\" ]")
                .contains("status --porcelain --untracked-files=no").contains("node_modules/@playwright/test")
                .contains("grep -Eq '^[0-9a-f]{40}$'").contains("refused under CI").contains("[ \"$KEEP\" = 1 ]")
                .contains("case $PROBE in '' | battery-no-drop) ;;").contains("is the battery scope's");
        assertThat(battery).contains("BATTERY_ALL=\"s1 s2 s3 s4 s5 s6 s7\"");
        assertThat(function(battery, "battery_scope")).contains("for bs_id in $BATTERY_SCENARIOS; do");
    }

    // ------------------------------------------------------------------------------------------- the seven

    @Test
    @DisplayName("each of the seven is judged by its rule - answered, revoked, conflict, drop, stale, duplicate, outage - and every journal"
            + " for the session tokens")
    void everyScenarioHasItsRule() {
        for (Map.Entry<String, Scenario> scenario : SCENARIOS.entrySet()) {
            String variable = "BATTERY_" + scenario.getKey().toUpperCase() + "_RULE='" + scenario.getValue().rule() + "'";
            assertThat(battery).as(scenario.getKey()).contains(variable);
        }
        String rule = function(battery, "battery_rule");
        assertThat(rule).contains("battery_judge \"$1: the journal\" journal outage")
                .contains("battery_judge \"$1: the journal\" journal \"$br_kind\" \"$br_journal\" \"$br_method\" \"$br_path\" $br_status")
                .contains("journal tokens \"$br_journal\"");
    }

    @Test
    @DisplayName("each scenario is the console's spec through its own proxy, judged by its report, its journal and its reading, with the"
            + " fleet read before and after it")
    void everyScenarioIsJudgedFromItsRecords() {
        String scenario = function(battery, "battery_scenario");
        List<String> order = List.of("ready_fleet 900 || fail", "battery_fleet > \"$BATTERY_DIR/$bsc_id/fleet-before.txt\"",
                "since=$(psql_query", "battery_proxy", "battery_spec", "battery_proxy_stop \"$bsc_id\"", "battery_report \"$bsc_id\"",
                "battery_rule \"$bsc_id\"", "battery_reading", "battery_fleet > \"$BATTERY_DIR/$bsc_id/fleet-after.txt\"");
        int at = -1;
        for (String step : order) {
            int next = scenario.indexOf(step, at + 1);
            assertThat(next).as("%s, in its order", step).isGreaterThan(at);
            at = next;
        }
        assertThat(scenario).contains("battery_spec \"$bsc_id\" || true").as("the spec's status is not the verdict: the report is")
                .contains("pass \"$bsc_id: at $FLEET_REPLICAS replicas throughout - the same pods, none restarted\"")
                .contains("fail \"$bsc_id: the fleet changed during the scenario");
        assertThat(function(battery, "battery_report")).contains("[ -s \"$BATTERY_DIR/$1/report.json\" ] || fail")
                .contains("report \"$(dpath \"$BATTERY_DIR/$1/report.json\")\" \"@battery-$1\"")
                .contains("BATTERY_CSP_TOTAL=$((BATTERY_CSP_TOTAL + brp_csp))");
        assertThat(function(battery, "battery_judge")).contains("&& pass").contains("|| fail");
        assertThat(function(battery, "battery_reading")).contains("grep -c '^SELECT CASE'").contains("! grep -q '^FAIL|'");
        assertThat(function(battery, "battery_spec")).contains("npx playwright test --project=kind --grep \"@battery-$bs_id\" --reporter=json")
                .contains("CONSOLE_BASE_URL=\"$BATTERY_OPERATOR_ORIGIN/console/\"").contains("PLAYWRIGHT_JSON_OUTPUT_NAME=")
                .contains("BATTERY_SEED=").contains("BATTERY_PEOPLE=").contains("BATTERY_CONTROL=").contains("BATTERY_EDGE_CA=")
                .contains("BATTERY_HOST_RULES=\"MAP operator.finapp.test 127.0.0.1\"");
    }

    @Test
    @DisplayName("each scenario's subject is its e2e-seed scenario, made just before its spec; s1, s2 and s7 seed nothing")
    void everyScenarioSeedsItsSubject() {
        String scenario = function(battery, "battery_scenario");
        for (Map.Entry<String, Scenario> entry : SCENARIOS.entrySet()) {
            String seeded = entry.getValue().seed();
            if (seeded == null) {
                assertThat(scenario).as(entry.getKey()).containsPattern("(?m)^        [s0-9 |]*\\b" + entry.getKey() + "\\b[s0-9 |]*\\) : >");
                continue;
            }
            assertThat(scenario).as(entry.getKey()).contains("        " + entry.getKey() + ") battery_seed " + entry.getKey() + " " + seeded + " ;;");
            assertThat(seed).as("e2e-seed.sh makes %s", seeded).containsPattern("(?m)^        " + Pattern.quote(seeded) + "\\) seed_");
        }
    }

    @Test
    @DisplayName("each reading is test-only, in the census's form, reads and never writes, and is handed every variable it names")
    void everyReadingIsHandedItsVariables() {
        Set<String> files = new TreeSet<>();
        for (Map.Entry<String, Scenario> entry : SCENARIOS.entrySet()) {
            String file = entry.getValue().reading();
            if (file == null) {
                assertThat(readingBranch(entry.getKey())).as("%s is judged by its journal and the fleet", entry.getKey()).contains(": ;;");
                continue;
            }
            files.add(file);
            String text = read(READINGS + file);
            assertThat(text).as(file).startsWith("-- TEST-ONLY (P17-TST-001). Scenario " + entry.getKey().substring(1) + "'s reading");
            String sql = text.replaceAll("(?m)--.*$", "");
            long statements = sql.replaceAll("'[^']*'", "''").chars().filter(c -> c == ';').count();
            assertThat(statements).as("%s: one verdict per statement", file).isEqualTo(sql.lines().filter(l -> l.startsWith("SELECT CASE")).count());
            assertThat(sql).as("%s reads and never writes", file).doesNotContainPattern("(?i)\\b(INSERT|UPDATE|DELETE|TRUNCATE|ALTER|DROP|GRANT|CREATE)\\b")
                    .as("%s takes only the quoted form", file).doesNotContainPattern("[^:]:[a-z_]+\\b(?!')");
            Set<String> named = new TreeSet<>();
            Matcher variable = Pattern.compile(":'([a-z_]+)'").matcher(sql);
            while (variable.find()) {
                named.add(variable.group(1));
            }
            String branch = readingBranch(entry.getKey());
            assertThat(branch).as(entry.getKey()).contains("battery_reading " + entry.getKey() + " " + file);
            for (String name : named) {
                assertThat(branch).as("%s hands %s :'%s'", entry.getKey(), file, name).contains("-v \"" + name + "=");
            }
        }
        try (var listed = Files.list(root.resolve(READINGS))) {
            assertThat(listed.map(f -> f.getFileName().toString()).toList()).containsExactlyInAnyOrderElementsOf(files);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("scenario 4's proxy alone drops, on the door its rule judges; the probe battery-no-drop runs it without, which its journal"
            + " must refuse")
    void onlyScenarioFourDrops() {
        String scenario = function(battery, "battery_scenario");
        assertThat(scenario).containsOnlyOnce(" drop POST ").contains("if [ \"$bsc_id\" = s4 ] && [ \"$PROBE\" != battery-no-drop ]; then\n"
                + "        battery_proxy s4 drop POST '^/v1/operator/refund-proposals/[^/]+/approval$'")
                .contains("PROBE battery-no-drop: s4's proxy runs without its drop");
        assertThat(battery).containsOnlyOnce("battery_proxy s4 drop");
    }

    @Test
    @DisplayName("every door the seven name is on the operator edge's generated routes, so a scenario never meets the edge's 404 instead"
            + " of the platform's answer")
    void everyDoorIsOnTheOperatorEdge() {
        String routes = read(OPERATOR_ROUTES);
        // The edge's spelling of one path segment, as EdgeAllowListTest generates it.
        String segment = "[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*";
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Scenario> entry : SCENARIOS.entrySet()) {
            String[] rule = entry.getValue().rule().split("\\|");
            if (rule.length < 3 || "ANY".equals(rule[1])) {
                continue;
            }
            String path = rule[2].replace("[^/]+", segment);
            String served = "(Method(`" + rule[1] + "`) && PathRegexp(`" + path + "`))";
            if (!routes.contains(served)) {
                missing.add(entry.getKey() + ": " + served);
            }
        }
        // Scenario 2's next act is the dead letters' read; scenario 7's, the page and a read; the console's own page on the operator host.
        for (String served : List.of("(Method(`GET`) && Path(`/v1/operator/events/abandoned`))", "(Method(`GET`) && PathPrefix(`/console/`))")) {
            if (!routes.contains(served)) {
                missing.add(served);
            }
        }
        assertThat(missing).as("the doors the battery's scenarios use, on " + OPERATOR_ROUTES).isEmpty();
    }

    // ------------------------------------------------------------------------------------------- the people and the money

    @Test
    @DisplayName("the battery's people are made through the doors, their credentials only in a new owner-only file outside the checkout,"
            + " their roles the scenarios' and no pair the segregation-of-duties table refuses")
    void thePeopleKeepTheirSecrets() {
        Matcher people = Pattern.compile("(?m)^BATTERY_PEOPLE=\"([^\"]+)\"$").matcher(seed);
        assertThat(people.find()).isTrue();
        Map<String, List<RoleName>> roles = new LinkedHashMap<>();
        for (String entry : people.group(1).split(" ")) {
            String[] who = entry.split(":");
            roles.put(who[0], List.of(who[1].split(",")).stream().map(RoleName::valueOf).toList());
        }
        assertThat(roles).containsOnlyKeys("viewer", "revoked", "analysta", "analystb", "refunder", "controller", "reconcilera",
                "reconcilerb", "eventsa", "eventsb");
        assertThat(roles.get("viewer")).containsExactly(RoleName.OPERATIONS_VIEWER);
        assertThat(roles.get("revoked")).containsExactly(RoleName.EVENT_OPERATOR, RoleName.OPERATIONS_VIEWER);
        assertThat(roles.get("refunder")).containsExactly(RoleName.LEDGER_OPERATOR);
        assertThat(roles.get("controller")).containsExactly(RoleName.FINANCIAL_CONTROLLER);
        for (Map.Entry<String, List<RoleName>> held : roles.entrySet()) {
            for (RoleConflicts.Pair pair : RoleConflicts.PAIRS) {
                assertThat(held.getValue().containsAll(List.of(pair.first(), pair.second()))).as("%s holds %s", held.getKey(), pair).isFalse();
            }
        }
        String make = function(seed, "seed_battery_people");
        assertThat(make).contains("( umask 077; : > \"$PEOPLE_PART\" )").contains("seed_http grant admin \"$role\" \"$id\"")
                .contains("mv \"$PEOPLE_PART\" \"$E2E_SEED_PEOPLE\"").contains("[ ! -e \"$E2E_SEED_PEOPLE\" ] ||");
        assertThat(make.lines().filter(line -> line.strip().startsWith("printf")).toList())
                .as("stdout carries ids alone").isNotEmpty().allMatch(line -> line.contains("_ID="));
        assertThat(function(seed, "hand_over")).contains("} >> \"$PEOPLE_PART\"");
        assertThat(seed).contains("refuse \"E2E_SEED_PEOPLE is inside this checkout").contains("the seed writes a new file, never over one")
                .contains("name the file absolutely");
    }

    @Test
    @DisplayName("scenario 4's payment is kind's alone and above the refund's four-eyes threshold in EUR, and the census counts its top-up"
            + " and takes its refund back out")
    void theRefundIsFourEyesAndCounted() {
        Matcher amount = Pattern.compile("(?m)^REFUNDABLE_AMOUNT=([0-9]+)\\.([0-9]{2})$").matcher(seed);
        assertThat(amount.find()).isTrue();
        long minor = Long.parseLong(amount.group(1) + amount.group(2));
        Matcher threshold = Pattern.compile("\\(1, 'EUR', 2, ([0-9]+),").matcher(read(THRESHOLD));
        assertThat(threshold.find()).isTrue();
        assertThat(minor).as("above the threshold, so the refund is a proposal and its approval the dropped request")
                .isGreaterThan(Long.parseLong(threshold.group(1)));
        assertThat(seed).contains("[ \"$TARGET\" = kind ] || refuse \"refundable-payment needs a card PSP");
        assertThat(function(seed, "seed_refundable_payment")).contains("seed_http top-up refundee \"$REFUNDABLE_AMOUNT\"");
        String census = function(harness, "census");
        assertThat(census).contains("+ ${BATTERY_TOPUPS:-0} + ${EXPERIMENTS_TOPUPS:-0} ))")
                .contains("+ ${BATTERY_WALLET_MINOR:-0} + ${EXPERIMENTS_TOPUP_MINOR:-0} ))");
        String s4 = readingBranch("s4");
        int read = s4.indexOf("battery_reading s4 s4-refund.sql");
        assertThat(s4.indexOf("battery_refund_settled \"$bsc_payment\"")).as("read once the provider answered").isLessThan(read).isPositive();
        assertThat(s4.indexOf("BATTERY_TOPUPS=$((BATTERY_TOPUPS + 1))")).isGreaterThan(read);
        assertThat(s4.indexOf("BATTERY_WALLET_MINOR=$((BATTERY_WALLET_MINOR - bsc_minor))"))
                .as("the refund taken out only after the reading held it COMPLETED")
                .isGreaterThan(s4.indexOf("BATTERY_WALLET_MINOR=$((BATTERY_WALLET_MINOR + bsc_minor))")).isGreaterThan(read);
        assertThat(function(battery, "battery_scope").indexOf("census battery")).isGreaterThan(function(battery, "battery_scope").indexOf("battery_spread"));
    }

    // ------------------------------------------------------------------------------------------- the outage, the criteria, the secrets

    @Test
    @DisplayName("scenario 7: the spec asks, the fleet goes to zero and comes back to the run's count, and a new fleet replaces the old")
    void theOutageIsAHandshake() {
        String outage = function(battery, "battery_outage");
        assertThat(outage).contains("[ -f \"$bo_control/stop\" ]").contains("scale deployment/finapp-app --replicas=0")
                .contains("> \"$bo_control/stopped\"").contains("[ -f \"$bo_control/start\" ]")
                .contains("scale deployment/finapp-app --replicas=\"$FLEET_REPLICAS\"").contains("ready_fleet 900 ||")
                .contains("> \"$bo_control/started\"").contains("the spec never asked for the backend away")
                .contains("the spec never asked for the backend back").contains("BATTERY_FLEET_STOPPED=1");
        String scenario = function(battery, "battery_scenario");
        assertThat(scenario).contains("battery_spec s7 &").contains("BACKGROUND=\"$BACKGROUND $bsc_pid\"").contains("battery_outage \"$bsc_pid\"")
                .contains("pass \"s7: a new fleet of $FLEET_REPLICAS replaced the one scaled away\"");
    }

    @Test
    @DisplayName("criterion 3 is the sum of the reports' violation counts, zero; criterion 4 the reports' and the journals'; the replicas"
            + " that served the battery are counted from Prometheus, at least the run's count")
    void theCriteriaAreReadFromTheRun() {
        String end = function(battery, "battery_scope");
        assertThat(end).contains("[ \"$BATTERY_CSP_TOTAL\" = 0 ]").contains("criterion 3:").contains("criterion 4:");
        assertThat(function(battery, "battery_spread")).contains("http_server_requests_seconds_count{job=\\\"finapp\\\",uri=~\\\"/v1/operator/.+\\\"}")
                .contains("[ \"$bsp_served\" -ge \"$FLEET_REPLICAS\" ]");
    }

    @Test
    @DisplayName("the proxy's key and password stay in the run: the edge's key into WORK and removed once the keystore is made, the password"
            + " exported only into the subshells that need it, the proxy on 127.0.0.1 alone, and its plain mode never the harness's")
    void theSecretsStayInTheRun() {
        assertThat(battery.split("export BATTERY_PROXY_KEYSTORE_PASSWORD", -1)).as("three exports, each in a subshell").hasSize(4);
        assertThat(battery).contains("    (\n        [ \"$1\" != tls-check ] || export BATTERY_PROXY_KEYSTORE_PASSWORD\n")
                .contains("    (\n        export BATTERY_PROXY_KEYSTORE_PASSWORD\n        dock run")
                .contains("    ( export BATTERY_PROXY_KEYSTORE_PASSWORD; openssl pkcs12 -export")
                .doesNotContain("BATTERY_PROXY_PLAIN");
        assertThat(harness).doesNotContain("BATTERY_PROXY_PLAIN");
        String scope = function(battery, "battery_scope");
        assertThat(scope).contains("BATTERY_WORK=\"$WORK/battery\"").contains("( umask 077; kube -n finapp-edge get secret finapp-edge-operator-tls")
                .contains("rm -f \"$BATTERY_WORK/tls.key\"").contains("E2E_SEED_PEOPLE=\"$BATTERY_WORK/people.properties\"");
        assertThat(scope.indexOf("rm -f \"$BATTERY_WORK/tls.key\"")).isGreaterThan(scope.indexOf("openssl pkcs12 -export"));
        assertThat(function(battery, "battery_proxy")).contains("-p \"127.0.0.1:$BATTERY_PORT:8443\"").contains("-e BATTERY_PROXY_KEYSTORE_PASSWORD")
                .doesNotContain("-e BATTERY_PROXY_KEYSTORE_PASSWORD=");
        assertThat(function(battery, "battery_tool")).contains("[ \"$1\" != tls-check ] || export BATTERY_PROXY_KEYSTORE_PASSWORD");
    }
}
