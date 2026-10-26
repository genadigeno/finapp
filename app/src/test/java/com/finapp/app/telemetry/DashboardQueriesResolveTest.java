package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every query in the committed dashboard names a series the application actually publishes.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A dashboard whose query names a series that does not exist does not fail. It renders, with
 * every panel reading "No data", and looks exactly like a quiet system. During an incident that is
 * the worst possible failure: the operator concludes nothing is wrong.
 *
 * <p>This is not hypothetical. It happened during {@code P0-TSK-029} itself: {@code
 * baseUnit("events")} made Micrometer publish {@code finapp_outbox_pending_events} while the
 * dashboard queried {@code finapp_outbox_pending}, and the endpoint test missed it because a
 * substring assertion is satisfied by the longer name. It was found by looking at a browser. This
 * is that check, automated.
 *
 * <h2>What it deliberately covers</h2>
 *
 * <p>Framework series too — {@code http_server_requests_seconds_count}, {@code
 * hikaricp_connections_active}. Those are not our contract, which is exactly why they are worth
 * checking: a Spring Boot or Micrometer upgrade can rename one, and nothing else in this build
 * would notice until somebody opened the dashboard during an outage.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardQueriesResolveTest {

    private static final String DASHBOARD = "infra/grafana/dashboards/finapp-platform.json";

    /** A PromQL metric selector: an identifier not immediately followed by an opening bracket. */
    private static final Pattern METRIC = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()");

    /**
     * PromQL tokens that are not series names.
     *
     * <p>Aggregation operators appear here because they are written {@code sum by (outcome) (...)}
     * - followed by a keyword rather than a bracket - so the lookahead above does not exclude
     * them. Functions like {@code rate(} are excluded by the lookahead and need no entry.
     *
     * <p>The parser is deliberately simple, and deliberately errs towards treating an unknown
     * token AS a series: that produces a visible, fixable failure, whereas the other direction
     * silently stops checking whichever query it misparsed - which is the failure this whole class
     * exists to prevent.
     */
    private static final Set<String> NOT_A_SERIES =
            Set.of(
                    // Aggregation operators.
                    "sum", "min", "max", "avg", "group", "stddev", "stdvar", "count",
                    "count_values", "bottomk", "topk", "quantile",
                    // Aggregation modifiers and label names used in this dashboard.
                    "by", "on", "without", "ignoring", "outcome", "le",
                    // P3-TSK-020: the trial-balance panel groups by currency.
                    "currency",
                    // P5-TSK-017: the provider-latency panel groups by operation.
                    "operation",
                    // The consent counters' tag key (P2-TSK-020) - a label, not a series.
                    "purpose",
                    // P9-TSK-027: the FX row groups by pair, provider and the residual direction - labels.
                    "direction", "pair", "provider",
                    // P10-TSK-020: the credit row groups by product, pinned policy version, decider kind, reason
                    // code, source kind, open state and replay verdict - labels, not series.
                    "product", "policy_version", "decision_maker", "reason_code", "source_kind", "status", "verdict",
                    // P11-TSK-030: the lending row groups by payout path, billed or fee kind and delinquency bucket.
                    "payout", "kind", "bucket",
                    // P12-TSK-026: the BNPL row groups by plan and servicing step too - labels, not series.
                    "plan", "step",
                    // P13-TSK-024: the risk row groups fired rules by their kind's code - a label, not a series.
                    "rule",
                    // P7-TSK-015: the rail and dispute row groups by rail, judged type and
                    // dispute stage - labels, not series.
                    "rail", "type", "stage",
                    // P8-TSK-024: the settlement and reconciliation row groups by settlement
                    // source and break severity - labels, not series.
                    "source", "severity");

    @LocalServerPort private int port;

    @Autowired private MeterRegistry registry;

    /**
     * Both conditions the dashboard's framework panels need, and each was found by this test
     * failing rather than by reading documentation.
     *
     * <p>{@code hikaricp_*} exists only once the pool has actually initialised - with the
     * datasource pointed at a closed port there is no pool and no Hikari metrics, only the
     * generic {@code jdbc_connections_*}. That is why this test needs a real database.
     *
     * <p>{@code http_server_requests_*} is registered when the first request is served, not at
     * startup, so a test that asserted before serving one would report a dashboard error that
     * does not exist.
     */
    @BeforeEach
    void serveOneRequestSoTheHttpTimerExists() throws Exception {
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
    @DisplayName("every series the dashboard queries is one the application publishes")
    void everyQueriedSeriesExists() {
        Set<String> published = publishedSeriesNames();
        assertThat(published).as("the registry must expose series to check against").isNotEmpty();

        List<String> queried = queriedSeriesNames();
        assertThat(queried).as("the dashboard must contain queries").isNotEmpty();

        assertThat(queried)
                .as(
                        """
                        A dashboard panel queries a series the application does not publish. It will \
                        render "No data" and look exactly like a quiet system, which during an \
                        incident is the worst way to be wrong. Published series: %s""",
                        published)
                .allSatisfy(series -> assertThat(published).contains(series));
    }

    @Test
    @DisplayName("the FX and cross-border row has exactly the twelve panels section 15 names (P9-TSK-027)")
    void theFxAndCrossBorderRowHasTwelvePanels() throws Exception {
        JsonNode panels = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD)).path("panels");
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("FX and cross-border")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
            }
        }
        assertThat(count).as("the FX and cross-border row's panels").isEqualTo(12);
    }

    @Test
    @DisplayName("the credit row: nine panels reading every PHASE_10_PLAN section 15 series, each published on a fresh"
            + " instance (P10-TSK-020)")
    void theCreditRowResolves() throws Exception {
        JsonNode panels = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD)).path("panels");
        List<String> rowQueries = new java.util.ArrayList<>();
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("Credit decisioning")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
                panel.path("targets").valueStream().map(target -> target.path("expr").stringValue()).forEach(rowQueries::add);
            }
        }
        assertThat(count).as("the credit row's panels").isEqualTo(9);
        Set<String> queried = rowQueries.stream().flatMap(DashboardQueriesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(queried).containsExactlyInAnyOrder(
                "finapp_credit_decision_total", "finapp_credit_decision_latency_seconds_bucket", "finapp_credit_reason_total",
                "finapp_credit_data_request_total", "finapp_credit_data_latency_seconds_sum",
                "finapp_credit_data_latency_seconds_count", "finapp_credit_request_open_age_seconds",
                "finapp_credit_review_age_seconds", "finapp_credit_replay", "finapp_credit_policy_active",
                "finapp_credit_progress_sweeper_enabled", "finapp_credit_data_retry_sweeper_enabled");
        assertThat(publishedSeriesNames()).containsAll(queried);
    }

    @Test
    @DisplayName("the lending row: nine panels reading every PHASE_11_PLAN section 15 series, each published on a fresh"
            + " instance (P11-TSK-030)")
    void theLendingRowResolves() throws Exception {
        JsonNode panels = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD)).path("panels");
        List<String> rowQueries = new java.util.ArrayList<>();
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("Lending")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
                panel.path("targets").valueStream().map(target -> target.path("expr").stringValue()).forEach(rowQueries::add);
            }
        }
        assertThat(count).as("the lending row's panels").isEqualTo(9);
        Set<String> queried = rowQueries.stream().flatMap(DashboardQueriesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        // Every row of PHASE_11_PLAN.md section 15, in its published form.
        assertThat(queried).containsExactlyInAnyOrder(
                "finapp_lending_application_total", "finapp_lending_offer_total", "finapp_lending_acceptance_total",
                "finapp_credit_consumption_total", "finapp_lending_disbursement_total", "finapp_lending_payout_total",
                "finapp_lending_disbursement_pending_age_seconds", "finapp_lending_payout_dispatched_age_seconds",
                "finapp_lending_accrual_total", "finapp_lending_billing_total", "finapp_lending_collection_total",
                "finapp_lending_fee_total", "finapp_lending_accrual_lag_seconds", "finapp_lending_repayment_total",
                "finapp_lending_draw_total", "finapp_lending_payoff_total", "finapp_lending_reversal_total",
                "finapp_lending_waiver_total", "finapp_lending_amendment_total", "finapp_lending_allocation_anomaly_total",
                "finapp_lending_loans", "finapp_lending_delinquency", "finapp_lending_default",
                "finapp_lending_subledger_proof", "finapp_lending_replay", "finapp_lending_capital_proof",
                "finapp_credit_exposure_census", "finapp_lending_capital_headroom_low", "finapp_lending_terms_active",
                "finapp_lending_origination_sweeper_enabled", "finapp_lending_disbursement_sweeper_enabled",
                "finapp_lending_payout_sweeper_enabled", "finapp_lending_servicing_sweeper_enabled");
        assertThat(publishedSeriesNames()).containsAll(queried);
    }

    @Test
    @DisplayName("the BNPL row: nine panels reading every BNPL series of PHASE_12_PLAN section 15, each published on a"
            + " fresh instance (P12-TSK-026)")
    void theBnplRowResolves() throws Exception {
        JsonNode panels = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD)).path("panels");
        List<String> rowQueries = new java.util.ArrayList<>();
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("BNPL")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
                panel.path("targets").valueStream().map(target -> target.path("expr").stringValue()).forEach(rowQueries::add);
            }
        }
        assertThat(count).as("the BNPL row's panels").isEqualTo(9);
        Set<String> queried = rowQueries.stream().flatMap(DashboardQueriesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        // Every BNPL row of PHASE_12_PLAN.md section 15 and the negative merchant payables, in their published form.
        assertThat(queried).containsExactlyInAnyOrder(
                "finapp_bnpl_application_total", "finapp_bnpl_eligibility_total", "finapp_bnpl_offer_total",
                "finapp_bnpl_purchase_total", "finapp_bnpl_origination_total", "finapp_bnpl_accrual_total",
                "finapp_bnpl_billing_total", "finapp_bnpl_servicing_total", "finapp_bnpl_collection_total",
                "finapp_bnpl_fee_total", "finapp_bnpl_accrual_lag_seconds", "finapp_bnpl_application_awaiting_age_seconds",
                "finapp_bnpl_repayment_total", "finapp_bnpl_payoff_total", "finapp_bnpl_allocation_anomaly_total",
                "finapp_bnpl_refund_total", "finapp_bnpl_dispute_total", "finapp_bnpl_reversal_total",
                "finapp_bnpl_waiver_total", "finapp_bnpl_obligations", "finapp_bnpl_delinquency", "finapp_bnpl_default",
                "finapp_bnpl_threeway_proof", "finapp_bnpl_subledger_proof", "finapp_bnpl_replay",
                "finapp_ledger_negative_positions", "finapp_bnpl_terms_active", "finapp_bnpl_origination_sweeper_enabled",
                "finapp_bnpl_servicing_sweeper_enabled", "finapp_bnpl_dispute_sweeper_enabled");
        assertThat(publishedSeriesNames()).containsAll(queried);
    }

    @Test
    @DisplayName("the risk row: nine panels reading every risk series of PHASE_13_PLAN section 15, each published on a"
            + " fresh instance (P13-TSK-024)")
    void theRiskRowResolves() throws Exception {
        JsonNode panels = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD)).path("panels");
        List<String> rowQueries = new java.util.ArrayList<>();
        int count = -1;
        for (JsonNode panel : panels) {
            boolean row = "row".equals(panel.path("type").asString());
            if (row && panel.path("title").asString().startsWith("Risk")) {
                count = 0;
            } else if (row && count >= 0) {
                break;
            } else if (count >= 0) {
                count++;
                panel.path("targets").valueStream().map(target -> target.path("expr").stringValue()).forEach(rowQueries::add);
            }
        }
        assertThat(count).as("the risk row's panels").isEqualTo(9);
        Set<String> queried = rowQueries.stream().flatMap(DashboardQueriesResolveTest::seriesIn)
                .collect(Collectors.toCollection(TreeSet::new));
        // Every risk row of PHASE_13_PLAN.md section 15, in its published form.
        assertThat(queried).containsExactlyInAnyOrder(
                "finapp_risk_evaluation_total", "finapp_risk_evaluation_unavailable_total", "finapp_risk_challenge_total",
                "finapp_risk_rule_hit_total", "finapp_risk_evaluation_latency_seconds_bucket",
                "finapp_risk_evaluation_latency_seconds_max", "finapp_risk_monitoring_latency_seconds_bucket",
                "finapp_risk_monitoring_latency_seconds_max", "finapp_risk_policy_active", "finapp_risk_sweeper_enabled",
                "finapp_risk_sweeper_claim_failed_total", "finapp_risk_alert_total", "finapp_risk_alert_suppressed_total",
                "finapp_risk_alert_unraised_total", "finapp_risk_signal_total", "finapp_risk_case_backlog",
                "finapp_risk_case_age", "finapp_risk_case_total", "finapp_risk_case_resolution_seconds_sum",
                "finapp_risk_case_resolution_seconds_count", "finapp_risk_action_total", "finapp_risk_action_failed_total",
                "finapp_risk_override_total", "finapp_risk_report_total", "finapp_risk_report_filing_total",
                "finapp_risk_monitoring_lag", "finapp_risk_monitoring_unclassified", "finapp_risk_monitoring_subject_total",
                "finapp_risk_monitoring_unplaced_total", "finapp_risk_collections_unplaced_total",
                "finapp_risk_velocity_proof", "finapp_risk_census", "finapp_risk_proof_reading_total",
                "finapp_risk_profile_rating", "finapp_risk_rescreening_backlog", "finapp_risk_rescreening_total");
        assertThat(publishedSeriesNames()).containsAll(queried);
    }

    @Test
    @DisplayName("the guard is not vacuous: it reads real queries and a real registry")
    void theGuardSeesBothSides() {
        assertThat(queriedSeriesNames())
                .as("the outbox panels are the ones this task added")
                .contains("finapp_outbox_pending", "finapp_outbox_oldest_seconds");
        assertThat(queriedSeriesNames())
                .as("the settlement and reconciliation row (P8-TSK-024) is read too")
                .contains(
                        "finapp_reconciliation_break_age",
                        "finapp_reconciliation_item_total",
                        "finapp_settlement_source_silence");
        assertThat(publishedSeriesNames()).contains("finapp_outbox_pending");
    }

    // -----------------------------------------------------------------

    /**
     * Series names as Prometheus publishes them, taken from the registry's own naming convention
     * rather than by translating dots to underscores here. Micrometer appends a base unit to the
     * name, which is what made the original defect invisible - a translation written in this test
     * would have reproduced the same wrong answer.
     */
    private Set<String> publishedSeriesNames() {
        PrometheusMeterRegistry prometheus =
                registry instanceof PrometheusMeterRegistry direct
                        ? direct
                        : (PrometheusMeterRegistry)
                                ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                                        .getRegistries().stream()
                                        .filter(PrometheusMeterRegistry.class::isInstance)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));

        Set<String> names = new TreeSet<>();
        for (String line : prometheus.scrape().lines().toList()) {
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

    private static List<String> queriedSeriesNames() {
        JsonNode dashboard = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD));
        return dashboard.path("panels").valueStream()
                .flatMap(panel -> panel.path("targets").valueStream())
                .map(target -> target.path("expr").stringValue())
                .filter(expr -> expr != null && !expr.isBlank())
                .flatMap(DashboardQueriesResolveTest::seriesIn)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static java.util.stream.Stream<String> seriesIn(String expr) {
        Matcher matcher = METRIC.matcher(expr);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!NOT_A_SERIES.contains(candidate)) {
                found.add(candidate);
            }
        }
        return found.stream();
    }
}
