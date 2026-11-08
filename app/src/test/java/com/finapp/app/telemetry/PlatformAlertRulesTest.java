package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * The platform rules (`P15-TSK-024`): a rule on {@code up} per instance at page, the fleet gone from the scrape, and the
 * gauge refresher's canary - as written, linked to their runbook section and loaded by the committed configuration.
 * <strong>Whether they fire is {@code promtool}'s</strong>: {@code infra/prometheus/tests/platform.test.yml}, run through
 * the digest-pinned Prometheus image (not yet in CI: `P15-TSK-025`). This suite holds what a build can hold without one:
 * the rule removed, made fleet-wide, demoted or unlinked is red here.
 */
@DisplayName("the platform rules: up per instance at page, the fleet absent, the gauges stale (P15-TSK-024)")
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
    @DisplayName("a fleet gone from the scrape pages, and a stale refresher tickets on its canary - the meter GaugeRefresherBeans registers, per instance")
    void theFleetAbsentAndTheGaugesStale() {
        Map<String, Map<String, Object>> rules = rules();
        assertThat(String.valueOf(rules.get("FinappTargetsAbsent").get("expr")).strip()).isEqualTo("absent(up{job=\"finapp\"})");
        assertThat(severity(rules.get("FinappTargetsAbsent"))).isEqualTo("page");
        String canary = GaugeRefresherBeans.REFRESHED.replace('.', '_');
        assertThat(String.valueOf(rules.get("GaugesStale").get("expr")).strip()).isEqualTo(canary + " != " + canary);
        assertThat(severity(rules.get("GaugesStale"))).isEqualTo("ticket");
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
