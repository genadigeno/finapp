package com.finapp.crossborder;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for corridor availability facts and enable requests (`P9-TSK-015`) - on the caller's unit
 * of work. {@code crossborder V002} holds the append-only facts, the enabling fact's approved-request
 * rule, the one-live-proposal index and the four-eyes {@code CHECK} for every writer.
 */
public interface CorridorAvailabilityStore {

    /** An enable request's decision-relevant columns. */
    record RequestRow(
            UUID id,
            CorridorKey corridor,
            EnableRequestStatus status,
            String proposedBy,
            Optional<String> decidedBy) {}

    /** An enable request's position. */
    enum EnableRequestStatus {
        PROPOSED,
        APPROVED,
        REJECTED
    }

    /**
     * Serialises every availability writer of {@code corridor} for the rest of the transaction
     * (advisory namespace 8), so "the newest fact" is the one the writer decided against.
     */
    void lockCorridor(Connection unitOfWork, CorridorKey corridor);

    /** Whether {@code corridor} is available now: its newest fact, or available when it has none. */
    boolean isAvailable(Connection unitOfWork, CorridorKey corridor);

    void insertFact(
            Connection unitOfWork,
            CorridorKey corridor,
            boolean available,
            String actorId,
            String reason,
            Instant at,
            Optional<UUID> enableRequestId);

    /**
     * Inserts a {@code PROPOSED} enable request.
     *
     * @throws CorridorAvailability.EnablePending when one already awaits a decision for the corridor
     *     (the caller's transaction is then aborted)
     */
    void insertRequest(Connection unitOfWork, UUID id, CorridorKey corridor, String proposedBy, String reason, Instant at);

    /** The request, {@code FOR UPDATE}. */
    Optional<RequestRow> lockRequest(Connection unitOfWork, UUID id);

    /** Moves the request out of {@code PROPOSED}; false when it was not {@code PROPOSED}. */
    boolean decideRequest(
            Connection unitOfWork, UUID id, EnableRequestStatus to, String decidedBy, String reason, Instant at);
}
