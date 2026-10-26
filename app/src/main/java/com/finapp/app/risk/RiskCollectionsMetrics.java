package com.finapp.app.risk;

import com.finapp.risk.CollectionsObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * The collections case's own meter (`P13-TSK-025`; {@code PHASE_13_PLAN.md} section 15), the
 * {@link CollectionsObserver} the composition gives {@code CollectionsCases}: {@code finapp.risk.collections.unplaced} -
 * every default delivered whose agreement's party could not be placed, refused before any alert was born, so a stalled
 * {@code risk.signals} partition's retries are counted here and nowhere else ({@code finapp.risk.alert} never climbs for
 * an alert that does not exist). No tag: no id, subject or agreement leaves ({@code INV-RSK-08}). Registered eagerly,
 * so a fresh instance publishes it from startup; any increase is the alert (`-024`).
 */
public final class RiskCollectionsMetrics implements CollectionsObserver {

    public static final String UNPLACED = "finapp.risk.collections.unplaced";

    private final Counter unplaced;

    public RiskCollectionsMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.unplaced = Counter.builder(UNPLACED)
                .description("Default deliveries refused because the defaulted agreement's party could not be placed")
                .register(registry);
    }

    @Override
    public void unplaced() {
        unplaced.increment();
    }
}
