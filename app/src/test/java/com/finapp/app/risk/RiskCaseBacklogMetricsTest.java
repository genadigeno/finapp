package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import com.finapp.risk.CaseKind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case queue's gauges (`P13-TSK-018`; {@code PHASE_13_PLAN.md} section 15): {@code finapp.risk.case.backlog{kind}}
 * and {@code finapp.risk.case.age{kind}} registered eagerly for every {@link CaseKind}, read from the queue's one grouped
 * statement - the open cases and the seconds since the oldest opened, 0 for a kind with none - behind a 15 s floor; NaN
 * when unreadable, never zero; tags from the closed set only.
 */
@DisplayName("the case backlog gauges: open cases and the oldest's age per kind, NaN when unreadable (P13-TSK-018)")
class RiskCaseBacklogMetricsTest {

    private final RiskActionWorld world = new RiskActionWorld();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-25T12:00:00Z"));
    private final Clock clock = new Clock() {
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
            return now.get();
        }
    };
    private boolean unreadable;

    @Test
    @DisplayName("eager per kind; the open cases counted and the oldest's age on the store's clock; 0 for a kind with"
            + " none open; refreshed only after the floor")
    void theFiguresAreTheQueues() {
        RiskCaseBacklogMetrics metrics = new RiskCaseBacklogMetrics(world.caseStore, this::connection, clock, registry);
        assertThat(metrics).isNotNull();
        for (CaseKind kind : CaseKind.values()) {
            assertThat(registry.find(RiskCaseBacklogMetrics.BACKLOG).tag("kind", kind.name()).gauge()).isNotNull();
            assertThat(registry.find(RiskCaseBacklogMetrics.AGE).tag("kind", kind.name()).gauge()).isNotNull();
        }
        world.partyCase(CaseKind.FRAUD);
        world.partyCase(CaseKind.FRAUD);
        world.caseStore.now = world.caseStore.now.plus(Duration.ofMinutes(10));
        assertThat(value(RiskCaseBacklogMetrics.BACKLOG, CaseKind.FRAUD)).isEqualTo(2.0);
        assertThat(value(RiskCaseBacklogMetrics.AGE, CaseKind.FRAUD)).isEqualTo(600.0);
        assertThat(value(RiskCaseBacklogMetrics.BACKLOG, CaseKind.AML)).isZero();
        assertThat(value(RiskCaseBacklogMetrics.AGE, CaseKind.AML)).isZero();

        world.partyCase(CaseKind.AML);
        assertThat(value(RiskCaseBacklogMetrics.BACKLOG, CaseKind.AML)).as("within the floor: the cached figure")
                .isZero();
        now.set(now.get().plus(RiskCaseBacklogMetrics.MIN_REFRESH));
        assertThat(value(RiskCaseBacklogMetrics.BACKLOG, CaseKind.AML)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("unreadable is NaN, never zero; every tag is a closed key and a CaseKind name")
    void unreadableIsNaN() {
        new RiskCaseBacklogMetrics(world.caseStore, this::connection, clock, registry);
        unreadable = true;
        assertThat(value(RiskCaseBacklogMetrics.BACKLOG, CaseKind.FRAUD)).isNaN();
        assertThat(value(RiskCaseBacklogMetrics.AGE, CaseKind.ATO)).isNaN();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(MetricNames.ALLOWED_TAG_KEYS).contains(tag.getKey());
                assertThat(CaseKind.valueOf(tag.getValue())).isNotNull();
            }
        }
    }

    private double value(String name, CaseKind kind) {
        Gauge gauge = registry.find(name).tag("kind", kind.name()).gauge();
        assertThat(gauge).isNotNull();
        return gauge.value();
    }

    /** A connection that accepts the read-only flag and its close - the store is in memory. */
    private Connection connection() throws SQLException {
        if (unreadable) {
            throw new SQLException("unreachable", "08001");
        }
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setReadOnly", "close" -> null;
                    case "toString" -> "in-memory";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
