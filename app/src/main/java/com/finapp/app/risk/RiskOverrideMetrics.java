package com.finapp.app.risk;

import com.finapp.risk.ActionKind;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.OverrideEffect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The override's meters (`P13-TSK-012`; {@code PHASE_13_PLAN.md} section 15; ADR-0112 decision 9):
 * {@code finapp.risk.override{outcome}} - {@code proposed}, {@code approved}, {@code rejected}, {@code refused} (a
 * self-approval or a self-dealing refused); {@code finapp.risk.action{kind,outcome}} - the outcome payments answered to
 * an override's issued action, per kind ({@code done}, {@code already}, {@code refused}); and
 * {@code finapp.risk.action.failed{kind}} - a dispatch that threw and is left to its re-drive, the input to the runbook's
 * "an action failed" alert. Every series registered eagerly at startup for the four kinds an override issues (`-017`
 * registers the rest with its machine); the tags are closed vocabularies - never an id, an amount or a reason
 * ({@code INV-RSK-08}, {@code MetricNames.ALLOWED_TAG_KEYS}).
 */
public final class RiskOverrideMetrics {

    public static final String OVERRIDE = "finapp.risk.override";
    public static final String ACTION = "finapp.risk.action";
    public static final String ACTION_FAILED = "finapp.risk.action.failed";

    /** What a door's act came to, as the {@code outcome} tag reads it. */
    public enum Act { PROPOSED, APPROVED, REJECTED, REFUSED }

    private final Map<Act, Counter> acts = new EnumMap<>(Act.class);
    private final Map<ActionKind, Map<ActionOutcome, Counter>> outcomes = new EnumMap<>(ActionKind.class);
    private final Map<ActionKind, Counter> failures = new EnumMap<>(ActionKind.class);

    public RiskOverrideMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (Act act : Act.values()) {
            acts.put(act, Counter.builder(OVERRIDE).tag("outcome", act.name().toLowerCase(Locale.ROOT))
                    .description("Decision override acts by their outcome - a refused act is a self-approval or a"
                            + " self-dealing refused and recorded FAILED")
                    .register(registry));
        }
        for (OverrideEffect effect : OverrideEffect.values()) {
            effect.action().ifPresent(kind -> {
                Map<ActionOutcome, Counter> byOutcome = new EnumMap<>(ActionOutcome.class);
                for (ActionOutcome outcome : ActionOutcome.values()) {
                    byOutcome.put(outcome, Counter.builder(ACTION).tag("kind", kind.name())
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                            .description("Risk actions concluded, by kind and the owning domain's answer")
                            .register(registry));
                }
                outcomes.put(kind, byOutcome);
                failures.put(kind, Counter.builder(ACTION_FAILED).tag("kind", kind.name())
                        .description("Risk action dispatches that threw and are left to their re-drive under the same"
                                + " key - any increase is the runbook's action alert")
                        .register(registry));
            });
        }
    }

    /** One door act, counted by what it came to. */
    public void act(Act act) {
        acts.get(Objects.requireNonNull(act, "act")).increment();
    }

    /** An issued action's recorded outcome. */
    public void concluded(ActionKind kind, ActionOutcome outcome) {
        counter(outcomes, kind).get(Objects.requireNonNull(outcome, "outcome")).increment();
    }

    /** A dispatch that threw - nothing recorded, left to the re-drive. */
    public void failed(ActionKind kind) {
        counter(failures, kind).increment();
    }

    private static <V> V counter(Map<ActionKind, V> meters, ActionKind kind) {
        V meter = meters.get(Objects.requireNonNull(kind, "kind"));
        if (meter == null) {
            throw new IllegalArgumentException(kind + " is no action an override issues");
        }
        return meter;
    }
}
