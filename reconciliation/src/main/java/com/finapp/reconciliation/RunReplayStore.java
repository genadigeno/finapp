package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Decision replay's persistence (`P8-TSK-022`, `V012`): every decision on a run's items - or
 * written under the run, for a {@code REPROCESS} run that owns no items - read back with its
 * whole snapshot, in decision order; and the appended verdict.
 */
public interface RunReplayStore {

    /**
     * The run's decisions as {@link DecisionReplay} reads them: the item's frozen facts, the
     * rule each fired under its pinned version, its candidates (by expectation id, the order
     * every locking reader takes), its parked originals in their stored order, and its positive
     * allocations. Lock-free: the caller reads it in one repeatable-read snapshot.
     */
    List<DecisionReplay.StoredDecision> storedDecisions(Connection unitOfWork, UUID runId);

    /** One appended replay verdict. */
    record NewReplay(
            UUID id,
            UUID runId,
            Actor requestedBy,
            String verdict,
            int replayed,
            int notReplayed,
            int divergences,
            int pendingRematch,
            Optional<UUID> firstDivergentDecision,
            Instant at,
            CorrelationId correlation) {

        public NewReplay {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(requestedBy, "requestedBy must not be null");
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(
                    firstDivergentDecision, "firstDivergentDecision must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    void insertReplay(Connection unitOfWork, NewReplay replay);
}
