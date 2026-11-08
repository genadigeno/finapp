package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.InMemoryDeadLetterStore;
import com.finapp.platform.deadletter.DeadLetterStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code finapp.deadletter.parked} (`P15-TSK-028`): eager, per group, the rest counted, and never a comforting zero. */
@DisplayName("the parked gauge is eager, per group, and absent rather than zero when unreadable (P15-TSK-028)")
class DeadLetterMetricsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> "close".equals(method.getName()) ? null : new UnsupportedOperationException());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("a series per group the handlers name, and one for a group no handler of this release names")
    void everyGroupIsCounted() {
        metrics(Map.of("finapp.kyc", 2L, "finapp.gone", 3L));

        assertThat(value("finapp.kyc")).isEqualTo(2.0);
        assertThat(value("finapp.risk")).as("eager: zero, and present").isZero();
        assertThat(value(DeadLetterMetrics.OTHER)).as("a handler since removed must not hide a parked record").isEqualTo(3.0);
    }

    @Test
    @DisplayName("an unreadable database reports no value, never zero")
    void unreadableIsNotZero() throws SQLException {
        new DeadLetterMetrics(List.of("finapp.kyc"), new InMemoryDeadLetterStore(), () -> {
            throw new SQLException("down");
        }, CLOCK, registry);

        assertThat(value("finapp.kyc")).isNaN();
        assertThat(value(DeadLetterMetrics.OTHER)).isNaN();
    }

    private double value(String group) {
        return registry.get(DeadLetterMetrics.PARKED).tag("group", group).gauge().value();
    }

    private void metrics(Map<String, Long> parked) {
        DeadLetterStore store = (DeadLetterStore) Proxy.newProxyInstance(DeadLetterStore.class.getClassLoader(),
                new Class<?>[] {DeadLetterStore.class}, (proxy, method, args) -> {
                    if ("parkedByGroup".equals(method.getName())) {
                        return parked;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        new DeadLetterMetrics(List.of("finapp.kyc", "finapp.risk"), store, () -> CONNECTION, CLOCK, registry);
    }
}
