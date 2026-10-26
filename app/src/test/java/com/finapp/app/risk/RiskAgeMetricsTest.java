package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.MonitoringWatermark;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.risk.monitoring.age{source}} and {@code finapp.risk.report.unconcluded.age} (`P13-TSK-024`, the review's
 * IMPORTANT 1 and 2): both registered from startup; the monitoring's age dated by the first unmonitored entry's UUIDv7 on
 * the database's clock and the filing's by its {@code filed_at}, 0 for none, never negative; read never on the scrape's
 * thread (single-flight), NaN when unreadable or stale - never 0. The statements' database half is owed (C5).
 */
@DisplayName("the ages: the oldest unmonitored entry and the oldest unconcluded filing, on the database's clock"
        + " (P13-TSK-024)")
class RiskAgeMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger readings = new AtomicInteger();
    private final AtomicReference<RiskAgeMetrics.Ages> ages = new AtomicReference<>(new RiskAgeMetrics.Ages(4_000, 120));
    private final AtomicReference<RuntimeException> failing = new AtomicReference<>();
    private final List<Runnable> queued = new ArrayList<>();

    private final RiskAgeMetrics metrics = new RiskAgeMetrics(() -> {
        readings.incrementAndGet();
        if (failing.get() != null) {
            throw failing.get();
        }
        return ages.get();
    }, queued::add, clock, registry);

    @Test
    @DisplayName("both gauges are registered from startup, the monitoring's tagged by its source - registration reads"
            + " nothing")
    void registeredEagerly() {
        assertThat(registry.get(RiskAgeMetrics.MONITORING_AGE).tag("source", MonitoringWatermark.LEDGER_JOURNAL).gauge())
                .isNotNull();
        assertThat(registry.get(RiskAgeMetrics.FILING_AGE).gauge()).isNotNull();
        assertThat(readings).hasValue(0);
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("an age is the database's now less the first unmonitored entry's UUIDv7 millisecond, or the oldest FILED"
            + " report's filing - 0 for none, never negative (an instance's clock minted the journal's id)")
    void theAgesAreOnTheDatabasesClock() {
        UUID posted = MonitoringWatermark.positionAt(NOW.minus(Duration.ofMinutes(90)).toEpochMilli());
        RiskAgeMetrics.Ages aged = RiskAgeMetrics.ages(NOW, Optional.of(posted),
                Optional.of(NOW.minus(Duration.ofMinutes(31))));
        assertThat(aged.monitoringSeconds()).isEqualTo(5_400);
        assertThat(aged.filingSeconds()).isEqualTo(1_860);
        assertThat(RiskAgeMetrics.ages(NOW, Optional.empty(), Optional.empty()))
                .isEqualTo(new RiskAgeMetrics.Ages(0, 0));
        UUID ahead = MonitoringWatermark.positionAt(NOW.plusSeconds(3).toEpochMilli());
        assertThat(RiskAgeMetrics.ages(NOW, Optional.of(ahead), Optional.empty()).monitoringSeconds())
                .as("an id minted by a clock ahead of the database's").isZero();
    }

    @Test
    @DisplayName("a scrape starts one reading off its thread and answers NaN until it completes; then the ages")
    void theAgesAreReadOffTheScrapeThread() {
        for (int scrape = 0; scrape < 10; scrape++) {
            assertThat(monitoring()).isNaN();
        }
        assertThat(readings).as("not on the scrape thread").hasValue(0);
        assertThat(queued).as("single-flight").hasSize(1);
        queued.removeFirst().run();
        assertThat(monitoring()).isEqualTo(4_000.0);
        assertThat(registry.get(RiskAgeMetrics.FILING_AGE).gauge().value()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("a failed reading is NaN, never 0 - no age would read as nothing standing; a reading that never finishes"
            + " goes NaN at two floors")
    void unreadableOrStaleIsNaN() {
        failing.set(new IllegalStateException("statement timeout"));
        monitoring();
        queued.removeFirst().run();
        assertThat(monitoring()).isNaN();
        failing.set(null);
        clock.now = clock.now.plus(RiskAgeMetrics.MIN_REFRESH);
        monitoring();
        queued.removeFirst().run();
        assertThat(monitoring()).isEqualTo(4_000.0);
        clock.now = clock.now.plus(RiskAgeMetrics.MIN_REFRESH);
        assertThat(monitoring()).as("started, not finished: the last figure within two floors").isEqualTo(4_000.0);
        clock.now = clock.now.plus(RiskAgeMetrics.MIN_REFRESH);
        assertThat(monitoring()).as("stale at two floors").isNaN();
        assertThat(queued).as("still single-flight while the first is stuck").hasSize(1);
    }

    private double monitoring() {
        return registry.get(RiskAgeMetrics.MONITORING_AGE).gauge().value();
    }

    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
