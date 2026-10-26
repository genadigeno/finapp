package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.risk.RegulatoryReportMetrics;
import com.finapp.app.risk.RescreeningMetrics;
import com.finapp.app.risk.RiskActionMetrics;
import com.finapp.app.risk.RiskAlertMetrics;
import com.finapp.app.risk.RiskCaseBacklogMetrics;
import com.finapp.app.risk.RiskCollectionsMetrics;
import com.finapp.app.risk.RiskEvaluationMetrics;
import com.finapp.app.risk.RiskMonitoringMetrics;
import com.finapp.app.risk.RiskPolicyMetrics;
import com.finapp.app.risk.RiskProfileMetrics;
import com.finapp.app.risk.RiskProofMetrics;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CensusKind;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.SlaDeadlines;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

/**
 * Every risk alert FIRES when its condition breaks, and only then (`P13-TSK-024`; {@code PHASE_13_PLAN.md} section 15;
 * {@code BnplAlertRulesTest}'s shape).
 *
 * <p>The scrape is a freshly started instance's - the real registry's names, units and tags, nothing translated here -
 * given the target labels the committed {@code prometheus.yml} attaches. Its risk samples are first set to a healthy
 * reading (every proof and census holding, every queue, lag and backlog empty, every sweeper running, every policy
 * active, a proof reading completed between the two scrapes), on which NO rule fires - judging or not; then, one alert at
 * a time, the one sample that alert watches is broken - at its threshold (silent) and one unit, one count or one NaN past
 * it (fires) - and exactly that alert must fire. {@link RuleEvaluator} evaluates the expressions; its grammar is closed,
 * so a rule it cannot evaluate fails here.
 *
 * <p>So the test is red when a series is renamed without its rule (the rule's sample is not published), when a threshold
 * or window drifts from the objective the code declares ({@link RiskObjectives}, {@link SlaDeadlines}), when a rule's
 * aggregation or matcher cannot see its break, when one break wakes another alert, when a rule names no invariant or an
 * invariant that does not exist, or when its runbook section is missing. The live-scrape resolution with a database is
 * {@code AlertRulesResolveTest#theRiskRulesResolve}'s (owed, C5).
 */
@Tag("slice")
// Pointed at a database that is not there (PlannedMetersExistTest's idiom): every series is the beans' own, from startup.
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every risk alert fires when its condition breaks, and only then (P13-TSK-024)")
class RiskAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/risk.yml";
    private static final String PROMETHEUS = "infra/prometheus/prometheus.yml";
    private static final String GROUP = "risk";

    private static final String UNAVAILABLE = "finapp_risk_evaluation_unavailable_total";
    private static final String LATENCY_COUNT = "finapp_risk_evaluation_latency_seconds_count";
    private static final String LATENCY_BUCKET = "finapp_risk_evaluation_latency_seconds_bucket";
    private static final String POLICY = "finapp_risk_policy_active";
    private static final String UNRAISED = "finapp_risk_alert_unraised_total";
    private static final String ALERT = "finapp_risk_alert_total";
    private static final String CASE_AGE = "finapp_risk_case_age";
    private static final String CASE_BACKLOG = "finapp_risk_case_backlog";
    private static final String ACTION = "finapp_risk_action_total";
    private static final String ACTION_FAILED = "finapp_risk_action_failed_total";
    private static final String FILING = "finapp_risk_report_filing_total";
    private static final String LAG = "finapp_risk_monitoring_lag";
    private static final String MONITORING_AGE = "finapp_risk_monitoring_age";
    private static final String FILING_AGE = "finapp_risk_report_unconcluded_age";
    private static final String UNCLASSIFIED = "finapp_risk_monitoring_unclassified";
    private static final String SUBJECT = "finapp_risk_monitoring_subject_total";
    private static final String MONITORING_UNPLACED = "finapp_risk_monitoring_unplaced_total";
    private static final String VELOCITY = "finapp_risk_velocity_proof";
    private static final String CENSUS = "finapp_risk_census";
    private static final String READING = "finapp_risk_proof_reading_total";
    private static final String SWEEPER = "finapp_risk_sweeper_enabled";
    private static final String CLAIM_FAILED = "finapp_risk_sweeper_claim_failed_total";
    private static final String COLLECTIONS_UNPLACED = "finapp_risk_collections_unplaced_total";
    private static final String INBOX = "finapp_inbox_consumption_total";
    private static final String RESCREENING = "finapp_risk_rescreening_total";
    private static final String RESCREENING_BACKLOG = "finapp_risk_rescreening_backlog";
    private static final String RATING = "finapp_risk_profile_rating";

    /** Every database-read gauge of the section - the NaN rule's inputs. */
    private static final List<String> READ_GAUGES = List.of(VELOCITY, CENSUS, POLICY, LAG, MONITORING_AGE, FILING_AGE,
            UNCLASSIFIED, CASE_BACKLOG,
            CASE_AGE, RESCREENING_BACKLOG, RATING);

    private static final Pattern INVARIANT = Pattern.compile("INV-[A-Z]+-\\d+");
    private static final Pattern CITED = Pattern.compile("9\\.(\\d+)");
    private static final Pattern MATCHER = Pattern.compile("([a-z_]+)\\s*!?=\\s*\"");
    private static final Pattern GROUPED = Pattern.compile("by \\(([a-z_]+)\\)");
    private static final Pattern TEMPLATED = Pattern.compile("\\$labels\\.([a-z_]+)");

    /** Every label a risk rule may select or group on: bounded codes, target labels, a bucket's boundary. */
    private static final Set<String> SELECTED_LABELS = Set.of("operation", "reason_code", "le", "risk_judging", "kind",
            "outcome", "verdict", "instance");

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("the published names are the code's constants - the rules read what the meters publish, units included")
    void theRulesSeriesAreTheCodesNames() {
        assertThat(name(RiskEvaluationMetrics.UNAVAILABLE) + "_total").isEqualTo(UNAVAILABLE);
        assertThat(name(RiskEvaluationMetrics.LATENCY) + "_seconds_count").isEqualTo(LATENCY_COUNT);
        assertThat(name(RiskEvaluationMetrics.LATENCY) + "_seconds_bucket").isEqualTo(LATENCY_BUCKET);
        assertThat(name(RiskEvaluationMetrics.UNRAISED) + "_total").isEqualTo(UNRAISED);
        assertThat(name(RiskPolicyMetrics.ACTIVE)).isEqualTo(POLICY);
        assertThat(name(RiskAlertMetrics.ALERT) + "_total").isEqualTo(ALERT);
        assertThat(name(RiskCaseBacklogMetrics.AGE)).isEqualTo(CASE_AGE);
        assertThat(name(RiskCaseBacklogMetrics.BACKLOG)).isEqualTo(CASE_BACKLOG);
        assertThat(name(RiskActionMetrics.ACTION) + "_total").isEqualTo(ACTION);
        assertThat(name(RiskActionMetrics.ACTION_FAILED) + "_total").isEqualTo(ACTION_FAILED);
        assertThat(name(RegulatoryReportMetrics.FILING) + "_total").isEqualTo(FILING);
        assertThat(name(RiskMonitoringMetrics.LAG)).isEqualTo(LAG);
        assertThat(name(RiskMonitoringMetrics.UNCLASSIFIED)).isEqualTo(UNCLASSIFIED);
        assertThat(name(com.finapp.app.risk.RiskAgeMetrics.MONITORING_AGE)).isEqualTo(MONITORING_AGE);
        assertThat(name(com.finapp.app.risk.RiskAgeMetrics.FILING_AGE)).isEqualTo(FILING_AGE);
        assertThat(name(RiskMonitoringMetrics.SUBJECT) + "_total").isEqualTo(SUBJECT);
        assertThat(name(RiskMonitoringMetrics.UNPLACED) + "_total").isEqualTo(MONITORING_UNPLACED);
        assertThat(name(RiskMonitoringMetrics.CLAIM_FAILED) + "_total").isEqualTo(CLAIM_FAILED);
        assertThat(name(RiskProofMetrics.VELOCITY)).isEqualTo(VELOCITY);
        assertThat(name(RiskProofMetrics.CENSUS)).isEqualTo(CENSUS);
        assertThat(name(RiskProofMetrics.READING) + "_total").isEqualTo(READING);
        assertThat(name(RiskCollectionsMetrics.UNPLACED) + "_total").isEqualTo(COLLECTIONS_UNPLACED);
        assertThat(name(RescreeningMetrics.RESCREENING) + "_total").isEqualTo(RESCREENING);
        assertThat(name(RescreeningMetrics.BACKLOG)).isEqualTo(RESCREENING_BACKLOG);
        assertThat(name(RiskProfileMetrics.RATING)).isEqualTo(RATING);
        assertThat(published()).as("every series a risk rule reads is published by a fresh instance")
                .containsAll(rules().values().stream().flatMap(expr -> RuleEvaluator.seriesOf(expr).stream())
                        .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("on a healthy reading no risk alert fires - judging or not")
    void nothingFiresWhenHealthy() {
        for (boolean judging : List.of(false, true)) {
            List<RuleEvaluator.Sample> before = healthy(target(judging));
            assertThat(firing(rules(), before, completed(before))).as("judging %s", judging).isEmpty();
        }
    }

    @Test
    @DisplayName("every alert fires when, and only when, its own sample breaks its condition - each threshold the declared"
            + " objective, every NaN gauge the unreadable rule's alone, the policy rule only where the target judges")
    void everyAlertFiresWhenItsConditionBreaks() {
        Map<String, String> rules = rules();
        Map<String, Boolean> covered = new TreeMap<>();
        List<RuleEvaluator.Sample> healthy = healthy(target(false));
        List<RuleEvaluator.Sample> judging = healthy(target(true));

        // The fail-safe: RISK_UNAVAILABLE fires for every operation; NO_POLICY_ACTIVE is the unjudged state's, never this.
        for (RiskOperation operation : movements()) {
            counter(rules, covered, "RiskEvaluationUnavailable", healthy, named(UNAVAILABLE).and(label("operation",
                    operation.name())).and(label("reason_code", "RISK_UNAVAILABLE")));
            assertThat(firing(rules, healthy, completed(bump(healthy, named(UNAVAILABLE).and(label("operation",
                    operation.name())).and(label("reason_code", "NO_POLICY_ACTIVE")), 1)))).as("not judging").isEmpty();
        }

        // The latency budget: exactly 1% above the 50 ms bucket is p99 at budget (silent); 2% fires.
        String bucket = Double.toString(RiskObjectives.EVALUATION_LATENCY_P99.toMillis() / 1000.0);
        for (RiskOperation operation : movements()) {
            Predicate<RuleEvaluator.Sample> count = named(LATENCY_COUNT).and(label("operation", operation.name()));
            Predicate<RuleEvaluator.Sample> within = named(LATENCY_BUCKET).and(label("operation", operation.name()))
                    .and(label("le", bucket));
            List<RuleEvaluator.Sample> atBudget = completed(bump(bump(healthy, count, 100), within, 99));
            assertThat(firing(rules, healthy, atBudget)).as("%s at p99 = 50 ms", operation).isEmpty();
            List<RuleEvaluator.Sample> over = completed(bump(bump(healthy, count, 100), within, 98));
            assertThat(firing(rules, healthy, over)).as(operation.name()).containsExactly("RiskEvaluationLatencyOverBudget");
        }
        covered.put("RiskEvaluationLatencyOverBudget", true);

        // No ACTIVE policy: only where the target judges - the gauge at 0, or a NO_POLICY_ACTIVE recorded there.
        for (RiskOperation operation : RiskOperation.values()) {
            if (operation == RiskOperation.CREDIT_SIGNAL) {
                continue; // carries no policy
            }
            Predicate<RuleEvaluator.Sample> gauge = named(POLICY).and(label("operation", operation.name()));
            List<RuleEvaluator.Sample> none = set(judging, gauge, value -> 0.0);
            assertThat(firing(rules, none, completed(none))).as(operation.name()).containsExactly("RiskPolicyNoneActive");
            List<RuleEvaluator.Sample> unjudged = set(healthy, gauge, value -> 0.0);
            assertThat(firing(rules, unjudged, completed(unjudged))).as("production's state before the gate").isEmpty();
        }
        for (RiskOperation operation : movements()) {
            assertThat(firing(rules, judging, completed(bump(judging, named(UNAVAILABLE).and(label("operation",
                    operation.name())).and(label("reason_code", "NO_POLICY_ACTIVE")), 1))))
                    .as(operation.name()).containsExactly("RiskPolicyNoneActive");
        }
        covered.put("RiskPolicyNoneActive", true);

        for (RiskOperation operation : movements()) {
            counter(rules, covered, "RiskAlertsUnraised", healthy, named(UNRAISED).and(label("operation",
                    operation.name())));
        }

        // The flood: a kind's alerts summed over its severities, at the objective silent, one past it fires.
        Predicate<RuleEvaluator.Sample> aKind = named(ALERT).and(label("kind", firstKind(healthy)));
        String severity = healthy.stream().filter(aKind).findFirst().orElseThrow().labels().get("severity");
        Predicate<RuleEvaluator.Sample> one = aKind.and(label("severity", severity));
        assertThat(firing(rules, healthy, completed(bump(healthy, one, RiskObjectives.ALERT_FLOOD_PER_HOUR)))).isEmpty();
        assertThat(firing(rules, healthy, completed(bump(healthy, one, RiskObjectives.ALERT_FLOOD_PER_HOUR + 1))))
                .containsExactly("RiskAlertFlood");
        covered.put("RiskAlertFlood", true);

        // A case past its kind's SLA, one rule per kind; a queue above its staffed capacity, one rule for every kind.
        for (CaseKind caseKind : CaseKind.values()) {
            threshold(rules, covered, "RiskCaseOverdue" + camel(caseKind), healthy,
                    named(CASE_AGE).and(label("kind", caseKind.name())), RiskObjectives.caseSla(caseKind).toSeconds());
            threshold(rules, covered, "RiskCaseBacklogAboveCapacity", healthy,
                    named(CASE_BACKLOG).and(label("kind", caseKind.name())), RiskObjectives.caseCapacity(caseKind));
        }

        // Actions: a dispatch that threw, by kind; a refusal recorded FAILED; a done action is no alert.
        for (String actionKind : values(healthy, ACTION_FAILED, "kind")) {
            counter(rules, covered, "RiskActionDispatchFailing", healthy, named(ACTION_FAILED).and(label("kind",
                    actionKind)));
            counter(rules, covered, "RiskActionRefused", healthy, named(ACTION).and(label("kind", actionKind))
                    .and(label("outcome", "refused")));
            assertThat(firing(rules, healthy, completed(bump(healthy, named(ACTION).and(label("kind", actionKind))
                    .and(label("outcome", "done")), 1)))).isEmpty();
        }

        // The filing: unknown and failed answers; an acknowledgement is no alert.
        // A filing unconcluded past two inquiry intervals - an age, never a counter's increase (the review's IMPORTANT 1).
        threshold(rules, covered, "RiskFilingUnknown", healthy, named(FILING_AGE),
                RiskObjectives.filingUnconcluded().toSeconds());
        assertThat(firing(rules, healthy, completed(bump(healthy, named(FILING).and(label("outcome", "unknown")), 1))))
                .as("one UNKNOWN answer alone is no alert - its report's age is").isEmpty();
        counter(rules, covered, "RiskFilingCallsFailing", healthy, named(FILING).and(label("outcome", "failed")));
        assertThat(firing(rules, healthy, completed(bump(healthy, named(FILING).and(label("outcome", "acknowledged")),
                1)))).isEmpty();

        // The monitoring's hour: the oldest unmonitored entry's age (the review's IMPORTANT 2); the entry count is no rule.
        threshold(rules, covered, "RiskMonitoringLagging", healthy, named(MONITORING_AGE),
                RiskObjectives.MONITORING_AGE.toSeconds());
        List<RuleEvaluator.Sample> busy = set(healthy, named(LAG), value -> 900_000.0);
        assertThat(firing(rules, healthy, completed(busy))).as("a busy journal's lag is no alert by itself").isEmpty();

        // New UNCLASSIFIED rows: the gauge's growth, never its level (the review's IMPORTANT 3).
        List<RuleEvaluator.Sample> standing = set(healthy, named(UNCLASSIFIED), value -> 5.0);
        assertThat(firing(rules, standing, completed(standing))).as("rows born long ago, none new").isEmpty();
        assertThat(firing(rules, standing, completed(set(healthy, named(UNCLASSIFIED), value -> 6.0))))
                .containsExactly("RiskMonitoringUnclassified");
        covered.put("RiskMonitoringUnclassified", true);
        counter(rules, covered, "RiskMonitoringSubjectFailing", healthy, named(SUBJECT).and(label("outcome", "failed")));
        assertThat(firing(rules, healthy, completed(bump(healthy, named(SUBJECT).and(label("outcome", "judged")), 1))))
                .isEmpty();
        counter(rules, covered, "RiskMonitoringUnplaced", healthy, named(MONITORING_UNPLACED));

        // The proofs: the velocity proof and each census DIVERGED, one alert per kind.
        List<RuleEvaluator.Sample> diverged = set(set(healthy, named(VELOCITY).and(label("verdict", "DIVERGED")),
                value -> 1.0), named(VELOCITY).and(label("verdict", "HELD")), value -> 0.0);
        assertThat(firing(rules, diverged, completed(diverged))).containsExactly("VelocityDiverged");
        covered.put("VelocityDiverged", true);
        for (CensusKind census : CensusKind.values()) {
            List<RuleEvaluator.Sample> broken = set(set(healthy, named(CENSUS).and(label("kind", census.name()))
                    .and(label("verdict", "DIVERGED")), value -> 1.0), named(CENSUS).and(label("kind", census.name()))
                    .and(label("verdict", "HELD")), value -> 0.0);
            assertThat(firing(rules, broken, completed(broken))).as(census.name()).containsExactly("RiskCensusDiverged");
        }
        covered.put("RiskCensusDiverged", true);
        // The readings: a failure fires the failing rule; none completed fires the stalled rule alone.
        counter(rules, covered, "RiskProofReadingsFailing", healthy, named(READING).and(label("outcome", "failed")));
        assertThat(firing(rules, healthy, healthy)).as("no reading completed between the scrapes")
                .containsExactly("RiskProofReadingsStalled");
        covered.put("RiskProofReadingsStalled", true);

        // Every database-read gauge unreadable: the unreadable rule alone - no threshold rule reads NaN.
        for (String gauge : READ_GAUGES) {
            List<RuleEvaluator.Sample> unreadable = set(healthy, named(gauge), value -> Double.NaN);
            assertThat(firing(rules, unreadable, completed(unreadable))).as(gauge).containsExactly("RiskGaugesUnreadable");
        }
        List<RuleEvaluator.Sample> policyUnreadable = set(judging, named(POLICY), value -> Double.NaN);
        assertThat(firing(rules, policyUnreadable, completed(policyUnreadable))).as("judging, the policy unreadable")
                .containsExactly("RiskGaugesUnreadable");
        covered.put("RiskGaugesUnreadable", true);

        // The schedules: each kind's flag off, and each kind's claims failing.
        for (String sweeper : values(healthy, SWEEPER, "kind")) {
            List<RuleEvaluator.Sample> off = set(healthy, named(SWEEPER).and(label("kind", sweeper)), value -> 0.0);
            assertThat(firing(rules, off, completed(off))).as(sweeper).containsExactly("RiskSweeperOff");
        }
        assertThat(values(healthy, SWEEPER, "kind")).containsExactlyInAnyOrder("monitoring", "action", "rescreening");
        covered.put("RiskSweeperOff", true);
        for (String claim : values(healthy, CLAIM_FAILED, "kind")) {
            counter(rules, covered, "RiskSweeperClaimFailing", healthy, named(CLAIM_FAILED).and(label("kind", claim)));
        }
        assertThat(values(healthy, CLAIM_FAILED, "kind"))
                .containsExactlyInAnyOrder("monitoring", "action", "report", "rescreening");

        // The signal intake stalled: a default unplaced; any inbox delivery failing; a processed one is no alert.
        counter(rules, covered, "RiskCollectionsUnplaced", healthy, named(COLLECTIONS_UNPLACED));
        counter(rules, covered, "InboxConsumptionFailing", healthy, named(INBOX).and(label("outcome", "failed")));
        assertThat(firing(rules, healthy, completed(bump(healthy, named(INBOX).and(label("outcome", "processed")), 1))))
                .isEmpty();

        // Rescreening: a permit FAILED; permits standing PENDING.
        counter(rules, covered, "RiskRescreeningFailing", healthy, named(RESCREENING).and(label("outcome", "failed")));
        assertThat(firing(rules, healthy, completed(bump(healthy, named(RESCREENING).and(label("outcome", "cleared")),
                1)))).isEmpty();
        threshold(rules, covered, "RiskRescreeningBacklogStanding", healthy, named(RESCREENING_BACKLOG), 0);

        assertThat(covered.keySet()).as("every rule in the file is broken by a case here, and nothing else is")
                .isEqualTo(rules.keySet());
    }

    @Test
    @DisplayName("each threshold, window and wait is the objective the code declares")
    void theThresholdsAreTheCodesObjectives() {
        Map<String, Map<String, Object>> rules = ruleMaps();
        String latency = expr(rules, "RiskEvaluationLatencyOverBudget");
        assertThat(latency).contains("le=\"" + RiskObjectives.EVALUATION_LATENCY_P99.toMillis() / 1000.0 + "\"")
                .endsWith("> " + java.math.BigDecimal.valueOf(RiskObjectives.EVALUATION_LATENCY_SHARE_PERCENT, 2)
                        .toPlainString());
        assertThat(scrape().lines()).as("the budget is a bucket the timer publishes, on a fresh instance")
                .anySatisfy(line -> assertThat(line).startsWith(LATENCY_BUCKET + "{")
                        .contains("le=\"" + RiskObjectives.EVALUATION_LATENCY_P99.toMillis() / 1000.0 + "\""));
        for (CaseKind kind : CaseKind.values()) {
            assertThat(threshold(expr(rules, "RiskCaseOverdue" + camel(kind)))).as(kind.name())
                    .isEqualTo(SlaDeadlines.of(kind).toSeconds());
            assertThat(expr(rules, "RiskCaseBacklogAboveCapacity")).contains("max by (kind) (" + CASE_BACKLOG + "{kind=\""
                    + kind.name() + "\"}) > " + RiskObjectives.caseCapacity(kind));
        }
        assertThat(threshold(expr(rules, "RiskMonitoringLagging"))).isEqualTo(RiskObjectives.MONITORING_AGE.toSeconds());
        assertThat(expr(rules, "RiskMonitoringLagging")).contains(MONITORING_AGE);
        assertThat(threshold(expr(rules, "RiskFilingUnknown"))).isEqualTo(RiskObjectives.filingUnconcluded().toSeconds());
        assertThat(expr(rules, "RiskFilingUnknown")).contains(FILING_AGE);
        String sparse = RiskObjectives.SPARSE_FAILURE_WINDOW.toHours() + "h";
        for (String hourly : List.of("RiskFilingCallsFailing", "RiskMonitoringSubjectFailing",
                "RiskMonitoringUnclassified")) {
            assertThat(expr(rules, hourly)).as(hourly).contains("[" + sparse + "]");
        }
        assertThat(expr(rules, "RiskMonitoringUnclassified")).startsWith("max(delta(" + UNCLASSIFIED);
        assertThat(String.valueOf(rules.get("RiskGaugesUnreadable").get("for")))
                .isEqualTo(minutes(RiskObjectives.UNREADABLE_WAIT));
        assertThat(RiskObjectives.UNREADABLE_WAIT).isGreaterThan(RiskObjectives.proofReadingFloors());
        assertThat(expr(rules, "RiskProofReadingsFailing")).startsWith("sum by (instance) (");
        assertThat(threshold(expr(rules, "RiskAlertFlood"))).isEqualTo(RiskObjectives.ALERT_FLOOD_PER_HOUR);
        assertThat(expr(rules, "RiskAlertFlood")).contains("[1h]");
        String floors = minutes(RiskObjectives.proofReadingFloors());
        assertThat(expr(rules, "RiskProofReadingsFailing")).contains("[" + floors + "]");
        assertThat(String.valueOf(rules.get("RiskProofReadingsFailing").get("for"))).isEqualTo(floors);
        assertThat(expr(rules, "RiskProofReadingsStalled")).contains("[" + floors + "]").endsWith("== 0");
        assertThat(String.valueOf(rules.get("RiskRescreeningBacklogStanding").get("for")))
                .isEqualTo(RiskObjectives.RESCREENING_BACKLOG_STANDING.toDays() + "d");
        assertThat(expr(rules, "RiskPolicyNoneActive")).contains("risk_judging=\"true\"");
        assertThat(expr(rules, "VelocityDiverged")).contains("verdict=\"DIVERGED\"");
        assertThat(expr(rules, "RiskCensusDiverged")).contains("verdict=\"DIVERGED\"").contains("by (kind)");
    }

    @Test
    @DisplayName("each rule is a well-formed alert - a for, a page or ticket severity, a summary, a runbook section, the"
            + " invariant it watches (each one that exists) - and names nothing of a party, a subject or a case")
    void everyRuleIsWellFormed() {
        List<Map<String, Object>> rules = ruleList();
        assertThat(rules).hasSize(rules().size()).hasSize(30);
        String invariants = RepositoryPaths.read("docs/domain/FINANCIAL_INVARIANTS.md");
        assertThat(rules).allSatisfy(rule -> {
            String alert = String.valueOf(rule.get("alert"));
            assertThat(String.valueOf(rule.get("for"))).as(alert).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels"))).as(alert).containsOnlyKeys("severity");
            assertThat(map(rule.get("labels")).get("severity")).as(alert).isIn("page", "ticket");
            Map<String, Object> annotations = map(rule.get("annotations"));
            assertThat(annotations).as(alert).containsOnlyKeys("summary", "description", "invariant");
            assertThat(String.valueOf(annotations.get("summary"))).as(alert).isNotBlank().isNotEqualTo("null");
            assertThat(String.valueOf(annotations.get("description"))).as(alert)
                    .containsPattern("OPERATIONS_RUNBOOK\\.md sections? 9\\.[0-9]+");
            Matcher named = INVARIANT.matcher(String.valueOf(annotations.get("invariant")));
            int count = 0;
            while (named.find()) {
                count++;
                assertThat(invariants).as("%s watches %s, which exists", alert, named.group())
                        .contains("### " + named.group() + " ");
            }
            assertThat(count).as("%s names the invariant it watches", alert).isPositive();
            // The labels a rule selects on and an annotation templates are closed sets - never one that could carry an
            // id, a party, an amount or a name (INV-RSK-08, tipping-off).
            Matcher matchers = MATCHER.matcher(String.valueOf(rule.get("expr")));
            while (matchers.find()) {
                assertThat(matchers.group(1)).as("%s selects on %s", alert, matchers.group(1)).isIn(SELECTED_LABELS);
            }
            Matcher grouped = GROUPED.matcher(String.valueOf(rule.get("expr")));
            while (grouped.find()) {
                assertThat(grouped.group(1)).as("%s groups by %s", alert, grouped.group(1)).isIn(SELECTED_LABELS);
            }
            Matcher templated = TEMPLATED.matcher(annotations.toString());
            while (templated.find()) {
                assertThat(templated.group(1)).as("%s templates %s", alert, templated.group(1))
                        .isIn("operation", "kind", "instance");
            }
        });
    }

    @Test
    @DisplayName("the runbook states the activation gate and every risk procedure: each rule's cited section exists and"
            + " names the alert, and section 9.1 carries every precondition of the gate")
    void everyAlertHasItsProcedure() {
        String runbook = RepositoryPaths.read("docs/architecture/OPERATIONS_RUNBOOK.md");
        int start = runbook.indexOf("\n## 9. Risk");
        assertThat(start).as("the runbook has a risk section 9").isPositive();
        int end = runbook.indexOf("\n## ", start + 1);
        String section = runbook.substring(start, end < 0 ? runbook.length() : end);
        assertThat(section).as("written from the code - the transition's planned marker dropped")
                .doesNotContain("(Phase 13, planned").doesNotContain("written in full by `P13-TSK-024`");
        for (Map<String, Object> rule : ruleList()) {
            String alert = String.valueOf(rule.get("alert"));
            String description = String.valueOf(map(rule.get("annotations")).get("description"));
            Matcher sections = CITED.matcher(description.substring(description.indexOf("OPERATIONS_RUNBOOK")));
            assertThat(sections.find()).as("%s cites a section", alert).isTrue();
            do {
                String heading = "\n### 9." + sections.group(1) + " ";
                int at = section.indexOf(heading);
                assertThat(at).as("%s cites section 9.%s, which exists", alert, sections.group(1)).isPositive();
                int next = section.indexOf("\n### ", at + 1);
                assertThat(section.substring(at, next < 0 ? section.length() : next))
                        .as("section 9.%s names %s", sections.group(1), alert).contains(alert);
            } while (sections.find());
        }
        String gate = section.substring(section.indexOf("\n### 9.1 "), section.indexOf("\n### 9.2 "));
        assertThat(gate).contains("Production ships closed to judging", "battery's fingerprint", "storm's latency",
                "fail-safe bands", "signed", "reporting jurisdiction", "simulation explicitly accepted",
                "named persons", "pairwise disjoint", "four-eyes rehearsal", "tipping-off guards",
                "owner's written allowance", "two holders", "risk_judging: \"true\"");
        for (String procedure : List.of("an alert flood", "a case backlog", "a filing `UNKNOWN`", "a `VelocityDiverged`",
                "a reprocessing", "an action `FAILED`", "the monitoring lag")) {
            assertThat(section.toLowerCase(Locale.ROOT)).as("the procedure for %s", procedure)
                    .contains(procedure.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("the committed prometheus.yml loads the risk rules, and its target is not judging until the gate opens")
    void theRulesFileIsLoaded() {
        Path rulesFile = Path.of(PROMETHEUS).getParent().relativize(Path.of(RULES));
        assertThat(list(yaml(PROMETHEUS).get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(pattern -> assertThat(
                        FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(rulesFile)).isTrue());
        assertThat(target(false)).containsEntry("risk_judging", "false");
    }

    // ------------------------------------------------------------------ time (the review's IMPORTANT 1)

    @Test
    @DisplayName("through time: one report stuck UNKNOWN fires past two inquiry intervals and stays firing; one acknowledged"
            + " at its first inquiry never fires - however far apart its inquiries answer")
    void aStuckFilingFiresThroughTime() {
        List<RuleEvaluator.Sample> healthy = healthy(target(false));
        long interval = RiskObjectives.filingInquiryInterval().toSeconds();
        Optional<Long> stuck = timeline("RiskFilingUnknown", second -> set(healthy, named(FILING_AGE),
                value -> (double) second)).firstFiring(4 * 3600);
        long wait = forOf("RiskFilingUnknown").toSeconds();
        assertThat(stuck).as("a report filed at 0, never concluded").isPresent();
        assertThat(stuck.get()).isBetween(2 * interval + wait, 2 * interval + wait + 2 * RuleTimeline.EVALUATION.toSeconds());
        assertThat(timeline("RiskFilingUnknown", second -> set(healthy, named(FILING_AGE),
                value -> second < interval + 5 ? (double) second : 0.0)).firing(4 * 3600))
                .as("acknowledged at its first inquiry, 15 min in").isEmpty();
    }

    @Test
    @DisplayName("through time: one entry left unmonitored at low volume fires past the hour; a busy, draining journal never"
            + " does - its oldest entry within the settle horizon and a poll")
    void aStalledSweepFiresAtAnyVolume() {
        List<RuleEvaluator.Sample> healthy = healthy(target(false));
        long hour = RiskObjectives.MONITORING_AGE.toSeconds();
        Optional<Long> stalled = timeline("RiskMonitoringLagging", second -> set(set(healthy, named(MONITORING_AGE),
                value -> (double) second), named(LAG), value -> 1.0)).firstFiring(3 * hour);
        assertThat(stalled).as("one entry, the sweep stopped").isPresent();
        assertThat(stalled.get()).isBetween(hour + forOf("RiskMonitoringLagging").toSeconds(),
                hour + forOf("RiskMonitoringLagging").toSeconds() + 2 * RuleTimeline.EVALUATION.toSeconds());
        assertThat(timeline("RiskMonitoringLagging", second -> set(set(healthy, named(MONITORING_AGE),
                value -> (double) (60 + second % 10)), named(LAG), value -> 50_000.0 + second % 7_000)).firing(3 * hour))
                .as("busy and draining").isEmpty();
    }

    @Test
    @DisplayName("through time: a new UNCLASSIFIED namespace fires at once and resolves an hour after its last row; rows"
            + " born long ago never fire")
    void newUnclassifiedRowsFireThenResolve() {
        List<RuleEvaluator.Sample> healthy = healthy(target(false));
        assertThat(timeline("RiskMonitoringUnclassified", second -> set(healthy, named(UNCLASSIFIED), value -> 5.0))
                .firing(6 * 3600)).as("five rows, standing").isEmpty();
        RuleTimeline grows = timeline("RiskMonitoringUnclassified", second -> set(healthy, named(UNCLASSIFIED),
                value -> second < 3600 ? 5.0 : 6.0));
        assertThat(grows.firstFiring(6 * 3600)).hasValueSatisfying(first -> assertThat(first).isBetween(3600L, 3780L));
        assertThat(grows.firing(6 * 3600).last()).as("resolved an hour after the last new row").isLessThan(2 * 3600 + 120L);
    }

    @Test
    @DisplayName("through time: a sparse failure - one party's leg, one filing call - fires within minutes; a dispatch"
            + " re-driven every lease and throwing each time fires after its quarter hour")
    void sparseAndRepeatedFailuresFire() {
        List<RuleEvaluator.Sample> healthy = healthy(target(false));
        for (String[] sparse : new String[][] {{"RiskMonitoringSubjectFailing", SUBJECT, "failed"},
                {"RiskFilingCallsFailing", FILING, "failed"}}) {
            Optional<Long> first = timeline(sparse[0], second -> set(healthy, named(sparse[1]).and(label("outcome",
                    sparse[2])), value -> second >= 600 ? 1.0 : 0.0)).firstFiring(3 * 3600);
            assertThat(first).as("%s on one failure", sparse[0])
                    .hasValueSatisfying(at -> assertThat(at).isBetween(600L, 600L + 180));
        }
        String kind = values(healthy, ACTION_FAILED, "kind").iterator().next();
        Optional<Long> redriven = timeline("RiskActionDispatchFailing", second -> set(healthy,
                named(ACTION_FAILED).and(label("kind", kind)), value -> (double) (second / 120))).firstFiring(3 * 3600);
        assertThat(redriven).hasValueSatisfying(at -> assertThat(at).isBetween(900L, 900L + 180));
    }

    @Test
    @DisplayName("through time: one instance's proofs failing at two floors in a row fires; one failure, or two instances"
            + " failing once each, never does; a gauge NaN for one floor is tolerated, for longer is ticketed")
    void theProofsAndTheGaugesHonourTheirFloors() {
        List<RuleEvaluator.Sample> healthy = healthy(target(false));
        long floor = RiskProofMetrics.DEFAULT_REFRESH.toSeconds();
        Predicate<RuleEvaluator.Sample> failed = named(READING).and(label("outcome", "failed"));
        assertThat(timeline("RiskProofReadingsFailing", second -> set(healthy, failed,
                value -> second >= 60 ? (second >= 60 + floor ? 2.0 : 1.0) : 0.0)).firstFiring(3 * 3600))
                .as("two floors in a row").isPresent();
        assertThat(timeline("RiskProofReadingsFailing", second -> set(healthy, failed,
                value -> second >= 60 ? 1.0 : 0.0)).firing(3 * 3600)).as("one failure").isEmpty();
        List<RuleEvaluator.Sample> twoInstances = withSecondInstance(healthy);
        assertThat(timeline("RiskProofReadingsFailing", second -> set(set(twoInstances,
                failed.and(label("instance", "a:1")), value -> second >= 60 ? 1.0 : 0.0),
                failed.and(label("instance", "b:2")), value -> second >= 60 + floor ? 1.0 : 0.0)).firing(3 * 3600))
                .as("one failure on each of two instances - no instance failed twice").isEmpty();
        assertThat(timeline("RiskGaugesUnreadable", second -> set(healthy, named(VELOCITY),
                value -> second >= 60 && second < 60 + floor ? Double.NaN : value)).firing(3 * 3600))
                .as("NaN until the next floor").isEmpty();
        assertThat(timeline("RiskGaugesUnreadable", second -> set(healthy, named(VELOCITY),
                value -> second >= 60 ? Double.NaN : value)).firstFiring(3 * 3600))
                .hasValueSatisfying(at -> assertThat(at).isGreaterThanOrEqualTo(60 + RiskObjectives.UNREADABLE_WAIT
                        .toSeconds()));
    }

    /** The rule {@code alert} of the committed file, with its own {@code for}, over {@code scrapeAt}. */
    private static RuleTimeline timeline(String alert, java.util.function.LongFunction<List<RuleEvaluator.Sample>> scrapeAt) {
        return RuleTimeline.of(rules().get(alert), forOf(alert), scrapeAt);
    }

    private static Duration forOf(String alert) {
        return RuleTimeline.duration(String.valueOf(ruleMaps().get(alert).get("for")));
    }

    /** {@code scrape}'s samples twice, under instances {@code a:1} and {@code b:2}. */
    private static List<RuleEvaluator.Sample> withSecondInstance(List<RuleEvaluator.Sample> scrape) {
        List<RuleEvaluator.Sample> both = new ArrayList<>();
        for (RuleEvaluator.Sample sample : scrape) {
            for (String instance : List.of("a:1", "b:2")) {
                Map<String, String> labels = new LinkedHashMap<>(sample.labels());
                labels.put("instance", instance);
                both.add(new RuleEvaluator.Sample(sample.name(), labels, sample.value()));
            }
        }
        return both;
    }

    // ------------------------------------------------------------------ cases

    /** At {@code threshold} the alert is silent; one past it, it alone fires. */
    private void threshold(Map<String, String> rules, Map<String, Boolean> covered, String alert,
            List<RuleEvaluator.Sample> healthy, Predicate<RuleEvaluator.Sample> sample, long threshold) {
        List<RuleEvaluator.Sample> at = set(healthy, sample, value -> (double) threshold);
        assertThat(firing(rules, at, completed(at))).as("%s at its threshold %d", alert, threshold).isEmpty();
        List<RuleEvaluator.Sample> past = set(healthy, sample, value -> (double) threshold + 1);
        assertThat(firing(rules, past, completed(past))).as("%s one past %d", alert, threshold).containsExactly(alert);
        covered.put(alert, true);
    }

    /** No increase between two scrapes is silent; one more, it alone fires. */
    private void counter(Map<String, String> rules, Map<String, Boolean> covered, String alert,
            List<RuleEvaluator.Sample> healthy, Predicate<RuleEvaluator.Sample> sample) {
        assertThat(firing(rules, healthy, completed(healthy))).doesNotContain(alert);
        assertThat(firing(rules, healthy, completed(bump(healthy, sample, 1)))).as(alert).containsExactly(alert);
        covered.put(alert, true);
    }

    private static List<RiskOperation> movements() {
        return java.util.Arrays.stream(RiskOperation.values()).filter(RiskOperation::judgesAMovement).toList();
    }

    private static String camel(CaseKind kind) {
        StringBuilder camel = new StringBuilder();
        for (String word : kind.name().split("_")) {
            camel.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return camel.toString();
    }

    private static String firstKind(List<RuleEvaluator.Sample> scrape) {
        return scrape.stream().filter(named(ALERT)).map(sample -> sample.labels().get("kind")).sorted().findFirst()
                .orElseThrow(() -> new AssertionError("the alert counter is published"));
    }

    private static Set<String> values(List<RuleEvaluator.Sample> scrape, String name, String label) {
        Set<String> values = scrape.stream().filter(named(name)).map(sample -> sample.labels().get(label))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(values).as("%s is published with its %s", name, label).isNotEmpty();
        return values;
    }

    private static Predicate<RuleEvaluator.Sample> named(String name) {
        return sample -> sample.name().equals(name);
    }

    private static Predicate<RuleEvaluator.Sample> label(String label, String value) {
        return sample -> value.equals(sample.labels().get(label));
    }

    /** {@code scrape} with every sample {@code which} selects re-valued - at least one must exist: the rule's series. */
    private static List<RuleEvaluator.Sample> set(List<RuleEvaluator.Sample> scrape,
            Predicate<RuleEvaluator.Sample> which, Function<Double, Double> value) {
        assertThat(scrape.stream().anyMatch(which)).as("the series the rule watches is published").isTrue();
        return scrape.stream().map(sample -> which.test(sample) ? sample.withValue(value.apply(sample.value())) : sample)
                .toList();
    }

    private static List<RuleEvaluator.Sample> bump(List<RuleEvaluator.Sample> scrape,
            Predicate<RuleEvaluator.Sample> which, long by) {
        return set(scrape, which, value -> value + by);
    }

    /** {@code scrape} a reading of the proofs later: every instance's {@code completed} counter one more. */
    private static List<RuleEvaluator.Sample> completed(List<RuleEvaluator.Sample> scrape) {
        return bump(scrape, named(READING).and(label("outcome", "completed")), 1);
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
     * The fresh instance's scrape with every risk sample read healthy: each proof and census holding, the queues, lags
     * and backlogs empty, every sweeper running, every operation's policy version 1 active, the ratings counted. Counters
     * and the timers' series keep their values.
     */
    private List<RuleEvaluator.Sample> healthy(Map<String, String> target) {
        List<RuleEvaluator.Sample> healthy = new ArrayList<>();
        for (RuleEvaluator.Sample sample : RuleEvaluator.samples(scrape(), target)) {
            String name = sample.name();
            boolean counted = name.endsWith("_total") || name.endsWith("_count") || name.endsWith("_bucket")
                    || name.endsWith("_sum") || name.endsWith("_max");
            if (!name.startsWith("finapp_risk_") || counted) {
                healthy.add(sample);
            } else if (name.equals(SWEEPER) || name.equals(POLICY)) {
                healthy.add(sample.withValue(1));
            } else if (name.equals(VELOCITY) || name.equals(CENSUS)) {
                healthy.add(sample.withValue("HELD".equals(sample.labels().get("verdict")) ? 1 : 0));
            } else if (name.equals(RATING)) {
                healthy.add(sample.withValue(3));
            } else {
                healthy.add(sample.withValue(0));
            }
        }
        return healthy;
    }

    /** The target labels the committed scrape configuration attaches, and {@code risk_judging} as an environment sets it. */
    private static Map<String, String> target(boolean judging) {
        Map<String, Object> config = yaml(PROMETHEUS);
        Map<String, Object> job = map(list(config.get("scrape_configs")).get(0));
        Map<String, Object> statics = map(list(job.get("static_configs")).get(0));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job", String.valueOf(job.get("job_name")));
        labels.put("instance", String.valueOf(list(statics.get("targets")).get(0)));
        map(statics.get("labels")).forEach((key, value) -> labels.put(key, String.valueOf(value)));
        assertThat(labels).as("the committed configuration states whether its target judges")
                .containsEntry("risk_judging", "false");
        labels.put("risk_judging", Boolean.toString(judging));
        return labels;
    }

    private Set<String> published() {
        Set<String> names = new TreeSet<>();
        scrape().lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("[{ ]", 2)[0]).forEach(names::add);
        return names;
    }

    private String scrape() {
        PrometheusMeterRegistry prometheus = registry instanceof PrometheusMeterRegistry direct ? direct
                : (PrometheusMeterRegistry) ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                        .getRegistries().stream().filter(PrometheusMeterRegistry.class::isInstance).findFirst()
                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));
        return prometheus.scrape();
    }

    private static String name(String meter) {
        return meter.replace('.', '_');
    }

    private static long threshold(String expr) {
        Matcher threshold = Pattern.compile(">\\s*(\\d+)\\s*$").matcher(expr);
        assertThat(threshold.find()).as("%s ends in a threshold", expr).isTrue();
        return Long.parseLong(threshold.group(1));
    }

    private static String minutes(Duration duration) {
        return duration.toMinutes() + "m";
    }

    private static String expr(Map<String, Map<String, Object>> rules, String alert) {
        assertThat(rules).containsKey(alert);
        return String.valueOf(rules.get(alert).get("expr")).strip();
    }

    /** Alert name to expression, in file order. */
    private static Map<String, String> rules() {
        Map<String, String> rules = new LinkedHashMap<>();
        ruleList().forEach(rule -> rules.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        return rules;
    }

    private static Map<String, Map<String, Object>> ruleMaps() {
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        ruleList().forEach(rule -> rules.put(String.valueOf(rule.get("alert")), rule));
        return rules;
    }

    private static List<Map<String, Object>> ruleList() {
        Map<String, Object> found = list(yaml(RULES).get("groups")).stream().map(RiskAlertRulesTest::map)
                .filter(candidate -> GROUP.equals(candidate.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no rules group named " + GROUP));
        return list(found.get("rules")).stream().map(RiskAlertRulesTest::map).collect(Collectors.toList());
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
