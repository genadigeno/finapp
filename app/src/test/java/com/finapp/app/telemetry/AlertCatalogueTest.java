package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every alert reaches a route and a runbook, and the objectives and correctness signals are what {@code docs/architecture/SLOS.md} says
 * (`P15-TSK-025`; ADR-0124 decisions 3, 4 and 5). Each test here reads <strong>every</strong> file in
 * {@code infra/prometheus/rules}, not a list of them, so a new rule file is covered the day it arrives.
 *
 * <p><strong>Whether a rule fires is {@code promtool}'s</strong>: {@code infra/prometheus/tests/*.test.yml}, run by
 * {@code infra/scripts/alert-rules-check.sh} and CI's {@code alerts} job. This suite holds what the build can hold without a
 * Prometheus: the shape, the links and the routing.
 */
@DisplayName("the alert catalogue: every alert routed, owned, linked to a runbook section that names it; the objectives' pairs and the correctness register as documented (P15-TSK-025)")
class AlertCatalogueTest {

    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final String SLOS = "docs/architecture/SLOS.md";
    private static final String COMPOSE_ALERTMANAGER = "infra/alertmanager/alertmanager.yml";
    private static final String DEPLOYED_ALERTMANAGER = "deploy/observability/alertmanager/alertmanager.yml";
    private static final String DASHBOARD = "infra/grafana/dashboards/finapp-slo.json";
    private static final String SCRIPT = "infra/scripts/alert-rules-check.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String WORKFLOW = ".github/workflows/ci.yml";

    /** The rule files this task wrote whole, and the telemetry stack's own (`P15-TSK-032`): each states its aggregation per rule. */
    private static final Set<String> STATED_FILES = Set.of("slo.yml", "correctness.yml", "flows.yml", "platform.yml", "telemetry.yml");

    /** Where kind's Prometheus loads every rule file from (`P15-TSK-032`): a byte-for-byte copy, kustomize reading no file outside it. */
    private static final String KIND_RULES = "deploy/observability/overlays/kind/rules/";
    private static final String KIND_KUSTOMIZATION = "deploy/observability/overlays/kind/kustomization.yaml";

    /** The rules ConfigMaps' names begin so: the platform's {@code finapp-prometheus-rules} and the domains' {@code -domain} (2026-11-13). */
    private static final String RULES_CONFIG_MAP = "finapp-prometheus-rules";

    private static final Pattern SECTIONS =
            Pattern.compile("OPERATIONS_RUNBOOK\\.md,? sections? ([0-9]+(?:\\.[0-9]+)*(?:(?:, | and )[0-9]+(?:\\.[0-9]+)*)*)");

    /** Keys no rule may label with: each names a person, an account or a figure (ADR-0018, ADR-0072). */
    private static final Pattern UNBOUNDED_KEY = Pattern.compile(".*(amount|customer|party|account|person|email|token|key|reference).*");

    private record Alert(String file, String group, String name, Map<String, Object> rule, Map<String, Object> groupLabels) {
        Map<String, Object> labels() {
            return rule.get("labels") == null ? Map.of() : map(rule.get("labels"));
        }

        Map<String, Object> annotations() {
            return rule.get("annotations") == null ? Map.of() : map(rule.get("annotations"));
        }

        String expr() {
            return String.valueOf(rule.get("expr")).strip();
        }

        String waitsFor() {
            return String.valueOf(rule.get("for"));
        }
    }

    @Test
    @DisplayName("every alert of every rule file cites a runbook section that exists, and one it cites names it")
    void everyAlertNamesARunbookSectionThatExistsAndNamesIt() throws IOException {
        String runbook = RepositoryPaths.read(RUNBOOK);
        List<Alert> alerts = alerts();
        assertThat(alerts).as("not vacuous: the alerts of every file are read").hasSizeGreaterThan(190);
        for (Alert alert : alerts) {
            Matcher cited = SECTIONS.matcher(alert.annotations().toString());
            assertThat(cited.find()).as("%s (%s) cites a runbook section", alert.name(), alert.file()).isTrue();
            List<String> sections = new ArrayList<>();
            do {
                sections.addAll(List.of(cited.group(1).split(", | and ")));
            } while (cited.find());
            boolean named = false;
            for (String section : sections) {
                String body = section(runbook, section);
                assertThat(body).as("%s cites section %s, which exists", alert.name(), section).isNotEmpty();
                named |= Pattern.compile("\\b" + alert.name() + "\\b").matcher(body).find();
            }
            assertThat(named).as("a section %s cites names it (%s)", alert.name(), sections).isTrue();
        }
    }

    /** kubectl's annotations cap, which a client-side apply's copy of the object counts against (`P15-TSK-032`'s review). */
    private static final int ANNOTATIONS_CAP = 262_144;

    /** What is left for the rule files to grow before the build fails, and before an apply would: 32 KiB. */
    private static final int MARGIN = 32_768;

    @Test
    @DisplayName("each of kind's rules ConfigMaps fits a client-side apply's annotation with 32 KiB to spare, measured on its own as kubectl"
            + " writes it: every file it lists JSON-escaped, < > & as six bytes each, plus a kilobyte for the object around them (P15-TSK-032;"
            + " split in two 2026-11-13)")
    void theRulesConfigMapFitsAClientSideApply() throws IOException {
        Path copies = RepositoryPaths.locate(KIND_RULES + "slo.yml").getParent();
        Map<String, List<String>> configMaps = rulesConfigMaps();
        assertThat(configMaps).as("the platform's rules ConfigMap and the domains'").containsKeys(RULES_CONFIG_MAP, RULES_CONFIG_MAP + "-domain");
        int files = 0;
        long all = 0;
        for (Map.Entry<String, List<String>> configMap : configMaps.entrySet()) {
            long serialised = 1_024;
            for (String file : configMap.getValue()) {
                serialised += file.length() + 6 + escapedLength(Files.readString(copies.resolve(file)));
                files++;
            }
            assertThat(serialised).as("%s as kubectl's last-applied annotation holds it: past %s bytes move a file to the other rules ConfigMap,"
                    + " split it again, or apply the overlay server-side, before it reaches the %s-byte cap", configMap.getKey(),
                    ANNOTATIONS_CAP - MARGIN, ANNOTATIONS_CAP).isLessThanOrEqualTo(ANNOTATIONS_CAP - MARGIN).isGreaterThan(1_024);
            all += serialised;
        }
        long copied;
        try (Stream<Path> listed = Files.list(copies)) {
            copied = listed.count();
        }
        assertThat(files).as("the copies were read: every file of the directory measured in a ConfigMap").isEqualTo((int) copied)
                .isGreaterThanOrEqualTo(13);
        assertThat(all).as("the copies were read, whole").isGreaterThan(150_000);
    }

    /**
     * Each rules ConfigMap kind's overlay generates, by name, and the files it lists under {@code rules/} (2026-11-13): every generator whose
     * name begins {@link #RULES_CONFIG_MAP}, and any other generator listing a rule file, so that none can list one unmeasured.
     */
    private static Map<String, List<String>> rulesConfigMaps() {
        Map<String, List<String>> configMaps = new TreeMap<>();
        for (Object node : (List<?>) yaml(KIND_KUSTOMIZATION).get("configMapGenerator")) {
            Map<String, Object> generator = map(node);
            String name = String.valueOf(generator.get("name"));
            List<String> files = generator.get("files") == null ? List.of()
                    : ((List<?>) generator.get("files")).stream().map(String::valueOf).toList();
            if (name.startsWith(RULES_CONFIG_MAP) || files.stream().anyMatch(file -> file.startsWith("rules/"))) {
                assertThat(name).as("a generator listing a rule file is a rules ConfigMap").startsWith(RULES_CONFIG_MAP);
                assertThat(files).as("%s lists rule files, and nothing else", name).isNotEmpty()
                        .allSatisfy(file -> assertThat(file).matches("rules/[a-z0-9-]+\\.yml"));
                assertThat(configMaps.put(name, files.stream().map(file -> file.substring("rules/".length())).toList()))
                        .as("%s is generated once", name).isNull();
            }
        }
        return configMaps;
    }

    /** A string's length as Go's encoding/json writes it, which kubectl uses: HTML-escaped. */
    private static long escapedLength(String text) {
        long length = 0;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (point == '"' || point == '\\' || point == '\n' || point == '\r' || point == '\t') {
                length += 2;
            } else if (point < 0x20 || point == '<' || point == '>' || point == '&' || point == 0x2028 || point == 0x2029) {
                length += 6;
            } else {
                length += new String(Character.toChars(point)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        return length;
    }

    @Test
    @DisplayName("every alert has a severity of page or ticket, a for and an owning module: the rule's label or its group's")
    void everyAlertIsRoutedAndOwned() throws IOException {
        assertThat(alerts()).allSatisfy(alert -> {
            assertThat(alert.labels().get("severity")).as("%s severity", alert.name()).isIn("page", "ticket");
            assertThat(alert.waitsFor()).as("%s for", alert.name()).matches("\\d+[smhd]");
            Object owner = alert.labels().containsKey("owner") ? alert.labels().get("owner") : alert.groupLabels().get("owner");
            assertThat(owner).as("%s owner", alert.name()).isNotNull();
            assertThat(String.valueOf(owner)).as("%s owner", alert.name()).matches("[a-z-]+");
        });
    }

    @Test
    @DisplayName("each rule this task wrote states its aggregation, and the statement is what its expression does")
    void theAggregationIsStatedPerRuleAndMatchesTheExpression() throws IOException {
        Path directory = rulesDirectory();
        int checked = 0;
        for (Alert alert : alerts()) {
            if (!STATED_FILES.contains(alert.file()) && !Set.of("CreditScorecardMissing", "BnplSweepClaimFailing").contains(alert.name())) {
                continue;
            }
            String stated = statedAggregation(Files.readString(directory.resolve(alert.file())), alert.name());
            assertThat(stated).as("%s states its aggregation", alert.name()).isNotBlank();
            String expr = alert.expr();
            if (stated.startsWith("max")) {
                assertThat(expr).as(alert.name()).containsPattern("max(\\(| by)|finapp:slo_");
            } else if (stated.startsWith("sum")) {
                assertThat(expr).as(alert.name()).containsPattern("sum(\\(| by)|finapp:slo_");
            } else if (stated.startsWith("min")) {
                assertThat(expr).as(alert.name()).contains("min by");
            } else if (stated.startsWith("per instance")) {
                assertThat(expr.replace("by (job, instance)", "")).as(alert.name()).doesNotContainPattern("(max|sum|min)( by|\\()");
            } else if (stated.startsWith("count")) {
                // The fleet-blindness rule: a count of instances is the question it asks, never a max (the owner's ruling, 2026-11-09).
                assertThat(expr).as(alert.name()).contains("count(");
            } else {
                assertThat(stated).as(alert.name()).startsWith("none");
                assertThat(expr).as(alert.name()).contains("absent(");
            }
            checked++;
        }
        assertThat(checked).as("not vacuous").isGreaterThan(50);
    }

    @Test
    @DisplayName("every SLI recording rule aggregates across the instances: a sum or max over the fleet, or a window over a rule that already did")
    void everySliRecordingRuleAggregatesAcrossTheInstances() {
        int checked = 0;
        for (Object node : (List<?>) yamlAt(rulesDirectory().resolve("slo-recording.yml")).get("groups")) {
            for (Object rule : (List<?>) map(node).get("rules")) {
                Map<String, Object> entry = map(rule);
                assertThat(String.valueOf(entry.get("expr"))).as("%s", entry.get("record"))
                        .containsPattern("^\\(?\\s*(1 - \\()?\\(?sum(\\(| by)|^max\\(|^avg_over_time\\(finapp:slo_");
                checked++;
            }
        }
        assertThat(checked).as("not vacuous").isEqualTo(42);
    }

    /**
     * Every alert of every rule file fires in a promtool case, and stays silent in another on series it reads, once its own
     * wait has passed (`P15-REM-004` (d), 2026-11-12). Until then this was {@code everyNewAlertHasAPromtoolCase}: it held that
     * some case <em>named</em> each alert of five files, so a case that never fired its alert passed, a silent case taken before
     * the rule's {@code for} on a breaking series passed as "silent", and the seven domain rule files had no case at all.
     *
     * <p>What counts. A <strong>firing</strong> case expects at least one alert, in a test file that loads the alert's rule file:
     * promtool, run by {@code alert-rules-check.sh}, then proves it fires as expected. A <strong>silent</strong> case expects
     * none, is evaluated at or after the rule's {@code for}, and sits in a group whose input series feed at least one series the
     * expression reads (recording rules followed to what they read) - so a not-yet case on a breaking series, a silence over
     * no series at all, and a case in a file that never loads the rule each count for nothing. Whether a group's series are
     * "healthy" beyond that is the case's own comment and its review: promtool proves only that the rule is silent there.
     */
    @Test
    @DisplayName("every alert of every rule file fires in a promtool case and stays silent in another, at or after its for, in a group feeding a"
            + " series it reads: a case that only names it, a silence before its wait or over none of its series, and a case in a file that"
            + " does not load its rules each count for nothing")
    void everyAlertFiresInOneCaseAndStaysSilentOnItsSeriesInAnother() throws IOException {
        List<Alert> alerts = alerts();
        List<PromtoolCase> cases = promtoolCases();
        assertThat(alerts).as("not vacuous: the alerts of every file").hasSizeGreaterThan(210);
        assertThat(cases).as("not vacuous: the cases of every test file").hasSizeGreaterThan(450);
        assertThat(promtoolCaseViolations(alerts, recordedSeries(), cases)).isEmpty();
    }

    @Test
    @DisplayName("the promtool-case rule refuses each planted gap, and passes a pair that holds, a recording rule followed to the series it reads")
    void thePromtoolCaseRuleRefusesItsPlants() {
        Alert alert = new Alert("x.yml", "x", "XBroken", Map.of("expr", "max(finapp_x{kind=\"a\"}) > 0", "for", "5m"), Map.of());
        Set<String> loads = Set.of("x.yml");
        PromtoolCase fires = new PromtoolCase("x.test.yml", loads, "XBroken", true, Duration.ofMinutes(10), Set.of("finapp_x"));
        PromtoolCase silent = new PromtoolCase("x.test.yml", loads, "XBroken", false, Duration.ofMinutes(10), Set.of("finapp_x"));
        Map<String, Set<String>> none = Map.of();

        assertThat(promtoolCaseViolations(List.of(alert), none, List.of(fires, silent))).as("a pair that holds").isEmpty();
        assertThat(promtoolCaseViolations(List.of(alert), none, List.of(silent)))
                .as("a case that names the alert and never fires it").anySatisfy(problem -> assertThat(problem).contains("never fires"));
        assertThat(promtoolCaseViolations(List.of(alert), none,
                List.of(fires, new PromtoolCase("x.test.yml", loads, "XBroken", false, Duration.ofMinutes(4), Set.of("finapp_x")))))
                .as("a silence taken before the rule's for, which a breaking series also shows").isNotEmpty();
        assertThat(promtoolCaseViolations(List.of(alert), none,
                List.of(fires, new PromtoolCase("x.test.yml", loads, "XBroken", false, Duration.ofMinutes(10), Set.of("finapp_y")))))
                .as("a silence over none of the series the rule reads").isNotEmpty();
        assertThat(promtoolCaseViolations(List.of(alert), none, List.of(
                new PromtoolCase("y.test.yml", Set.of("y.yml"), "XBroken", true, Duration.ofMinutes(10), Set.of("finapp_x")), silent)))
                .as("a firing case in a file that does not load the rule").anySatisfy(problem -> assertThat(problem).contains("never fires"));
        assertThat(promtoolCaseViolations(List.of(new Alert("x.yml", "x", "XEmpty", Map.of("expr", "vector(1)", "for", "0s"), Map.of())),
                none, List.of())).as("a rule whose expression reads no series").anySatisfy(problem -> assertThat(problem).contains("no series"));

        Alert recorded = new Alert("x.yml", "x", "XRecorded", Map.of("expr", "finapp:x_ratio:rate5m > 0.1", "for", "1m"), Map.of());
        assertThat(promtoolCaseViolations(List.of(recorded), Map.of("finapp:x_ratio:rate5m", Set.of("finapp_x_total")), List.of(
                new PromtoolCase("x.test.yml", loads, "XRecorded", true, Duration.ofMinutes(10), Set.of("finapp_x_total")),
                new PromtoolCase("x.test.yml", loads, "XRecorded", false, Duration.ofMinutes(10), Set.of("finapp_x_total")))))
                .as("a recording rule is followed to the raw series a test file feeds").isEmpty();
        assertThat(seriesRead("label_replace(max by (job, instance) (finapp_a != finapp_a), \"gauge\", \"finapp_b\", \"\", \"\")"
                + " or sum(increase(finapp_c{outcome=\"x\"}[1h] offset 10m)) / clamp_min(absent(finapp_d), 1)"))
                .as("the series an expression reads: not a label, a string, a function, a window or an offset")
                .containsExactlyInAnyOrder("finapp_a", "finapp_c", "finapp_d");
    }

    @Test
    @DisplayName("each objective the document lists has its four recording windows, a fast pair at 14.4 paging and a slow pair at 6 ticketing, at the documented budget")
    void everyObjectiveHasBothPairs() throws IOException {
        Map<String, String> budgets = documentedBudgets();
        assertThat(budgets).as("the document's objectives").hasSize(10);
        Map<String, Alert> byName = byName();
        Set<String> recorded = recordingRules();
        for (Map.Entry<String, String> objective : budgets.entrySet()) {
            String slo = objective.getKey();
            for (String window : List.of("5m", "30m", "1h", "6h")) {
                assertThat(recorded).as(slo).contains("finapp:slo_" + slo + ":error_ratio_rate" + window);
            }
            String stem = camel(slo);
            Alert fast = byName.get(stem + "BurnFast");
            Alert slow = byName.get(stem + "BurnSlow");
            assertThat(fast).as("%s's fast pair", slo).isNotNull();
            assertThat(slow).as("%s's slow pair", slo).isNotNull();
            String budget = objective.getValue();
            assertThat(fast.expr()).isEqualTo("finapp:slo_" + slo + ":error_ratio_rate1h > (14.4 * " + budget + ") and finapp:slo_" + slo
                    + ":error_ratio_rate5m > (14.4 * " + budget + ")");
            assertThat(slow.expr()).isEqualTo("finapp:slo_" + slo + ":error_ratio_rate6h > (6 * " + budget + ") and finapp:slo_" + slo
                    + ":error_ratio_rate30m > (6 * " + budget + ")");
            assertThat(fast.labels().get("severity")).isEqualTo("page");
            assertThat(slow.labels().get("severity")).isEqualTo("ticket");
            assertThat(fast.labels().get("slo")).isEqualTo(slo);
        }
    }

    @Test
    @DisplayName("every correctness signal in the register pages on its first sample, none has a budget, and no proof alert escapes the register")
    void theCorrectnessSignalsPageOnTheirFirstSample() throws IOException {
        Set<String> register = correctnessRegister();
        Map<String, Alert> byName = byName();
        assertThat(register).as("the register's own five classes are present").contains("LedgerTrialBalanceNonZero", "LedgerProjectionDrift",
                "GlNotEqualLedger", "OutboxDeadLetter", "BnplThreeWayProofFailing");
        for (String name : register) {
            Alert alert = byName.get(name);
            assertThat(alert).as("%s is a rule", name).isNotNull();
            assertThat(alert.labels().get("severity")).as("%s pages", name).isEqualTo("page");
            // The one exception by the owner's ruling of 2026-11-09: the fleet-blindness rule waits five minutes, not nothing.
            assertThat(alert.waitsFor()).as("%s waits for nothing", name).isEqualTo("GaugesStaleAcrossTheFleet".equals(name) ? "5m" : "0s");
            assertThat(alert.file()).as("%s is not budgeted", name).isNotEqualTo("slo.yml");
        }
        Pattern proofLike = Pattern.compile(".*(ProofFailing|Diverged|Unexplained|CensusFailing).*");
        assertThat(byName.keySet().stream().filter(name -> proofLike.matcher(name).matches()).toList())
                .as("a new proof's alert joins the register, or it would wait and be budgeted unnoticed")
                .allSatisfy(name -> assertThat(register).contains(name));
    }

    @Test
    @DisplayName("the trial balance pages on its first non-zero sample, per currency, by max() across the instances")
    void theTrialBalancePagesOnItsFirstNonZeroSample() throws IOException {
        Alert alert = byName().get("LedgerTrialBalanceNonZero");
        assertThat(alert).isNotNull();
        assertThat(alert.expr()).isEqualTo("max by (currency) (finapp_ledger_trial_balance) > 0");
        assertThat(alert.waitsFor()).isEqualTo("0s");
        assertThat(alert.labels().get("severity")).isEqualTo("page");
    }

    @Test
    @DisplayName("Alertmanager routes page and ticket to placeholder receivers that send nothing; compose and deploy/ carry the same bytes; Prometheus sends to it")
    void theRoutingIsBySeverityToAPlaceholderReceiver() throws IOException {
        Map<String, Object> config = yaml(COMPOSE_ALERTMANAGER);
        Map<String, Object> route = map(config.get("route"));
        Map<String, String> bySeverity = new TreeMap<>();
        for (Object node : (List<?>) route.get("routes")) {
            Map<String, Object> child = map(node);
            bySeverity.put(String.valueOf(((List<?>) child.get("matchers")).get(0)), String.valueOf(child.get("receiver")));
        }
        assertThat(bySeverity).containsOnly(Map.entry("severity=\"page\"", "placeholder-page"),
                Map.entry("severity=\"ticket\"", "placeholder-ticket"));
        assertThat(route.get("receiver")).as("an unrouted alert still arrives").isEqualTo("placeholder-ticket");
        for (Object node : (List<?>) config.get("receivers")) {
            assertThat(map(node)).as("a placeholder has no integration: it sends nothing").containsOnlyKeys("name");
        }
        assertThat(RepositoryPaths.read(DEPLOYED_ALERTMANAGER)).isEqualTo(RepositoryPaths.read(COMPOSE_ALERTMANAGER));
        assertThat(RepositoryPaths.read("infra/prometheus/prometheus.yml")).contains("alerting:").contains("alertmanager:9093");
    }

    @Test
    @DisplayName("kind's Prometheus loads every rule file as committed: its copy holds each file byte for byte and nothing else, the overlay"
            + " lists each in exactly one of its rules ConfigMaps, and deploy/'s Alertmanager is generated from the committed routing"
            + " (P15-TSK-032; two ConfigMaps 2026-11-13)")
    void kindLoadsEveryRuleFileAsCommitted() throws IOException {
        Set<String> source = new TreeSet<>();
        try (Stream<Path> files = Files.list(rulesDirectory())) {
            files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".yml")).forEach(source::add);
        }
        Set<String> copied = new TreeSet<>();
        try (Stream<Path> files = Files.list(RepositoryPaths.locate(KIND_RULES + "slo.yml").getParent())) {
            files.map(path -> path.getFileName().toString()).forEach(copied::add);
        }
        assertThat(copied).as("kind's copy is every rule file and only those: copy a new or changed rule file into %s", KIND_RULES)
                .isEqualTo(source);
        for (String file : source) {
            assertThat(RepositoryPaths.read(KIND_RULES + file)).as("%s%s is infra/prometheus/rules/%s byte for byte: copy it", KIND_RULES, file, file)
                    .isEqualTo(RepositoryPaths.read("infra/prometheus/rules/" + file));
        }
        String kustomization = RepositoryPaths.read(KIND_KUSTOMIZATION).replace("\r\n", "\n");
        Map<String, List<String>> configMaps = rulesConfigMaps();
        assertThat(configMaps.keySet()).as("the platform's rules ConfigMap and the domains' (2026-11-13)")
                .contains(RULES_CONFIG_MAP, RULES_CONFIG_MAP + "-domain");
        Map<String, String> generated = new TreeMap<>();
        for (Map.Entry<String, List<String>> configMap : configMaps.entrySet()) {
            for (String file : configMap.getValue()) {
                String also = generated.put(file, configMap.getKey());
                assertThat(also).as("%s is listed once, in one rules ConfigMap: here in %s, and in %s too", file, configMap.getKey(), also).isNull();
            }
        }
        assertThat(generated.keySet()).as("the rules ConfigMaps together list every file: list a new one in one of them").isEqualTo(source);
        assertThat(kustomization).contains("  - name: finapp-prometheus-rules\n").contains("  - name: finapp-prometheus-rules-domain\n")
                .contains("  - ../../alertmanager\n");
        assertThat(RepositoryPaths.read("deploy/observability/alertmanager/kustomization.yaml").replace("\r\n", "\n"))
                .contains("  - name: finapp-alertmanager-config\n    files:\n      - alertmanager.yml\n");
    }

    @Test
    @DisplayName("the check script runs every promtool case and both amtool configurations through images pinned by digest, compose runs those versions, and CI runs the script")
    void theCheckScriptIsPinnedAndRun() throws IOException {
        String script = RepositoryPaths.read(SCRIPT);
        assertThat(script).contains("promtool test rules *.test.yml").contains("promtool check config prometheus.yml")
                .contains("amtool check-config /am/alertmanager.yml /deployed/alertmanager.yml")
                .contains("$PROMETHEUS_REPOSITORY:$PROMETHEUS_VERSION@$PROMETHEUS_DIGEST")
                .contains("$ALERTMANAGER_REPOSITORY:$ALERTMANAGER_VERSION@$ALERTMANAGER_DIGEST");
        String pins = RepositoryPaths.read(PINS);
        String compose = RepositoryPaths.read("compose.yaml");
        assertThat(compose).contains("prom/prometheus:" + pin(pins, "PROMETHEUS_VERSION"))
                .contains("prom/alertmanager:" + pin(pins, "ALERTMANAGER_VERSION"));
        assertThat(pin(pins, "PROMETHEUS_DIGEST")).matches("sha256:[0-9a-f]{64}");
        assertThat(pin(pins, "ALERTMANAGER_DIGEST")).matches("sha256:[0-9a-f]{64}");
        assertThat(RepositoryPaths.read("infra/scripts/check-pinned-images.sh")).contains("\"$PROMETHEUS_DIGEST\"").contains("\"$ALERTMANAGER_DIGEST\"");
        assertThat(RepositoryPaths.read(WORKFLOW)).contains("run: ./infra/scripts/alert-rules-check.sh");
        try (Stream<Path> files = Files.list(testsDirectory())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("the glob is what runs them, so every case file must match it")
                    .allSatisfy(name -> assertThat(name).endsWith(".test.yml"));
        }
    }

    @Test
    @DisplayName("the check script is committed executable, because CI runs it directly (P15-TSK-025's fix round)")
    void theCheckScriptIsCommittedExecutable() throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "ls-files", "-s", SCRIPT)
                .directory(RepositoryPaths.locate("settings.gradle.kts").getParent().toFile())
                .redirectErrorStream(true)
                .start();
        String index = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }

    @Test
    @DisplayName("the SLO dashboard is provisioned read-only and reads only what the rules record, with a row for every objective")
    void theDashboardReadsWhatTheRulesRecord() throws IOException {
        JsonNode dashboard = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD));
        assertThat(dashboard.path("editable").asBoolean(true)).isFalse();
        Set<String> recorded = recordingRules();
        Set<String> read = new TreeSet<>();
        for (JsonNode panel : dashboard.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                Matcher name = Pattern.compile("finapp:[a-z0-9_:]+").matcher(target.path("expr").asString(""));
                while (name.find()) {
                    read.add(name.group());
                }
            }
        }
        assertThat(recorded).as("the panels read series the rules record").containsAll(read);
        for (String slo : documentedBudgets().keySet()) {
            assertThat(read).as("%s has its panels", slo).anySatisfy(name -> assertThat(name).startsWith("finapp:slo_" + slo + ":"));
        }
    }

    @Test
    @DisplayName("no rule groups or labels by anything that names a person, an account or a figure")
    void noUnboundedLabel() throws IOException {
        Pattern grouped = Pattern.compile("\\bby \\(([a-z_, ]+)\\)");
        for (Alert alert : alerts()) {
            assertThat(alert.labels().keySet()).as(alert.name()).noneMatch(key -> UNBOUNDED_KEY.matcher(key).matches());
            Matcher by = grouped.matcher(alert.expr());
            while (by.find()) {
                assertThat(List.of(by.group(1).split(",\\s*"))).as(alert.name()).noneMatch(key -> UNBOUNDED_KEY.matcher(key).matches());
            }
        }
    }

    // ------------------------------------------------------------------------------------ the files

    private static Path rulesDirectory() {
        return RepositoryPaths.locate("infra/prometheus/rules/slo.yml").getParent();
    }

    private static Path testsDirectory() {
        return RepositoryPaths.locate("infra/prometheus/tests/slo.test.yml").getParent();
    }

    private static List<Alert> alerts() throws IOException {
        List<Alert> alerts = new ArrayList<>();
        try (Stream<Path> files = Files.list(rulesDirectory())) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                for (Object node : (List<?>) yamlAt(file).get("groups")) {
                    Map<String, Object> group = map(node);
                    Map<String, Object> labels = group.get("labels") == null ? Map.of() : map(group.get("labels"));
                    for (Object rule : (List<?>) group.get("rules")) {
                        Map<String, Object> entry = map(rule);
                        if (entry.get("alert") != null) {
                            alerts.add(new Alert(file.getFileName().toString(), String.valueOf(group.get("name")),
                                    String.valueOf(entry.get("alert")), entry, labels));
                        }
                    }
                }
            }
        }
        return alerts;
    }

    private static Map<String, Alert> byName() throws IOException {
        Map<String, Alert> named = new LinkedHashMap<>();
        for (Alert alert : alerts()) {
            assertThat(named.put(alert.name(), alert)).as("the alert name %s is unique", alert.name()).isNull();
        }
        return named;
    }

    private static Set<String> recordingRules() throws IOException {
        Set<String> recorded = new TreeSet<>();
        for (Object node : (List<?>) yamlAt(rulesDirectory().resolve("slo-recording.yml")).get("groups")) {
            for (Object rule : (List<?>) map(node).get("rules")) {
                recorded.add(String.valueOf(map(rule).get("record")));
            }
        }
        return recorded;
    }

    // ------------------------------------------------------------------------------------ the promtool cases

    /** One {@code alert_rule_test} entry: its file, the rule files that file loads, the alert, whether it expects one, when, and what its group feeds. */
    record PromtoolCase(String file, Set<String> loads, String alert, boolean fires, Duration at, Set<String> fed) {}

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern LABEL_MATCHERS = Pattern.compile("\\{[^}]*}");
    private static final Pattern RANGE = Pattern.compile("\\[[^\\]]*]");
    private static final Pattern GROUPING = Pattern.compile("\\b(?:by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)");

    /** An identifier that is whole (no longer one around it) and not called: a series or a recorded rule's name, once the above are gone. */
    private static final Pattern SERIES_NAME = Pattern.compile("(?<![A-Za-z0-9_:.])([a-zA-Z_:][a-zA-Z0-9_:]*)(?![a-zA-Z0-9_:])(?!\\s*\\()");
    private static final Set<String> PROMQL_WORDS = Set.of("and", "or", "unless", "bool", "offset", "by", "without", "on", "ignoring",
            "group_left", "group_right", "inf", "nan", "Inf", "NaN");
    private static final Pattern INPUT_SERIES = Pattern.compile("^\\s*([a-zA-Z_:][a-zA-Z0-9_:]*)");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)(ms|s|m|h|d|w|y)");

    /** The series an expression reads: string literals, label matchers, windows and grouping clauses removed, then every name not called. */
    static Set<String> seriesRead(String expr) {
        String text = STRING_LITERAL.matcher(expr).replaceAll("\"\"");
        text = LABEL_MATCHERS.matcher(text).replaceAll(" ");
        text = RANGE.matcher(text).replaceAll(" ");
        text = GROUPING.matcher(text).replaceAll(" ");
        Set<String> names = new TreeSet<>();
        Matcher name = SERIES_NAME.matcher(text);
        while (name.find()) {
            if (!PROMQL_WORDS.contains(name.group(1))) {
                names.add(name.group(1));
            }
        }
        return names;
    }

    static Duration duration(Object text) {
        if (text == null) {
            return Duration.ZERO;
        }
        Duration total = Duration.ZERO;
        Matcher part = DURATION_PART.matcher(String.valueOf(text));
        while (part.find()) {
            long amount = Long.parseLong(part.group(1));
            total = total.plus(switch (part.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(7 * amount);
                default -> Duration.ofDays(365 * amount);
            });
        }
        return total;
    }

    /** The gaps: an alert never fired by a case of a file that loads it, or never shown silent past its wait on series it reads. */
    static List<String> promtoolCaseViolations(List<Alert> alerts, Map<String, Set<String>> recorded, List<PromtoolCase> cases) {
        List<String> problems = new ArrayList<>();
        for (Alert alert : alerts) {
            Set<String> read = followed(seriesRead(alert.expr()), recorded);
            if (read.isEmpty()) {
                problems.add(alert.name() + " (" + alert.file() + ") reads no series, so no silent case can be on its series");
            }
            Duration waits = duration(alert.rule().get("for"));
            List<PromtoolCase> own = cases.stream()
                    .filter(promtool -> promtool.alert().equals(alert.name()) && promtool.loads().contains(alert.file()))
                    .toList();
            if (own.stream().noneMatch(PromtoolCase::fires)) {
                problems.add(alert.name() + " (" + alert.file() + ") never fires in a case of a file that loads it");
            }
            if (own.stream().noneMatch(promtool -> !promtool.fires() && promtool.at().compareTo(waits) >= 0
                    && promtool.fed().stream().anyMatch(read::contains))) {
                problems.add(alert.name() + " (" + alert.file() + ") is never shown silent at or after its for (" + waits
                        + ") in a group feeding a series it reads " + read);
            }
        }
        return problems;
    }

    private static Set<String> followed(Set<String> names, Map<String, Set<String>> recorded) {
        Set<String> all = new TreeSet<>();
        List<String> pending = new ArrayList<>(names);
        while (!pending.isEmpty()) {
            String name = pending.remove(pending.size() - 1);
            if (all.add(name) && recorded.containsKey(name)) {
                pending.addAll(recorded.get(name));
            }
        }
        return all;
    }

    /** Every recording rule of every rule file, and the series its expression reads. */
    private static Map<String, Set<String>> recordedSeries() throws IOException {
        Map<String, Set<String>> recorded = new TreeMap<>();
        try (Stream<Path> files = Files.list(rulesDirectory())) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                for (Object node : (List<?>) yamlAt(file).get("groups")) {
                    for (Object rule : (List<?>) map(node).get("rules")) {
                        Map<String, Object> entry = map(rule);
                        if (entry.get("record") != null) {
                            recorded.put(String.valueOf(entry.get("record")), seriesRead(String.valueOf(entry.get("expr"))));
                        }
                    }
                }
            }
        }
        return recorded;
    }

    private static List<PromtoolCase> promtoolCases() throws IOException {
        List<PromtoolCase> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(testsDirectory())) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".test.yml")).sorted().toList()) {
                Map<String, Object> document = yamlAt(file);
                Set<String> loads = new TreeSet<>();
                for (Object ruleFile : (List<?>) document.get("rule_files")) {
                    loads.add(Path.of(String.valueOf(ruleFile)).getFileName().toString());
                }
                for (Object node : (List<?>) document.get("tests")) {
                    Map<String, Object> test = map(node);
                    Set<String> fed = new TreeSet<>();
                    for (Object series : test.get("input_series") == null ? List.of() : (List<?>) test.get("input_series")) {
                        Matcher name = INPUT_SERIES.matcher(String.valueOf(map(series).get("series")));
                        if (name.find()) {
                            fed.add(name.group(1));
                        }
                    }
                    for (Object entry : test.get("alert_rule_test") == null ? List.of() : (List<?>) test.get("alert_rule_test")) {
                        Map<String, Object> promtool = map(entry);
                        Object expected = promtool.get("exp_alerts");
                        cases.add(new PromtoolCase(file.getFileName().toString(), loads, String.valueOf(promtool.get("alertname")),
                                expected instanceof List<?> list && !list.isEmpty(), duration(promtool.get("eval_time")), fed));
                    }
                }
            }
        }
        return cases;
    }

    private static String statedAggregation(String text, String alert) {
        Matcher matcher = Pattern.compile("(?m)^\\s*- alert: " + alert + "\\s*\\n\\s*# Aggregation: (.*)$").matcher(text.replace("\r\n", "\n"));
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    /** One section, from its heading to the next of its level or above; empty when there is none. */
    private static String section(String runbook, String number) {
        Matcher heading = Pattern.compile("(?m)^(#{2,4}) " + Pattern.quote(number) + "[. ]").matcher(runbook);
        if (!heading.find()) {
            return "";
        }
        int level = heading.group(1).length();
        Matcher next = Pattern.compile("(?m)^#{1," + level + "} ").matcher(runbook);
        return next.find(heading.end()) ? runbook.substring(heading.start(), next.start()) : runbook.substring(heading.start());
    }

    /** The objective's budget exactly as the document writes it, the text the rules' thresholds must carry. */
    private static Map<String, String> documentedBudgets() {
        Map<String, String> budgets = new LinkedHashMap<>();
        Matcher row = Pattern.compile("(?m)^\\| `([a-z_]+)` \\|.*\\| ([0-9.]+) \\|\\s*$").matcher(RepositoryPaths.read(SLOS));
        while (row.find()) {
            budgets.put(row.group(1), row.group(2));
        }
        return budgets;
    }

    private static Set<String> correctnessRegister() {
        String document = RepositoryPaths.read(SLOS);
        int start = document.indexOf("\n## 3. ");
        assertThat(start).as("the correctness register exists").isPositive();
        int end = document.indexOf("\n## ", start + 1);
        Matcher row = Pattern.compile("(?m)^\\| `([A-Za-z]+)` \\|").matcher(document.substring(start, end < 0 ? document.length() : end));
        Set<String> register = new TreeSet<>();
        while (row.find()) {
            register.add(row.group(1));
        }
        return register;
    }

    private static String camel(String slo) {
        StringBuilder name = new StringBuilder();
        for (String part : slo.split("_")) {
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.toString();
    }

    private static String pin(String pins, String name) {
        Matcher line = Pattern.compile("(?m)^" + name + "=(\\S+)\\s*$").matcher(pins);
        assertThat(line.find()).as(name).isTrue();
        return line.group(1);
    }

    private static Map<String, Object> yaml(String path) {
        return yamlAt(RepositoryPaths.locate(path));
    }

    private static Map<String, Object> yamlAt(Path path) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(path));
        Map<String, Object> document = factory.getObject();
        assertThat(document).as("%s must parse", path).isNotNull();
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }
}
