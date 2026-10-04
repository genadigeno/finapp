package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;

/**
 * Every alert rule in the committed rules file names a series the application actually publishes
 * (P8-TSK-024).
 *
 * <h2>Why this exists</h2>
 *
 * <p>An alert over a series that does not exist is worse than a dashboard panel over one: the
 * panel at least says "No data", the alert says nothing at all, forever. {@code
 * max(finapp_reconciliation_suspense_age) > 2592000} - the gauge carries a {@code seconds} base
 * unit, so the published name ends {@code _seconds} - would be a valid rule that never fires. This
 * is {@link DashboardQueriesResolveTest}'s check, applied to {@code
 * infra/prometheus/rules/settlement-reconciliation.yml}, with the same scrape as the oracle.
 *
 * <p>It also holds the rules file to the shape PHASE_8_PLAN section 15 asks of it: loaded by the
 * committed {@code prometheus.yml}, every rule an alert with a {@code for}, a {@code severity}
 * label and a summary, every must-be-0 gauge and the other alerted series covered, and the four
 * break-age thresholds exactly as decided (CRITICAL above 0, HIGH above a day, MEDIUM above five
 * days, LOW above fifteen).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertRulesResolveTest {

    private static final String PROMETHEUS_CONFIG = "infra/prometheus/prometheus.yml";

    private static final String RULES = "infra/prometheus/rules/settlement-reconciliation.yml";

    private static final String GROUP = "settlement-reconciliation";

    /** The FX rules (P9-TSK-005 onward), held to the same checks. */
    private static final String FX_RULES = "infra/prometheus/rules/fx.yml";

    private static final String FX_GROUP = "fx";

    /** A PromQL metric selector: an identifier not immediately followed by an opening bracket. */
    private static final Pattern METRIC = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()");

    /**
     * A PromQL string literal - a label matcher's value or a {@code label_replace} argument. Removed
     * before identifiers are read, so {@code severity="CRITICAL"} does not read as a series named
     * {@code CRITICAL}.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"[^\"]*\"");

    /**
     * PromQL tokens that are not series names: aggregation operators (written {@code max by
     * (source) (...)}, so the bracket lookahead does not exclude them), set operators and modifiers,
     * and the label names these rules group or select by. As in {@link
     * DashboardQueriesResolveTest}, an unknown token is treated AS a series - a visible failure,
     * never a silently unchecked rule.
     */
    private static final Set<String> NOT_A_SERIES =
            Set.of(
                    // Aggregation operators.
                    "sum", "min", "max", "avg", "group", "count", "topk", "bottomk",
                    // Set operators and modifiers.
                    "or", "and", "unless", "by", "without", "on", "ignoring", "bool", "offset",
                    // Label names the rules group or select by.
                    "source", "outcome", "purpose", "currency", "severity", "type", "pair");

    /** Every series PHASE_8_PLAN section 15 says is alerted, in its published form. */
    private static final Set<String> ALERTED_SERIES =
            Set.of(
                    "finapp_reconciliation_position_proof",
                    "finapp_reconciliation_line_unattributed",
                    "finapp_reconciliation_cash_proof",
                    "finapp_reconciliation_suspense_unowned",
                    "finapp_reconciliation_run_blocked",
                    "finapp_settlement_source_silence",
                    "finapp_reconciliation_expectation_overdue",
                    "finapp_reconciliation_suspense_age_seconds",
                    "finapp_reconciliation_break_age",
                    "finapp_settlement_delivery_refused_total");

    /** The break-age thresholds in seconds: 0 h, 1 d, 5 d, 15 d. */
    private static final Map<String, Long> BREAK_AGE_THRESHOLDS =
            // The severity tag's values are lowercase, as every tag value is (MetricNames).
            Map.of("critical", 0L, "high", 86_400L, "medium", 432_000L, "low", 1_296_000L);

    private static final Pattern SEVERITY_SELECTOR = Pattern.compile("severity=\"([a-z]+)\"");

    private static final Pattern TRAILING_THRESHOLD = Pattern.compile(">\\s*(\\d+)\\s*$");

    @LocalServerPort private int port;

    @Autowired private MeterRegistry registry;

    /** As in {@link DashboardQueriesResolveTest}: some framework series exist only after a request. */
    @BeforeEach
    void serveOneRequest() throws Exception {
        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("every series an alert rule queries is one the application publishes")
    void everyQueriedSeriesExists() {
        Set<String> published = publishedSeriesNames();
        assertThat(published).as("the registry must expose series to check against").isNotEmpty();

        Set<String> queried = queriedSeriesNames();
        assertThat(queried).as("the rules must contain expressions").isNotEmpty();

        assertThat(queried)
                .as(
                        """
                        An alert rule queries a series the application does not publish. It will \
                        never fire, and a silent alert looks exactly like a healthy system. \
                        Published series: %s""",
                        published)
                .allSatisfy(series -> assertThat(published).contains(series));
    }

    @Test
    @DisplayName("the fx rules: every queried series is published, each rule a well-formed alert,"
            + " the file loaded, and the reference age covered (P9-TSK-005)")
    void theFxRulesResolve() {
        List<Map<String, Object>> fx = rules(FX_RULES, FX_GROUP);
        Set<String> queried =
                fx.stream()
                        .map(rule -> String.valueOf(rule.get("expr")))
                        .flatMap(AlertRulesResolveTest::seriesIn)
                        .collect(Collectors.toCollection(TreeSet::new));
        assertThat(queried).contains("finapp_fx_rate_age");
        assertThat(publishedSeriesNames()).containsAll(queried);
        assertThat(fx)
                .allSatisfy(
                        rule -> {
                            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
                            assertThat(map(rule.get("labels")).get("severity"))
                                    .isIn("page", "ticket");
                            assertThat(String.valueOf(map(rule.get("annotations")).get("summary")))
                                    .isNotBlank();
                        });
        Path rulesFile = Path.of(PROMETHEUS_CONFIG).getParent().relativize(Path.of(FX_RULES));
        assertThat(list(yaml(PROMETHEUS_CONFIG).get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(
                        pattern ->
                                assertThat(
                                                FileSystems.getDefault()
                                                        .getPathMatcher("glob:" + pattern)
                                                        .matches(rulesFile))
                                        .isTrue());
    }

    @Test
    @DisplayName("the guard is not vacuous: every alerted series of section 15 is queried")
    void everyAlertedSeriesIsCovered() {
        assertThat(queriedSeriesNames()).containsAll(ALERTED_SERIES);
        assertThat(publishedSeriesNames()).contains("finapp_reconciliation_position_proof");
    }

    @Test
    @DisplayName("the committed prometheus.yml loads the rules file")
    void thePrometheusConfigurationLoadsTheRules() {
        Map<String, Object> config = yaml(PROMETHEUS_CONFIG);
        List<String> ruleFiles =
                list(config.get("rule_files")).stream().map(String::valueOf).toList();
        assertThat(ruleFiles).as("prometheus.yml must declare rule_files").isNotEmpty();

        // rule_files resolve relative to prometheus.yml's own directory.
        Path configDirectory = Path.of(PROMETHEUS_CONFIG).getParent();
        Path rules = configDirectory.relativize(Path.of(RULES));
        assertThat(ruleFiles)
                .as("one rule_files pattern must match %s", rules)
                .anySatisfy(
                        pattern -> {
                            PathMatcher matcher =
                                    FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                            assertThat(matcher.matches(rules)).isTrue();
                        });
    }

    @Test
    @DisplayName("every rule is an alert with a for, a severity label and a summary")
    void everyRuleIsAWellFormedAlert() {
        List<Map<String, Object>> rules = rules();
        assertThat(rules).isNotEmpty();
        assertThat(rules)
                .allSatisfy(
                        rule -> {
                            assertThat(rule.get("alert")).as("rule %s", rule).isNotNull();
                            assertThat(String.valueOf(rule.get("for")))
                                    .as("%s's for", rule.get("alert"))
                                    .matches("\\d+[smhd]");
                            assertThat(map(rule.get("labels")).get("severity"))
                                    .as("%s's severity label", rule.get("alert"))
                                    .isIn("page", "ticket");
                            assertThat(String.valueOf(map(rule.get("annotations")).get("summary")))
                                    .as("%s's summary", rule.get("alert"))
                                    .isNotBlank()
                                    .isNotEqualTo("null");
                        });
        assertThat(rules.stream().map(rule -> rule.get("alert")).distinct().count())
                .as("alert names are unique")
                .isEqualTo(rules.size());
    }

    @Test
    @DisplayName("the break-age alerts hold the decided threshold for each severity")
    void theFourBreakAgeThresholdsExist() {
        Map<String, Long> found = new TreeMap<>();
        for (Map<String, Object> rule : rules()) {
            String expr = String.valueOf(rule.get("expr")).strip();
            if (!expr.contains("finapp_reconciliation_break_age")) {
                continue;
            }
            Matcher severity = SEVERITY_SELECTOR.matcher(expr);
            Matcher threshold = TRAILING_THRESHOLD.matcher(expr);
            assertThat(severity.find()).as("%s selects one severity", rule.get("alert")).isTrue();
            assertThat(threshold.find()).as("%s ends in a threshold", rule.get("alert")).isTrue();
            assertThat(map(rule.get("labels")).get("break_severity"))
                    .as("%s names the break's severity apart from the alert's", rule.get("alert"))
                    .isEqualTo(severity.group(1));
            found.put(severity.group(1), Long.parseLong(threshold.group(1)));
        }
        assertThat(found).isEqualTo(new TreeMap<>(BREAK_AGE_THRESHOLDS));
    }

    // -----------------------------------------------------------------

    /** Series names as Prometheus publishes them - the registry's own naming, never a translation. */
    private Set<String> publishedSeriesNames() {
        PrometheusMeterRegistry prometheus =
                registry instanceof PrometheusMeterRegistry direct
                        ? direct
                        : (PrometheusMeterRegistry)
                                ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                                        .getRegistries().stream()
                                        .filter(PrometheusMeterRegistry.class::isInstance)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));

        Set<String> names = new TreeSet<>();
        for (String line : prometheus.scrape().lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int end = line.indexOf('{');
            if (end < 0) {
                end = line.indexOf(' ');
            }
            if (end > 0) {
                names.add(line.substring(0, end));
            }
        }
        return names;
    }

    private static Set<String> queriedSeriesNames() {
        return rules().stream()
                .map(rule -> String.valueOf(rule.get("expr")))
                .flatMap(AlertRulesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static java.util.stream.Stream<String> seriesIn(String expr) {
        Matcher matcher = METRIC.matcher(STRING_LITERAL.matcher(expr).replaceAll(""));
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!NOT_A_SERIES.contains(candidate)) {
                found.add(candidate);
            }
        }
        return found.stream();
    }

    private static List<Map<String, Object>> rules() {
        return rules(RULES, GROUP);
    }

    private static List<Map<String, Object>> rules(String file, String name) {
        List<Map<String, Object>> groups =
                list(yaml(file).get("groups")).stream().map(AlertRulesResolveTest::map).toList();
        Map<String, Object> group =
                groups.stream()
                        .filter(candidate -> name.equals(candidate.get("name")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no rules group named " + name));
        return list(group.get("rules")).stream().map(AlertRulesResolveTest::map).toList();
    }

    private static Map<String, Object> yaml(String relativePath) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(RepositoryPaths.locate(relativePath)));
        Map<String, Object> document = factory.getObject();
        assertThat(document).as("%s must parse", relativePath).isNotNull();
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    private static List<?> list(Object node) {
        assertThat(node).isInstanceOf(List.class);
        return (List<?>) node;
    }
}
