package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditProfileId;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionSnapshotId;
import com.finapp.credit.PinnedVersions;
import com.finapp.credit.ReasonCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision's meters (`P10-TSK-016`): one decision counted once with its closed tags - the policy by version number,
 * the decider by kind - every reason counted by its catalogue code, the latency timed per product; never an id.
 */
@DisplayName("the credit decision's meters (P10-TSK-016, P10-TSK-020)")
class CreditDecisionMetricsTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static CreditDecision declined(String decidedByType) {
        return new CreditDecision(CreditDecisionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f1001")),
                UUID.randomUUID(), UUID.randomUUID(),
                CreditProfileId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f1002")), CreditProduct.CREDIT_LINE,
                DecisionSnapshotId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f1003")), new byte[32],
                DecisionOutcome.DECLINED, Money.ofMinorUnits(500_000, EUR), Optional.empty(), Optional.empty(),
                List.of(ReasonCode.EXPOSURE_LIMIT, ReasonCode.RISK_REFERRAL),
                new PinnedVersions(UUID.randomUUID(), UUID.randomUUID(), 1), "system", decidedByType, Instant.EPOCH,
                Instant.EPOCH.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("a decline: counted once under its closed tags, each reason by its code, the latency timed")
    void aDeclineIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditDecisionMetrics(registry).recorded(declined("SYSTEM"), 3, Duration.ofMinutes(2));
        assertThat(registry.get("finapp.credit.decision").tags("product", "credit_line", "outcome", "declined",
                "policy_version", "3", "decision_maker", "system").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finapp.credit.reason").tags("product", "credit_line", "reason_code", "CRD-EXPOSURE-LIMIT")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finapp.credit.reason").tags("reason_code", "CRD-RISK-REFERRAL").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finapp.credit.decision.latency").tags("product", "credit_line", "decision_maker", "system")
                .timer().count()).isEqualTo(1);
        new CreditDecisionMetrics(registry).recorded(declined("EMPLOYEE"), 3, Duration.ofMinutes(5));
        assertThat(registry.get("finapp.credit.decision").tags("decision_maker", "person", "policy_version", "3")
                .counter().count()).as("a person's decision, by kind").isEqualTo(1.0);
        assertThat(registry.get("finapp.credit.decision.latency").tags("product", "credit_line", "decision_maker", "person")
                .timer().count()).as("a person's latency apart from the platform's").isEqualTo(1);
        assertThat(registry.get("finapp.credit.decision.latency").tags("product", "credit_line", "decision_maker", "system")
                .timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getValue()).as("never an id in a tag").doesNotContain("0190a1b2")));
    }

    @Test
    @DisplayName("published from startup (P10-TSK-020): every product's reasons and latencies, the decision counter at the"
            + " never-incremented version 0, and the objective among the latency's buckets")
    void publishedFromStartup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditDecisionMetrics(registry);
        for (CreditProduct product : CreditProduct.values()) {
            String tag = product.name().toLowerCase(java.util.Locale.ROOT);
            for (String maker : List.of("system", "person")) {
                assertThat(registry.get("finapp.credit.decision.latency").tags("product", tag, "decision_maker", maker)
                        .timer().count()).isZero();
                for (DecisionOutcome outcome : DecisionOutcome.values()) {
                    assertThat(registry.get("finapp.credit.decision").tags("product", tag, "decision_maker", maker,
                            "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT), "policy_version", "0")
                            .counter().count()).isZero();
                }
            }
            for (ReasonCode reason : ReasonCode.values()) {
                assertThat(registry.get("finapp.credit.reason").tags("product", tag, "reason_code", reason.code())
                        .counter().count()).isZero();
            }
        }
        double[] buckets = java.util.Arrays.stream(registry.get("finapp.credit.decision.latency")
                        .tags("product", "personal_loan", "decision_maker", "system").timer().takeSnapshot()
                        .histogramCounts())
                .mapToDouble(count -> count.bucket(java.util.concurrent.TimeUnit.SECONDS))
                .toArray();
        assertThat(buckets).as("the objective is a bucket boundary, so the alert reads it exactly")
                .contains((double) com.finapp.app.telemetry.CreditObjectives.DECISION_LATENCY_P99.toSeconds());
    }
}
