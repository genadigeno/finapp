package com.finapp.app.risk;

import com.finapp.risk.ActionKind;
import com.finapp.risk.ActionOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The action machine's meters (`P13-TSK-017`; {@code PHASE_13_PLAN.md} section 15; ADR-0112 decision 9; the Phase 12 →
 * 13 gate's N5): {@code finapp.risk.action{kind,outcome}} - the owning domain's answer recorded on an action
 * ({@code done}, {@code already}, {@code refused}); {@code finapp.risk.action.failed{kind}} - a dispatch that threw and is
 * left to its re-drive under the same key, the input to the runbook's "an action failed" alert; and
 * {@code finapp.risk.sweeper.claim.failed{kind=action}} - a claim of {@code RiskActionSchedule} (or the approval door's)
 * that failed. Moved here from `-012`'s {@code RiskOverrideMetrics} with the override's action itself; every series
 * registered eagerly at startup for every kind with an owning command; the tags closed vocabularies - never an id, an
 * amount or a reason ({@code INV-RSK-08}, {@code MetricNames.ALLOWED_TAG_KEYS}). The schedule's
 * {@code finapp.risk.sweeper.enabled{kind=action}} gauge is {@code RiskActionBeans}'.
 */
public final class RiskActionMetrics {

    public static final String ACTION = "finapp.risk.action";
    public static final String ACTION_FAILED = "finapp.risk.action.failed";

    /** Exported as {@code finapp_risk_sweeper_claim_failed_total}, beside the monitoring sweep's. */
    public static final String CLAIM_FAILED = RiskMonitoringMetrics.CLAIM_FAILED;

    /** The {@code kind} tag of the action schedule's sweeper series. */
    public static final String KIND = "action";

    private final Map<ActionKind, Map<ActionOutcome, Counter>> outcomes = new EnumMap<>(ActionKind.class);
    private final Map<ActionKind, Counter> failures = new EnumMap<>(ActionKind.class);
    private final Counter claimFailed;

    public RiskActionMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (ActionKind kind : ActionKind.values()) {
            if (!kind.available() || kind == ActionKind.RESCREEN_PARTY) {
                // No owning command (accounts, crossborder), or the rescreening sweep's (-022): never dispatched here.
                continue;
            }
            Map<ActionOutcome, Counter> byOutcome = new EnumMap<>(ActionOutcome.class);
            for (ActionOutcome outcome : ActionOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(ACTION).tag("kind", kind.name())
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .description("Risk actions concluded, by kind and the owning domain's answer")
                        .register(registry));
            }
            outcomes.put(kind, byOutcome);
            failures.put(kind, Counter.builder(ACTION_FAILED).tag("kind", kind.name())
                    .description("Risk action dispatches that threw and are left to their re-drive under the same key -"
                            + " any increase is the runbook's action alert")
                    .register(registry));
        }
        claimFailed = Counter.builder(CLAIM_FAILED).tag("kind", KIND)
                .description("Risk action claims that failed and were left for a later tick (N5)")
                .register(registry);
    }

    /** An action's recorded outcome. */
    public void concluded(ActionKind kind, ActionOutcome outcome) {
        counter(outcomes, kind).get(Objects.requireNonNull(outcome, "outcome")).increment();
    }

    /** A dispatch that threw - nothing recorded, left to the re-drive. */
    public void failed(ActionKind kind) {
        counter(failures, kind).increment();
    }

    /** A claim that failed - nothing claimed for certain; a later tick claims again. */
    public void claimFailed() {
        claimFailed.increment();
    }

    private static <V> V counter(Map<ActionKind, V> meters, ActionKind kind) {
        V meter = meters.get(Objects.requireNonNull(kind, "kind"));
        if (meter == null) {
            throw new IllegalArgumentException(kind + " is dispatched by no owning command");
        }
        return meter;
    }
}
