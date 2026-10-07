package com.finapp.app.credit;

import com.finapp.credit.CreditDataObserver;
import com.finapp.credit.CreditSourceKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Credit data collection's meters (`P10-TSK-006`; {@code PHASE_10_PLAN.md} section 15):
 * {@code finapp.credit.data.request{source_kind, provider, outcome}} - the unavailability ratio's numerator and the
 * bureau-cost proxy - and {@code finapp.credit.data.latency{source_kind, provider}}. Tag values are closed sets: a
 * source kind, a declared provider code and an outcome - never an identifier, never an attribute.
 */
public final class CreditDataMetrics implements CreditDataObserver {

    private final MeterRegistry registry;

    public CreditDataMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void answered(CreditSourceKind kind, String providerCode, Outcome outcome) {
        registry.counter("finapp.credit.data.request",
                        "source_kind", tag(kind.name()), "provider", providerCode, "outcome", tag(outcome.name()))
                .increment();
    }

    @Override
    public void called(CreditSourceKind kind, String providerCode, Duration took) {
        Timer.builder("finapp.credit.data.latency")
                .description("How long one credit data provider call took")
                .tags("source_kind", tag(kind.name()), "provider", providerCode)
                .register(registry)
                .record(took);
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
