package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.UnderwritingCaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.credit.review.age} (`P10-TSK-018`): the oldest open review case's wait in seconds, 0 when none waits,
 * NaN - never zero - when the read fails; registered eagerly, untagged, and cached.
 */
@DisplayName("the review age gauge (P10-TSK-018)")
class CreditReviewMetricsTest {

    @Test
    @DisplayName("the oldest open case's wait in seconds, and 0 when no case waits - registered from startup, untagged")
    void theOldestOpenCasesWait() {
        SimpleMeterRegistry waiting = new SimpleMeterRegistry();
        new CreditReviewMetrics(store(Optional.of(Duration.ofMillis(90_500)), new AtomicInteger()),
                CreditReviewMetricsTest::connection, Clock.systemUTC(), waiting);
        assertThat(gauge(waiting)).isEqualTo(90.5);
        assertThat(waiting.find(CreditReviewMetrics.AGE).gauge().getId().getTags()).isEmpty();
        SimpleMeterRegistry idle = new SimpleMeterRegistry();
        new CreditReviewMetrics(store(Optional.empty(), new AtomicInteger()), CreditReviewMetricsTest::connection,
                Clock.systemUTC(), idle);
        assertThat(gauge(idle)).as("no case waits").isEqualTo(0.0);
    }

    @Test
    @DisplayName("an unreadable database reads NaN, never zero - zero would be a false all-clear on a silent queue")
    void anUnreadableReadIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditReviewMetrics(store(Optional.empty(), new AtomicInteger()), () -> {
            throw new SQLException("down");
        }, Clock.systemUTC(), registry);
        assertThat(gauge(registry)).isNaN();
    }

    @Test
    @DisplayName("the read is cached for 15 seconds - a scrape storm reads the database once")
    void theReadIsCached() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        new CreditReviewMetrics(store(Optional.of(Duration.ofSeconds(5)), reads), CreditReviewMetricsTest::connection,
                Clock.systemUTC(), registry);
        gauge(registry);
        gauge(registry);
        assertThat(reads).hasValue(1);
    }

    private static double gauge(SimpleMeterRegistry registry) {
        Gauge gauge = registry.find(CreditReviewMetrics.AGE).gauge();
        assertThat(gauge).as("registered").isNotNull();
        return gauge.value();
    }

    /** A store answering only {@code oldestOpenAge}, counting its reads. */
    private static UnderwritingCaseStore store(Optional<Duration> age, AtomicInteger reads) {
        return (UnderwritingCaseStore) Proxy.newProxyInstance(UnderwritingCaseStore.class.getClassLoader(),
                new Class<?>[] {UnderwritingCaseStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("oldestOpenAge")) {
                        reads.incrementAndGet();
                        return age;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A connection whose every call is a no-op - the store above never uses it. */
    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> null);
    }
}
