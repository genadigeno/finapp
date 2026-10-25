package com.finapp.app.risk;

import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseObserver;
import com.finapp.risk.CaseStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The case's meters (`P13-TSK-016`; {@code PHASE_13_PLAN.md} section 15; ADR-0110's operational impact), the
 * {@link CaseObserver} the composition gives {@code Cases} - every series registered eagerly, so a fresh instance
 * publishes it from startup:
 *
 * <ul>
 *   <li>{@code finapp.risk.case{kind,status}} - every move of a case into a status: its birth {@code OPEN}, the take,
 *       the first escalation, the disposal;</li>
 *   <li>{@code finapp.risk.case.resolution{kind}} - opened to disposed, on the database's two stamps.</li>
 * </ul>
 *
 * The gauges {@code finapp.risk.case.age{kind}} and {@code .case.backlog{kind}} are `-018`'s, read from its queues. No
 * tag carries an id, a subject, a person or a disposition's reason ({@code INV-RSK-08}; {@code kind} and {@code status}
 * are {@code MetricNames.ALLOWED_TAG_KEYS}'). Counted inside the act's transaction: a caller that rolls back after a
 * move has been counted - a counter's tolerance, never an audit's (`-015`'s {@code RiskAlertMetrics} shape).
 */
public final class RiskCaseMetrics implements CaseObserver {

    public static final String CASE = "finapp.risk.case";
    public static final String RESOLUTION = "finapp.risk.case.resolution";

    private final Map<CaseKind, Map<CaseStatus, Counter>> moved = new EnumMap<>(CaseKind.class);
    private final Map<CaseKind, Timer> resolved = new EnumMap<>(CaseKind.class);

    public RiskCaseMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (CaseKind kind : CaseKind.values()) {
            Map<CaseStatus, Counter> byStatus = new EnumMap<>(CaseStatus.class);
            for (CaseStatus status : CaseStatus.values()) {
                byStatus.put(status, Counter.builder(CASE).tag("kind", kind.name()).tag("status", status.name())
                        .description("Risk cases moved into a status - born OPEN, taken, escalated, disposed")
                        .register(registry));
            }
            moved.put(kind, byStatus);
            resolved.put(kind, Timer.builder(RESOLUTION).tag("kind", kind.name())
                    .description("Risk cases from their opening to their disposal, on the database's stamps")
                    .register(registry));
        }
    }

    @Override
    public void moved(CaseKind kind, CaseStatus status) {
        moved.get(Objects.requireNonNull(kind, "kind")).get(Objects.requireNonNull(status, "status")).increment();
    }

    @Override
    public void resolved(CaseKind kind, Duration openToDisposed) {
        resolved.get(Objects.requireNonNull(kind, "kind")).record(Objects.requireNonNull(openToDisposed,
                "openToDisposed"));
    }
}
