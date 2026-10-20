package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two series {@code PHASE_12_PLAN.md} section 15 names that no earlier task built, hermetic (`P12-TSK-026`):
 * {@code finapp.bnpl.obligations{product, status}} and {@code finapp.bnpl.application.awaiting.age} - registered eagerly,
 * read from the store, NaN when unreadable and never zero for a failed read, re-read only past the 15 s floor, tagged by
 * the closed vocabularies only. No {@code DataSource} named (TestTaxonomyTest).
 */
@DisplayName("the BNPL obligation counts and the oldest decision wait (P12-TSK-026)")
class BnplOperationsMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    @DisplayName("finapp.bnpl.obligations: every product and status registered, the store's counts, absent ones zero, NaN"
            + " when unreadable")
    void theObligationCounts() {
        Map<BnplProduct, Map<BnplObligationStatus, Long>> counts = new EnumMap<>(BnplProduct.class);
        counts.put(BnplProduct.BNPL_PAY_IN_4, new EnumMap<>(Map.of(BnplObligationStatus.ACTIVE, 7L,
                BnplObligationStatus.CLOSED, 2L)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BnplObligationMetrics(conditions(() -> counts), BnplOperationsMetricsTest::connection, Clock.systemUTC(),
                registry);
        assertThat(registry.find(BnplObligationMetrics.OBLIGATIONS).gauges())
                .hasSize(BnplProduct.values().length * BnplObligationStatus.values().length);
        assertThat(value(registry, BnplObligationMetrics.OBLIGATIONS, "bnpl_pay_in_4", "active")).isEqualTo(7.0);
        assertThat(value(registry, BnplObligationMetrics.OBLIGATIONS, "bnpl_pay_in_4", "closed")).isEqualTo(2.0);
        assertThat(value(registry, BnplObligationMetrics.OBLIGATIONS, "bnpl_pay_in_n", "active")).isZero();

        SimpleMeterRegistry unreadable = new SimpleMeterRegistry();
        new BnplObligationMetrics(conditions(() -> counts), () -> {
            throw new SQLException("unreadable");
        }, Clock.fixed(NOW, ZoneOffset.UTC), unreadable);
        assertThat(value(unreadable, BnplObligationMetrics.OBLIGATIONS, "bnpl_pay_in_4", "active")).isNaN();
    }

    @Test
    @DisplayName("finapp.bnpl.application.awaiting.age: the oldest wait in seconds, 0 when none waits, NaN when unreadable,"
            + " re-read only past the floor")
    void theOldestDecisionWait() {
        AtomicReference<Optional<Duration>> oldest = new AtomicReference<>(Optional.of(Duration.ofSeconds(90_061)));
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BnplAwaitingDecisionMetrics(applications(oldest::get), BnplOperationsMetricsTest::connection, clock,
                registry);
        assertThat(registry.get(BnplAwaitingDecisionMetrics.AWAITING_AGE).gauge().value()).isEqualTo(90_061.0);
        assertThat(registry.get(BnplAwaitingDecisionMetrics.AWAITING_AGE).gauge().getId().getTags()).isEmpty();
        oldest.set(Optional.empty());
        assertThat(registry.get(BnplAwaitingDecisionMetrics.AWAITING_AGE).gauge().value())
                .as("within the floor: the cached reading").isEqualTo(90_061.0);
        now.set(NOW.plus(BnplAwaitingDecisionMetrics.MIN_REFRESH));
        assertThat(registry.get(BnplAwaitingDecisionMetrics.AWAITING_AGE).gauge().value()).as("none waits").isZero();

        SimpleMeterRegistry unreadable = new SimpleMeterRegistry();
        new BnplAwaitingDecisionMetrics(applications(oldest::get), () -> {
            throw new SQLException("unreadable");
        }, Clock.fixed(NOW, ZoneOffset.UTC), unreadable);
        assertThat(unreadable.get(BnplAwaitingDecisionMetrics.AWAITING_AGE).gauge().value()).isNaN();
    }

    private static double value(SimpleMeterRegistry registry, String name, String product, String status) {
        return registry.get(name).tags("product", product, "status", status).gauge().value();
    }

    /** A condition store answering the obligation counts only. */
    private static BnplConditionStore conditions(
            java.util.function.Supplier<Map<BnplProduct, Map<BnplObligationStatus, Long>>> counts) {
        return (BnplConditionStore) Proxy.newProxyInstance(BnplOperationsMetricsTest.class.getClassLoader(),
                new Class<?>[] {BnplConditionStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("obligationsByProductAndStatus")) {
                        return counts.get();
                    }
                    throw new UnsupportedOperationException("the gauge reads the obligation counts only");
                });
    }

    /** An application store answering the oldest wait only. */
    private static BnplApplicationStore applications(java.util.function.Supplier<Optional<Duration>> oldest) {
        return (BnplApplicationStore) Proxy.newProxyInstance(BnplOperationsMetricsTest.class.getClassLoader(),
                new Class<?>[] {BnplApplicationStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("oldestAwaitingDecisionAge")) {
                        return oldest.get();
                    }
                    throw new UnsupportedOperationException("the gauge reads the oldest wait only");
                });
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(BnplOperationsMetricsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    }
}
