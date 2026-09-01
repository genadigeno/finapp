package com.finapp.platform.idempotency;

import com.finapp.platform.correlation.CorrelationId;
import java.time.Instant;
import java.util.Optional;

/**
 * Storage for idempotency claims.
 *
 * <p><strong>Why this is a port.</strong> The platform has not chosen a data-access mechanism —
 * JPA, Spring Data JDBC or plain JDBC is unresolved question 12, due Phase 3 — and it matters
 * more than usual, because Hibernate's dirty checking emits {@code UPDATE}s while
 * {@code INV-LED-03} and {@code INV-HIST-01} say posted financial records are never updated.
 * {@link IdempotentExecutor} depends on this interface so that decision stays open and is not
 * made by accident here, exactly as {@code MoneyColumns} avoided making it in P0-TSK-011.
 *
 * <p><strong>The transaction is the caller's.</strong> Every method operates inside a
 * transaction the caller owns and commits. ADR-0004 requires the claim and the command's
 * financial effect to commit together; a store that opened its own transaction would make that
 * impossible and would silently reintroduce the crash window the whole design avoids. The unit
 * of work is passed in, never created here.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection} today, whatever the
 *     Phase 3 decision produces later
 */
public interface IdempotencyRecordStore<T> {

    /**
     * Inserts a new {@code IN_PROGRESS} claim.
     *
     * @return {@code true} if the claim was taken, {@code false} if the key is already claimed.
     *     Not an exception: losing a race is the mechanism working, not a fault.
     */
    boolean claim(
            T unitOfWork,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            CorrelationId correlationId,
            Instant now,
            Instant expiresAt);

    /** Reads a claim, if one exists. */
    Optional<IdempotencyRecord> find(T unitOfWork, IdempotencyKey key);

    /**
     * Records a terminal outcome against an {@code IN_PROGRESS} claim.
     *
     * @return {@code true} if the claim was still in progress and is now terminal
     */
    boolean complete(
            T unitOfWork,
            IdempotencyKey key,
            IdempotencyState terminalState,
            StoredResponse response,
            Instant completedAt);

    /**
     * Takes over a claim abandoned by a crashed process.
     *
     * <p>Conditional on the record still being {@code IN_PROGRESS} and older than
     * {@code staleBefore}, evaluated by the database rather than by the caller, so two
     * processes reclaiming at once cannot both succeed.
     *
     * @return {@code true} if this caller now owns the claim
     */
    boolean reclaimIfStale(
            T unitOfWork,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            CorrelationId correlationId,
            Instant staleBefore,
            Instant now,
            Instant expiresAt);
}
