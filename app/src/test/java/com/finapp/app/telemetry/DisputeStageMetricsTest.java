package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.DisputeStage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispute stage gauges' own rules (`P7-TSK-015`), hermetically: every stage registered at
 * construction under a bounded {@code stage} tag, each stage's count published, and NaN for every
 * stage — never a false zero — when the rows cannot be read.
 */
@DisplayName("the dispute stage gauges (P7-TSK-015)")
class DisputeStageMetricsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("every stage is a series from the first scrape, tagged by the enum's own words -"
            + " the open stages the workload, the terminal ones the outcomes")
    void everyStageIsRegisteredEagerly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DisputeStageMetrics(connection -> Map.of(), () -> null, CLOCK, registry);

        Set<String> stages =
                registry.find(DisputeStageMetrics.DISPUTE).gauges().stream()
                        .map(gauge -> gauge.getId().getTag("stage"))
                        .collect(Collectors.toCollection(TreeSet::new));
        assertThat(stages)
                .containsExactlyInAnyOrder(
                        "inquiry", "charged_back", "represented", "won", "lost", "accepted",
                        "closed");
        assertThat(registry.find(DisputeStageMetrics.DISPUTE).gauges())
                .allSatisfy(gauge -> assertThat(gauge.getId().getTags())
                        .as("a count per stage, never an identifier (INV-AUD-02)")
                        .hasSize(1));
    }

    @Test
    @DisplayName("each stage publishes its own count")
    void eachStagePublishesItsCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Map<DisputeStage, Long> counts = new EnumMap<>(DisputeStage.class);
        for (DisputeStage stage : DisputeStage.values()) {
            counts.put(stage, 0L);
        }
        counts.put(DisputeStage.CHARGED_BACK, 3L);
        counts.put(DisputeStage.WON, 2L);
        new DisputeStageMetrics(connection -> counts, () -> null, CLOCK, registry);

        assertThat(gauge(registry, DisputeStage.CHARGED_BACK)).isEqualTo(3.0);
        assertThat(gauge(registry, DisputeStage.WON)).isEqualTo(2.0);
        assertThat(gauge(registry, DisputeStage.LOST)).isZero();
    }

    @Test
    @DisplayName("an unreadable database reports NaN for EVERY stage, never zero")
    void unreadableIsNaNNeverZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DisputeStageMetrics(
                connection -> {
                    throw new IllegalStateException("the database is unreachable");
                },
                () -> null,
                CLOCK,
                registry);

        for (DisputeStage stage : DisputeStage.values()) {
            assertThat(gauge(registry, stage)).as(stage.name()).isNaN();
        }
    }

    private static double gauge(SimpleMeterRegistry registry, DisputeStage stage) {
        Gauge gauge =
                registry.find(DisputeStageMetrics.DISPUTE)
                        .tag("stage", stage.name().toLowerCase(Locale.ROOT))
                        .gauge();
        assertThat(gauge).as("the %s stage must be registered eagerly", stage).isNotNull();
        return gauge.value();
    }
}
