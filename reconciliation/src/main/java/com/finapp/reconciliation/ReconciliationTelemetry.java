package com.finapp.reconciliation;

import com.finapp.platform.telemetry.Spans;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What reconciliation tells telemetry (`P8-TSK-024`, `PHASE_8_PLAN.md` §15) - the
 * {@code ReceptionOutcomeObserver} shape: the module reports facts from inside the transaction
 * that makes them true, and the composition counts them only after it commits (a rolled-back
 * decision counted would be an alert about nothing). Counts, kinds and durations only - never an
 * amount (ADR-0072). Every method defaults to nothing: {@link #NONE} is the module's and its
 * tests' telemetry.
 */
public interface ReconciliationTelemetry {

    /** A run completed: its items' outcomes, and the time since its birth when known. */
    default void runCompleted(
            UUID sourceId, Map<DecisionOutcome, Long> outcomes, Optional<Duration> sinceBirth) {}

    /**
     * A late leg decided an item again - every {@code REMATCH} decision, and a
     * {@code REPROCESS} decision only when it allocated.
     */
    default void rematched(UUID sourceId, DecisionOutcome outcome) {}

    /** A resolution ended; {@code sinceRaised} for a break-subject resolution. */
    default void resolved(
            ResolutionKind kind, ResolutionOutcome outcome, Optional<Duration> sinceRaised) {}

    /** A posting kind's ledger adjustment was made (its approval). */
    default void adjusted(ResolutionKind kind) {}

    /**
     * An approval refused because its subject moved - counted at once, since the refusal
     * commits nothing to count after.
     */
    default void staleRefused(ResolutionKind kind) {}

    /**
     * How many counts the current transaction has deferred so far - a mark taken beside a JDBC
     * savepoint (`P8-TSK-024`'s gate). Spring never sees a {@code rollback(savepoint)}, so a
     * count deferred after the mark would otherwise still run at the outer commit, counting a
     * fact the savepoint undid.
     */
    default int countMark() {
        return 0;
    }

    /** Drops every count deferred after {@code mark} - called beside {@code rollback(savepoint)}. */
    default void discardCountsAfter(int mark) {}

    /** Where reconciliation's legs record their spans. */
    default Spans spans() {
        return Spans.NONE;
    }

    ReconciliationTelemetry NONE = new ReconciliationTelemetry() {};
}
