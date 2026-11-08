package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0063's per-instance clock-offset signal (`P15-TSK-025`). */
@DisplayName("finapp.platform.clock.offset: this instance's clock minus the database's, NaN when unreadable (P15-TSK-025)")
class ClockOffsetMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    @Test
    @DisplayName("an instance three seconds ahead of the database reads +3, and one behind reads negative")
    void theOffsetIsSigned() {
        assertThat(gauge(NOW.minusSeconds(3))).isEqualTo(3.0);
        assertThat(gauge(NOW.plusSeconds(2))).isEqualTo(-2.0);
    }

    @Test
    @DisplayName("the two readings bracket the query, so a slow round trip does not read as skew (P15-TSK-025's fix round)")
    void theMidpointBracketsTheQuery() {
        MovingClock clock = new MovingClock(NOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // A four-second round trip, the database answering halfway through it: the two clocks agree.
        new ClockOffsetMetrics(() -> connection(NOW.plusSeconds(2), () -> clock.advance(Duration.ofSeconds(4))), clock, registry);

        assertThat(registry.get(ClockOffsetMetrics.OFFSET).gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a read that fails is NaN, never a zero that says the clocks agree")
    void unreadableIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ClockOffsetMetrics(() -> {
            throw new SQLException("the database is away");
        }, Clock.fixed(NOW, ZoneOffset.UTC), registry);

        assertThat(registry.get(ClockOffsetMetrics.OFFSET).gauge().value()).isNaN();
    }

    @Test
    @DisplayName("repeated scrapes inside its floor read the database once")
    void itsFloorBoundsTheReads() {
        AtomicInteger opened = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ClockOffsetMetrics(() -> {
            opened.incrementAndGet();
            return connection(NOW);
        }, Clock.fixed(NOW, ZoneOffset.UTC), registry);

        registry.get(ClockOffsetMetrics.OFFSET).gauge().value();
        registry.get(ClockOffsetMetrics.OFFSET).gauge().value();

        assertThat(opened).hasValue(1);
    }

    private static double gauge(Instant database) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ClockOffsetMetrics(() -> connection(database), Clock.fixed(NOW, ZoneOffset.UTC), registry);
        return registry.get(ClockOffsetMetrics.OFFSET).gauge().value();
    }

    private static Connection connection(Instant database) {
        return connection(database, () -> { });
    }

    private static Connection connection(Instant database, Runnable whileTheQueryRuns) {
        ResultSet rows = proxy(ResultSet.class, (name, args) -> switch (name) {
            case "next" -> true;
            case "getObject" -> OffsetDateTime.ofInstant(database, ZoneOffset.UTC);
            default -> null;
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (name, args) -> {
            if (!name.equals("executeQuery")) {
                return null;
            }
            whileTheQueryRuns.run();
            return rows;
        });
        return proxy(Connection.class, (name, args) -> name.equals("prepareStatement") ? statement : null);
    }

    /** A clock the query can move: {@code Clock.fixed} cannot show which side of the query a reading was taken. */
    private static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object of(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(ClockOffsetMetricsTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> method.getName().equals("close") ? null : answer.of(method.getName(), args));
    }
}
