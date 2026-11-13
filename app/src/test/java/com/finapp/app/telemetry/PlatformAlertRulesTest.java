package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * The platform rules (`P15-TSK-024`): a rule on {@code up} per instance at page, the fleet gone from the scrape, and the
 * gauge refresher's canary - as written, linked to their runbook section and loaded by the committed configuration.
 * <strong>Whether they fire is {@code promtool}'s</strong>: {@code infra/prometheus/tests/platform.test.yml}, run through
 * the digest-pinned Prometheus image (CI's {@code alerts} job, since `P15-TSK-025`). This suite holds what a build can hold without one:
 * the rule removed, made fleet-wide, demoted or unlinked is red here. And, over every rule file, that every gauge an alert
 * reads has an unreadable rule, since every gauge can now be NaN (the fix round's guard).
 */
@DisplayName("the platform rules: up per instance at page, the fleet absent, the gauges stale, every read gauge seen unreadable (P15-TSK-024)")
class PlatformAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/platform.yml";
    private static final String TESTS = "infra/prometheus/tests/platform.test.yml";
    private static final String CONFIG = "infra/prometheus/prometheus.yml";
    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";

    @Test
    @DisplayName("InstanceDown pages on up == 0 for the finapp job, per instance - no aggregation hides one instance behind the fleet")
    void anInstanceDownPagesPerInstance() {
        Map<String, Object> rule = rules().get("InstanceDown");
        assertThat(rule).as("the rule on up").isNotNull();
        assertThat(String.valueOf(rule.get("expr")).strip()).isEqualTo("up{job=\"finapp\"} == 0");
        assertThat(severity(rule)).isEqualTo("page");
        assertThat(String.valueOf(rule.get("for"))).isEqualTo("2m");
    }

    @Test
    @DisplayName("a fleet gone from the scrape pages, and a stale refresher tickets on its canary - the meter GaugeRefresherBeans registers, per instance, its stale evaluations counted over 30 minutes, never a NaN held for a duration that a thread behind but not stuck never gives")
    void theFleetAbsentAndTheGaugesStale() {
        Map<String, Map<String, Object>> rules = rules();
        assertThat(String.valueOf(rules.get("FinappTargetsAbsent").get("expr")).strip()).isEqualTo("absent(up{job=\"finapp\"})");
        assertThat(severity(rules.get("FinappTargetsAbsent"))).isEqualTo("page");
        String canary = GaugeRefresherBeans.REFRESHED.replace('.', '_');
        assertThat(String.valueOf(rules.get("GaugesStale").get("expr")).strip())
                .isEqualTo("sum_over_time((" + canary + " != bool " + canary + ")[30m:15s]) > 6");
        assertThat(severity(rules.get("GaugesStale"))).isEqualTo("ticket");
    }

    /** {@code x != x}, or {@code x != bool x}: true only for NaN. */
    private static final Pattern NAN_CHECK = Pattern.compile("(finapp_[a-z0-9_]+)(\\{[^}]*})?\\s*!=\\s*(?:bool\\s+)?\\1\\b");
    private static final Pattern SERIES = Pattern.compile("\\bfinapp_[a-z0-9_]+");
    /** A counter's or a histogram's series: never NaN, so never needs an unreadable rule. */
    private static final Pattern NOT_A_GAUGE = Pattern.compile(".*_(total|count|sum|bucket)$");
    /**
     * A sweep's switch, {@code enabled ? 1 : 0} from its configuration, or the configuration fingerprint, always 1 (`P15-TSK-031`): each reads no
     * database, so its only no-value is the refresher's - before its first read, or once an ask has waited stale-after - which is exactly the
     * canary's, and {@code GaugesStale} counts that per instance. The fingerprint's alert reads its label and never its value, so a NaN there
     * cannot blind it even for the instance it comes from: {@code config-fingerprint.test.yml}'s NaN case holds that. The audit
     * archive's switch (`P16-TSK-010`) is one more: {@code enabled ? 1 : 0} from whether a store is named, reading no database.
     */
    private static final Pattern CONFIGURATION_CONSTANT =
            Pattern.compile("finapp_.+_sweeper_enabled|finapp_config_fingerprint|finapp_audit_archive_enabled");

    @Test
    @DisplayName("every gauge an alert of any rule file reads has an unreadable rule (x != x) in some rule file, or is a sweep's configuration switch, whose only NaN is the refresher's and GaugesStale's - P15-TSK-024's fix round: since every gauge reads off the scrape, every gauge can be NaN, and a threshold never compares a NaN")
    void everyGaugeAnAlertReadsCanBeSeenUnreadable() throws IOException {
        Map<String, Set<String>> readBy = new TreeMap<>();
        Set<String> unreadable = new TreeSet<>();
        Path directory = RepositoryPaths.locate(RULES).getParent();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                YamlMapFactoryBean factory = new YamlMapFactoryBean();
                factory.setResources(new FileSystemResource(file));
                for (Object group : (List<?>) factory.getObject().get("groups")) {
                    for (Object node : (List<?>) map(group).get("rules")) {
                        Map<String, Object> rule = map(node);
                        String expr = String.valueOf(rule.get("expr"));
                        Matcher nan = NAN_CHECK.matcher(expr);
                        while (nan.find()) {
                            unreadable.add(nan.group(1));
                        }
                        Matcher series = SERIES.matcher(expr.replaceAll("\"[^\"]*\"", "\"\""));
                        while (series.find()) {
                            readBy.computeIfAbsent(series.group(), name -> new TreeSet<>())
                                    .add(file.getFileName() + " " + rule.get("alert"));
                        }
                    }
                }
            }
        }
        readBy.keySet().removeIf(name -> NOT_A_GAUGE.matcher(name).matches() || CONFIGURATION_CONSTANT.matcher(name).matches());
        assertThat(readBy).as("not vacuous: the gauges the fix round found uncovered are read")
                .containsKeys("finapp_fx_proof", "finapp_payments_outbound_unknown_age", "finapp_reconciliation_break_age",
                        "finapp_ledger_negative_positions", GaugeRefresherBeans.REFRESHED.replace('.', '_'));
        assertThat(readBy).allSatisfy((gauge, alerts) -> assertThat(unreadable)
                .as("%s, read by %s, has an unreadable rule - or every alert on it is blind while it is NaN", gauge, alerts)
                .contains(gauge));
    }

    @Test
    @DisplayName("every platform alert links to OPERATIONS_RUNBOOK.md section 11.8, which names it; the promtool cases name every alert; the committed configuration loads the file")
    void everyAlertIsLinkedTestedAndLoaded() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        int at = runbook.indexOf("\n### 11.8 ");
        assertThat(at).as("section 11.8 exists").isPositive();
        int next = runbook.indexOf("\n### ", at + 1);
        String section = runbook.substring(at, next < 0 ? runbook.length() : next);
        String tests = RepositoryPaths.read(TESTS);
        assertThat(rules()).hasSize(3).allSatisfy((alert, rule) -> {
            assertThat(String.valueOf(annotations(rule).get("description"))).as(alert).contains("See OPERATIONS_RUNBOOK.md section 11.8.");
            assertThat(String.valueOf(annotations(rule).get("summary"))).as(alert).isNotBlank().isNotEqualTo("null");
            assertThat(section).as("section 11.8 names %s", alert).contains("`" + alert + "`");
            assertThat(tests).as("a promtool case for %s", alert).contains("alertname: " + alert);
        });
        Path rulesFile = Path.of(CONFIG).getParent().relativize(Path.of(RULES));
        Map<String, Object> config = yaml(CONFIG);
        assertThat(((List<?>) config.get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(pattern -> assertThat(FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(rulesFile)).isTrue());
        assertThat(((List<?>) config.get("rule_files")).stream().map(String::valueOf))
                .as("the promtool cases are not loaded as rules").noneSatisfy(pattern -> assertThat(
                        FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(CONFIG).getParent().relativize(Path.of(TESTS)))).isTrue());
    }

    private static Map<String, Map<String, Object>> rules() {
        Map<String, Object> document = yaml(RULES);
        List<?> groups = (List<?>) document.get("groups");
        Map<String, Object> platform = groups.stream().map(PlatformAlertRulesTest::map).filter(group -> "platform".equals(group.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("no rules group named platform"));
        return ((List<?>) platform.get("rules")).stream().map(PlatformAlertRulesTest::map)
                .collect(Collectors.toMap(rule -> String.valueOf(rule.get("alert")), Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private static String severity(Map<String, Object> rule) {
        return String.valueOf(map(rule.get("labels")).get("severity"));
    }

    private static Map<String, Object> annotations(Map<String, Object> rule) {
        return map(rule.get("annotations"));
    }

    private static Map<String, Object> yaml(String path) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(RepositoryPaths.locate(path)));
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
