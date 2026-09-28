package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.PaymentAttemptStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stuck-operation gauge pair's own rules (`P7-TSK-015`), hermetically — the
 * {@code MerchantPayoutMetricsTest} shape: the reading published whole, NaN rather than a false
 * zero, and the refresh floor. What the readings COUNT is the database's to show
 * ({@code RailAndDisputeMetersDatabaseTest}).
 */
@DisplayName("the stuck-operation gauges (P7-TSK-015)")
class StuckOperationMetricsTest {

    private static final Instant FIXED = Instant.parse("2026-09-27T12:00:00Z");
    private static final StuckOperationMetrics.Series SERIES =
            new StuckOperationMetrics.Series(
                    "finapp.payments.withdrawal.unknown.active", "active",
                    "finapp.payments.withdrawal.unknown.age", "age");

    @Test
    @DisplayName("the reading is published whole: the count, and the OLDEST wait")
    void theReadingIsPublished() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics(connection -> new PaymentAttemptStore.UnknownReading(4, 900), () -> null,
                Clock.fixed(FIXED, ZoneOffset.UTC), registry);

        assertThat(gauge(registry, SERIES.activeName())).isEqualTo(4.0);
        assertThat(gauge(registry, SERIES.ageName()))
                .as("the alert watches the oldest, not the average")
                .isEqualTo(900.0);
    }

    @Test
    @DisplayName("an unreadable database reports NaN, NEVER zero - the alert must not be"
            + " silenced at the moment the platform is least healthy")
    void unreadableIsNaNNeverZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics(
                connection -> {
                    throw new IllegalStateException("the database is unreachable");
                },
                () -> null,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, SERIES.activeName())).isNaN();
        assertThat(gauge(registry, SERIES.ageName())).isNaN();
    }

    @Test
    @DisplayName("a connection that cannot be opened is NaN too, not an exception at scrape")
    void anUnopenableConnectionIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics(
                connection -> new PaymentAttemptStore.UnknownReading(1, 1),
                () -> {
                    throw new SQLException("no connection");
                },
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, SERIES.activeName())).isNaN();
        assertThat(gauge(registry, SERIES.ageName())).isNaN();
    }

    @Test
    @DisplayName("the refresh floor holds: N instances scraping cannot become N queries per"
            + " scrape, and the reading refreshes once the floor passes")
    void theRefreshFloorHolds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        MovingClock clock = new MovingClock(FIXED);
        metrics(
                connection -> {
                    reads.incrementAndGet();
                    return new PaymentAttemptStore.UnknownReading(1, 1);
                },
                () -> null,
                clock,
                registry);

        gauge(registry, SERIES.activeName());
        gauge(registry, SERIES.ageName());
        gauge(registry, SERIES.activeName());
        assertThat(reads.get()).as("the floor is the fleet's protection").isEqualTo(1);

        clock.advance(StuckOperationMetrics.MIN_REFRESH.plusSeconds(1));
        gauge(registry, SERIES.ageName());
        assertThat(reads.get()).as("and it is a floor, not a freeze").isEqualTo(2);
    }

    // -----------------------------------------------------------------

    private static void metrics(
            StuckOperationMetrics.Unknowns unknowns,
            StuckOperationMetrics.Connections connections,
            Clock clock,
            SimpleMeterRegistry registry) {
        new StuckOperationMetrics("stuck-test", SERIES, unknowns, connections, clock, registry);
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).as("%s must be registered eagerly, at construction", name).isNotNull();
        return gauge.value();
    }

    /** The injected clock the gauges judge staleness by — never an ambient one (ADR-0014). */
    private static final class MovingClock extends Clock {

        private Instant now;

        private MovingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

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
