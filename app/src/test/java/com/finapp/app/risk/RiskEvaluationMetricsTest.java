package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluationObserver;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.UnavailableCause;
import com.finapp.risk.VelocityOutcome;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-eval's meters (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section 15): every series registered eagerly for every
 * judged operation, every tag key an allowed one ({@code rule} among them, C9), no tag value an id; a report counted
 * into its verdict, its latency, its fail-safe's reason, its fired rules and its challenge's outcome.
 */
@DisplayName("T-eval's meters: eager, bounded tags, every report counted (P13-TSK-004)")
class RiskEvaluationMetricsTest {

    private static final IdGenerator IDS = new IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom());

    @Test
    @DisplayName("registered eagerly per judged operation - verdicts it admits, both fail-safe reasons, the rule kinds"
            + " written for it, both challenge outcomes, the latency timer - every tag key allowed")
    void everySeriesIsEager() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RiskEvaluationMetrics(registry);
        for (String name : List.of(RiskEvaluationMetrics.EVALUATION, RiskEvaluationMetrics.LATENCY,
                RiskEvaluationMetrics.UNAVAILABLE, RiskEvaluationMetrics.RULE_HIT, RiskEvaluationMetrics.CHALLENGE)) {
            assertThat(registry.find(name).meters()).as(name).isNotEmpty();
        }
        assertThat(registry.find(RiskEvaluationMetrics.EVALUATION).tag("operation", "WITHDRAWAL").meters())
                .hasSize(RiskDecision.admittedBy(RiskOperation.WITHDRAWAL).size());
        assertThat(registry.find(RiskEvaluationMetrics.EVALUATION).tag("operation", "MONITORING").meters()).isEmpty();
        for (Meter meter : registry.getMeters()) {
            if (meter.getId().getName().endsWith(".histogram")) {
                continue; // the simple registry's SLO buckets ("le"); Prometheus folds them into the timer itself
            }
            meter.getId().getTags().forEach(tag -> {
                assertThat(MetricNames.ALLOWED_TAG_KEYS).as(meter.getId().getName()).contains(tag.getKey());
                assertThat(tag.getValue()).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}.*");
            });
        }
        assertThat(MetricNames.ALLOWED_TAG_KEYS).contains("rule");
    }

    @Test
    @DisplayName("a report is counted: its verdict, its latency, its fail-safe's reason, each fired rule, its challenge")
    void aReportIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RiskEvaluationMetrics metrics = new RiskEvaluationMetrics(registry);
        metrics.evaluated(new RiskEvaluationObserver.Report(RiskAssessmentId.next(IDS),
                RiskOperation.TRANSFER, RiskDecision.CHALLENGE, Optional.empty(),
                List.of(ReasonCode.AMOUNT_BAND, ReasonCode.AMOUNT_BAND), Optional.of(false),
                VelocityOutcome.NOT_PERMITTED, Duration.ofMillis(12)));
        metrics.evaluated(new RiskEvaluationObserver.Report(RiskAssessmentId.next(IDS),
                RiskOperation.TRANSFER, RiskDecision.DENY, Optional.of(UnavailableCause.TIMEOUT), List.of(),
                Optional.empty(), VelocityOutcome.NOT_PERMITTED, Duration.ofMillis(260)));

        assertThat(registry.get(RiskEvaluationMetrics.EVALUATION).tags("operation", "TRANSFER", "verdict", "CHALLENGE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(RiskEvaluationMetrics.RULE_HIT).tags("operation", "TRANSFER", "rule", "AMOUNT_BAND")
                .counter().count()).isEqualTo(2);
        assertThat(registry.get(RiskEvaluationMetrics.CHALLENGE).tags("operation", "TRANSFER", "outcome", "refused")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(RiskEvaluationMetrics.UNAVAILABLE)
                .tags("operation", "TRANSFER", "reason_code", "RISK_UNAVAILABLE").counter().count()).isEqualTo(1);
        assertThat(registry.get(RiskEvaluationMetrics.LATENCY).tags("operation", "TRANSFER").timer().count())
                .isEqualTo(2);
    }
}
