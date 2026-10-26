package com.finapp.app.risk;

import com.finapp.risk.RiskSignals;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.risk.signal{kind,outcome}} - every signal recorded, by its kind and whether it was born or already stood
 * (`P13-TSK-024`; {@code PHASE_13_PLAN.md} section 15; `-005`'s hand-off), the {@link SignalObserver} the composition
 * gives {@code RiskSignals}. Registered eagerly for every {@link SignalKind} and both outcomes ({@code born},
 * {@code already}), so a fresh instance publishes them from startup; the tags bounded vocabularies - never a subject, a
 * source event or an attribute ({@code INV-RSK-08}). The intake's own {@code finapp.inbox.consumption{outcome}} counts
 * deliveries across every module; this says which signals they became. Counted inside the recording's transaction: a
 * delivery rolled back after its signal was born - a stalled partition's retries among them - counts {@code born} on each
 * attempt (a counter's tolerance; the stall's own series is {@code finapp.risk.collections.unplaced}).
 */
public final class RiskSignalMetrics implements SignalObserver {

    /** Exported as {@code finapp_risk_signal_total}. */
    public static final String SIGNAL = "finapp.risk.signal";

    private final Map<SignalKind, Map<RiskSignals.Outcome, Counter>> counters = new EnumMap<>(SignalKind.class);

    public RiskSignalMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (SignalKind kind : SignalKind.values()) {
            Map<RiskSignals.Outcome, Counter> byOutcome = new EnumMap<>(RiskSignals.Outcome.class);
            for (RiskSignals.Outcome outcome : RiskSignals.Outcome.values()) {
                byOutcome.put(outcome, Counter.builder(SIGNAL).tag("kind", kind.name())
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .description("Risk signals recorded, by kind: born, or already recorded from their source event")
                        .register(registry));
            }
            counters.put(kind, byOutcome);
        }
    }

    @Override
    public void recorded(SignalKind kind, RiskSignals.Outcome outcome) {
        counters.get(Objects.requireNonNull(kind, "kind")).get(Objects.requireNonNull(outcome, "outcome")).increment();
    }
}
