package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A completed money movement, announced by its owner, as a HINT to the monitoring sweep (`P13-TSK-005`;
 * {@code PHASE_13_PLAN.md} sections 7.1 T-sig, 10, 12.7; ADR-0107 decision 8): the transfers, payments and crossborder
 * completions pull the monitoring watermark's permit ({@code next_sweep_at}) forward so the sweep takes its intake step
 * on the next tick instead of after the permit lapses. The sweep reads the ledger's journal - never an event - for
 * correctness, so a hint delivered twice, late, out of order, forged or never changes nothing the journal would not
 * ({@code INV-EVT-04}; lending's and bnpl's origination hints).
 *
 * <p><strong>No signal is recorded for a completion</strong> (the design's deviation from the backlog entry's words,
 * 2026-10-23): the movement's born-once record is the {@code monitored_transaction} read from the journal (`-019`,
 * {@code INV-RSK-09}); a signal per completion would be a second, event-derived copy of the same fact - a projection
 * of Kafka into risk's evidence table, which "Kafka is not truth" forbids - under a subject no rule reads.
 *
 * <p>Declared here, implemented by `P13-TSK-019` over its watermark store ({@link #NONE} until then - a hint lost is
 * free); the handler that calls it is `-005`'s {@code RiskSignalIntake} in {@code app}. `-019` is free to reshape it.
 */
@FunctionalInterface
public interface MonitoringHints {

    /** A hint that falls on the floor: the sweep converges on its own permit. The composition until `-019`. */
    MonitoringHints NONE = (unitOfWork, movement) -> { };

    /** A completed movement, on the handler's unit of work - the permit pull joins the inbox's transaction. */
    void movementCompleted(Connection unitOfWork, CompletedMovement movement);

    /** The completion as its owner announced it: which module, which event, which aggregate, when. */
    record CompletedMovement(String producer, String eventType, UUID aggregateId, Instant occurredAt) {

        public CompletedMovement {
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
