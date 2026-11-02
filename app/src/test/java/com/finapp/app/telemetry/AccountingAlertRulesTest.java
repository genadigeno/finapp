package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.accounting.CloseRunStallMetrics;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

/**
 * The accounting alert <strong>fires when its condition breaks, and only then</strong> (`P14-TSK-017`; {@code PHASE_14_PLAN.md}
 * section 16; the {@code LendingAlertRulesTest} shape): the close run's birth by the schedule stands on a signal that reaches someone,
 * and a report speaks only when asked. The scrape is a freshly started instance's - the real registry's name, nothing translated here -
 * and this context has no schedule running and no database: the meter is published all the same. {@code P14-TSK-020} grows this suite with
 * the plan's other alerts. So it is red when the series is renamed without its rule, when the rule's aggregation cannot see the break, or
 * when an unreadable gauge wakes the threshold rule.
 */
@Tag("slice")
// Pointed at a database that is not there (PlannedMetersExistTest's idiom): the series is the bean's own, from startup.
@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
        "finapp.accounting.sweeper.enabled=false"})
@DisplayName("the accounting alert fires when its condition breaks, and only then (P14-TSK-017)")
class AccountingAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/accounting.yml";
    private static final String STALLED = "finapp_accounting_close_run_stalled";

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("the published name is the code's constant; the meter is there with the schedule off, carrying no tag")
    void theSeriesIsTheCodes() {
        assertThat(CloseRunStallMetrics.STALLED.replace('.', '_')).isEqualTo(STALLED);
        assertThat(samples().stream().filter(sample -> sample.name().equals(STALLED)).toList())
                .as("off is exactly when it must speak").singleElement()
                .satisfies(sample -> assertThat(sample.labels()).containsOnlyKeys("job", "instance"));
    }

    @Test
    @DisplayName("the plan's meters table names the series and both its alerts - PlannedMetersExistTest reads it once the phase is complete,"
            + " and until then this holds it")
    void thePlanNamesIt() throws java.io.IOException {
        String row = java.nio.file.Files.readAllLines(RepositoryPaths.locate("docs/project/PHASE_14_PLAN.md")).stream()
                .filter(line -> line.startsWith("| `" + CloseRunStallMetrics.STALLED + "` |")).findFirst()
                .orElseThrow(() -> new AssertionError("the plan's table has no row for " + CloseRunStallMetrics.STALLED));
        assertThat(row).contains("`AccountingCloseRunStalled`").contains("`AccountingCloseRunStallUnreadable`");
    }

    @Test
    @DisplayName("healthy, nothing fires; one stalled lock fires the stall alone, an unreadable gauge the unreadable rule alone - the threshold"
            + " rule never reads NaN - and two instances publishing one number page once")
    void itFiresOnItsBreakAlone() {
        List<RuleEvaluator.Sample> healthy = set(samples(), value -> 0.0);
        assertThat(firing(healthy)).isEmpty();
        assertThat(firing(set(healthy, value -> 1.0))).containsExactly("AccountingCloseRunStalled");
        assertThat(firing(set(healthy, value -> 2.0))).containsExactly("AccountingCloseRunStalled");
        assertThat(firing(set(healthy, value -> Double.NaN))).containsExactly("AccountingCloseRunStallUnreadable");
        assertThat(rules().keySet()).containsExactly("AccountingCloseRunStalled", "AccountingCloseRunStallUnreadable");
    }

    @Test
    @DisplayName("each rule is a well-formed alert - a for, a page or ticket severity, a summary pointing at the runbook - and names no figure")
    void everyRuleIsWellFormed() {
        assertThat(ruleList()).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank();
            assertThat(String.valueOf(map(rule.get("annotations")).get("description"))).contains("OPERATIONS_RUNBOOK.md section 10");
            assertThat(String.valueOf(rule.get("expr"))).doesNotContain("period=").doesNotContain("amount");
        });
    }

    // -----------------------------------------------------------------

    private List<RuleEvaluator.Sample> samples() {
        return RuleEvaluator.samples(scrape(), Map.of("job", "finapp", "instance", "host.docker.internal:8080"));
    }

    private static List<RuleEvaluator.Sample> set(List<RuleEvaluator.Sample> scrape, Function<Double, Double> value) {
        assertThat(scrape.stream().anyMatch(sample -> sample.name().equals(STALLED))).as("the rule's series is published").isTrue();
        return scrape.stream()
                .map(sample -> sample.name().equals(STALLED) ? sample.withValue(value.apply(sample.value())) : sample)
                .toList();
    }

    private Set<String> firing(List<RuleEvaluator.Sample> scrape) {
        Set<String> firing = new TreeSet<>();
        rules().forEach((alert, expr) -> {
            if (RuleEvaluator.fires(expr, scrape, scrape)) {
                firing.add(alert);
            }
        });
        return firing;
    }

    private String scrape() {
        PrometheusMeterRegistry prometheus = registry instanceof PrometheusMeterRegistry direct ? direct
                : (PrometheusMeterRegistry) ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                        .getRegistries().stream().filter(PrometheusMeterRegistry.class::isInstance).findFirst()
                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));
        return prometheus.scrape();
    }

    private static Map<String, String> rules() {
        Map<String, String> rules = new LinkedHashMap<>();
        ruleList().forEach(rule -> rules.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        return rules;
    }

    private static List<Map<String, Object>> ruleList() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(RepositoryPaths.locate(RULES)));
        Map<String, Object> document = factory.getObject();
        assertThat(document).as("%s must parse", RULES).isNotNull();
        Map<String, Object> group = list(document.get("groups")).stream().map(AccountingAlertRulesTest::map)
                .filter(candidate -> "accounting".equals(candidate.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no rules group named accounting"));
        return list(group.get("rules")).stream().map(AccountingAlertRulesTest::map).collect(Collectors.toList());
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
