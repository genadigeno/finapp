package com.finapp.app.risk;

import com.finapp.risk.CaseKind;
import com.finapp.risk.RiskCaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The case queue's gauges (`P13-TSK-018`; {@code PHASE_13_PLAN.md} section 15; `-019`'s {@code RiskMonitoringMetrics}
 * shape), read from the queue itself - no projection - and registered eagerly for every {@link CaseKind}, so a fresh
 * instance publishes them from startup:
 *
 * <ul>
 *   <li>{@code finapp.risk.case.backlog{kind}} - the open cases ({@code OPEN}, {@code IN_INVESTIGATION},
 *       {@code ESCALATED}); alert above the queue's staffed capacity;</li>
 *   <li>{@code finapp.risk.case.age{kind}} - seconds since the oldest open case opened, on the database clock; 0 when
 *       none is open; alert older than the kind's SLA (the case SLA alert's queue - the rule is `-024`'s,
 *       {@code infra/prometheus/rules/risk.yml}).</li>
 * </ul>
 *
 * One grouped statement on {@code risk V014}'s {@code risk_case_open_queue} behind a 15 s floor, so every instance
 * reports the same figure: aggregate with {@code max()}, never {@code sum()}. NaN when unreadable, never zero for a read
 * that failed. No tag carries an id, a subject or a person ({@code INV-RSK-08}; {@code kind} is
 * {@code MetricNames.ALLOWED_TAG_KEYS}' and a {@link CaseKind} name).
 */
@Slf4j
public final class RiskCaseBacklogMetrics {

    /** Exported as {@code finapp_risk_case_backlog}. */
    public static final String BACKLOG = "finapp.risk.case.backlog";

    /** Exported as {@code finapp_risk_case_age}. */
    public static final String AGE = "finapp.risk.case.age";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private record Figures(Map<CaseKind, RiskCaseStore.Backlog> byKind) {}

    private record Cached(Instant takenAt, Optional<Figures> figures) {}

    private final RiskCaseStore cases;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    public RiskCaseBacklogMetrics(RiskCaseStore cases, Connections connections, Clock clock, MeterRegistry registry) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (CaseKind kind : CaseKind.values()) {
            Gauge.builder(BACKLOG, this, self -> self.figure(kind, RiskCaseStore.Backlog::open))
                    .tag("kind", kind.name())
                    .description("Open risk cases of the kind - OPEN, IN_INVESTIGATION, ESCALATED. NaN when unreadable."
                            + " Fleet-wide from every instance: aggregate with max(), never sum(). Alert above the"
                            + " queue's staffed capacity")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(AGE, this, self -> self.figure(kind, RiskCaseStore.Backlog::oldestAgeSeconds))
                    .tag("kind", kind.name())
                    .description("Seconds since the oldest open risk case of the kind opened, on the database clock; 0"
                            + " when none is open, NaN when unreadable. Aggregate with max(). Alert older than the"
                            + " kind's SLA")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** {@code kind}'s figure as {@code read} takes it - 0 for a kind with none open, NaN when the read failed. */
    private double figure(CaseKind kind, java.util.function.ToLongFunction<RiskCaseStore.Backlog> read) {
        return refreshed().figures()
                .map(figures -> Optional.ofNullable(figures.byKind().get(kind)).map(read::applyAsLong).orElse(0L)
                        .doubleValue())
                .orElse(Double.NaN);
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            Map<CaseKind, RiskCaseStore.Backlog> byKind = new EnumMap<>(CaseKind.class);
            for (RiskCaseStore.Backlog row : cases.backlog(connection)) {
                byKind.put(row.kind(), row);
            }
            fresh = new Cached(clock.instant(), Optional.of(new Figures(Map.copyOf(byKind))));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The risk case backlog could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
