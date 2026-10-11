package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsStore;
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
 * {@code finapp.lending.terms.active{product}} (`P11-TSK-005`): the ACTIVE terms version's number per product, 0 for
 * none (the product is not offered), NaN - never zero - when the read fails; registered eagerly for every product, and
 * cached.
 */
@DisplayName("the active loan terms gauge (P11-TSK-005)")
class LoanTermsMetricsTest {

    @Test
    @DisplayName("every product has its series from startup: the active version's number, 0 for a product with none")
    void theActiveVersionPerProduct() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new LoanTermsMetrics(store(Map.of(LoanProduct.PERSONAL_LOAN, 4), new AtomicInteger()),
                LoanTermsMetricsTest::connection, Clock.systemUTC(), registry);
        assertThat(gauge(registry, LoanProduct.PERSONAL_LOAN)).isEqualTo(4.0);
        assertThat(gauge(registry, LoanProduct.CREDIT_LINE)).as("none active - not offered").isEqualTo(0.0);
        assertThat(registry.find(LoanTermsMetrics.ACTIVE).gauges()).hasSize(LoanProduct.values().length);
    }

    @Test
    @DisplayName("an unreadable database reads NaN, never zero - zero would be a false alarm, or a false all-clear")
    void anUnreadableReadIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new LoanTermsMetrics(store(Map.of(), new AtomicInteger()), () -> {
            throw new SQLException("down");
        }, Clock.systemUTC(), registry);
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(gauge(registry, product)).as(product.name()).isNaN();
        }
    }

    @Test
    @DisplayName("the read is cached for 15 seconds - a scrape storm reads the database once")
    void theReadIsCached() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        Instant now = Instant.parse("2026-10-11T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        new LoanTermsMetrics(store(Map.of(LoanProduct.CREDIT_LINE, 1), reads), LoanTermsMetricsTest::connection, clock,
                registry);
        gauge(registry, LoanProduct.CREDIT_LINE);
        gauge(registry, LoanProduct.PERSONAL_LOAN);
        assertThat(reads).hasValue(1);
        clock.now = now.plus(Duration.ofSeconds(16));
        assertThat(gauge(registry, LoanProduct.CREDIT_LINE)).isEqualTo(1.0);
        assertThat(reads).hasValue(2);
    }

    private static double gauge(SimpleMeterRegistry registry, LoanProduct product) {
        Gauge gauge = registry.find(LoanTermsMetrics.ACTIVE).tag("product", product.name()).gauge();
        assertThat(gauge).as(product.name() + " is registered").isNotNull();
        return gauge.value();
    }

    /** A store answering only {@code activeVersions}, counting its reads. */
    private static LoanTermsStore store(Map<LoanProduct, Integer> versions, AtomicInteger reads) {
        return (LoanTermsStore) Proxy.newProxyInstance(LoanTermsStore.class.getClassLoader(),
                new Class<?>[] {LoanTermsStore.class}, (proxy, method, args) -> {
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
