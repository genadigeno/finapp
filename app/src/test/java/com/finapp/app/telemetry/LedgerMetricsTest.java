package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.TrialBalance;
import com.finapp.sharedkernel.money.CurrencyCode;
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
@DisplayName("the projection-drift, trial-balance and hold gauges (P3-TSK-010/-019/-020)")
class LedgerMetricsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an unverifiable projection reports absent, never zero")
    void anUnverifiableProjectionReportsAbsent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new LedgerMetrics(counting(3).verification, balancedTrial(), noHolds(), unreachable(), CLOCK, registry);

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
        new LedgerMetrics(counting(3).verification, balancedTrial(), noHolds(), reachable(), CLOCK, registry);

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
        new LedgerMetrics(counting.verification, balancedTrial(), noHolds(), reachable(), CLOCK, registry);

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
        new LedgerMetrics(counting.verification, balancedTrial(), noHolds(), reachable(), clock, registry);

        assertThat(gauge(registry)).isEqualTo(0.0d);
        clock.advance(LedgerMetrics.MIN_REFRESH.plusSeconds(1));
        assertThat(gauge(registry)).isEqualTo(0.0d);

        assertThat(counting.runs.get()).as("past the floor, it verifies again").isEqualTo(2);
    }

    @Test
    @DisplayName("the trial-balance series exist per supported currency before any flow runs")
    void theTrialBalanceSeriesAreEager() {
        // P1-TSK-029: a freshly started instance publishes every series, or the
        // zero-threshold alert has nothing to evaluate at exactly the moment it is needed.
        MeterRegistry registry = new SimpleMeterRegistry();
        new LedgerMetrics(counting(0).verification, balancedTrial(), noHolds(), reachable(), CLOCK, registry);

        for (String currency : List.of("EUR", "GBP", "USD")) {
            assertThat(trialGauge(registry, currency))
                    .as("a balanced ledger reads verified-zero for %s", currency)
                    .isEqualTo(0.0d);
        }
    }

    @Test
    @DisplayName("an out-of-balance currency reads 1 while its siblings stay at verified zero")
    void anOutOfBalanceCurrencyReadsOne() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new LedgerMetrics(
                counting(0).verification,
                connection ->
                        new TrialBalance.Report(3, List.of(CurrencyCode.of("GBP"))),
                noHolds(),
                reachable(),
                CLOCK,
                registry);

        assertThat(trialGauge(registry, "GBP")).isEqualTo(1.0d);
        // The siblings stay at zero, so the alert names the currency rather than the world.
        assertThat(trialGauge(registry, "USD")).isEqualTo(0.0d);
        assertThat(trialGauge(registry, "EUR")).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("an unsweepable trial balance reports absent on every series, never zero")
    void anUnsweepableTrialBalanceReportsAbsent() {
        // Zero means "verified balanced" (INV-ACC-01 held). Publishing it when nothing
        // could be swept would silence the one alert the gauge exists to fire - the same
        // rule as the drift gauge, biting the same way.
        MeterRegistry registry = new SimpleMeterRegistry();
        new LedgerMetrics(counting(0).verification, balancedTrial(), noHolds(), unreachable(), CLOCK, registry);

        for (String currency : List.of("EUR", "GBP", "USD")) {
            assertThat(trialGauge(registry, currency)).isNaN();
        }
    }

    @Test
    @DisplayName("the hold gauge reads the active count, and unreadable is absent never zero")
    void theHoldGaugeReadsTheCountAndUnreadableIsAbsent() {
        // Two registries, one claim each: the count is the count (the positive control),
        // and an unreadable count is NaN - zero would say "no reservations stand" at the
        // moment nothing can be known (P1-TSK-029, the sibling gauges' rule).
        MeterRegistry counted = new SimpleMeterRegistry();
        new LedgerMetrics(
                counting(0).verification, balancedTrial(), connection -> 7L, reachable(),
                CLOCK, counted);
        assertThat(holdGauge(counted)).isEqualTo(7.0d);

        MeterRegistry unreadable = new SimpleMeterRegistry();
        new LedgerMetrics(
                counting(0).verification, balancedTrial(), noHolds(), unreachable(),
                CLOCK, unreadable);
        assertThat(holdGauge(unreadable)).as("absent is alertable; a comforting zero is not")
                .isNaN();
    }

    // -----------------------------------------------------------------

    private static double gauge(MeterRegistry registry) {
        return registry.get(LedgerMetrics.PROJECTION_DRIFT).gauge().value();
    }

    private static double trialGauge(MeterRegistry registry, String currency) {
        return registry.get(LedgerMetrics.TRIAL_BALANCE).tag("currency", currency)
                .gauge()
                .value();
    }

    /** Every currency balanced - the healthy state, and most tests' background. */
    private static LedgerMetrics.Trial balancedTrial() {
        return connection -> new TrialBalance.Report(3, List.of());
    }

    /** No reservation stands - the healthy background for the sibling gauges' tests. */
    private static LedgerMetrics.Holds noHolds() {
        return connection -> 0L;
    }

    private static double holdGauge(MeterRegistry registry) {
        return registry.get(LedgerMetrics.HOLD_ACTIVE).gauge().value();
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
