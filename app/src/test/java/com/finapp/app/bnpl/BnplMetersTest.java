package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplDelinquencyBucket;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every BNPL series {@code PHASE_12_PLAN.md} section 15 names is registered at startup with its closed tags - and no BNPL
 * series can carry a figure (`P12-TSK-026`; ADR-0072, {@code PHASE_12_PLAN.md} section 11, {@code INV-AUD-02}; lending's
 * {@code LendingMetersTest}).
 *
 * <p>The context boots with nothing configured and no reachable database: exactly the "freshly started instance" the
 * observability criterion names - armed ahead of the phase flip, after which {@code PlannedMetersExistTest} reads the same
 * table. The section's rows are counted EXACTLY (32: the twenty-nine BNPL series and the three shared ones its alerts
 * read), so a row added without a meter, or a meter renamed, fails here.
 *
 * <p>The needle: every tag of every {@code finapp.bnpl.} series has a key from BNPL's closed set and a value from the
 * closed set that key admits - a product, a plan, a status, a bucket or a verdict, each an enum constant of the code, or a
 * lowercase outcome, kind or step word, the only free-form values, letters and underscores alone. No amount, rate,
 * balance, DPD, party, merchant, purchase, obligation or agreement id can be carried by any of them, under any key (the
 * probe: tag a counter with an amount and this is red).
 *
 * <p>And the dashboard's BNPL row reads every BNPL series of the section - each in the form this instance publishes it.
 */
@org.junit.jupiter.api.Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every BNPL series is registered at startup with closed tags, and none can carry a figure (P12-TSK-026)")
class BnplMetersTest {

    private static final Pattern PLANNED_METER = Pattern.compile("^\\|\\s*`(finapp\\.[a-z][a-z0-9.]*)`");

    /** A lowercase word - an outcome, a kind or a step: letters and underscores only, never a digit, a hyphen or a dot. */
    private static final Pattern WORD = Pattern.compile("[a-z][a-z_]{0,39}");

    private static final Pattern SERIES = Pattern.compile("\\b(finapp_[a-z0-9_]+)\\b");

    /** The section's rows another context owns - BNPL's alerts and gate read them; the lending row charts the first two. */
    private static final Set<String> SHARED = Set.of("finapp.lending.capital.headroom.low", "finapp.credit.exposure.census",
            "finapp.ledger.negative.positions");

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("section 15's 32 rows are each a registered series on a fresh instance, with the tag keys the plan gives it")
    void everySectionFifteenSeriesIsRegisteredWithItsTags() {
        List<String> planned = plannedSeries();
        assertThat(planned).as("PHASE_12_PLAN.md section 15 has exactly 32 series rows").hasSize(32)
                .doesNotHaveDuplicates().containsAll(SHARED);
        Map<String, Set<String>> registered = new TreeMap<>();
        registry.getMeters().forEach(meter -> registered.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                .addAll(meter.getId().getTags().stream().map(Tag::getKey).toList()));
        assertThat(registered.keySet()).containsAll(planned);

        Map<String, Set<String>> keys = new TreeMap<>();
        for (String name : List.of("application", "eligibility", "offer", "billing", "payoff")) {
            keys.put("finapp.bnpl." + name, Set.of("product", "outcome"));
        }
        keys.put("finapp.bnpl.origination", Set.of("kind", "outcome"));
        keys.put("finapp.bnpl.purchase", Set.of("product", "plan", "outcome"));
        keys.put("finapp.bnpl.servicing", Set.of("step", "outcome"));
        keys.put("finapp.bnpl.repayment", Set.of("kind", "outcome"));
        keys.put("finapp.bnpl.refund", Set.of("kind", "outcome"));
        for (String name : List.of("accrual", "collection", "fee", "dispute", "reversal", "waiver")) {
            keys.put("finapp.bnpl." + name, Set.of("outcome"));
        }
        keys.put("finapp.bnpl.delinquency", Set.of("product", "bucket"));
        keys.put("finapp.bnpl.default", Set.of("product"));
        keys.put("finapp.bnpl.obligations", Set.of("product", "status"));
        keys.put("finapp.bnpl.terms.active", Set.of("product"));
        for (String name : List.of("finapp.bnpl.threeway.proof", "finapp.bnpl.subledger.proof", "finapp.bnpl.replay",
                "finapp.credit.exposure.census")) {
            keys.put(name, Set.of("verdict"));
        }
        keys.put("finapp.ledger.negative.positions", Set.of("purpose"));
        for (String name : planned) {
            assertThat(registered.get(name)).as("%s's tag keys", name).isEqualTo(keys.getOrDefault(name, Set.of()));
        }
        // Each gauge-by-category is registered for every value of its category, from startup.
        for (BnplProduct product : BnplProduct.values()) {
            String lower = product.name().toLowerCase(Locale.ROOT);
            assertThat(registry.find("finapp.bnpl.terms.active").tag("product", product.name()).gauges()).isNotEmpty();
            assertThat(registry.find("finapp.bnpl.default").tag("product", lower).gauges()).isNotEmpty();
            for (BnplDelinquencyBucket bucket : BnplDelinquencyBucket.values()) {
                assertThat(registry.find("finapp.bnpl.delinquency").tag("product", lower)
                        .tag("bucket", bucket.name().toLowerCase(Locale.ROOT)).gauges()).as(bucket.name()).isNotEmpty();
            }
            for (BnplObligationStatus status : BnplObligationStatus.values()) {
                assertThat(registry.find("finapp.bnpl.obligations").tag("product", lower)
                        .tag("status", status.name().toLowerCase(Locale.ROOT)).gauges()).as(status.name()).isNotEmpty();
            }
            for (BnplPlan plan : product.plans()) {
                assertThat(registry.find("finapp.bnpl.purchase").tag("product", product.name()).tag("plan", plan.name())
                        .tag("outcome", "financed").counters()).as(plan.name()).isNotEmpty();
            }
        }
        for (String verdict : List.of("HOLDS", "FAILS")) {
            assertThat(registry.find("finapp.bnpl.threeway.proof").tag("verdict", verdict).gauges()).isNotEmpty();
            assertThat(registry.find("finapp.bnpl.subledger.proof").tag("verdict", verdict).gauges()).isNotEmpty();
        }
        assertThat(registry.find("finapp.bnpl.replay").tag("verdict", "DIVERGED").gauges()).isNotEmpty();
        assertThat(registry.find("finapp.bnpl.servicing").tag("step", "service").tag("outcome", "failed").counters())
                .isNotEmpty();
        assertThat(registry.find("finapp.bnpl.dispute").tag("outcome", "sweep_failed").counters()).isNotEmpty();
        assertThat(registry.find("finapp.bnpl.accrual").tag("outcome", "failed").counters()).isNotEmpty();
        assertThat(registry.find("finapp.ledger.negative.positions").tag("purpose", "MERCHANT_PAYABLE").gauges())
                .isNotEmpty();
    }

    @Test
    @DisplayName("every BNPL tag is a closed key with a closed value - an enum constant, or a word of letters alone - so no"
            + " amount, rate, balance, DPD, party, merchant, purchase or agreement id can be carried")
    void noBnplSeriesCanCarryAFigure() {
        List<Meter> bnpl = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("finapp.bnpl.")).toList();
        assertThat(bnpl).as("the BNPL meters are published and read").hasSizeGreaterThan(150);
        Map<String, Pattern> closed = closedValues();
        for (Meter meter : bnpl) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(closed).as("%s carries tag %s, not one of BNPL's closed keys", meter.getId().getName(),
                        tag.getKey()).containsKey(tag.getKey());
                assertThat(tag.getValue()).as("%s's %s", meter.getId().getName(), tag.getKey())
                        .matches(closed.get(tag.getKey()));
            }
        }
    }

    @Test
    @DisplayName("the dashboard's BNPL row: nine panels reading every BNPL series of section 15 in the form this instance"
            + " publishes it")
    void theDashboardRowReadsEverySeries() {
        JsonNode panels = JsonMapper.builder().build()
                .readTree(RepositoryPaths.read("infra/grafana/dashboards/finapp-platform.json")).path("panels");
        List<String> queries = new ArrayList<>();
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("BNPL")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
                panel.path("targets").valueStream().map(target -> target.path("expr").stringValue()).forEach(queries::add);
            }
        }
        assertThat(count).as("the BNPL row's panels").isEqualTo(9);
        Set<String> queried = new TreeSet<>();
        for (String query : queries) {
            Matcher series = SERIES.matcher(query);
            while (series.find()) {
                queried.add(series.group(1));
            }
        }
        Set<String> published = publishedNames();
        assertThat(published).as("every series the row queries is published by a fresh instance").containsAll(queried);
        for (String planned : plannedSeries()) {
            if (planned.startsWith("finapp.lending.") || planned.startsWith("finapp.credit.")) {
                continue; // the lending row's
            }
            String base = planned.replace('.', '_');
            assertThat(queried).as("the BNPL row reads %s", planned)
                    .containsAnyOf(base, base + "_total", base + "_seconds");
        }
    }

    /** Each key BNPL's meters carry, and the closed set of values it admits. */
    private static Map<String, Pattern> closedValues() {
        return Map.of(
                "product", oneOf(Arrays.stream(BnplProduct.values()).map(Enum::name)
                        .flatMap(name -> Stream.of(name, name.toLowerCase(Locale.ROOT))).collect(Collectors.toSet())),
                "plan", oneOf(Arrays.stream(BnplPlan.values()).map(Enum::name).collect(Collectors.toSet())),
                "status", oneOf(Arrays.stream(BnplObligationStatus.values())
                        .map(value -> value.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet())),
                "bucket", oneOf(Arrays.stream(BnplDelinquencyBucket.values())
                        .map(value -> value.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet())),
                "verdict", oneOf(Set.of("HOLDS", "FAILS", "IDENTICAL", "DIVERGED")),
                "outcome", WORD,
                "kind", WORD,
                "step", WORD);
    }

    private static Pattern oneOf(Set<String> values) {
        return Pattern.compile(values.stream().map(Pattern::quote).collect(Collectors.joining("|")));
    }

    /** The sample names of this instance's Prometheus scrape. */
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

    static List<String> plannedSeries() {
        List<String> planned = new ArrayList<>();
        boolean inObservability = false;
        for (String line : RepositoryPaths.read("docs/project/PHASE_12_PLAN.md").lines().toList()) {
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
}
