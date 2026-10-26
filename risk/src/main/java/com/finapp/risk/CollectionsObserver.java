package com.finapp.risk;

/**
 * What a collections case's opening tells the operators (`P13-TSK-025`; {@code PHASE_13_PLAN.md} section 15) - the
 * stall's own signal: a default delivered whose agreement's party could not be placed is refused whole (the delivery
 * retried, the partition stalled), and each refusal is counted here - before any alert is born, so nothing else is
 * counted for it. Implemented in {@code app} by {@code RiskCollectionsMetrics} ({@code finapp.risk.collections.unplaced},
 * no tag). {@link #NONE} counts nothing.
 */
@FunctionalInterface
public interface CollectionsObserver {

    /** Counts nothing - the suites' default. */
    CollectionsObserver NONE = () -> { };

    /** A default's agreement named no placeable party: its delivery was refused. */
    void unplaced();
}
