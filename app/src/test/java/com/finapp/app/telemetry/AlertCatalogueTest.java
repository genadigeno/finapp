package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The rule files this task wrote whole: each states its aggregation per rule. */
    private static final Set<String> STATED_FILES = Set.of("slo.yml", "correctness.yml", "flows.yml", "platform.yml");

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

    @Test
    @DisplayName("every alert this task wrote, and every rule the routed notes asked for, is named by a promtool case")
    void everyNewAlertHasAPromtoolCase() throws IOException {
        String cases = allTestFiles();
        for (Alert alert : alerts()) {
            if (STATED_FILES.contains(alert.file()) || Set.of("CreditScorecardMissing", "BnplSweepClaimFailing").contains(alert.name())) {
                assertThat(cases).as("a promtool case for %s", alert.name()).contains("alertname: " + alert.name() + "\n");
            }
        }
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

    private static String allTestFiles() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(testsDirectory())) {
            for (Path file : files.sorted().toList()) {
                all.append(Files.readString(file).replace("\r\n", "\n")).append('\n');
            }
        }
        return all.toString();
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
