package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CensusKind;
import com.finapp.risk.ProofVerdict;
import com.finapp.risk.RegulatoryFilingProvider;
import com.finapp.risk.RegulatoryReportStatus;
import com.finapp.risk.RescreeningObserver;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.SignalKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every risk series {@code PHASE_13_PLAN.md} section 15 names is registered at startup with its closed tags - and every
 * {@code finapp.risk.*} series the application registers is one of the section's rows (`P13-TSK-024`; bnpl's
 * {@code BnplMetersTest}, the plan's "a {@code RiskMetersTest} counts this table's rows").
 *
 * <p>The context boots with nothing configured and no reachable database: exactly the "freshly started instance" the
 * observability criterion names - armed ahead of the phase flip, after which {@code PlannedMetersExistTest} reads the same
 * table. The section's rows are counted EXACTLY (33), and the reconciliation runs both ways: a row added without a meter,
 * a meter renamed, or a meter registered with no row each fail here. Each row's tag keys are the plan's, and every
 * category-tagged series is registered for every value of its category (each operation, case kind, census kind and
 * verdict, rating bucket, signal kind, schedule kind). Tag VALUES are {@code RiskMetricTagsAreBoundedTest}'s.
 *
 * <p>And the dashboard's risk row reads every risk series of the section - each in the form this instance publishes it.
 */
@org.junit.jupiter.api.Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every risk series of section 15 is registered at startup with its tags, and no other (P13-TSK-024)")
class RiskMetersTest {

    private static final Pattern PLANNED_METER = Pattern.compile("^\\|\\s*`(finapp\\.[a-z][a-z0-9.]*)`");

    private static final Pattern SERIES = Pattern.compile("\\b(finapp_[a-z0-9_]+)\\b");

    /** The section's rows and the tag keys each carries - the plan's table, as built. */
    static final Map<String, Set<String>> KEYS = keys();

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("section 15's 33 rows are each a registered series on a fresh instance with the plan's tag keys, and"
            + " every registered finapp.risk series is a row")
    void everySectionFifteenSeriesIsRegisteredWithItsTags() {
        List<String> planned = plannedSeries();
        assertThat(planned).as("PHASE_13_PLAN.md section 15 has exactly 33 series rows").hasSize(33)
                .doesNotHaveDuplicates();
        assertThat(planned).as("the table and this test's keys name the same series")
                .containsExactlyInAnyOrderElementsOf(KEYS.keySet());
        Map<String, Set<String>> registered = new TreeMap<>();
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("finapp.risk."))
                .forEach(meter -> registered.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                        .addAll(meter.getId().getTags().stream().map(Tag::getKey).toList()));
        assertThat(registered.keySet()).as("every row registered, and every registered risk series a row")
                .containsExactlyInAnyOrderElementsOf(planned);
        for (String name : planned) {
            assertThat(registered.get(name)).as("%s's tag keys", name).isEqualTo(KEYS.get(name));
        }
    }

    @Test
    @DisplayName("each category-tagged series is registered for every value of its category, from startup")
    void everyCategoryIsRegisteredEagerly() {
        for (RiskOperation operation : RiskOperation.values()) {
            if (operation.judgesAMovement()) {
                assertThat(registry.find("finapp.risk.evaluation.latency").tag("operation", operation.name()).timers())
                        .as(operation.name()).isNotEmpty();
                for (String reason : List.of("RISK_UNAVAILABLE", "NO_POLICY_ACTIVE")) {
                    assertThat(registry.find("finapp.risk.evaluation.unavailable").tag("operation", operation.name())
                            .tag("reason_code", reason).counters()).as(operation + " " + reason).isNotEmpty();
                }
                assertThat(registry.find("finapp.risk.alert.unraised").tag("operation", operation.name()).counters())
                        .as(operation.name()).isNotEmpty();
            }
            if (operation != RiskOperation.CREDIT_SIGNAL) {
                assertThat(registry.find("finapp.risk.policy.active").tag("operation", operation.name()).gauges())
                        .as(operation.name()).isNotEmpty();
            }
        }
        for (CaseKind kind : CaseKind.values()) {
            for (String gauge : List.of("finapp.risk.case.backlog", "finapp.risk.case.age")) {
                assertThat(registry.find(gauge).tag("kind", kind.name()).gauges()).as(gauge + " " + kind).isNotEmpty();
            }
            assertThat(registry.find("finapp.risk.case.resolution").tag("kind", kind.name()).timers()).isNotEmpty();
        }
        for (CensusKind kind : CensusKind.values()) {
            for (ProofVerdict verdict : ProofVerdict.values()) {
                assertThat(registry.find("finapp.risk.census").tag("kind", kind.name()).tag("verdict", verdict.name())
                        .gauges()).as(kind + " " + verdict).isNotEmpty();
            }
        }
        assertThat(registry.find("finapp.risk.census").tag("kind", "RESCREENING").gauges())
                .as("-022's routed census row, built here").hasSize(ProofVerdict.values().length);
        for (RiskRating rating : RiskRating.values()) {
            assertThat(registry.find("finapp.risk.profile.rating").tag("bucket", rating.name()).gauges())
                    .as(rating.name()).isNotEmpty();
        }
        assertThat(registry.find("finapp.risk.profile.rating").tag("bucket", RiskProfileMetrics.UNRATED).gauges())
                .isNotEmpty();
        for (SignalKind kind : SignalKind.values()) {
            for (RiskSignals.Outcome outcome : RiskSignals.Outcome.values()) {
                assertThat(registry.find("finapp.risk.signal").tag("kind", kind.name())
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).counters()).as(kind + " " + outcome)
                        .isNotEmpty();
            }
        }
        for (RescreeningObserver.Conclusion conclusion : RescreeningObserver.Conclusion.values()) {
            assertThat(registry.find("finapp.risk.rescreening").tag("outcome", conclusion.tag()).counters())
                    .as(conclusion.name()).isNotEmpty();
        }
        for (RegulatoryReportStatus status : RegulatoryReportStatus.values()) {
            assertThat(registry.find("finapp.risk.report").tag("status", status.name()).counters()).isNotEmpty();
        }
        for (RegulatoryFilingProvider.Outcome outcome : RegulatoryFilingProvider.Outcome.values()) {
            assertThat(registry.find("finapp.risk.report.filing").tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .counters()).isNotEmpty();
        }
        assertThat(registry.find("finapp.risk.report.filing").tag("outcome", "failed").counters()).isNotEmpty();
        assertThat(kinds("finapp.risk.sweeper.enabled")).containsExactlyInAnyOrder("monitoring", "action", "rescreening");
        assertThat(kinds("finapp.risk.sweeper.claim.failed"))
                .containsExactlyInAnyOrder("monitoring", "action", "report", "rescreening");
        for (String outcome : List.of("completed", "failed")) {
            assertThat(registry.find("finapp.risk.proof.reading").tag("outcome", outcome).counters()).isNotEmpty();
        }
        assertThat(registry.find("finapp.risk.velocity.proof").gauges()).hasSize(3);
    }

    @Test
    @DisplayName("the dashboard's risk row: nine panels reading every risk series of section 15 in the form this instance"
            + " publishes it")
    void theDashboardRowReadsEverySeries() {
        JsonNode panels = JsonMapper.builder().build()
                .readTree(RepositoryPaths.read("infra/grafana/dashboards/finapp-platform.json")).path("panels");
        List<String> queries = new ArrayList<>();
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("Risk")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
                panel.path("targets").valueStream().map(target -> target.path("expr").stringValue()).forEach(queries::add);
            }
        }
        assertThat(count).as("the risk row's panels").isEqualTo(9);
        Set<String> queried = new TreeSet<>();
        for (String query : queries) {
            Matcher series = SERIES.matcher(query);
            while (series.find()) {
                queried.add(series.group(1));
            }
        }
        assertThat(publishedNames()).as("every series the row queries is published by a fresh instance")
                .containsAll(queried);
        for (String planned : plannedSeries()) {
            String base = planned.replace('.', '_');
            assertThat(queried).as("the risk row reads %s", planned).anyMatch(name -> name.equals(base)
                    || name.equals(base + "_total") || name.startsWith(base + "_seconds"));
        }
    }

    private Set<String> kinds(String name) {
        Set<String> kinds = new TreeSet<>();
        registry.find(name).meters().forEach(meter -> kinds.add(meter.getId().getTag("kind")));
        return kinds;
    }

    private Set<String> publishedNames() {
        PrometheusMeterRegistry prometheus = registry instanceof PrometheusMeterRegistry direct ? direct
                : (PrometheusMeterRegistry) ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                        .getRegistries().stream().filter(PrometheusMeterRegistry.class::isInstance).findFirst()
                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));
        Set<String> names = new TreeSet<>();
        prometheus.scrape().lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("[{ ]", 2)[0]).forEach(names::add);
        return names;
    }

    /** The series rows of {@code PHASE_13_PLAN.md} section 15, in table order. */
    static List<String> plannedSeries() {
        List<String> planned = new ArrayList<>();
        boolean inObservability = false;
        for (String line : RepositoryPaths.read("docs/project/PHASE_13_PLAN.md").lines().toList()) {
            if (line.startsWith("## ")) {
                inObservability = line.startsWith("## 15.");
                continue;
            }
            Matcher row = PLANNED_METER.matcher(line);
            if (inObservability && row.find()) {
                planned.add(row.group(1));
            }
        }
        return planned;
    }

    private static Map<String, Set<String>> keys() {
        Map<String, Set<String>> keys = new TreeMap<>();
        keys.put("finapp.risk.evaluation", Set.of("operation", "verdict"));
        keys.put("finapp.risk.evaluation.latency", Set.of("operation"));
        keys.put("finapp.risk.evaluation.unavailable", Set.of("operation", "reason_code"));
        keys.put("finapp.risk.rule.hit", Set.of("operation", "rule"));
        keys.put("finapp.risk.challenge", Set.of("operation", "outcome"));
        keys.put("finapp.risk.override", Set.of("outcome"));
        keys.put("finapp.risk.alert", Set.of("kind", "severity"));
        keys.put("finapp.risk.alert.suppressed", Set.of("reason_code"));
        keys.put("finapp.risk.alert.unraised", Set.of("operation"));
        keys.put("finapp.risk.case", Set.of("kind", "status"));
        keys.put("finapp.risk.collections.unplaced", Set.of());
        keys.put("finapp.risk.case.backlog", Set.of("kind"));
        keys.put("finapp.risk.case.age", Set.of("kind"));
        keys.put("finapp.risk.case.resolution", Set.of("kind"));
        keys.put("finapp.risk.action", Set.of("kind", "outcome"));
        keys.put("finapp.risk.action.failed", Set.of("kind"));
        keys.put("finapp.risk.report", Set.of("status"));
        keys.put("finapp.risk.report.filing", Set.of("outcome"));
        keys.put("finapp.risk.policy.active", Set.of("operation"));
        keys.put("finapp.risk.monitoring.lag", Set.of("source"));
        keys.put("finapp.risk.monitoring.unclassified", Set.of());
        keys.put("finapp.risk.monitoring.subject", Set.of("outcome"));
        keys.put("finapp.risk.monitoring.unplaced", Set.of());
        keys.put("finapp.risk.monitoring.latency", Set.of());
        keys.put("finapp.risk.velocity.proof", Set.of("verdict"));
        keys.put("finapp.risk.census", Set.of("kind", "verdict"));
        keys.put("finapp.risk.proof.reading", Set.of("outcome"));
        keys.put("finapp.risk.profile.rating", Set.of("bucket"));
        keys.put("finapp.risk.sweeper.enabled", Set.of("kind"));
        keys.put("finapp.risk.sweeper.claim.failed", Set.of("kind"));
        keys.put("finapp.risk.rescreening", Set.of("outcome"));
        keys.put("finapp.risk.rescreening.backlog", Set.of());
        keys.put("finapp.risk.signal", Set.of("kind", "outcome"));
        return Map.copyOf(keys);
    }
}
