package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.DecisionRequestStatus;
import com.finapp.credit.DecisionRequestStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.credit.request.open.age{status}} (`P10-TSK-020`): per open state, the oldest request's age in seconds
 * since submission, 0 for a state no request is in, NaN - never zero - when the read fails; registered eagerly for every
 * open state and no other, and cached.
 */
@DisplayName("the open request age gauge (P10-TSK-020)")
class CreditRequestAgeMetricsTest {

    @Test
    @DisplayName("each open state's oldest age in seconds, 0 for an empty state - one series per open state, none for a"
            + " closed one")
    void eachOpenStatesOldestAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditRequestAgeMetrics(store(Map.of(DecisionRequestStatus.COLLECTING, Duration.ofMillis(90_500),
                        DecisionRequestStatus.IN_REVIEW, Duration.ofDays(9)), new AtomicInteger()),
                CreditRequestAgeMetricsTest::connection, Clock.systemUTC(), registry);
        assertThat(gauge(registry, "collecting")).isEqualTo(90.5);
        assertThat(gauge(registry, "in_review")).isEqualTo(Duration.ofDays(9).toSeconds());
        assertThat(gauge(registry, "submitted")).as("no request is submitted").isEqualTo(0.0);
        assertThat(registry.find(CreditRequestAgeMetrics.AGE).gauges()).hasSize(DecisionRequestStatus.OPEN.size());
        assertThat(registry.find(CreditRequestAgeMetrics.AGE).tag("status", "decided").gauges())
                .as("a closed state has no age").isEmpty();
    }

    @Test
    @DisplayName("an unreadable database reads NaN in every state, never zero - zero would be a false all-clear")
    void anUnreadableReadIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditRequestAgeMetrics(store(Map.of(), new AtomicInteger()), () -> {
            throw new SQLException("down");
        }, Clock.systemUTC(), registry);
        assertThat(gauge(registry, "submitted")).isNaN();
        assertThat(gauge(registry, "in_review")).isNaN();
    }

    @Test
    @DisplayName("the read is cached for 15 seconds - five series and a scrape storm read the database once")
    void theReadIsCached() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        new CreditRequestAgeMetrics(store(Map.of(), reads), CreditRequestAgeMetricsTest::connection, Clock.systemUTC(),
                registry);
        registry.find(CreditRequestAgeMetrics.AGE).gauges().forEach(Gauge::value);
        registry.find(CreditRequestAgeMetrics.AGE).gauges().forEach(Gauge::value);
        assertThat(reads).hasValue(1);
    }

    private static double gauge(SimpleMeterRegistry registry, String status) {
        Gauge gauge = registry.find(CreditRequestAgeMetrics.AGE).tag("status", status).gauge();
        assertThat(gauge).as("registered for %s", status).isNotNull();
        return gauge.value();
    }

    /** A store answering only {@code oldestOpenAges}, counting its reads. */
    private static DecisionRequestStore store(Map<DecisionRequestStatus, Duration> ages, AtomicInteger reads) {
        return (DecisionRequestStore) Proxy.newProxyInstance(DecisionRequestStore.class.getClassLoader(),
                new Class<?>[] {DecisionRequestStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("oldestOpenAges")) {
                        reads.incrementAndGet();
                        return ages;
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
