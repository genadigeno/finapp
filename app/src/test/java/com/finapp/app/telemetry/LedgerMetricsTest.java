package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.ProjectionVerification;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drift gauge's operational properties, none of which needs a database (`P3-TSK-010`).
 *
 * <p>What the number <em>means</em> is {@code ProjectionVerification}'s subject, proven
 * against a real PostgreSQL in {@code ProjectionVerificationDatabaseTest}. What is tested
 * here is the wrapper: what it publishes when it cannot verify, and how often it verifies —
 * and the NaN half matters more on this meter than on any sibling, because a zero here means
 * "verified clean", so a comforting zero would silence the one alert the gauge exists to
 * fire.
 */
@DisplayName("the projection-drift gauge (P3-TSK-010)")
class LedgerMetricsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an unverifiable projection reports absent, never zero")
    void anUnverifiableProjectionReportsAbsent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new LedgerMetrics(counting(3).verification, unreachable(), CLOCK, registry);

        // Zero means "verified clean". Publishing it when nothing could be verified would
        // keep the drift alert silent through the exact outage it exists for (P1-TSK-029).
        assertThat(gauge(registry)).as("absent is alertable; a comforting zero is not").isNaN();
    }

    @Test
    @DisplayName("a completed verification reports the drifting count")
    void aCompletedVerificationReportsTheDriftingCount() {
        // The positive control, without which the NaN assertion passes against a gauge
        // that is never wrong and never useful.
        MeterRegistry registry = new SimpleMeterRegistry();
        new LedgerMetrics(counting(3).verification, reachable(), CLOCK, registry);

        assertThat(gauge(registry)).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("the reading is cached, so a scrape does not become a full recomputation")
    void theReadingIsCached() {
        // This gauge's reading walks every posted account and folds its history, so an
        // uncached version would make every scrape a full ledger recomputation - the
        // observation causing the incident.
        MeterRegistry registry = new SimpleMeterRegistry();
        Counting counting = counting(0);
        new LedgerMetrics(counting.verification, reachable(), CLOCK, registry);

        for (int scrape = 0; scrape < 20; scrape++) {
            assertThat(gauge(registry)).isEqualTo(0.0d);
        }
        assertThat(counting.runs.get())
                .as("twenty scrapes within the floor must not be twenty sweeps")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the cache expires, so the gauge is not frozen at its first verification")
    void theCacheExpires() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Counting counting = counting(0);
        MovingClock clock = new MovingClock(CLOCK.instant());
        new LedgerMetrics(counting.verification, reachable(), clock, registry);

        assertThat(gauge(registry)).isEqualTo(0.0d);
        clock.advance(LedgerMetrics.MIN_REFRESH.plusSeconds(1));
        assertThat(gauge(registry)).isEqualTo(0.0d);

        assertThat(counting.runs.get()).as("past the floor, it verifies again").isEqualTo(2);
    }

    // -----------------------------------------------------------------

    private static double gauge(MeterRegistry registry) {
        return registry.get(LedgerMetrics.PROJECTION_DRIFT).gauge().value();
    }

    private static LedgerMetrics.Connections reachable() {
        // Never used: the counting verification ignores it (the IdentityMetricsTest shape).
        return () -> null;
    }

    private static LedgerMetrics.Connections unreachable() {
        return () -> {
            throw new SQLException("the database is unreachable");
        };
    }

    private record Counting(LedgerMetrics.Verification verification, AtomicInteger runs) {}

    /** Counts sweeps, so "is it cached" is a fact rather than an inference from timing. */
    private static Counting counting(long drifting) {
        AtomicInteger runs = new AtomicInteger();
        LedgerMetrics.Verification verification =
                connection -> {
                    runs.incrementAndGet();
                    return new ProjectionVerification.Report(5, drifting, 0, List.of());
                };
        return new Counting(verification, runs);
    }

    /** A clock that moves forwards, so the cache's expiry is exercised rather than waited for. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant from) {
            this.now = from;
        }

        void advance(java.time.Duration by) {
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
