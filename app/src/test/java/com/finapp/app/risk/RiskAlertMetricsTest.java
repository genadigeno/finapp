package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import com.finapp.risk.AlertFolding;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.SuppressionReason;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alert's meters and wiring (`P13-TSK-015`; {@code PHASE_13_PLAN.md} section 15): every series registered eagerly
 * - every kind by every severity, every suppression reason - every tag key an allowed one, no value an id; a birth and
 * an approved suppression counted; and the composition's folding {@code NONE} until `-016` replaces the bean.
 */
@DisplayName("the alert's meters: eager, bounded tags, births and suppressions counted (P13-TSK-015)")
class RiskAlertMetricsTest {

    @Test
    @DisplayName("registered eagerly - every kind by every severity, every suppression reason code - every tag key"
            + " allowed and no value an id")
    void everySeriesIsEager() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RiskAlertMetrics(registry);
        assertThat(registry.find(RiskAlertMetrics.ALERT).meters())
                .hasSize(AlertKind.values().length * AlertSeverity.values().length);
        assertThat(registry.find(RiskAlertMetrics.SUPPRESSED).meters()).hasSize(SuppressionReason.values().length);
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertThat(MetricNames.ALLOWED_TAG_KEYS).as(meter.getId().getName()).contains(tag.getKey());
                assertThat(tag.getValue()).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}.*");
            });
        }
    }

    @Test
    @DisplayName("a birth is counted by its kind and severity, an approved suppression by its reason code")
    void birthsAndSuppressionsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RiskAlertMetrics metrics = new RiskAlertMetrics(registry);
        metrics.raised(AlertKind.AML, AlertSeverity.HIGH);
        metrics.raised(AlertKind.AML, AlertSeverity.HIGH);
        metrics.suppressed(SuppressionReason.RULE_DEFECT);
        assertThat(registry.get(RiskAlertMetrics.ALERT).tags("kind", "AML", "severity", "HIGH").counter().count())
                .isEqualTo(2);
        assertThat(registry.get(RiskAlertMetrics.ALERT).tags("kind", "FRAUD", "severity", "HIGH").counter().count())
                .isZero();
        assertThat(registry.get(RiskAlertMetrics.SUPPRESSED).tag("reason_code", "RULE_DEFECT").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the composition folds nothing until P13-TSK-016: its AlertFolding bean is NONE")
    void theFoldingIsNoneUntilTheCase() {
        assertThat(new RiskAlertBeans().alertFolding()).isSameAs(AlertFolding.NONE);
    }
}
