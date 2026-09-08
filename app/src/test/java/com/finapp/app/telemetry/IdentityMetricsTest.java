package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.IdentityId;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gauge's two operational properties, neither of which needs a database (`P1-TSK-029`).
 *
 * <p>What it counts is {@code SessionStore.countLive}'s subject and is asserted against a real
 * PostgreSQL in {@code SessionLiveCountDatabaseTest} — because "live, not {@code ACTIVE}" is a
 * claim about a {@code WHERE} clause and a stub could only confirm what its author already
 * believed. What is tested here is the wrapper: what it publishes when it cannot read, and how
 * often it reads.
 */
@DisplayName("the live-session gauge (P1-TSK-029)")
class IdentityMetricsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an unreadable database reports absent, never zero")
    void anUnreadableDatabaseReportsAbsent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new IdentityMetrics(new CountingStore(7), unreachable(), CLOCK, registry);

        // NaN, which Prometheus records as absent. A ZERO would say "nobody is logged in" at the
        // exact moment nothing can be known, and an alert written on a drop to zero would stay
        // SILENT through the outage - the failure this reasoning exists to prevent (P0-TSK-029).
        assertThat(gauge(registry))
                .as("a comforting zero is not alertable; absent data is")
                .isNaN();
    }

    @Test
    @DisplayName("a readable database reports the count")
    void aReadableDatabaseReportsTheCount() {
        // The positive control. Without it the assertion above passes against an implementation
        // that reports NaN unconditionally - which would be a gauge that is never wrong and never
        // useful.
        MeterRegistry registry = new SimpleMeterRegistry();
        new IdentityMetrics(new CountingStore(7), reachable(), CLOCK, registry);

        assertThat(gauge(registry)).isEqualTo(7.0d);
    }

    @Test
    @DisplayName("the reading is cached, so a scrape does not become load on the database")
    void theReadingIsCached() {
        // A scrape asks every gauge at once and several scrapers may be attached. Without a floor,
        // monitoring becomes load on the thing it is monitoring - which is the failure where the
        // observation causes the incident.
        MeterRegistry registry = new SimpleMeterRegistry();
        CountingStore store = new CountingStore(3);
        new IdentityMetrics(store, reachable(), CLOCK, registry);

        for (int scrape = 0; scrape < 20; scrape++) {
            assertThat(gauge(registry)).isEqualTo(3.0d);
        }

        assertThat(store.reads())
                .as("twenty scrapes within the refresh floor must not be twenty queries")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the cache expires, so the gauge is not frozen at its first reading")
    void theCacheExpires() {
        // The other half, and the one that matters more: a cache with no expiry is a gauge that
        // reports the number of sessions there were when the instance started.
        MeterRegistry registry = new SimpleMeterRegistry();
        CountingStore store = new CountingStore(3);
        MovingClock clock = new MovingClock(CLOCK.instant());
        new IdentityMetrics(store, reachable(), clock, registry);

        assertThat(gauge(registry)).isEqualTo(3.0d);
        clock.advance(IdentityMetrics.MIN_REFRESH.plusSeconds(1));
        assertThat(gauge(registry)).isEqualTo(3.0d);

        assertThat(store.reads()).as("past the floor, it reads again").isEqualTo(2);
    }

    // -----------------------------------------------------------------

    private static double gauge(MeterRegistry registry) {
        return registry.get(IdentityMetrics.ACTIVE_SESSIONS).gauge().value();
    }

    /**
     * A connection that is never used: {@code CountingStore} ignores it.
     *
     * <p>{@code null} rather than a mock, because the point of the {@code Connections} seam is that
     * this test needs no database — and a mock connection would be a thing to maintain that nothing
     * asks a question of.
     */
    private static IdentityMetrics.Connections reachable() {
        return () -> null;
    }

    private static IdentityMetrics.Connections unreachable() {
        return () -> {
            throw new SQLException("the database is unreachable");
        };
    }

    /** Counts reads, so "is it cached" is a fact rather than an inference from timing. */
    private static final class CountingStore implements SessionStore<Connection> {

        private final long live;
        private final AtomicInteger reads = new AtomicInteger();

        CountingStore(long live) {
            this.live = live;
        }

        int reads() {
            return reads.get();
        }

        @Override
        public long countLive(Connection unitOfWork, Instant at) {
            reads.incrementAndGet();
            return live;
        }

        @Override
        public void insert(Connection unitOfWork, Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Session> findLive(Connection unitOfWork, SessionToken token, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Session> findLiveFor(Connection unitOfWork, IdentityId identityId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revoke(Connection unitOfWork, SessionId sessionId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OptionalLong revokeOwned(
                Connection unitOfWork, SessionId sessionId, IdentityId owner, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int revokeAllFor(Connection unitOfWork, IdentityId identityId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int revokeAllForExcept(
                Connection unitOfWork, IdentityId identityId, SessionId spare, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean touch(
                Connection unitOfWork,
                SessionId sessionId,
                Instant at,
                com.finapp.identity.SessionPolicy policy) {
            throw new UnsupportedOperationException();
        }
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
