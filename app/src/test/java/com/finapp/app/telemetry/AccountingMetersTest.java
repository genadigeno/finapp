package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.app.accounting.AccountingBreakAgeMetrics;
import com.finapp.app.accounting.AccountingBreakMetrics;
import com.finapp.app.accounting.AccountingGaugeRead;
import com.finapp.app.accounting.CloseRunMetrics;
import com.finapp.app.accounting.RegulatoryAttentionMetrics;
import com.finapp.app.accounting.RegulatorySubmissionMetrics;
import com.finapp.app.accounting.ReportRunMetrics;
import com.finapp.ledger.PostingObserver;
import com.finapp.platform.metrics.MetricNames;
import com.finapp.platform.testing.RepositoryPaths;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code PHASE_14_PLAN.md} section 16's table is exact, both ways (`P14-TSK-020`): every row a meter a fresh instance registers, with the row's tag keys, and every {@code finapp.accounting.*} meter the application registers a row; every alert the table names a rule of the
 * file, and every rule of the file named by the table. Nothing is kept by name here: the rows come from the plan, the meters from the live registry, the rules from the YAML, so a meter added without a row, or a row without a meter, fails with no edit here.
 *
 * <p>The context boots with nothing configured and no reachable database: exactly the "freshly started instance" the observability criterion names, armed ahead of the phase flip, after which {@code PlannedMetersExistTest} reads the same table.
 *
 * <p>And the tag values (ADR-0072, {@code INV-AUD-02}): every tag of every meter of the table holds a value of a closed set the code owns: never an id, an amount or a name.
 */
@org.junit.jupiter.api.Tag("slice")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent", "finapp.accounting.sweeper.enabled=false"})
@DisplayName("section 16's meters, exact both ways, with closed tags (P14-TSK-020)")
class AccountingMetersTest {

    private static final Pattern ROW = Pattern.compile("^\\|\\s*`(finapp\\.[a-z][a-z0-9.]*)`\\s*\\|(.*)\\|([^|]*)\\|([^|]*)\\|\\s*$");
    private static final Pattern ALERT = Pattern.compile("`([A-Z][A-Za-z]+)`");

    @Autowired private MeterRegistry registry;
    @Autowired private ReportDefinitions definitions;

    /** A row of the table: its meter, its tag keys, the alerts it names. */
    private record Row(String meter, Set<String> tags, Set<String> alerts) {}

    @Test
    @DisplayName("every row is a registered meter, and every registered finapp.accounting meter is a row")
    void theTableIsExactBothWays() {
        List<Row> rows = rows();
        assertThat(rows).as("the table parses, or this checks nothing").hasSizeGreaterThanOrEqualTo(20);
        assertThat(rows.stream().map(Row::meter).toList()).as("one row per meter").doesNotHaveDuplicates();
        Set<String> registered = registry.getMeters().stream().map(meter -> meter.getId().getName()).collect(Collectors.toCollection(TreeSet::new));
        assertThat(registered).as("every row registered on a fresh instance").containsAll(rows.stream().map(Row::meter).toList());
        assertThat(registered.stream().filter(name -> name.startsWith("finapp.accounting.")).toList())
                .as("every accounting meter a row of the table")
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(Row::meter).filter(name -> name.startsWith("finapp.accounting.")).toList());
    }

    @Test
    @DisplayName("each row's tag keys are the meter's own")
    void eachRowsTagsAreTheMeters() {
        Map<String, Set<String>> keys = new TreeMap<>();
        registry.getMeters().forEach(meter -> keys.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                .addAll(meter.getId().getTags().stream().map(Tag::getKey).toList()));
        for (Row row : rows()) {
            assertThat(keys.get(row.meter())).as("%s's tag keys", row.meter()).isEqualTo(row.tags());
        }
    }

    @Test
    @DisplayName("the table's alerts are the file's rules, both ways")
    void theTablesAlertsAreTheRules() {
        Set<String> named = rows().stream().flatMap(row -> row.alerts().stream()).collect(Collectors.toCollection(TreeSet::new));
        assertThat(named).isEqualTo(new TreeSet<>(AccountingAlertRulesTest.rules().keySet()));
    }

    @Test
    @DisplayName("each category-tagged series is there for every value of its category, from startup: the code's list, read from the code")
    void everyCategoryIsRegisteredFromStartup() {
        for (CloseCheckCode check : CloseCheckCode.values()) {
            for (String outcome : CloseRunMetrics.OUTCOMES) {
                assertThat(registry.find(CloseRunMetrics.CHECK).tag("check", check.name()).tag("outcome", outcome).counters()).as(check + " " + outcome).hasSize(1);
            }
        }
        for (String leg : CloseRunMetrics.LEGS) {
            assertThat(registry.find(CloseRunMetrics.LEG_FAILED).tag("leg", leg).counters()).as(leg).hasSize(1);
        }
        for (BreakType type : BreakType.values()) {
            assertThat(registry.find(AccountingBreakMetrics.OPEN).tag("type", type.name()).gauges()).as(type.name()).hasSize(1);
            assertThat(registry.find(AccountingBreakAgeMetrics.AGED).tag("type", type.name()).gauges()).as(type.name()).hasSize(1);
        }
        for (String kind : RegulatoryAttentionMetrics.KINDS) {
            assertThat(registry.find(RegulatoryAttentionMetrics.ATTENTION).tag("kind", kind).gauges()).as(kind).hasSize(1);
        }
        for (String outcome : RegulatorySubmissionMetrics.OUTCOMES) {
            assertThat(registry.find(RegulatorySubmissionMetrics.SUBMISSION).tag("outcome", outcome).counters()).as(outcome).hasSize(1);
        }
        for (String definition : definitions.codes()) {
            assertThat(registry.find(ReportRunMetrics.DIVERGED).tag("definition", definition).counters()).as(definition).hasSize(1);
            for (String outcome : ReportRunMetrics.OUTCOMES) {
                assertThat(registry.find(ReportRunMetrics.RUN).tag("definition", definition).tag("outcome", outcome).counters()).as(definition + " " + outcome).hasSize(1);
            }
        }
        for (String refusal : ReportRunMetrics.REFUSALS) {
            assertThat(registry.find(ReportRunMetrics.REFUSED).tag("outcome", refusal).counters()).as(refusal).hasSize(1);
        }
        for (PostingObserver.Command command : PostingObserver.Command.values()) {
            String tag = command.name().toLowerCase(Locale.ROOT);
            assertThat(registry.find(LedgerWriteMeters.CUTOFF_REFUSED).tag("command", tag).counters()).as(tag).hasSize(1);
            assertThat(registry.find(LedgerWriteMeters.CUTOFF_FLOORED).tag("command", tag).counters()).as(tag).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ the tags

    @Test
    @DisplayName("every tag of every meter of the table holds a value of the closed set its key admits - never an id, an amount or a name; discovered from the live registry")
    void everyTagValueIsClosed() {
        Set<String> tabled = rows().stream().map(Row::meter).collect(Collectors.toSet());
        List<Meter> meters = registry.getMeters().stream().filter(meter -> tabled.contains(meter.getId().getName())).toList();
        assertThat(meters).as("the guard sees the table's meters").hasSizeGreaterThan(100);
        assertThat(violations(meters, vocabularies())).isEmpty();
    }

    @Test
    @DisplayName("the rule has teeth: a tag carrying an id, an amount, a name or an unknown key is refused")
    void theTagRuleHasTeeth() {
        SimpleMeterRegistry planted = new SimpleMeterRegistry();
        io.micrometer.core.instrument.Counter.builder("finapp.accounting.planted").tag("outcome", "0192e1a0-0000-7000-8000-000000000018").register(planted);
        io.micrometer.core.instrument.Counter.builder("finapp.accounting.planted").tag("kind", "ada lovelace").register(planted);
        io.micrometer.core.instrument.Counter.builder("finapp.accounting.planted").tag("definition", "4210.00").register(planted);
        io.micrometer.core.instrument.Counter.builder("finapp.accounting.planted").tag("period", "2026-10").register(planted);
        io.micrometer.core.instrument.Counter.builder("finapp.accounting.planted").tag("outcome", "contrary").register(planted);
        List<String> found = violations(planted.getMeters(), vocabularies());
        assertThat(found).hasSize(4).anyMatch(violation -> violation.contains("0192e1a0")).anyMatch(violation -> violation.contains("ada lovelace"))
                .anyMatch(violation -> violation.contains("4210.00")).anyMatch(violation -> violation.contains("period"));
    }

    private Map<String, Set<String>> vocabularies() {
        Set<String> outcomes = new TreeSet<>(CloseRunMetrics.OUTCOMES);
        outcomes.addAll(ReportRunMetrics.OUTCOMES);
        outcomes.addAll(ReportRunMetrics.REFUSALS);
        outcomes.addAll(RegulatorySubmissionMetrics.OUTCOMES);
        outcomes.addAll(lower(com.finapp.app.lending.LoanWriteOffMetrics.Outcome.values()));
        outcomes.addAll(lower(com.finapp.app.bnpl.BnplWriteOffMetrics.Outcome.values()));
        Set<String> products = new TreeSet<>(lower(com.finapp.lending.LoanProduct.values()));
        products.addAll(lower(com.finapp.bnpl.BnplProduct.values()));
        return Map.of(
                "check", names(CloseCheckCode.values()),
                "leg", Set.copyOf(CloseRunMetrics.LEGS),
                "command", lower(PostingObserver.Command.values()),
                "type", names(BreakType.values()),
                "definition", Set.copyOf(definitions.codes()),
                "kind", Set.copyOf(RegulatoryAttentionMetrics.KINDS),
                "outcome", outcomes,
                "product", products);
    }

    private static List<String> violations(List<Meter> meters, Map<String, Set<String>> vocabularies) {
        List<String> violations = new ArrayList<>();
        for (Meter meter : meters) {
            for (Tag tag : meter.getId().getTags()) {
                Set<String> vocabulary = vocabularies.get(tag.getKey());
                if (vocabulary == null) {
                    violations.add(meter.getId().getName() + " carries the key '" + tag.getKey() + "', which no vocabulary here bounds");
                } else if (!vocabulary.contains(tag.getValue())) {
                    violations.add(meter.getId().getName() + "{" + tag.getKey() + "=" + tag.getValue() + "} is not a value of its vocabulary");
                }
            }
        }
        return violations;
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> lower(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    // ------------------------------------------------------------------ the reads

    @Test
    @DisplayName("every accounting class that builds a gauge reads through AccountingGaugeRead, found by what the bytecode calls: the schedule's flag, which reads no database, the one exception")
    void everyGaugeReadIsBounded() {
        JavaClasses classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.finapp.app.accounting");
        Set<String> building = classes.stream()
                .filter(candidate -> candidate.getCodeUnitCallsFromSelf().stream().anyMatch(call -> call.getName().equals("builder")
                        && call.getTargetOwner().getName().equals("io.micrometer.core.instrument.Gauge")))
                .map(JavaClass::getName).collect(Collectors.toCollection(TreeSet::new));
        assertThat(building).as("the census").hasSizeGreaterThanOrEqualTo(8).contains("com.finapp.app.accounting.AccountingCloseBeans");
        for (String name : building) {
            if (name.equals("com.finapp.app.accounting.AccountingCloseBeans")) {
                continue;
            }
            assertThat(classes.get(name).getDirectDependenciesFromSelf().stream().map(dependency -> dependency.getTargetClass().getName()))
                    .as("%s reads through the bounded read", name).contains(AccountingGaugeRead.class.getName());
        }
    }

    // ------------------------------------------------------------------ the table

    /** {@code PHASE_14_PLAN.md} section 16's rows. The Type column may hold a pipe's worth of prose, so the row is read from its two right-hand columns. */
    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        boolean inObservability = false;
        for (String line : RepositoryPaths.read("docs/project/PHASE_14_PLAN.md").lines().toList()) {
            if (line.startsWith("## ")) {
                inObservability = line.startsWith("## 16.");
                continue;
            }
            Matcher row = ROW.matcher(line);
            if (inObservability && row.matches()) {
                Set<String> tags = Arrays.stream(row.group(3).split(",")).map(String::strip).filter(key -> !key.isEmpty() && !key.equals("—"))
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(tags).as("%s's tags are the plain keys", row.group(1)).allSatisfy(key -> assertThat(MetricNames.ALLOWED_TAG_KEYS).contains(key));
                Set<String> alerts = new TreeSet<>();
                Matcher alert = ALERT.matcher(row.group(4));
                while (alert.find()) {
                    alerts.add(alert.group(1));
                }
                rows.add(new Row(row.group(1), tags, alerts));
            }
        }
        return rows;
    }
}
