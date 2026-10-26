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

    /** The credit rules (P10-TSK-020), held to the same checks and to the objectives the code declares. */
    private static final String CREDIT_RULES = "infra/prometheus/rules/credit.yml";

    private static final String CREDIT_GROUP = "credit";

    /** The lending rules (P11-TSK-030), held to the same checks and to the objectives the code declares. */
    private static final String LENDING_RULES = "infra/prometheus/rules/lending.yml";

    private static final String LENDING_GROUP = "lending";

    /** The BNPL rules (P12-TSK-026), held to the same checks and to the objectives the code declares. */
    private static final String BNPL_RULES = "infra/prometheus/rules/bnpl.yml";

    private static final String BNPL_GROUP = "bnpl";

    /** The risk rules (P13-TSK-024), held to the same checks and to the objectives the code declares. */
    private static final String RISK_RULES = "infra/prometheus/rules/risk.yml";

    private static final String RISK_GROUP = "risk";

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
                    "source", "outcome", "purpose", "currency", "severity", "type", "pair", "provider", "corridor",
                    // P10-TSK-020: the credit rules' labels - and le, the latency bucket's boundary.
                    "product", "decision_maker", "le", "status", "verdict", "source_kind",
                    // P11-TSK-030: the lending rules read a TARGET label the environment sets (never a metric tag).
                    "lending_offered",
                    // P12-TSK-026: the BNPL rules select a servicing step, and read their own TARGET label.
                    "step", "bnpl_offered",
                    // P13-TSK-024: the risk rules select a fail-safe's reason, group by operation, kind and instance, and
                    // read their own TARGET label.
                    "operation", "reason_code", "kind", "instance", "risk_judging");

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
        // Section 15's alerts, every one (P9-TSK-027).
        assertThat(queried).contains("finapp_fx_quote_total", "finapp_fx_cover_unknown_age_seconds",
                "finapp_fx_cover_open_age_seconds", "finapp_fx_proof", "finapp_fx_plan_verdict", "finapp_payments_outbound_unknown_age",
                "finapp_payments_outbound_received_age", "finapp_crossborder_payment_in_transit_age",
                "finapp_kyc_counterparty_review_age_seconds", "finapp_reconciliation_rule_set_missing",
                "finapp_crossborder_cancellation_total");
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
    @DisplayName("the credit rules: every section 15 alert present, every queried series published - the latency"
            + " objective's bucket too - each threshold the objective the code declares, each rule well formed, the file"
            + " loaded (P10-TSK-020)")
    void theCreditRulesResolve() {
        List<Map<String, Object>> credit = rules(CREDIT_RULES, CREDIT_GROUP);
        Map<String, String> expressions = new TreeMap<>();
        credit.forEach(rule -> expressions.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        // PHASE_10_PLAN.md section 15's alerts, every one.
        assertThat(expressions).containsOnlyKeys("CreditDecisionLatencyAboveObjective", "CreditDataUnavailable",
                "CreditRequestOpenPastValidity", "CreditReviewAged", "CreditDecisionDiverged", "CreditPolicyMissing",
                "CreditProgressSweeperOff", "CreditDataRetrySweeperOff", "CreditGaugesUnreadable");
        Set<String> queried = credit.stream()
                .map(rule -> String.valueOf(rule.get("expr")))
                .flatMap(AlertRulesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(queried).contains("finapp_credit_decision_latency_seconds_count",
                "finapp_credit_decision_latency_seconds_bucket", "finapp_credit_data_request_total",
                "finapp_credit_request_open_age_seconds", "finapp_credit_review_age_seconds", "finapp_credit_replay",
                "finapp_credit_policy_active", "finapp_credit_progress_sweeper_enabled",
                "finapp_credit_data_retry_sweeper_enabled");
        assertThat(publishedSeriesNames()).containsAll(queried);

        // The thresholds are the objectives the code declares, not numbers typed twice; the label values the rules
        // select are values the live scrape carries.
        String scrape = scrape();
        String objectiveBucket = Double.toString(CreditObjectives.DECISION_LATENCY_P99.toSeconds());
        assertThat(expressions.get("CreditDecisionLatencyAboveObjective"))
                .contains("le=\"" + objectiveBucket + "\"")
                .contains("decision_maker=\"system\"")
                .endsWith("> 0.01");
        assertThat(scrape.lines())
                .as("the objective's boundary is a bucket the timer publishes for the platform's decisions, on a fresh"
                        + " instance")
                .anySatisfy(line -> assertThat(line)
                        .startsWith("finapp_credit_decision_latency_seconds_bucket{")
                        .contains("decision_maker=\"system\"")
                        .contains("le=\"" + objectiveBucket + "\""));
        assertThat(threshold(expressions.get("CreditRequestOpenPastValidity")))
                .isEqualTo(CreditObjectives.requestValidity().toSeconds());
        assertThat(expressions.get("CreditRequestOpenPastValidity")).contains("status!=\"in_review\"");
        assertThat(threshold(expressions.get("CreditReviewAged"))).isEqualTo(CreditObjectives.REVIEW_AGE.toSeconds());
        assertThat(expressions.get("CreditDecisionDiverged")).contains("verdict=\"DIVERGED\"");
        assertThat(scrape)
                .contains("finapp_credit_replay{verdict=\"DIVERGED\"}")
                .contains("finapp_credit_request_open_age_seconds{status=\"in_review\"}")
                .containsPattern("finapp_credit_data_request_total\\{outcome=\"unavailable\",provider=\"[a-z-]+\","
                        + "source_kind=\"bureau\"\\}");

        assertThat(credit).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank().isNotEqualTo("null");
        });
        Path rulesFile = Path.of(PROMETHEUS_CONFIG).getParent().relativize(Path.of(CREDIT_RULES));
        assertThat(list(yaml(PROMETHEUS_CONFIG).get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(pattern -> assertThat(
                        FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(rulesFile)).isTrue());
    }

    @Test
    @DisplayName("the lending rules: every section 15 alert present, every queried series published on a fresh instance with"
            + " the verdicts and products the rules select, each threshold the objective the code declares, each rule well"
            + " formed, the file loaded (P11-TSK-030)")
    void theLendingRulesResolve() {
        List<Map<String, Object>> lending = rules(LENDING_RULES, LENDING_GROUP);
        Map<String, String> expressions = new TreeMap<>();
        lending.forEach(rule -> expressions.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        // PHASE_11_PLAN.md section 15's alerts, every one (PHASE_GATES.md section 5 Phase 11 names them), and the NaN rule.
        assertThat(expressions).containsOnlyKeys("LendingDisbursementPastDeadline", "LendingPayoutUnconcluded",
                "LendingAccrualLagging", "LendingAllocationAnomaly", "LendingSubledgerProofFailing", "LendingReplayDiverged",
                "LendingCapitalProofFailing", "LendingCapitalHeadroomLow", "CreditExposureCensusFailing",
                "LendingTermsNoneActive", "LendingOriginationSweeperOff", "LendingDisbursementSweeperOff",
                "LendingPayoutSweeperOff", "LendingServicingSweeperOff", "LendingGaugesUnreadable",
                "LendingApplicationAwaitingTooLong", "LendingOriginationStepFailing");
        Set<String> queried = lending.stream()
                .map(rule -> String.valueOf(rule.get("expr")))
                .flatMap(AlertRulesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(queried).containsExactlyInAnyOrder("finapp_lending_disbursement_pending_age_seconds",
                "finapp_lending_payout_dispatched_age_seconds", "finapp_lending_accrual_lag_seconds",
                "finapp_lending_allocation_anomaly_total", "finapp_lending_subledger_proof", "finapp_lending_replay",
                "finapp_lending_capital_proof", "finapp_lending_capital_headroom_low", "finapp_credit_exposure_census",
                "finapp_lending_terms_active", "finapp_lending_origination_sweeper_enabled",
                "finapp_lending_disbursement_sweeper_enabled", "finapp_lending_payout_sweeper_enabled",
                "finapp_lending_servicing_sweeper_enabled", "finapp_lending_application_awaiting_age_seconds",
                "finapp_lending_origination_total");
        assertThat(publishedSeriesNames()).containsAll(queried);

        // The thresholds are the objectives the code declares; the label values the rules select are on the live scrape.
        assertThat(threshold(expressions.get("LendingDisbursementPastDeadline")))
                .isEqualTo(LendingObjectives.disbursementDeadline().toSeconds());
        assertThat(threshold(expressions.get("LendingPayoutUnconcluded")))
                .isEqualTo(LendingObjectives.PAYOUT_OUTCOME.toSeconds());
        assertThat(threshold(expressions.get("LendingAccrualLagging"))).isEqualTo(LendingObjectives.ACCRUAL_LAG.toSeconds());
        assertThat(threshold(expressions.get("LendingApplicationAwaitingTooLong")))
                .isEqualTo(LendingObjectives.awaitingDecision().toSeconds());
        String scrape = scrape();
        assertThat(scrape).contains("finapp_lending_subledger_proof{verdict=\"FAILS\"}",
                "finapp_lending_replay{verdict=\"DIVERGED\"}", "finapp_lending_capital_proof{verdict=\"FAILS\"}",
                "finapp_credit_exposure_census{verdict=\"FAILS\"}", "finapp_lending_terms_active{product=\"PERSONAL_LOAN\"}",
                "finapp_lending_terms_active{product=\"CREDIT_LINE\"}");
        assertThat(expressions.get("LendingTermsNoneActive")).contains("lending_offered=\"true\"");

        assertThat(lending).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank().isNotEqualTo("null");
        });
        Path rulesFile = Path.of(PROMETHEUS_CONFIG).getParent().relativize(Path.of(LENDING_RULES));
        assertThat(list(yaml(PROMETHEUS_CONFIG).get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(pattern -> assertThat(
                        FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(rulesFile)).isTrue());
    }

    @Test
    @DisplayName("the BNPL rules: every section 15 alert present, every queried series published on a fresh instance with"
            + " the verdicts and products the rules select, each threshold the objective the code declares, each rule well"
            + " formed, the file loaded (P12-TSK-026)")
    void theBnplRulesResolve() {
        List<Map<String, Object>> bnpl = rules(BNPL_RULES, BNPL_GROUP);
        Map<String, String> expressions = new TreeMap<>();
        bnpl.forEach(rule -> expressions.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        // PHASE_12_PLAN.md section 15's alerts, every one (PHASE_GATES.md section 5 Phase 12 names them), and the NaN rule.
        assertThat(expressions).containsOnlyKeys("BnplAccrualLagging", "BnplAccrualFailing",
                "BnplApplicationAwaitingTooLong", "BnplOriginationStepFailing", "BnplServicingStepFailing",
                "BnplDisputeDeadlineStepFailing", "BnplAllocationAnomaly", "BnplThreeWayProofFailing",
                "BnplSubledgerProofFailing", "BnplReplayDiverged", "MerchantPayableNegative", "BnplTermsNoneActive",
                "BnplOriginationSweeperOff", "BnplServicingSweeperOff", "BnplDisputeSweeperOff", "BnplGaugesUnreadable",
                // The Phase 12 -> 13 gate's A7-1 and A7-2: a dispute that cannot conclude is never silent.
                "BnplDisputeRefundNotPostable", "BnplDisputeContestedTooLong");
        Set<String> queried = bnpl.stream()
                .map(rule -> String.valueOf(rule.get("expr")))
                .flatMap(AlertRulesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(queried).containsExactlyInAnyOrder("finapp_bnpl_accrual_lag_seconds", "finapp_bnpl_accrual_total",
                "finapp_bnpl_application_awaiting_age_seconds", "finapp_bnpl_origination_total",
                "finapp_bnpl_servicing_total", "finapp_bnpl_dispute_total", "finapp_bnpl_allocation_anomaly_total",
                "finapp_bnpl_threeway_proof", "finapp_bnpl_subledger_proof", "finapp_bnpl_replay",
                "finapp_ledger_negative_positions", "finapp_bnpl_terms_active", "finapp_bnpl_origination_sweeper_enabled",
                "finapp_bnpl_servicing_sweeper_enabled", "finapp_bnpl_dispute_sweeper_enabled",
                "finapp_bnpl_dispute_contested_age_seconds");
        assertThat(publishedSeriesNames()).containsAll(queried);

        assertThat(threshold(expressions.get("BnplAccrualLagging"))).isEqualTo(BnplObjectives.ACCRUAL_LAG.toSeconds());
        assertThat(threshold(expressions.get("BnplApplicationAwaitingTooLong")))
                .isEqualTo(BnplObjectives.awaitingDecision().toSeconds());
        assertThat(threshold(expressions.get("BnplDisputeContestedTooLong")))
                .isEqualTo(BnplObjectives.CONTESTED_DISPUTE.toSeconds());
        assertThat(expressions.get("BnplDisputeRefundNotPostable")).contains("outcome=\"not_postable\"");
        String scrape = scrape();
        assertThat(scrape).contains("finapp_bnpl_threeway_proof{verdict=\"FAILS\"}",
                "finapp_bnpl_subledger_proof{verdict=\"FAILS\"}", "finapp_bnpl_replay{verdict=\"DIVERGED\"}",
                "finapp_bnpl_terms_active{product=\"BNPL_PAY_IN_4\"}", "finapp_bnpl_terms_active{product=\"BNPL_PAY_IN_N\"}",
                "finapp_ledger_negative_positions{purpose=\"MERCHANT_PAYABLE\"}");
        assertThat(expressions.get("BnplTermsNoneActive")).contains("bnpl_offered=\"true\"");

        assertThat(bnpl).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank().isNotEqualTo("null");
        });
        Path rulesFile = Path.of(PROMETHEUS_CONFIG).getParent().relativize(Path.of(BNPL_RULES));
        assertThat(list(yaml(PROMETHEUS_CONFIG).get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(pattern -> assertThat(
                        FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(rulesFile)).isTrue());
    }

    @Test
    @DisplayName("the risk rules: every section 15 alert present, every queried series published on a fresh instance with"
            + " the reasons, verdicts, kinds and bucket the rules select, each threshold the objective the code declares,"
            + " each rule well formed, the file loaded (P13-TSK-024)")
    void theRiskRulesResolve() {
        List<Map<String, Object>> risk = rules(RISK_RULES, RISK_GROUP);
        Map<String, String> expressions = new TreeMap<>();
        risk.forEach(rule -> expressions.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        // PHASE_13_PLAN.md section 15's alerts, every one, and the routed ones (the plan's table names each).
        assertThat(expressions).containsOnlyKeys("RiskEvaluationUnavailable", "RiskEvaluationLatencyOverBudget",
                "RiskPolicyNoneActive", "RiskAlertsUnraised", "RiskAlertFlood", "RiskCaseOverdueAto", "RiskCaseOverdueFraud",
                "RiskCaseOverdueMerchantAbuse", "RiskCaseOverdueAml", "RiskCaseOverdueCollections",
                "RiskCaseBacklogAboveCapacity", "RiskActionDispatchFailing", "RiskActionRefused", "RiskFilingUnknown",
                "RiskFilingCallsFailing", "RiskMonitoringLagging", "RiskMonitoringUnclassified",
                "RiskMonitoringSubjectFailing", "RiskMonitoringUnplaced", "VelocityDiverged", "RiskCensusDiverged",
                "RiskProofReadingsFailing", "RiskProofReadingsStalled", "RiskGaugesUnreadable", "RiskSweeperOff",
                "RiskSweeperClaimFailing", "RiskCollectionsUnplaced", "InboxConsumptionFailing", "RiskRescreeningFailing",
                "RiskRescreeningBacklogStanding");
        Set<String> queried = risk.stream()
                .map(rule -> String.valueOf(rule.get("expr")))
                .flatMap(AlertRulesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(queried).containsExactlyInAnyOrder("finapp_risk_evaluation_unavailable_total",
                "finapp_risk_evaluation_latency_seconds_count", "finapp_risk_evaluation_latency_seconds_bucket",
                "finapp_risk_policy_active", "finapp_risk_alert_unraised_total", "finapp_risk_alert_total",
                "finapp_risk_case_age", "finapp_risk_case_backlog", "finapp_risk_action_failed_total",
                "finapp_risk_action_total", "finapp_risk_report_filing_total", "finapp_risk_monitoring_lag",
                "finapp_risk_monitoring_age", "finapp_risk_report_unconcluded_age",
                "finapp_risk_monitoring_unclassified", "finapp_risk_monitoring_subject_total",
                "finapp_risk_monitoring_unplaced_total", "finapp_risk_velocity_proof", "finapp_risk_census",
                "finapp_risk_proof_reading_total", "finapp_risk_rescreening_backlog", "finapp_risk_profile_rating",
                "finapp_risk_sweeper_enabled", "finapp_risk_sweeper_claim_failed_total",
                "finapp_risk_collections_unplaced_total", "finapp_inbox_consumption_total", "finapp_risk_rescreening_total");
        assertThat(publishedSeriesNames()).containsAll(queried);

        // The thresholds are the code's objectives; the label values the rules select are on the live scrape.
        for (com.finapp.risk.CaseKind kind : com.finapp.risk.CaseKind.values()) {
            StringBuilder camel = new StringBuilder();
            for (String word : kind.name().split("_")) {
                camel.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
            assertThat(threshold(expressions.get("RiskCaseOverdue" + camel))).as(kind.name())
                    .isEqualTo(RiskObjectives.caseSla(kind).toSeconds());
            assertThat(expressions.get("RiskCaseBacklogAboveCapacity")).contains("{kind=\"" + kind.name() + "\"}) > "
                    + RiskObjectives.caseCapacity(kind));
        }
        assertThat(threshold(expressions.get("RiskAlertFlood"))).isEqualTo(RiskObjectives.ALERT_FLOOD_PER_HOUR);
        String scrape = scrape();
        String bucket = Double.toString(RiskObjectives.EVALUATION_LATENCY_P99.toMillis() / 1000.0);
        assertThat(expressions.get("RiskEvaluationLatencyOverBudget")).contains("le=\"" + bucket + "\"");
        assertThat(scrape.lines()).as("the budget is a bucket the timer publishes, on a fresh instance")
                .anySatisfy(line -> assertThat(line).startsWith("finapp_risk_evaluation_latency_seconds_bucket{")
                        .contains("le=\"" + bucket + "\""));
        assertThat(scrape).contains("reason_code=\"RISK_UNAVAILABLE\"", "reason_code=\"NO_POLICY_ACTIVE\"",
                "finapp_risk_velocity_proof{verdict=\"DIVERGED\"}", "kind=\"RESCREENING\"",
                "finapp_risk_profile_rating{bucket=\"UNRATED\"}", "finapp_inbox_consumption_total{outcome=\"failed\"}");
        assertThat(expressions.get("RiskPolicyNoneActive")).contains("risk_judging=\"true\"");

        assertThat(risk).allSatisfy(rule -> {
            assertThat(String.valueOf(rule.get("for"))).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels")).get("severity")).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).isNotBlank().isNotEqualTo("null");
            assertThat(String.valueOf(map(rule.get("annotations")).get("invariant"))).containsPattern("INV-[A-Z]+-\\d+");
        });
        Path rulesFile = Path.of(PROMETHEUS_CONFIG).getParent().relativize(Path.of(RISK_RULES));
        assertThat(list(yaml(PROMETHEUS_CONFIG).get("rule_files")).stream().map(String::valueOf))
                .anySatisfy(pattern -> assertThat(
                        FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(rulesFile)).isTrue());
    }

    private static long threshold(String expr) {
        Matcher threshold = TRAILING_THRESHOLD.matcher(expr);
        assertThat(threshold.find()).as("%s ends in a threshold", expr).isTrue();
        return Long.parseLong(threshold.group(1));
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
        return names(scrape());
    }

    /** The live scrape, as Prometheus reads it. */
    private String scrape() {
        PrometheusMeterRegistry prometheus =
                registry instanceof PrometheusMeterRegistry direct
                        ? direct
                        : (PrometheusMeterRegistry)
                                ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                                        .getRegistries().stream()
                                        .filter(PrometheusMeterRegistry.class::isInstance)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));
        return prometheus.scrape();
    }

    private static Set<String> names(String scrape) {
        Set<String> names = new TreeSet<>();
        for (String line : scrape.lines().toList()) {
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
