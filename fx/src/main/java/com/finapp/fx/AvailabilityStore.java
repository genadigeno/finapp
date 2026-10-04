package com.finapp.fx;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for FX availability facts and enable requests (`P9-TSK-007`) - on the caller's unit of
 * work. {@code fx V004} holds the append-only facts, the enabling fact's approved-request rule, the
 * one-live-proposal index and the four-eyes {@code CHECK} for every writer.
 */
public interface AvailabilityStore {

    /** An enable request's decision-relevant columns. */
    record RequestRow(
            UUID id,
            AvailabilitySubject subject,
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
     * Serialises every availability writer of {@code subject} for the rest of the transaction
     * (advisory namespace 7), so "the newest fact" is the one the writer decided against.
     */
    void lockSubject(Connection unitOfWork, AvailabilitySubject subject);

    /** Whether {@code subject} is available now: its newest fact, or available when it has none. */
    boolean isAvailable(Connection unitOfWork, AvailabilitySubject subject);

    void insertFact(
            Connection unitOfWork,
            AvailabilitySubject subject,
            boolean available,
            String actorId,
            String reason,
            Instant at,
            Optional<UUID> enableRequestId);

    /**
     * Inserts a {@code PROPOSED} enable request.
     *
     * @throws FxAvailability.EnablePending when one already awaits a decision for the subject (the
     *     caller's transaction is then aborted)
     */
    void insertRequest(
            Connection unitOfWork,
            UUID id,
            AvailabilitySubject subject,
            String proposedBy,
            String reason,
            Instant at);

    /** The request, {@code FOR UPDATE}. */
    Optional<RequestRow> lockRequest(Connection unitOfWork, UUID id);

    /** Moves the request out of {@code PROPOSED}; false when it was not {@code PROPOSED}. */
    boolean decideRequest(
            Connection unitOfWork,
            UUID id,
            EnableRequestStatus to,
            String decidedBy,
            String reason,
            Instant at);
}
