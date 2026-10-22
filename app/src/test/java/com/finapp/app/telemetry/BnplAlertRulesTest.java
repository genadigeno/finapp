package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.bnpl.BnplAccrualMetrics;
import com.finapp.app.bnpl.BnplAwaitingDecisionMetrics;
import com.finapp.app.bnpl.BnplOriginationMetrics;
import com.finapp.app.bnpl.BnplProofMetrics;
import com.finapp.app.bnpl.BnplRepaymentMetrics;
import com.finapp.app.bnpl.BnplServicingMetrics;
import com.finapp.app.bnpl.BnplTermsMetrics;
import com.finapp.bnpl.BnplProduct;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

/**
 * Every BNPL alert FIRES when its condition breaks, and only then (`P12-TSK-026`; {@code PHASE_12_PLAN.md} section 15,
 * {@code PHASE_GATES.md} section 5 Phase 12's observability criterion: "every alert fires in a test that breaks its
 * condition, and only then"; lending's {@code LendingAlertRulesTest}).
 *
 * <p>The scrape is a freshly started instance's - the real registry's names, units and tags, nothing translated here -
 * given the target labels the committed {@code prometheus.yml} attaches. Its BNPL samples are first set to a healthy
 * reading (every proof holding, every queue and lag empty, every sweeper running, every product's terms active, no merchant
 * payable negative), on which NO rule fires; then, one alert at a time, the one sample that alert watches is broken - at
 * its threshold (silent) and one second, one unit or one NaN past it (fires) - and exactly that alert must fire.
 * {@link RuleEvaluator} evaluates the expressions; its grammar is closed, so a rule it cannot evaluate fails here.
 *
 * <p>So the test is red when a series is renamed without its rule (the rule's sample is not published), when a threshold
 * drifts from the objective {@link BnplObjectives} declares (in either direction), when a rule's aggregation or matcher
 * cannot see its break, or when one break wakes another alert. The gate's two shared alerts - lending capital headroom and
 * the credit exposure census, {@code lending.yml}'s - are proven to fire on their BNPL breaks too. The live-scrape
 * resolution with a database is {@code AlertRulesResolveTest#theBnplRulesResolve}'s.
 */
@Tag("slice")
// Pointed at a database that is not there (PlannedMetersExistTest's idiom): every series is the beans' own, from startup.
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every BNPL alert fires when its condition breaks, and only then (P12-TSK-026)")
class BnplAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/bnpl.yml";
    private static final String LENDING_RULES = "infra/prometheus/rules/lending.yml";
    private static final String PROMETHEUS = "infra/prometheus/prometheus.yml";

    private static final String ACCRUAL_LAG = "finapp_bnpl_accrual_lag_seconds";
    private static final String AWAITING_AGE = "finapp_bnpl_application_awaiting_age_seconds";
    private static final String CONTESTED_AGE = "finapp_bnpl_dispute_contested_age_seconds";
    private static final String ACCRUAL = "finapp_bnpl_accrual_total";
    private static final String ORIGINATION = "finapp_bnpl_origination_total";
    private static final String SERVICING = "finapp_bnpl_servicing_total";
    private static final String DISPUTE = "finapp_bnpl_dispute_total";
    private static final String ANOMALY = "finapp_bnpl_allocation_anomaly_total";
    private static final String THREE_WAY = "finapp_bnpl_threeway_proof";
    private static final String SUBLEDGER = "finapp_bnpl_subledger_proof";
    private static final String REPLAY = "finapp_bnpl_replay";
    private static final String TERMS = "finapp_bnpl_terms_active";
    private static final String NEGATIVE = "finapp_ledger_negative_positions";
    private static final String HEADROOM_LOW = "finapp_lending_capital_headroom_low";
    private static final String CENSUS = "finapp_credit_exposure_census";
    private static final List<String> SWEEPERS = List.of("origination", "servicing", "dispute");

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("the published names are the code's constants, and the thresholds the code's objectives - the rules read"
            + " what the meters publish, units included")
    void theRulesSeriesAreTheCodesNames() {
        assertThat(prometheusName(BnplAccrualMetrics.LAG) + "_seconds").isEqualTo(ACCRUAL_LAG);
        assertThat(prometheusName(BnplAwaitingDecisionMetrics.AWAITING_AGE) + "_seconds").isEqualTo(AWAITING_AGE);
        assertThat(prometheusName(com.finapp.app.bnpl.BnplContestedDisputeMetrics.CONTESTED_AGE) + "_seconds")
                .isEqualTo(CONTESTED_AGE);
        assertThat(prometheusName(BnplAccrualMetrics.ACCRUAL) + "_total").isEqualTo(ACCRUAL);
        assertThat(prometheusName(BnplOriginationMetrics.ORIGINATION) + "_total").isEqualTo(ORIGINATION);
        assertThat(prometheusName(BnplServicingMetrics.SERVICING) + "_total").isEqualTo(SERVICING);
        assertThat(prometheusName(com.finapp.app.bnpl.BnplDisputeMetrics.DISPUTE) + "_total").isEqualTo(DISPUTE);
        assertThat(prometheusName(BnplRepaymentMetrics.ANOMALY) + "_total").isEqualTo(ANOMALY);
        assertThat(prometheusName(BnplProofMetrics.THREE_WAY)).isEqualTo(THREE_WAY);
        assertThat(prometheusName(BnplProofMetrics.SUBLEDGER)).isEqualTo(SUBLEDGER);
        assertThat(prometheusName(BnplProofMetrics.REPLAY)).isEqualTo(REPLAY);
        assertThat(prometheusName(BnplTermsMetrics.ACTIVE)).isEqualTo(TERMS);
        // P12-TSK-017's hand-off: the lag's alert at one day - the metric's own declaration and the objective agree.
        assertThat(BnplObjectives.ACCRUAL_LAG).isEqualTo(BnplAccrualMetrics.LAG_ALERT);
    }

    @Test
    @DisplayName("on a healthy reading no BNPL alert fires - offered or not")
    void nothingFiresWhenHealthy() {
        List<RuleEvaluator.Sample> healthy = healthy(targetLabels(false));
        assertThat(firing(rules(RULES, "bnpl"), healthy, healthy)).isEmpty();
        List<RuleEvaluator.Sample> offered = healthy(targetLabels(true));
        assertThat(firing(rules(RULES, "bnpl"), offered, offered)).isEmpty();
    }

    @Test
    @DisplayName("every alert fires when, and only when, its own sample breaks its condition - each threshold the declared"
            + " objective, every NaN gauge the unreadable rule's alone, the terms rule only where the target offers BNPL")
    void everyAlertFiresWhenItsConditionBreaks() {
        Map<String, String> rules = rules(RULES, "bnpl");
        Map<String, Boolean> covered = new TreeMap<>();
        List<RuleEvaluator.Sample> healthy = healthy(targetLabels(false));
        List<RuleEvaluator.Sample> offered = healthy(targetLabels(true));

        threshold(rules, covered, "BnplAccrualLagging", healthy, named(ACCRUAL_LAG), BnplObjectives.ACCRUAL_LAG.toSeconds());
        threshold(rules, covered, "BnplApplicationAwaitingTooLong", healthy, named(AWAITING_AGE),
                BnplObjectives.awaitingDecision().toSeconds());
        // The Phase 12 -> 13 gate's A7-2: a contested dispute awaiting an operator longer than the objective.
        threshold(rules, covered, "BnplDisputeContestedTooLong", healthy, named(CONTESTED_AGE),
                BnplObjectives.CONTESTED_DISPUTE.toSeconds());
        threshold(rules, covered, "BnplThreeWayProofFailing", healthy, verdict(THREE_WAY, "FAILS"), 0);
        threshold(rules, covered, "BnplSubledgerProofFailing", healthy, verdict(SUBLEDGER, "FAILS"), 0);
        threshold(rules, covered, "BnplReplayDiverged", healthy, verdict(REPLAY, "DIVERGED"), 0);
        threshold(rules, covered, "MerchantPayableNegative", healthy,
                named(NEGATIVE).and(sample -> "MERCHANT_PAYABLE".equals(sample.labels().get("purpose"))), 0);
        // A wallet below zero is not a merchant's debt: the rule does not read it.
        List<RuleEvaluator.Sample> wallet = set(healthy,
                named(NEGATIVE).and(sample -> "CUSTOMER_WALLET".equals(sample.labels().get("purpose"))), value -> 3.0);
        assertThat(firing(rules, wallet, wallet)).isEmpty();

        // A counter: one anomaly between two scrapes fires; none does not.
        counter(rules, covered, "BnplAllocationAnomaly", healthy, named(ANOMALY));
        // The failed-step counters: one failure between two scrapes fires; an outcome that is not a failure does not.
        counter(rules, covered, "BnplAccrualFailing", healthy, outcome(ACCRUAL, "failed"));
        assertThat(firing(rules, healthy, set(healthy, outcome(ACCRUAL, "posted"), value -> value + 1))).isEmpty();
        for (String kind : List.of("application", "offer")) {
            assertThat(firing(rules, healthy, set(healthy, outcome(ORIGINATION, "failed").and(sample ->
                    kind.equals(sample.labels().get("kind"))), value -> value + 1))).as(kind)
                    .containsExactly("BnplOriginationStepFailing");
        }
        covered.put("BnplOriginationStepFailing", true);
        counter(rules, covered, "BnplServicingStepFailing", healthy,
                outcome(SERVICING, "failed").and(sample -> "service".equals(sample.labels().get("step"))));
        assertThat(firing(rules, healthy, set(healthy, outcome(SERVICING, "serviced"), value -> value + 1))).isEmpty();
        counter(rules, covered, "BnplDisputeDeadlineStepFailing", healthy, outcome(DISPUTE, "sweep_failed"));
        assertThat(firing(rules, healthy, set(healthy, outcome(DISPUTE, "opened"), value -> value + 1))).isEmpty();
        // The Phase 12 -> 13 gate's A7-1: a dispute's refund that cannot post - its own alert, never the step-failure one.
        counter(rules, covered, "BnplDisputeRefundNotPostable", healthy, outcome(DISPUTE, "not_postable"));
        assertThat(firing(rules, healthy, set(healthy, outcome(DISPUTE, "wallet_unavailable"), value -> value + 1)))
                .as("an opening refused for want of a wallet posts nothing and is owed nothing").isEmpty();

        // The terms: a product with none ACTIVE fires where the target offers BNPL - and never where it does not.
        for (BnplProduct product : BnplProduct.values()) {
            Predicate<RuleEvaluator.Sample> terms = named(TERMS).and(sample -> product.name().equals(
                    sample.labels().get("product")));
            assertThat(firing(rules, offered, set(offered, terms, value -> 0.0))).as(product.name())
                    .containsExactly("BnplTermsNoneActive");
            assertThat(firing(rules, healthy, set(healthy, terms, value -> 0.0)))
                    .as("production's expected state - no ACTIVE terms - is silent where BNPL is not offered").isEmpty();
        }
        covered.put("BnplTermsNoneActive", true);

        for (String sweeper : SWEEPERS) {
            String alert = "Bnpl" + Character.toUpperCase(sweeper.charAt(0)) + sweeper.substring(1) + "SweeperOff";
            List<RuleEvaluator.Sample> off = set(healthy, named("finapp_bnpl_" + sweeper + "_sweeper_enabled"),
                    value -> 0.0);
            assertThat(firing(rules, off, off)).as(sweeper).containsExactly(alert);
            covered.put(alert, true);
        }

        // Every gauge read from the database, unreadable: the unreadable rule alone - no threshold rule reads NaN.
        for (String gauge : List.of(THREE_WAY, SUBLEDGER, REPLAY, ACCRUAL_LAG, AWAITING_AGE, CONTESTED_AGE)) {
            List<RuleEvaluator.Sample> unreadable = set(healthy, named(gauge), value -> Double.NaN);
            assertThat(firing(rules, unreadable, unreadable)).as(gauge).containsExactly("BnplGaugesUnreadable");
        }
        List<RuleEvaluator.Sample> termsUnreadable = set(offered, named(TERMS), value -> Double.NaN);
        assertThat(firing(rules, termsUnreadable, termsUnreadable)).containsExactly("BnplGaugesUnreadable");
        covered.put("BnplGaugesUnreadable", true);

        assertThat(covered.keySet()).as("every rule in the file is broken by a case here, and nothing else is")
                .isEqualTo(rules.keySet());
    }

    @Test
    @DisplayName("the gate's shared alerts - lending capital headroom and the credit exposure census - fire on their"
            + " breaks from this instance's scrape, and only they")
    void theSharedAlertsFireOnTheirBreaks() {
        Map<String, String> lending = rules(LENDING_RULES, "lending");
        Map<String, String> shared = new LinkedHashMap<>();
        shared.put("LendingCapitalHeadroomLow", lending.get("LendingCapitalHeadroomLow"));
        shared.put("CreditExposureCensusFailing", lending.get("CreditExposureCensusFailing"));
        assertThat(shared.values()).as("lending.yml carries both").doesNotContainNull();
        List<RuleEvaluator.Sample> healthy = healthy(targetLabels(false));
        assertThat(firing(shared, healthy, healthy)).isEmpty();
        List<RuleEvaluator.Sample> low = set(healthy, named(HEADROOM_LOW), value -> 1.0);
        assertThat(firing(shared, low, low)).containsExactly("LendingCapitalHeadroomLow");
        List<RuleEvaluator.Sample> census = set(healthy, verdict(CENSUS, "FAILS"), value -> 1.0);
        assertThat(firing(shared, census, census)).containsExactly("CreditExposureCensusFailing");
    }

    @Test
    @DisplayName("each rule is a well-formed alert - a for, a page or ticket severity, a summary, a runbook section - and"
            + " names no figure")
    void everyRuleIsWellFormed() {
        List<Map<String, Object>> rules = ruleList(RULES, "bnpl");
        assertThat(rules).hasSize(rules(RULES, "bnpl").size()).hasSize(18);
        assertThat(rules).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank().isNotEqualTo("null");
            assertThat(String.valueOf(map(rule.get("annotations")).get("description")))
                    .containsPattern("OPERATIONS_RUNBOOK\\.md sections? 8\\.[0-9]");
            assertThat(String.valueOf(rule.get("expr"))).doesNotContain("agreement").doesNotContain("party")
                    .doesNotContain("merchant_id").doesNotContain("obligation_id");
        });
    }

    @Test
    @DisplayName("the runbook states the production gate and every BNPL procedure: each rule's cited section exists and"
            + " names the alert, and section 8.1 carries every precondition of the activation gate")
    void everyAlertHasItsProcedure() {
        String runbook = RepositoryPaths.read("docs/architecture/OPERATIONS_RUNBOOK.md");
        int start = runbook.indexOf("\n## 8. BNPL");
        assertThat(start).as("the runbook has a BNPL section 8").isPositive();
        int end = runbook.indexOf("\n## ", start + 1);
        String section = runbook.substring(start, end < 0 ? runbook.length() : end);
        java.util.regex.Pattern cited = java.util.regex.Pattern.compile("8\\.([0-9])");
        for (Map<String, Object> rule : ruleList(RULES, "bnpl")) {
            String alert = String.valueOf(rule.get("alert"));
            String description = String.valueOf(map(rule.get("annotations")).get("description"));
            java.util.regex.Matcher sections = cited.matcher(description.substring(description.indexOf("OPERATIONS_RUNBOOK")));
            assertThat(sections.find()).as("%s cites a section", alert).isTrue();
            do {
                String heading = "\n### 8." + sections.group(1) + " ";
                int at = section.indexOf(heading);
                assertThat(at).as("%s cites section 8.%s, which exists", alert, sections.group(1)).isPositive();
                int next = section.indexOf("\n### ", at + 1);
                assertThat(section.substring(at, next < 0 ? section.length() : next))
                        .as("section 8.%s names %s", sections.group(1), alert).contains(alert);
            } while (sections.find());
        }
        String gate = section.substring(section.indexOf("\n### 8.1 "), section.indexOf("\n### 8.2 "));
        assertThat(gate).contains("Production ships closed", "#13 and #14", "real bureau adapter",
                "credit policy version", "lending capital recognised", "Legal review of the merchant agreement",
                "Legal review of the consumer terms", "merchant-fee schedule approved", "four-eyes BNPL terms activation",
                "bnpl_offered: \"true\"");
    }

    // ------------------------------------------------------------------ cases

    /** At {@code threshold} the alert is silent; one past it, it alone fires. */
    private void threshold(Map<String, String> rules, Map<String, Boolean> covered, String alert,
            List<RuleEvaluator.Sample> healthy, Predicate<RuleEvaluator.Sample> sample, long threshold) {
        List<RuleEvaluator.Sample> at = set(healthy, sample, value -> (double) threshold);
        assertThat(firing(rules, at, at)).as("%s at its threshold %d", alert, threshold).isEmpty();
        List<RuleEvaluator.Sample> past = set(healthy, sample, value -> (double) threshold + 1);
        assertThat(firing(rules, past, past)).as("%s one past %d", alert, threshold).containsExactly(alert);
        covered.put(alert, true);
    }

    /** No increase between two scrapes is silent; one more, it alone fires. */
    private void counter(Map<String, String> rules, Map<String, Boolean> covered, String alert,
            List<RuleEvaluator.Sample> healthy, Predicate<RuleEvaluator.Sample> sample) {
        assertThat(firing(rules, healthy, healthy)).doesNotContain(alert);
        assertThat(firing(rules, healthy, set(healthy, sample, value -> value + 1))).as(alert).containsExactly(alert);
        covered.put(alert, true);
    }

    private static Predicate<RuleEvaluator.Sample> named(String name) {
        return sample -> sample.name().equals(name);
    }

    private static Predicate<RuleEvaluator.Sample> verdict(String name, String verdict) {
        return named(name).and(sample -> verdict.equals(sample.labels().get("verdict")));
    }

    private static Predicate<RuleEvaluator.Sample> outcome(String name, String outcome) {
        return named(name).and(sample -> outcome.equals(sample.labels().get("outcome")));
    }

    /** {@code scrape} with every sample {@code which} selects re-valued - at least one must exist: the rule's series. */
    private static List<RuleEvaluator.Sample> set(List<RuleEvaluator.Sample> scrape,
            Predicate<RuleEvaluator.Sample> which, Function<Double, Double> value) {
        assertThat(scrape.stream().anyMatch(which)).as("the series the rule watches is published").isTrue();
        return scrape.stream().map(sample -> which.test(sample) ? sample.withValue(value.apply(sample.value())) : sample)
                .toList();
    }

    /** The alerts of {@code rules} that fire on {@code after}, {@code before} a window earlier. */
    private static Set<String> firing(Map<String, String> rules, List<RuleEvaluator.Sample> before,
            List<RuleEvaluator.Sample> after) {
        Set<String> firing = new TreeSet<>();
        rules.forEach((alert, expr) -> {
            if (RuleEvaluator.fires(expr, before, after)) {
                firing.add(alert);
            }
        });
        return firing;
    }

    /**
     * The fresh instance's scrape with every BNPL sample read healthy: proofs holding (3 each), queues and lags empty,
     * sweepers running, every product's terms version 1 active, no counterparty below zero, the shared headroom and census
     * clean. Counters keep their values.
     */
    private List<RuleEvaluator.Sample> healthy(Map<String, String> target) {
        List<RuleEvaluator.Sample> healthy = new ArrayList<>();
        for (RuleEvaluator.Sample sample : RuleEvaluator.samples(scrape(), target)) {
            String name = sample.name();
            boolean watched = name.startsWith("finapp_bnpl_") || name.equals(NEGATIVE) || name.equals(HEADROOM_LOW)
                    || name.equals(CENSUS);
            if (!watched || name.endsWith("_total")) {
                healthy.add(sample);
            } else if (name.endsWith("_sweeper_enabled") || name.equals(TERMS)) {
                healthy.add(sample.withValue(1));
            } else if (Set.of("HOLDS", "IDENTICAL").contains(sample.labels().getOrDefault("verdict", ""))) {
                healthy.add(sample.withValue(3));
            } else {
                healthy.add(sample.withValue(0));
            }
        }
        return healthy;
    }

    /** The target labels the committed scrape configuration attaches, and {@code bnpl_offered} as an environment sets it. */
    private static Map<String, String> targetLabels(boolean offered) {
        Map<String, Object> config = yaml(PROMETHEUS);
        Map<String, Object> job = map(list(config.get("scrape_configs")).get(0));
        Map<String, Object> statics = map(list(job.get("static_configs")).get(0));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job", String.valueOf(job.get("job_name")));
        labels.put("instance", String.valueOf(list(statics.get("targets")).get(0)));
        map(statics.get("labels")).forEach((key, value) -> labels.put(key, String.valueOf(value)));
        assertThat(labels).as("the committed configuration states whether its target offers BNPL")
                .containsEntry("bnpl_offered", "false");
        labels.put("bnpl_offered", Boolean.toString(offered));
        return labels;
    }

    private String scrape() {
        PrometheusMeterRegistry prometheus = registry instanceof PrometheusMeterRegistry direct ? direct
                : (PrometheusMeterRegistry) ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                        .getRegistries().stream().filter(PrometheusMeterRegistry.class::isInstance).findFirst()
                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));
        return prometheus.scrape();
    }

    private static String prometheusName(String meter) {
        return meter.replace('.', '_');
    }

    /** Alert name to expression, in file order. */
    private static Map<String, String> rules(String file, String group) {
        Map<String, String> rules = new LinkedHashMap<>();
        ruleList(file, group).forEach(rule -> rules.put(String.valueOf(rule.get("alert")),
                String.valueOf(rule.get("expr")).strip()));
        return rules;
    }

    private static List<Map<String, Object>> ruleList(String file, String group) {
        Map<String, Object> found = list(yaml(file).get("groups")).stream().map(BnplAlertRulesTest::map)
                .filter(candidate -> group.equals(candidate.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no rules group named " + group));
        return list(found.get("rules")).stream().map(BnplAlertRulesTest::map).collect(Collectors.toList());
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
