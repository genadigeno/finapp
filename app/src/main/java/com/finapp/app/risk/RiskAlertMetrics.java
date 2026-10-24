package com.finapp.app.risk;

import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.SuppressionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The alert's meters (`P13-TSK-015`; {@code PHASE_13_PLAN.md} section 15; ADR-0110's operational impact), the
 * {@link AlertObserver} the composition gives {@code Alerts} - every series registered eagerly, so a fresh instance
 * publishes it from startup:
 *
 * <ul>
 *   <li>{@code finapp.risk.alert{kind,severity}} - every alert born (a raise that found its alert standing counts
 *       nothing): the input of `-024`'s flood rule; until `-016` folds alerts into cases each is also an {@code OPEN}
 *       alert in the queue;</li>
 *   <li>{@code finapp.risk.alert.suppressed{reason_code}} - every approved suppression, by its closed reason code.</li>
 * </ul>
 *
 * No tag carries an id, a subject, a rule's parameters or a reason ({@code INV-RSK-08}; the keys {@code kind},
 * {@code severity}, {@code reason_code} are {@code MetricNames.ALLOWED_TAG_KEYS}'). Counted inside the raising or
 * approving transaction: a caller that rolls back after the birth has been counted - a counter's tolerance, never an
 * audit's (`-004`'s {@code RiskEvaluationMetrics} shape).
 */
public final class RiskAlertMetrics implements AlertObserver {

    public static final String ALERT = "finapp.risk.alert";
    public static final String SUPPRESSED = "finapp.risk.alert.suppressed";

    private final Map<AlertKind, Map<AlertSeverity, Counter>> raised = new EnumMap<>(AlertKind.class);
    private final Map<SuppressionReason, Counter> suppressed = new EnumMap<>(SuppressionReason.class);

    public RiskAlertMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (AlertKind kind : AlertKind.values()) {
            Map<AlertSeverity, Counter> bySeverity = new EnumMap<>(AlertSeverity.class);
            for (AlertSeverity severity : AlertSeverity.values()) {
                bySeverity.put(severity, Counter.builder(ALERT).tag("kind", kind.name())
                        .tag("severity", severity.name())
                        .description("Risk alerts born - one per (subject, rule, window start), a re-raise counting"
                                + " nothing")
                        .register(registry));
            }
            raised.put(kind, bySeverity);
        }
        for (SuppressionReason reason : SuppressionReason.values()) {
            suppressed.put(reason, Counter.builder(SUPPRESSED).tag("reason_code", reason.name())
                    .description("Risk alerts suppressed by two persons, by the proposal's reason code")
                    .register(registry));
        }
    }

    @Override
    public void raised(AlertKind kind, AlertSeverity severity) {
        raised.get(Objects.requireNonNull(kind, "kind")).get(Objects.requireNonNull(severity, "severity")).increment();
    }

    @Override
    public void suppressed(SuppressionReason reason) {
        suppressed.get(Objects.requireNonNull(reason, "reason")).increment();
    }
}
