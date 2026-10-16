package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.ExposureCensusMetrics;
import com.finapp.app.lending.LendingCapitalMetrics;
import com.finapp.app.lending.LendingProofMetrics;
import com.finapp.app.lending.LoanAwaitingDecisionMetrics;
import com.finapp.app.lending.LoanDisbursementMetrics;
import com.finapp.app.lending.LoanOfferMetrics;
import com.finapp.app.lending.LoanPayoutMetrics;
import com.finapp.app.lending.LoanServicingMetrics;
import com.finapp.app.lending.LoanTermsMetrics;
import com.finapp.app.lending.RepaymentMetrics;
import com.finapp.lending.LoanProduct;
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
 * Every lending alert FIRES when its condition breaks, and only then (`P11-TSK-030`; PHASE_11_PLAN.md section 15,
 * PHASE_GATES.md section 5 Phase 11's observability criterion: "every alert fires in a test that breaks its condition").
 *
 * <p>The scrape is a freshly started instance's - the real registry's names, units and tags, nothing translated here -
 * given the target labels the committed {@code prometheus.yml} attaches. Its lending samples are first set to a healthy
 * reading (every proof holding, every queue and lag empty, every sweeper running, every product's terms active), on
 * which NO rule fires; then, one alert at a time, the one sample that alert watches is broken - at its threshold (silent)
 * and one second, one unit or one NaN past it (fires) - and exactly that alert must fire. {@link RuleEvaluator} evaluates
 * the expressions; its grammar is closed, so a rule it cannot evaluate fails here.
 *
 * <p>So the test is red when a series is renamed without its rule (the rule's sample is not published), when a threshold
 * drifts from the objective {@link LendingObjectives} declares (in either direction), when a rule's aggregation or
 * matcher cannot see its break, or when one break wakes another alert. The live-scrape resolution with a database is
 * {@code AlertRulesResolveTest#theLendingRulesResolve}'s.
 */
@Tag("slice")
// Pointed at a database that is not there (PlannedMetersExistTest's idiom): every series is the beans' own, from startup.
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every lending alert fires when its condition breaks, and only then (P11-TSK-030)")
class LendingAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/lending.yml";
    private static final String PROMETHEUS = "infra/prometheus/prometheus.yml";

    private static final String PENDING_AGE = "finapp_lending_disbursement_pending_age_seconds";
    private static final String PAYOUT_AGE = "finapp_lending_payout_dispatched_age_seconds";
    private static final String ACCRUAL_LAG = "finapp_lending_accrual_lag_seconds";
    private static final String AWAITING_AGE = "finapp_lending_application_awaiting_age_seconds";
    private static final String ORIGINATION = "finapp_lending_origination_total";
    private static final String ANOMALY = "finapp_lending_allocation_anomaly_total";
    private static final String SUBLEDGER = "finapp_lending_subledger_proof";
    private static final String REPLAY = "finapp_lending_replay";
    private static final String CAPITAL = "finapp_lending_capital_proof";
    private static final String HEADROOM_LOW = "finapp_lending_capital_headroom_low";
    private static final String CENSUS = "finapp_credit_exposure_census";
    private static final String TERMS = "finapp_lending_terms_active";
    private static final List<String> SWEEPERS = List.of("origination", "disbursement", "payout", "servicing");

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("the published names are the code's constants - the rules read what the meters publish, units included")
    void theRulesSeriesAreTheCodesNames() {
        assertThat(prometheusName(LoanDisbursementMetrics.PENDING_AGE) + "_seconds").isEqualTo(PENDING_AGE);
        assertThat(prometheusName(LoanPayoutMetrics.DISPATCHED_AGE) + "_seconds").isEqualTo(PAYOUT_AGE);
        assertThat(prometheusName(LoanServicingMetrics.LAG) + "_seconds").isEqualTo(ACCRUAL_LAG);
        assertThat(prometheusName(LoanAwaitingDecisionMetrics.AWAITING_AGE) + "_seconds").isEqualTo(AWAITING_AGE);
        assertThat(prometheusName(LoanOfferMetrics.ORIGINATION) + "_total").isEqualTo(ORIGINATION);
        assertThat(prometheusName(RepaymentMetrics.ANOMALY) + "_total").isEqualTo(ANOMALY);
        assertThat(prometheusName(LendingProofMetrics.SUBLEDGER)).isEqualTo(SUBLEDGER);
        assertThat(prometheusName(LendingProofMetrics.REPLAY)).isEqualTo(REPLAY);
        assertThat(prometheusName(LendingCapitalMetrics.PROOF)).isEqualTo(CAPITAL);
        assertThat(prometheusName(LendingCapitalMetrics.HEADROOM_LOW)).isEqualTo(HEADROOM_LOW);
        assertThat(prometheusName(ExposureCensusMetrics.CENSUS)).isEqualTo(CENSUS);
        assertThat(prometheusName(LoanTermsMetrics.ACTIVE)).isEqualTo(TERMS);
    }

    @Test
    @DisplayName("on a healthy reading no lending alert fires - offered or not")
    void nothingFiresWhenHealthy() {
        List<RuleEvaluator.Sample> healthy = healthy(targetLabels(false));
        assertThat(firing(healthy, healthy)).isEmpty();
        List<RuleEvaluator.Sample> offered = healthy(targetLabels(true));
        assertThat(firing(offered, offered)).isEmpty();
    }

    @Test
    @DisplayName("every alert fires when, and only when, its own sample breaks its condition - each threshold the declared"
            + " objective, every NaN gauge the unreadable rule's alone, the terms rule only where the target offers lending")
    void everyAlertFiresWhenItsConditionBreaks() {
        Map<String, Boolean> covered = new TreeMap<>();
        List<RuleEvaluator.Sample> healthy = healthy(targetLabels(false));
        List<RuleEvaluator.Sample> offered = healthy(targetLabels(true));

        long deadline = LendingObjectives.disbursementDeadline().toSeconds();
        threshold(covered, "LendingDisbursementPastDeadline", healthy, named(PENDING_AGE), deadline);
        threshold(covered, "LendingPayoutUnconcluded", healthy, named(PAYOUT_AGE),
                LendingObjectives.PAYOUT_OUTCOME.toSeconds());
        threshold(covered, "LendingAccrualLagging", healthy, named(ACCRUAL_LAG), LendingObjectives.ACCRUAL_LAG.toSeconds());
        threshold(covered, "LendingApplicationAwaitingTooLong", healthy, named(AWAITING_AGE),
                LendingObjectives.awaitingDecision().toSeconds());
        threshold(covered, "LendingSubledgerProofFailing", healthy, verdict(SUBLEDGER, "FAILS"), 0);
        threshold(covered, "LendingReplayDiverged", healthy, verdict(REPLAY, "DIVERGED"), 0);
        threshold(covered, "LendingCapitalProofFailing", healthy, verdict(CAPITAL, "FAILS"), 0);
        threshold(covered, "LendingCapitalHeadroomLow", healthy, named(HEADROOM_LOW), 0);
        threshold(covered, "CreditExposureCensusFailing", healthy, verdict(CENSUS, "FAILS"), 0);

        // A counter: one anomaly between two scrapes fires; none does not.
        List<RuleEvaluator.Sample> anomalous = set(healthy, named(ANOMALY), value -> value + 1);
        assertThat(firing(healthy, healthy)).doesNotContain("LendingAllocationAnomaly");
        assertThat(firing(healthy, anomalous)).containsExactly("LendingAllocationAnomaly");
        covered.put("LendingAllocationAnomaly", true);

        // A counter by kind: one failed origination step or offer expiry between two scrapes fires; none does not.
        for (String kind : List.of("application", "offer")) {
            List<RuleEvaluator.Sample> failing = set(healthy, named(ORIGINATION).and(sample ->
                    kind.equals(sample.labels().get("kind"))), value -> value + 1);
            assertThat(firing(healthy, failing)).as(kind).containsExactly("LendingOriginationStepFailing");
        }
        assertThat(firing(healthy, healthy)).doesNotContain("LendingOriginationStepFailing");
        covered.put("LendingOriginationStepFailing", true);

        // The terms: a product with none ACTIVE fires where the target offers lending - and never where it does not.
        for (LoanProduct product : LoanProduct.values()) {
            Predicate<RuleEvaluator.Sample> terms = named(TERMS).and(sample -> product.name().equals(
                    sample.labels().get("product")));
            assertThat(firing(offered, set(offered, terms, value -> 0.0))).as(product.name())
                    .containsExactly("LendingTermsNoneActive");
            assertThat(firing(healthy, set(healthy, terms, value -> 0.0)))
                    .as("production's expected state - no ACTIVE terms - is silent where lending is not offered").isEmpty();
        }
        covered.put("LendingTermsNoneActive", true);

        for (String sweeper : SWEEPERS) {
            String alert = "Lending" + Character.toUpperCase(sweeper.charAt(0)) + sweeper.substring(1) + "SweeperOff";
            List<RuleEvaluator.Sample> off = set(healthy, named("finapp_lending_" + sweeper + "_sweeper_enabled"),
                    value -> 0.0);
            assertThat(firing(off, off)).as(sweeper).containsExactly(alert);
            covered.put(alert, true);
        }

        // Every gauge read from the database, unreadable: the unreadable rule alone - no threshold rule reads NaN.
        for (String gauge : List.of(SUBLEDGER, REPLAY, CAPITAL, HEADROOM_LOW, CENSUS, PENDING_AGE, PAYOUT_AGE, ACCRUAL_LAG,
                AWAITING_AGE)) {
            List<RuleEvaluator.Sample> unreadable = set(healthy, named(gauge), value -> Double.NaN);
            assertThat(firing(unreadable, unreadable)).as(gauge).containsExactly("LendingGaugesUnreadable");
        }
        List<RuleEvaluator.Sample> termsUnreadable = set(offered, named(TERMS), value -> Double.NaN);
        assertThat(firing(termsUnreadable, termsUnreadable)).containsExactly("LendingGaugesUnreadable");
        covered.put("LendingGaugesUnreadable", true);

        assertThat(covered.keySet()).as("every rule in the file is broken by a case here, and nothing else is")
                .isEqualTo(rules().keySet());
    }

    @Test
    @DisplayName("each rule is a well-formed alert - a for, a page or ticket severity, a summary - and names no figure")
    void everyRuleIsWellFormed() {
        List<Map<String, Object>> rules = ruleList();
        assertThat(rules).hasSize(rules().size());
        assertThat(rules).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank().isNotEqualTo("null");
            assertThat(String.valueOf(rule.get("expr"))).doesNotContainIgnoringCase("loan_id").doesNotContain("party");
        });
    }

    // ------------------------------------------------------------------ cases

    /** At {@code threshold} the alert is silent; one past it, it alone fires. */
    private void threshold(Map<String, Boolean> covered, String alert, List<RuleEvaluator.Sample> healthy,
            Predicate<RuleEvaluator.Sample> sample, long threshold) {
        List<RuleEvaluator.Sample> at = set(healthy, sample, value -> (double) threshold);
        assertThat(firing(at, at)).as("%s at its threshold %d", alert, threshold).isEmpty();
        List<RuleEvaluator.Sample> past = set(healthy, sample, value -> (double) threshold + 1);
        assertThat(firing(past, past)).as("%s one past %d", alert, threshold).containsExactly(alert);
        covered.put(alert, true);
    }

    private static Predicate<RuleEvaluator.Sample> named(String name) {
        return sample -> sample.name().equals(name);
    }

    private static Predicate<RuleEvaluator.Sample> verdict(String name, String verdict) {
        return named(name).and(sample -> verdict.equals(sample.labels().get("verdict")));
    }

    /** {@code scrape} with every sample {@code which} selects re-valued - at least one must exist: the rule's series. */
    private static List<RuleEvaluator.Sample> set(List<RuleEvaluator.Sample> scrape,
            Predicate<RuleEvaluator.Sample> which, Function<Double, Double> value) {
        assertThat(scrape.stream().anyMatch(which)).as("the series the rule watches is published").isTrue();
        return scrape.stream().map(sample -> which.test(sample) ? sample.withValue(value.apply(sample.value())) : sample)
                .toList();
    }

    /** The alerts that fire on {@code after}, {@code before} a window earlier. */
    private Set<String> firing(List<RuleEvaluator.Sample> before, List<RuleEvaluator.Sample> after) {
        Set<String> firing = new TreeSet<>();
        rules().forEach((alert, expr) -> {
            if (RuleEvaluator.fires(expr, before, after)) {
                firing.add(alert);
            }
        });
        return firing;
    }

    /**
     * The fresh instance's scrape with every lending sample read healthy: proofs holding (3 accounts each), queues and lags
     * empty, sweepers running, every product's terms version 1 active. Counters keep their values.
     */
    private List<RuleEvaluator.Sample> healthy(Map<String, String> target) {
        List<RuleEvaluator.Sample> healthy = new ArrayList<>();
        for (RuleEvaluator.Sample sample : RuleEvaluator.samples(scrape(), target)) {
            String name = sample.name();
            boolean watched = name.startsWith("finapp_lending_") || name.startsWith("finapp_credit_exposure_census");
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

    /** The target labels the committed scrape configuration attaches, and {@code lending_offered} as an environment sets it. */
    private static Map<String, String> targetLabels(boolean offered) {
        Map<String, Object> config = yaml(PROMETHEUS);
        Map<String, Object> job = map(list(config.get("scrape_configs")).get(0));
        Map<String, Object> statics = map(list(job.get("static_configs")).get(0));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job", String.valueOf(job.get("job_name")));
        labels.put("instance", String.valueOf(list(statics.get("targets")).get(0)));
        map(statics.get("labels")).forEach((key, value) -> labels.put(key, String.valueOf(value)));
        assertThat(labels).as("the committed configuration states whether its target offers lending")
                .containsKey("lending_offered");
        labels.put("lending_offered", Boolean.toString(offered));
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
    private static Map<String, String> rules() {
        Map<String, String> rules = new LinkedHashMap<>();
        ruleList().forEach(rule -> rules.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        return rules;
    }

    private static List<Map<String, Object>> ruleList() {
        Map<String, Object> group = list(yaml(RULES).get("groups")).stream().map(LendingAlertRulesTest::map)
                .filter(candidate -> "lending".equals(candidate.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no rules group named lending"));
        return list(group.get("rules")).stream().map(LendingAlertRulesTest::map).collect(Collectors.toList());
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
