package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.bnpl.terms.active{product}} (`P12-TSK-004`; lending's {@code LoanTermsMetricsTest}): the ACTIVE terms
 * version's number per product, 0 for none - production's closed state - NaN, never zero, when the read fails;
 * registered eagerly for every product, and cached.
 */
@DisplayName("the active BNPL terms gauge (P12-TSK-004)")
class BnplTermsMetricsTest {

    @Test
    @DisplayName("every product has its series from startup: the active version's number, 0 for a product with none")
    void theActiveVersionPerProduct() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BnplTermsMetrics(store(Map.of(BnplProduct.BNPL_PAY_IN_N, 3), new AtomicInteger()),
                BnplTermsMetricsTest::connection, Clock.systemUTC(), registry);
        assertThat(gauge(registry, BnplProduct.BNPL_PAY_IN_N)).isEqualTo(3.0);
        assertThat(gauge(registry, BnplProduct.BNPL_PAY_IN_4)).as("none active - not offered").isEqualTo(0.0);
        assertThat(registry.find(BnplTermsMetrics.ACTIVE).gauges()).hasSize(BnplProduct.values().length);
    }

    @Test
    @DisplayName("an unreadable database reads NaN, never zero - zero would be a false alarm, or a false all-clear")
    void anUnreadableReadIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BnplTermsMetrics(store(Map.of(), new AtomicInteger()), () -> {
            throw new SQLException("down");
        }, Clock.systemUTC(), registry);
        for (BnplProduct product : BnplProduct.values()) {
            assertThat(gauge(registry, product)).as(product.name()).isNaN();
        }
    }

    @Test
    @DisplayName("the read is cached for 15 seconds - a scrape storm reads the database once")
    void theReadIsCached() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        Instant now = Instant.parse("2026-10-17T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        new BnplTermsMetrics(store(Map.of(BnplProduct.BNPL_PAY_IN_4, 1), reads), BnplTermsMetricsTest::connection, clock,
                registry);
        gauge(registry, BnplProduct.BNPL_PAY_IN_4);
        gauge(registry, BnplProduct.BNPL_PAY_IN_N);
        assertThat(reads).hasValue(1);
        clock.now = now.plus(Duration.ofSeconds(16));
        assertThat(gauge(registry, BnplProduct.BNPL_PAY_IN_4)).isEqualTo(1.0);
        assertThat(reads).hasValue(2);
    }

    private static double gauge(SimpleMeterRegistry registry, BnplProduct product) {
        Gauge gauge = registry.find(BnplTermsMetrics.ACTIVE).tag("product", product.name()).gauge();
        assertThat(gauge).as(product.name() + " is registered").isNotNull();
        return gauge.value();
    }

    /** A store answering only {@code activeVersions}, counting its reads. */
    private static BnplTermsStore store(Map<BnplProduct, Integer> versions, AtomicInteger reads) {
        return (BnplTermsStore) Proxy.newProxyInstance(BnplTermsStore.class.getClassLoader(),
                new Class<?>[] {BnplTermsStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("activeVersions")) {
                        reads.incrementAndGet();
                        return versions;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A connection whose every call is a no-op - the store above never uses it. */
    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> null);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
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
