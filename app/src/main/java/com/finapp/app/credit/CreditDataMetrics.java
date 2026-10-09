package com.finapp.app.credit;

import com.finapp.app.telemetry.AfterCommit;
import com.finapp.credit.CreditDataObserver;
import com.finapp.credit.CreditDataSource;
import com.finapp.credit.CreditSourceKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Credit data collection's meters (`P10-TSK-006`; {@code PHASE_10_PLAN.md} section 15):
 * {@code finapp.credit.data.request{source_kind, provider, outcome}} - the unavailability ratio's numerator and the
 * bureau-cost proxy - and {@code finapp.credit.data.latency{source_kind, provider}}. Tag values are closed sets: a
 * source kind, a declared provider code and an outcome - never an identifier, never an attribute.
 *
 * <p><strong>Counted after the commit</strong> (`P10-TSK-020`; the plan's "counters count committed facts"): collection
 * reports an answer's outcome from inside the transaction that records it, so the count rides {@link AfterCommit} - a
 * rolled-back answer (a storage failure, a lost conditional) counts nothing, and the retry that records it counts once.
 * The latency is the provider call's own, timed outside any transaction. <strong>Published from startup</strong> for
 * every source this instance declares - each kind's configured order and its fail-safe - with every outcome, so the
 * unavailability ratio and its alert have their series before the first request.
 */
public final class CreditDataMetrics implements CreditDataObserver {

    public static final String REQUEST = "finapp.credit.data.request";
    public static final String LATENCY = "finapp.credit.data.latency";

    private final MeterRegistry registry;

    public CreditDataMetrics(MeterRegistry registry, List<? extends CreditDataSource> declared) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (CreditDataSource source : declared) {
            for (Outcome outcome : Outcome.values()) {
                counter(source.kind(), source.code(), outcome);
            }
            timer(source.kind(), source.code());
        }
    }

    @Override
    public void answered(CreditSourceKind kind, String providerCode, Outcome outcome) {
        AfterCommit.run(() -> counter(kind, providerCode, outcome).increment());
    }

    @Override
    public void called(CreditSourceKind kind, String providerCode, Duration took) {
        timer(kind, providerCode).record(took);
    }

    private Counter counter(CreditSourceKind kind, String providerCode, Outcome outcome) {
        return Counter.builder(REQUEST)
                .description("Credit data answers recorded, by source kind, provider and outcome - counted after commit")
                .tags("source_kind", tag(kind.name()), "provider", providerCode, "outcome", tag(outcome.name()))
                .register(registry);
    }

    private Timer timer(CreditSourceKind kind, String providerCode) {
        return Timer.builder(LATENCY)
                .description("How long one credit data provider call took")
                .tags("source_kind", tag(kind.name()), "provider", providerCode)
                .register(registry);
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
