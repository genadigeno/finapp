package com.finapp.app.risk;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The override's meters (`P13-TSK-012`; {@code PHASE_13_PLAN.md} section 15; ADR-0112 decision 9):
 * {@code finapp.risk.override{outcome}} - {@code proposed}, {@code approved}, {@code rejected}, {@code refused} (a
 * self-approval or a self-dealing refused). The issued action's series - {@code finapp.risk.action{kind,outcome}} and
 * {@code finapp.risk.action.failed{kind}} - moved to {@link RiskActionMetrics} with the action itself (`P13-TSK-017`'s
 * fold). Registered eagerly at startup; the tag a closed vocabulary - never an id, an amount or a reason
 * ({@code INV-RSK-08}, {@code MetricNames.ALLOWED_TAG_KEYS}).
 */
public final class RiskOverrideMetrics {

    public static final String OVERRIDE = "finapp.risk.override";

    /** What a door's act came to, as the {@code outcome} tag reads it. */
    public enum Act { PROPOSED, APPROVED, REJECTED, REFUSED }

    private final Map<Act, Counter> acts = new EnumMap<>(Act.class);

    public RiskOverrideMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (Act act : Act.values()) {
            acts.put(act, Counter.builder(OVERRIDE).tag("outcome", act.name().toLowerCase(Locale.ROOT))
                    .description("Decision override acts by their outcome - a refused act is a self-approval or a"
                            + " self-dealing refused and recorded FAILED")
                    .register(registry));
        }
    }

    /** One door act, counted by what it came to. */
    public void act(Act act) {
        acts.get(Objects.requireNonNull(act, "act")).increment();
    }
}
