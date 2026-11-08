package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.OutboxBacklog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The dead-letter and freshness gauges read from the backlog's one statement (`P15-TSK-025`). */
@DisplayName("the outbox gauges: dead letters standing, and the oldest row the relay may still publish, from one reading (P15-TSK-025)")
class OutboxGaugesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("each gauge reads its own column, so a lone dead letter counts as one and leaves the publishable age alone")
    void eachGaugeReadsItsColumn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxMetrics(new OutboxBacklog(() -> connection(Map.of(
                "pending", 3L, "oldest_seconds", 900L, "dead_lettered", 1L, "oldest_publishable_seconds", 4L))), CLOCK, registry);

        assertThat(registry.get(OutboxMetrics.DEAD_LETTERED).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(OutboxMetrics.OLDEST_PUBLISHABLE).gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(OutboxMetrics.OLDEST).gauge().value()).as("the old gauge keeps its meaning").isEqualTo(900.0);
    }

    @Test
    @DisplayName("both ages are floored at zero in the statement, so a row stamped by an instance ahead of the database cannot trip the reading's guard and blank every gauge (P15-TSK-025's fix round)")
    void anAgeIsNeverNegative() {
        List<String> statements = new ArrayList<>();
        new OutboxBacklog(() -> connection(Map.of("pending", 1L, "oldest_seconds", 0L, "dead_lettered", 0L, "oldest_publishable_seconds", 0L), statements)).read();

        assertThat(statements).singleElement().satisfies(sql -> assertThat(sql)
                .contains("greatest(coalesce(extract(epoch FROM now() - min(occurred_at))::bigint, 0), 0) AS oldest_seconds")
                .contains("greatest(coalesce(extract(epoch FROM now() - min(occurred_at) FILTER (WHERE dead_lettered_at IS NULL))::bigint, 0), 0)"));
    }

    @Test
    @DisplayName("an unreadable backlog is NaN on the new gauges too, never a zero that says nothing is dead-lettered")
    void unreadableIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxMetrics(new OutboxBacklog(() -> {
            throw new SQLException("the database is away");
        }), CLOCK, registry);

        assertThat(registry.get(OutboxMetrics.DEAD_LETTERED).gauge().value()).isNaN();
        assertThat(registry.get(OutboxMetrics.OLDEST_PUBLISHABLE).gauge().value()).isNaN();
    }

    private static Connection connection(Map<String, Long> columns) {
        return connection(columns, new ArrayList<>());
    }

    private static Connection connection(Map<String, Long> columns, List<String> statements) {
        ResultSet rows = proxy(ResultSet.class, (name, args) -> switch (name) {
            case "next" -> true;
            case "getLong" -> columns.get(String.valueOf(args[0]));
            default -> null;
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (name, args) -> name.equals("executeQuery") ? rows : null);
        return proxy(Connection.class, (name, args) -> {
            if (!name.equals("prepareStatement")) {
                return null;
            }
            statements.add(String.valueOf(args[0]));
            return statement;
        });
    }

    @FunctionalInterface
    private interface Answer {
        Object of(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(OutboxGaugesTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> method.getName().equals("close") ? null : answer.of(method.getName(), args));
    }
}
