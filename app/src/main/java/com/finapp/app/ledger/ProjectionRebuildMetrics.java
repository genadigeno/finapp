package com.finapp.app.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;

/**
 * {@code finapp.ledger.projection.rebuild}{outcome} (`P14-TSK-026`, ADR-0119): the four-eyes projection rebuilds this
 * instance concluded - {@code proposed}, {@code applied} (the row moved), {@code unchanged} (nothing to correct),
 * {@code rejected}, and {@code refused} (any door's domain refusal). Counted after the commit, once per act this instance
 * made; a replayed or converged act counts nothing. Beside it the drift gauge, {@code finapp.ledger.projection.drift},
 * falls at its next sweep after an {@code applied}: this meter counts the acts, the gauge judges the projection. No tag
 * names an account, a rebuild or a figure; registered eagerly so a dashboard reads a zero, not an absence.
 */
public final class ProjectionRebuildMetrics {

    /** Spelled with dots - exported as {@code finapp_ledger_projection_rebuild_total}. */
    public static final String REBUILD = "finapp.ledger.projection.rebuild";

    /** The {@code outcome} tag's values, a closed list. */
    public static final List<String> OUTCOMES = List.of("proposed", "applied", "unchanged", "rejected", "refused");

    private final MeterRegistry registry;

    public ProjectionRebuildMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        OUTCOMES.forEach(this::counter);
    }

    /** One act concluded, by its outcome - one of {@link #OUTCOMES}. */
    void count(String outcome) {
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("not a rebuild outcome: " + outcome);
        }
        counter(outcome).increment();
    }

    private Counter counter(String outcome) {
        return Counter.builder(REBUILD)
                .description("Four-eyes projection rebuilds concluded by this instance, by outcome (proposed, applied,"
                        + " unchanged, rejected, refused). Per instance; sum() aggregates")
                .tag("outcome", outcome)
                .register(registry);
    }
}
