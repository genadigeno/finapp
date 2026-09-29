package com.finapp.reconciliation;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The run gauges' reads (`P8-TSK-011`, `PHASE_8_PLAN.md` §15) — lock-free counts over the
 * run register, per source and deciding nothing: {@code run.pending}, {@code run.age} from
 * the stored {@code created_at}, and {@code run.blocked}, which must read 0 and is
 * alerted. The app's metrics map the source ids onto declared codes; a source absent from
 * a map holds nothing.
 */
public interface RunReadings {

    /** Runs still owed work per source: {@code OPEN} or {@code IN_PROGRESS}, any kind. */
    Map<UUID, Long> pendingCountBySource(Connection unitOfWork);

    /** Each source's oldest non-terminal run's stored birth — ingestion-to-disposition. */
    Map<UUID, Instant> oldestPendingBySource(Connection unitOfWork);

    /** Runs a person must requeue ({@code BLOCKED}) per source — must read 0, alerted. */
    Map<UUID, Long> blockedCountBySource(Connection unitOfWork);
}
