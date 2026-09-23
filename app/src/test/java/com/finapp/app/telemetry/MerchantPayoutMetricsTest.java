package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.MerchantPayoutStore;
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
 * The stuck-payout gauges' own rules (`P6-TSK-013`), hermetically: the reading published whole,
 * NaN rather than a false zero, and the refresh floor — the {@code PaymentMetricsTest} shape.
 * What the reading COUNTS is the database's to show ({@code MerchantPayoutMetricsDatabaseTest}).
 */
@DisplayName("the stuck-payout gauges (P6-TSK-013)")
class MerchantPayoutMetricsTest {

    private static final Instant FIXED = Instant.parse("2026-09-23T12:00:00Z");

    @Test
    @DisplayName("the reading is published whole: the count, and the OLDEST wait")
    void theReadingIsPublished() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MerchantPayoutMetrics(
                connection -> new MerchantPayoutStore.UnknownReading(3, 1_200),
                () -> null,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, MerchantPayoutMetrics.UNKNOWN_ACTIVE)).isEqualTo(3.0);
        assertThat(gauge(registry, MerchantPayoutMetrics.UNKNOWN_AGE))
                .as("the alert watches the oldest parked payout, not the average")
                .isEqualTo(1_200.0);
    }

    @Test
    @DisplayName("an unreadable database reports NaN, NEVER zero - the alert must not be"
            + " silenced at the moment the platform is least healthy")
    void unreadableIsNaNNeverZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MerchantPayoutMetrics(
                connection -> {
                    throw new IllegalStateException("the database is unreachable");
                },
                () -> null,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, MerchantPayoutMetrics.UNKNOWN_ACTIVE)).isNaN();
        assertThat(gauge(registry, MerchantPayoutMetrics.UNKNOWN_AGE)).isNaN();
    }

    @Test
    @DisplayName("a connection that cannot be opened is NaN too, not an exception at scrape")
    void anUnopenableConnectionIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MerchantPayoutMetrics(
                connection -> new MerchantPayoutStore.UnknownReading(1, 1),
                () -> {
                    throw new SQLException("no connection");
                },
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, MerchantPayoutMetrics.UNKNOWN_ACTIVE)).isNaN();
        assertThat(gauge(registry, MerchantPayoutMetrics.UNKNOWN_AGE)).isNaN();
    }

    @Test
    @DisplayName("the refresh floor holds: N instances scraping cannot become N queries per"
            + " scrape, and the reading refreshes once the floor passes")
    void theRefreshFloorHolds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        MovingClock clock = new MovingClock(FIXED);
        new MerchantPayoutMetrics(
                connection -> {
                    reads.incrementAndGet();
                    return new MerchantPayoutStore.UnknownReading(1, 1);
                },
                () -> null,
                clock,
                registry);

        // Three scrapes inside the floor, across both gauges: one read, the rest cached.
        gauge(registry, MerchantPayoutMetrics.UNKNOWN_ACTIVE);
        gauge(registry, MerchantPayoutMetrics.UNKNOWN_AGE);
        gauge(registry, MerchantPayoutMetrics.UNKNOWN_ACTIVE);
        assertThat(reads.get()).as("the floor is the fleet's protection").isEqualTo(1);

        clock.advance(MerchantPayoutMetrics.MIN_REFRESH.plusSeconds(1));
        gauge(registry, MerchantPayoutMetrics.UNKNOWN_AGE);
        assertThat(reads.get()).as("and it is a floor, not a freeze").isEqualTo(2);
    }

    // -----------------------------------------------------------------

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
