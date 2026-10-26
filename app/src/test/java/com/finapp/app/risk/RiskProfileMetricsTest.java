package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.CustomerRiskProfileStore;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.SignalKind;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.risk.profile.rating{bucket}} and {@code finapp.risk.signal{kind,outcome}} (`P13-TSK-024`; plan section
 * 15): the ratings gauged per bucket - every rating and {@code UNRATED} - from startup, read never on the scrape's thread
 * (single-flight, NaN when unreadable or stale - {@code RescreeningMetrics}' pattern); the in-memory store counts the
 * heads as {@code JdbcCustomerRiskProfileStore.RATINGS} groups them (the database's half is
 * {@code ProfileDatabaseTest#theRatingsCountTheHeads}, owed, C5); every signal recorded counted by kind and outcome.
 */
@DisplayName("the rating distribution and the signal counter (P13-TSK-024)")
class RiskProfileMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger readings = new AtomicInteger();
    private final AtomicReference<CustomerRiskProfileStore.Ratings> figures = new AtomicReference<>(
            new CustomerRiskProfileStore.Ratings(Map.of(RiskRating.LOW, 7L, RiskRating.HIGH, 2L), 5));
    private final AtomicReference<RuntimeException> failing = new AtomicReference<>();
    private final List<Runnable> queued = new ArrayList<>();

    private final RiskProfileMetrics metrics = new RiskProfileMetrics(() -> {
        readings.incrementAndGet();
        if (failing.get() != null) {
            throw failing.get();
        }
        return figures.get();
    }, queued::add, clock, registry);

    @Test
    @DisplayName("every bucket is registered from startup - each rating and UNRATED - and registration reads nothing")
    void registeredEagerly() {
        assertThat(RiskProfileMetrics.buckets()).containsExactly("LOW", "MEDIUM", "HIGH", "PROHIBITED", "UNRATED");
        for (String bucket : RiskProfileMetrics.buckets()) {
            assertThat(registry.get(RiskProfileMetrics.RATING).tag("bucket", bucket).gauge()).as(bucket).isNotNull();
        }
        assertThat(registry.get(RiskProfileMetrics.RATING).gauges()).hasSize(5);
        assertThat(readings).hasValue(0);
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("a scrape starts one reading off its thread and answers NaN until it completes; then each bucket's count,"
            + " 0 for a rating no party holds")
    void theRatingsAreReadOffTheScrapeThread() {
        for (int scrape = 0; scrape < 10; scrape++) {
            assertThat(gauge("LOW")).isNaN();
        }
        assertThat(readings).as("not on the scrape thread").hasValue(0);
        assertThat(queued).as("single-flight").hasSize(1);
        queued.removeFirst().run();
        assertThat(gauge("LOW")).isEqualTo(7.0);
        assertThat(gauge("MEDIUM")).isZero();
        assertThat(gauge("HIGH")).isEqualTo(2.0);
        assertThat(gauge("PROHIBITED")).isZero();
        assertThat(gauge("UNRATED")).isEqualTo(5.0);
        assertThat(queued).as("behind the floor nothing more is started").isEmpty();
    }

    @Test
    @DisplayName("a failed reading is NaN, never zero; a reading that never finishes goes NaN at two floors")
    void unreadableOrStaleIsNaN() {
        failing.set(new IllegalStateException("statement timeout"));
        gauge("LOW");
        queued.removeFirst().run();
        assertThat(gauge("LOW")).isNaN();
        failing.set(null);
        clock.now = clock.now.plus(RiskProfileMetrics.MIN_REFRESH);
        gauge("LOW");
        queued.removeFirst().run();
        assertThat(gauge("LOW")).isEqualTo(7.0);
        clock.now = clock.now.plus(RiskProfileMetrics.MIN_REFRESH);
        assertThat(gauge("LOW")).as("started, not finished: the last figure within two floors").isEqualTo(7.0);
        clock.now = clock.now.plus(RiskProfileMetrics.MIN_REFRESH);
        assertThat(gauge("LOW")).as("stale at two floors").isNaN();
        assertThat(queued).as("still single-flight while the first is stuck").hasSize(1);
    }

    @Test
    @DisplayName("the in-memory store counts the heads by rating, an unassessed head UNRATED - what the gauge reads")
    void theStoreCountsTheHeads() {
        InMemoryCustomerRiskProfileStore store = new InMemoryCustomerRiskProfileStore();
        store.pullForward(null, UUID.randomUUID());
        store.seedDisposition(UUID.randomUUID(), RiskRating.PROHIBITED);
        store.seedDisposition(UUID.randomUUID(), RiskRating.HIGH);
        store.seedDisposition(UUID.randomUUID(), RiskRating.HIGH);
        CustomerRiskProfileStore.Ratings ratings = store.ratings(null);
        assertThat(ratings.unrated()).isEqualTo(1);
        assertThat(ratings.at(RiskRating.HIGH)).isEqualTo(2);
        assertThat(ratings.at(RiskRating.PROHIBITED)).isEqualTo(1);
        assertThat(ratings.at(RiskRating.LOW)).isZero();
    }

    @Test
    @DisplayName("every signal kind and outcome is counted from startup; a recording counts its own pair only")
    void everySignalIsCounted() {
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        RiskSignalMetrics signals = new RiskSignalMetrics(own);
        assertThat(own.get(RiskSignalMetrics.SIGNAL).counters())
                .hasSize(SignalKind.values().length * RiskSignals.Outcome.values().length);
        signals.recorded(SignalKind.LOAN_DEFAULTED, RiskSignals.Outcome.BORN);
        signals.recorded(SignalKind.LOAN_DEFAULTED, RiskSignals.Outcome.ALREADY);
        signals.recorded(SignalKind.LOAN_DEFAULTED, RiskSignals.Outcome.ALREADY);
        assertThat(own.get(RiskSignalMetrics.SIGNAL).tag("kind", "LOAN_DEFAULTED").tag("outcome", "born").counter()
                .count()).isEqualTo(1.0);
        assertThat(own.get(RiskSignalMetrics.SIGNAL).tag("kind", "LOAN_DEFAULTED").tag("outcome", "already").counter()
                .count()).isEqualTo(2.0);
        assertThat(own.get(RiskSignalMetrics.SIGNAL).tag("kind", "SCREENING_HIT").tag("outcome", "born").counter()
                .count()).isZero();
    }

    private double gauge(String bucket) {
        return registry.get(RiskProfileMetrics.RATING).tag("bucket", bucket).gauge().value();
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
