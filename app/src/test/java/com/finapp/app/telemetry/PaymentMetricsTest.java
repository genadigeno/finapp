package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The stuck-payment gauges' own rules (`P5-TSK-017`), hermetically: NaN rather than a false
 * zero, both machines in one number, the oldest age winning, and the refresh floor.
 *
 * <p>The {@code LedgerMetricsTest} shape — the seams exist so this needs no database and no
 * schema, which is what lets the unreadable case be driven at all.
 */
@DisplayName("the stuck-payment gauges (P5-TSK-017)")
class PaymentMetricsTest {

    private static final Instant FIXED = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    @DisplayName("attempts and refunds are one count, and the OLDEST age wins")
    void bothMachinesAreOneReading() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMetrics(
                connection -> new PaymentMetrics.Reading(2, 30),
                connection -> new PaymentMetrics.Reading(3, 900),
                () -> null,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, PaymentMetrics.UNKNOWN_ACTIVE)).isEqualTo(5.0);
        assertThat(gauge(registry, PaymentMetrics.UNKNOWN_AGE))
                .as("the alert watches the oldest parked operation, not the average")
                .isEqualTo(900.0);
    }

    @Test
    @DisplayName("an unreadable database reports NaN, NEVER zero - the alert must not be"
            + " silenced at the moment the platform is least healthy")
    void unreadableIsNaNNeverZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMetrics(
                connection -> {
                    throw new IllegalStateException("the database is unreachable");
                },
                connection -> new PaymentMetrics.Reading(0, 0),
                () -> null,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, PaymentMetrics.UNKNOWN_ACTIVE)).isNaN();
        assertThat(gauge(registry, PaymentMetrics.UNKNOWN_AGE)).isNaN();
    }

    @Test
    @DisplayName("a connection that cannot be opened is NaN too, not an exception at scrape")
    void anUnopenableConnectionIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMetrics(
                connection -> new PaymentMetrics.Reading(1, 1),
                connection -> new PaymentMetrics.Reading(1, 1),
                () -> {
                    throw new SQLException("no connection");
                },
                Clock.fixed(FIXED, ZoneOffset.UTC),
                registry);

        assertThat(gauge(registry, PaymentMetrics.UNKNOWN_ACTIVE)).isNaN();
    }

    @Test
    @DisplayName("the refresh floor holds: N instances scraping cannot become N queries per"
            + " scrape, and the reading refreshes once the floor passes")
    void theRefreshFloorHolds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        MovingClock clock = new MovingClock(FIXED);
        new PaymentMetrics(
                connection -> {
                    reads.incrementAndGet();
                    return new PaymentMetrics.Reading(1, 1);
                },
                connection -> new PaymentMetrics.Reading(0, 0),
                () -> null,
                clock,
                registry);

        // Three scrapes inside the floor: one read (the first), the rest cached.
        gauge(registry, PaymentMetrics.UNKNOWN_ACTIVE);
        gauge(registry, PaymentMetrics.UNKNOWN_AGE);
        gauge(registry, PaymentMetrics.UNKNOWN_ACTIVE);
        assertThat(reads.get()).as("the floor is the fleet's protection").isEqualTo(1);

        clock.advance(PaymentMetrics.MIN_REFRESH.plusSeconds(1));
        gauge(registry, PaymentMetrics.UNKNOWN_ACTIVE);
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
