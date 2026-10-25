package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case's meters (`P13-TSK-016`; {@code PHASE_13_PLAN.md} section 15) read for the collections kind (`P13-TSK-025`,
 * the entry's {@code case{kind=COLLECTIONS,status}}): every kind by every status registered eagerly, so a fresh
 * instance publishes {@code COLLECTIONS} from startup; a move counted by its kind and status, a resolution by its kind;
 * every tag key an allowed one and no value an id - and the default's alert kind among {@code alert{kind,severity}}.
 */
@DisplayName("the case's meters carry the collections kind (P13-TSK-025)")
class RiskCaseMetricsTest {

    @Test
    @DisplayName("case{kind=COLLECTIONS,status} and case.resolution{kind=COLLECTIONS} registered eagerly and counted;"
            + " alert{kind=OBLIGATION_DEFAULTED,severity} beside them; bounded tags only")
    void theCollectionsKindIsMetered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RiskCaseMetrics metrics = new RiskCaseMetrics(registry);
        new RiskAlertMetrics(registry);
        assertThat(registry.find(RiskCaseMetrics.CASE).meters())
                .hasSize(CaseKind.values().length * CaseStatus.values().length);
        for (CaseStatus status : CaseStatus.values()) {
            assertThat(registry.get(RiskCaseMetrics.CASE).tags("kind", "COLLECTIONS", "status", status.name())
                    .counter().count()).as(status.name()).isZero();
        }
        assertThat(registry.get(RiskCaseMetrics.RESOLUTION).tag("kind", "COLLECTIONS").timer().count()).isZero();
        for (AlertSeverity severity : AlertSeverity.values()) {
            assertThat(registry.get(RiskAlertMetrics.ALERT).tags("kind", AlertKind.OBLIGATION_DEFAULTED.name(),
                    "severity", severity.name()).counter()).isNotNull();
        }

        metrics.moved(CaseKind.COLLECTIONS, CaseStatus.OPEN);
        metrics.moved(CaseKind.COLLECTIONS, CaseStatus.DISPOSED);
        metrics.resolved(CaseKind.COLLECTIONS, Duration.ofHours(30));
        assertThat(registry.get(RiskCaseMetrics.CASE).tags("kind", "COLLECTIONS", "status", "OPEN").counter().count())
                .isEqualTo(1);
        assertThat(registry.get(RiskCaseMetrics.CASE).tags("kind", "COLLECTIONS", "status", "DISPOSED").counter()
                .count()).isEqualTo(1);
        assertThat(registry.get(RiskCaseMetrics.CASE).tags("kind", "AML", "status", "OPEN").counter().count()).isZero();
        assertThat(registry.get(RiskCaseMetrics.RESOLUTION).tag("kind", "COLLECTIONS").timer().count()).isEqualTo(1);
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertThat(MetricNames.ALLOWED_TAG_KEYS).as(meter.getId().getName()).contains(tag.getKey());
                assertThat(tag.getValue()).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}.*");
            });
        }
    }
}
