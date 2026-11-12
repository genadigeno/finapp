package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;

/**
 * The telemetry stack's own alerts (`P15-TSK-032`; {@code THREAT_MODEL.md} §6 item 37 (d) (i), T46), over the series the pinned
 * Prometheus really stores from the pinned collector, Tempo and Loki. <strong>Whether a rule fires is {@code promtool}'s</strong>:
 * {@code infra/prometheus/tests/telemetry.test.yml}, run by {@code infra/scripts/alert-rules-check.sh}. What promtool cannot see is a rule
 * on a name the scrape never stores, because its cases are series written by hand: the collector's counters carry no {@code _total} as
 * the pinned Prometheus stores them, its failure counters do not exist before the first failure, and its exporter series carry labels a
 * rule must sum away. {@code infra/prometheus/telemetry-series.txt} is what the pinned images stored, written by
 * {@code infra/scripts/telemetry-series.sh}; this holds every name, label and label value the rules read to that file, and that file to
 * the pins.
 */
@DisplayName("the telemetry stack's own alerts read the names, labels and values the pinned images store, and tell a new count from an old one (P15-TSK-032)")
class TelemetryAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/telemetry.yml";
    private static final String CASES = "infra/prometheus/tests/telemetry.test.yml";
    private static final String SERIES = "infra/prometheus/telemetry-series.txt";
    private static final String SCRIPT = "infra/scripts/telemetry-series.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";

    /** The rules that ticket on any drop or refusal: each counter they read is born above zero at a failure, or may be. */
    private static final Set<String> ANY_LOSS = Set.of("TelemetryExportFailing", "TelemetryExportQueueFull", "TelemetryReceiverRefusing");

    private static final Set<String> NOT_A_SERIES = Set.of("sum", "by", "or", "and", "unless", "on", "ignoring", "increase", "rate",
            "present_over_time", "absent", "count", "offset", "bool", "max", "min", "group_left", "group_right");

    /** A name not inside another word or a number (the m of {@code 10m}), with its matchers when it has any. */
    private static final Pattern SELECTOR = Pattern.compile("(?<![a-zA-Z0-9_:.])([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*(\\{[^}]*})?");
    private static final Pattern MATCHER = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_.]*|\"[^\"]+\")\\s*(=~|!=|!~|=)\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("every series name a rule reads is one the pinned Prometheus stored, every label it selects or groups by is on that series,"
            + " and every value it selects was seen")
    void everyNameLabelAndValueIsStored() {
        Map<String, List<Map<String, String>>> stored = stored();
        int names = 0;
        int groupings = 0;
        for (Map.Entry<String, String> rule : expressions().entrySet()) {
            for (Map.Entry<String, String> selector : selectors(rule.getValue()).entrySet()) {
                String name = selector.getKey();
                names++;
                assertThat(stored).as("%s reads %s, a name the pinned Prometheus stored (a suffix it does not carry, or one it does, is"
                        + " a rule that never fires)", rule.getKey(), name).containsKey(name);
                List<Map<String, String>> series = stored.get(name);
                Matcher matcher = MATCHER.matcher(selector.getValue());
                while (matcher.find()) {
                    String label = matcher.group(1).replace("\"", "");
                    assertThat(matcher.group(2)).as("%s selects %s by equality or by a list", rule.getKey(), name).isIn("=", "=~");
                    for (String value : matcher.group(2).equals("=~") ? List.of(matcher.group(3).split("\\|")) : List.of(matcher.group(3))) {
                        assertThat(series).as("%s selects %s{%s=\"%s\"}, a value the pinned images were seen to store", rule.getKey(), name,
                                label, value).anySatisfy(labels -> assertThat(labels.get(label)).isIn(value, "*"));
                    }
                }
            }
            // A label summed by is on every series summed, within the aggregation's own parentheses. The operand an `on` matches against
            // (the collector's uptime, a filter or a count it is compared with) lends no labels to the sum, and is not held to them.
            for (Map.Entry<List<String>, String> grouping : groupings(rule.getValue())) {
                String summed = grouping.getValue().replaceAll(
                        "\\b(on|ignoring)\\([^)]*\\)\\s*(group_(left|right)\\s*)?([a-z_]+\\()?[a-zA-Z_:][a-zA-Z0-9_:]*", " ");
                for (String name : selectors(summed).keySet()) {
                    groupings++;
                    for (String label : grouping.getKey()) {
                        assertThat(stored.get(name)).as("%s groups %s by %s, a label every stored series of it carries", rule.getKey(), name, label)
                                .allSatisfy(labels -> assertThat(labels).containsKey(label));
                    }
                }
            }
            // A label an `on` matches by is on every series of both sides, or the match is empty and the rule never fires.
            Matcher on = Pattern.compile("\\bon\\(([^)]+)\\)").matcher(rule.getValue());
            while (on.find()) {
                assertThat(List.of(on.group(1).split(",\\s*"))).as("%s matches on labels the uptime series carries, or adds", rule.getKey())
                        .allSatisfy(label -> assertThat(label).isIn("job", "instance", "exporter"));
            }
        }
        assertThat(names).as("not vacuous: each rule's distinct series were read, nineteen over the eight").isGreaterThanOrEqualTo(19);
        assertThat(groupings).as("not vacuous: the aggregations of every rule were read").isGreaterThanOrEqualTo(12);
    }

    /** Each {@code by (labels) (inner)} of an expression: its labels and the expression it aggregates. */
    private static List<Map.Entry<List<String>, String>> groupings(String expr) {
        List<Map.Entry<List<String>, String>> found = new ArrayList<>();
        Matcher by = Pattern.compile("\\bby \\(([^)]*)\\)\\s*\\(").matcher(expr);
        while (by.find()) {
            int depth = 1;
            int at = by.end();
            while (at < expr.length() && depth > 0) {
                char next = expr.charAt(at++);
                depth += next == '(' ? 1 : next == ')' ? -1 : 0;
            }
            found.add(Map.entry(List.of(by.group(1).split(",\\s*")), expr.substring(by.end(), at - 1)));
        }
        return found;
    }

    @Test
    @DisplayName("the stored series are the pinned images': the capture names the four digests infra/scanner-pins.sh pins, and its script is committed executable")
    void theCaptureIsOfThePinnedImages() throws IOException, InterruptedException {
        String capture = RepositoryPaths.read(SERIES).replace("\r\n", "\n");
        String pins = RepositoryPaths.read(PINS);
        for (String image : List.of("prometheus", "collector", "tempo", "loki")) {
            assertThat(capture).as("the capture was taken from the pinned %s: run %s again after a pin moves", image, SCRIPT)
                    .contains("\n# " + image + " " + pin(pins, image.toUpperCase(java.util.Locale.ROOT) + "_DIGEST") + "\n");
        }
        assertThat(capture.lines().filter(line -> line.startsWith("failing ")).count()).as("the failing phase was reached").isPositive();
        String script = RepositoryPaths.read(SCRIPT);
        assertThat(script).as("the committed collector configuration, unchanged, through the pinned images, read back as Prometheus stored it")
                .contains("cp \"$ROOT/deploy/observability/base/collector.yaml\" \"$CONFIGS/collector.yaml\"")
                .contains("PROMETHEUS_IMAGE=\"${PROMETHEUS_REPOSITORY}@${PROMETHEUS_DIGEST}\"")
                .contains("COLLECTOR_IMAGE=\"${COLLECTOR_REPOSITORY}@${COLLECTOR_DIGEST}\"")
                .contains("/api/v1/series");
        Process git = new ProcessBuilder("git", "ls-files", "-s", SCRIPT)
                .directory(RepositoryPaths.locate("settings.gradle.kts").getParent().toFile())
                .redirectErrorStream(true)
                .start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }

    @Test
    @DisplayName("each rule that tickets on any drop or refusal also reads a series born above zero - its collector younger than the ten"
            + " minutes, or the series with fewer samples in them than its collector's uptime series - never an `offset` term")
    void aCounterBornAboveZeroIsSeen() {
        Map<String, List<Map<String, String>>> stored = stored();
        Set<String> bornAtAFailure = new TreeSet<>();
        for (String line : RepositoryPaths.read(SERIES).lines().toList()) {
            if (line.startsWith("failing ")) {
                bornAtAFailure.add(line.substring("failing ".length(), line.indexOf('{')));
            }
        }
        assertThat(bornAtAFailure).as("the capture shows the failure counters do not exist until the first failure")
                .contains("otelcol_exporter_send_failed_spans", "otelcol_exporter_send_failed_log_records",
                        "otelcol_exporter_enqueue_failed_spans", "otelcol_exporter_enqueue_failed_log_records");
        Map<String, String> expressions = expressions();
        Set<String> read = new TreeSet<>();
        for (String alert : ANY_LOSS) {
            String expr = expressions.get(alert);
            assertThat(expr).as("%s is a rule", alert).isNotNull();
            Matcher increase = Pattern.compile("increase\\(([a-z_]+)\\[10m]\\)").matcher(expr);
            int counters = 0;
            while (increase.find()) {
                String name = increase.group(1);
                counters++;
                read.add(name);
                assertThat(stored).containsKey(name);
                assertThat(expr).as("%s sees %s when it is born above zero, after a restart in place too", alert, name)
                        .contains("((" + name + " > 0 and on(job, instance) otelcol_process_uptime < 600) or (" + name + " > 0 and count_over_time("
                                + name + "[10m]) < on(job, instance) group_left count_over_time(otelcol_process_uptime[10m])))");
            }
            assertThat(counters).as("%s reads its counters for spans and for log records", alert).isEqualTo(2);
        }
        assertThat(read).as("every counter born at a failure is read by a rule that sees it born").containsAll(bornAtAFailure.stream()
                .filter(name -> name.startsWith("otelcol_exporter_")).toList());
        for (Map.Entry<String, String> rule : expressions.entrySet()) {
            assertThat(rule.getValue()).as("%s has no offset term: a staleness marker or a gap empties one", rule.getKey())
                    .doesNotContain("offset").doesNotContain("present_over_time");
        }
    }

    @Test
    @DisplayName("every rule tickets, never pages; any drop or refusal tickets at once, per collector; each names OPERATIONS_RUNBOOK.md"
            + " section 11.34, which names it")
    void eachRuleIsAsDecided() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        int at = runbook.indexOf("\n### 11.34 ");
        assertThat(at).as("section 11.34 exists").isPositive();
        int next = runbook.indexOf("\n### ", at + 1);
        String section = runbook.substring(at, next < 0 ? runbook.length() : next);
        Map<String, Map<String, Object>> rules = rules();
        assertThat(rules.keySet()).containsExactlyInAnyOrder("TelemetryCollectorDown", "TelemetryCollectorsAbsent", "TelemetryExportFailing",
                "TelemetryExportQueueFull", "TelemetryReceiverRefusing", "TelemetryBackendDown", "TraceIngestStopped", "LogIngestStopped");
        for (Map.Entry<String, Map<String, Object>> rule : rules.entrySet()) {
            String alert = rule.getKey();
            assertThat(map(rule.getValue().get("labels"))).as("%s: telemetry carries no money", alert).containsEntry("severity", "ticket");
            assertThat(String.valueOf(map(rule.getValue().get("annotations")).get("description"))).as(alert)
                    .endsWith("See OPERATIONS_RUNBOOK.md section 11.34.");
            assertThat(section).as("section 11.34 names %s", alert).contains("`" + alert + "`");
            String expr = String.valueOf(rule.getValue().get("expr"));
            if (ANY_LOSS.contains(alert)) {
                assertThat(String.valueOf(rule.getValue().get("for"))).as("%s: a drop is a fact, not a condition to wait out", alert).isEqualTo("0s");
                assertThat(expr).as("%s: per collector, never across the fleet", alert).contains("sum by (job, instance, ");
            }
        }
        // The owner's rulings (2026-11-12): the load-balancing tier is not read while its own collector's peers change - the collector young,
        // or its set of peers changed - over every term of the rule, and nothing else is excluded anywhere.
        String exclusion = "unless on(job, instance, exporter) label_replace(otelcol_process_uptime < 780 or on(job, instance)"
                + " increase(otelcol_loadbalancer_num_backend_updates[13m]) > 0, \"exporter\", \"load_balancing\", \"\", \"\")";
        assertThat(String.valueOf(rules.get("TelemetryExportFailing").get("expr")).strip()).as("the start-up window, over the whole rule")
                .startsWith("( ").endsWith(") " + exclusion);
        for (Map.Entry<String, String> rule : expressions().entrySet()) {
            if (!"TelemetryExportFailing".equals(rule.getKey())) {
                assertThat(rule.getValue()).as("%s excludes nothing", rule.getKey()).doesNotContain("otelcol_loadbalancer_num_backend_updates")
                        .doesNotContain("label_replace").doesNotContain("< 780");
            }
        }
        assertThat(String.valueOf(rules.get("TelemetryCollectorDown").get("expr"))).isEqualTo("up{job=\"finapp-collector\"} == 0");
        assertThat(String.valueOf(rules.get("TelemetryCollectorsAbsent").get("expr"))).isEqualTo("absent(up{job=\"finapp-collector\"})");
        for (String ingest : List.of("TraceIngestStopped", "LogIngestStopped")) {
            assertThat(String.valueOf(rules.get(ingest).get("expr"))).as("%s is gated on its back end being scraped at all", ingest)
                    .containsPattern("and on\\(\\) count\\(up\\{job=\"finapp-(tempo|loki)\"}\\) > 0$");
        }
    }

    @Test
    @DisplayName("each rule has promtool cases that fire it and that keep it silent, and each counter rule is kept silent through a"
            + " staleness marker and through a gap")
    void eachIsTestedBothWays() {
        String cases = RepositoryPaths.read(CASES).replace("\r\n", "\n");
        assertThat(cases).contains("rule_files:\n  - ../rules/telemetry.yml");
        for (String alert : rules().keySet()) {
            Matcher evaluation = Pattern.compile("alertname: " + alert + "\\n\\s+exp_alerts:( \\[])?").matcher(cases);
            int firing = 0;
            int silent = 0;
            while (evaluation.find()) {
                if (evaluation.group(1) == null) {
                    firing++;
                } else {
                    silent++;
                }
            }
            assertThat(firing).as("%s: a case where it fires", alert).isPositive();
            assertThat(silent).as("%s: the opposite case, where it stays silent", alert).isPositive();
        }
        Set<String> throughAStaleMarker = new TreeSet<>();
        Set<String> throughAGap = new TreeSet<>();
        for (String block : cases.split("\\n  - interval: ")) {
            Matcher named = Pattern.compile("alertname: (\\w+)\\n\\s+exp_alerts: \\[]").matcher(block);
            while (named.find()) {
                if (block.contains("values: '1x5 stale 1x20'")) {
                    throughAStaleMarker.add(named.group(1));
                }
                if (block.contains("values: '1x5 _x6 1x20'")) {
                    throughAGap.add(named.group(1));
                }
            }
        }
        assertThat(throughAStaleMarker).as("an old count through a failed scrape's staleness marker, silent").containsAll(ANY_LOSS);
        assertThat(throughAGap).as("an old count through a six-minute gap, silent").containsAll(ANY_LOSS);
    }

    @Test
    @DisplayName("compose and kind scrape the collector and the back ends under the job names the rules read")
    void theJobsAreScraped() {
        for (String config : List.of("infra/prometheus/prometheus.yml", "deploy/observability/overlays/kind/prometheus.yml")) {
            Set<String> jobs = new TreeSet<>();
            for (Object job : (List<?>) yaml(RepositoryPaths.read(config)).get("scrape_configs")) {
                jobs.add(String.valueOf(map(job).get("job_name")));
            }
            assertThat(jobs).as(config).contains("finapp", "finapp-collector", "finapp-tempo", "finapp-loki");
        }
    }

    // ------------------------------------------------------------------------------------ reading

    private static Map<String, Map<String, Object>> rules() {
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        for (Object group : (List<?>) yaml(RepositoryPaths.read(RULES)).get("groups")) {
            Map<String, Object> named = map(group);
            assertThat(map(named.get("labels"))).as("the telemetry group is the platform's").containsEntry("owner", "platform");
            for (Object entry : (List<?>) named.get("rules")) {
                Map<String, Object> rule = map(entry);
                rules.put(String.valueOf(rule.get("alert")), rule);
            }
        }
        return rules;
    }

    private static Map<String, String> expressions() {
        Map<String, String> expressions = new TreeMap<>();
        rules().forEach((alert, rule) -> expressions.put(alert, String.valueOf(rule.get("expr")).strip()));
        return expressions;
    }

    /** Every series selector of a term, by name, with its matchers ({@code ""} when it has none). */
    private static Map<String, String> selectors(String term) {
        // A function's string arguments (label_replace's) are no series: each follows a comma, which a matcher's label never does here.
        String bare = term.replaceAll("\\b(by|on|ignoring) ?\\([^)]*\\)", " ").replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll(",\\s*\"[^\"]*\"", " ");
        Map<String, String> found = new LinkedHashMap<>();
        Matcher selector = SELECTOR.matcher(bare);
        while (selector.find()) {
            String name = selector.group(1);
            int after = selector.end();
            while (after < bare.length() && bare.charAt(after) == ' ') {
                after++;
            }
            boolean call = selector.group(2) == null && after < bare.length() && bare.charAt(after) == '(';
            if (call || NOT_A_SERIES.contains(name) || Character.isDigit(name.charAt(0))) {
                continue;
            }
            found.merge(name, selector.group(2) == null ? "" : selector.group(2), (left, right) -> left + right);
        }
        return found;
    }

    /** The capture, by series name: each stored series' labels, a value {@code *} where the run's own. */
    private static Map<String, List<Map<String, String>>> stored() {
        Map<String, List<Map<String, String>>> stored = new TreeMap<>();
        Pattern line = Pattern.compile("^(fresh|healthy|failing) ([a-zA-Z_:][a-zA-Z0-9_:]*)\\{(.*)}$");
        for (String text : RepositoryPaths.read(SERIES).lines().filter(entry -> !entry.startsWith("#")).toList()) {
            Matcher parsed = line.matcher(text);
            assertThat(parsed.matches()).as("a line of the capture: %s", text).isTrue();
            Map<String, String> labels = new TreeMap<>();
            if (!parsed.group(3).isEmpty()) {
                for (String pair : parsed.group(3).split(",")) {
                    labels.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
                }
            }
            stored.computeIfAbsent(parsed.group(2), name -> new ArrayList<>()).add(labels);
        }
        assertThat(stored).as("not vacuous: the capture was read").hasSizeGreaterThan(50);
        return stored;
    }

    private static String pin(String pins, String name) {
        Matcher value = Pattern.compile("(?m)^" + name + "=(\\S+)\\s*$").matcher(pins);
        assertThat(value.find()).as(name).isTrue();
        return value.group(1);
    }

    private static Map<String, Object> yaml(String text) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> document = factory.getObject();
        assertThat(document).isNotNull();
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }
}
