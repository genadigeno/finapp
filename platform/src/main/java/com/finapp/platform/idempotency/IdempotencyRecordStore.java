package com.finapp.platform.idempotency;

import com.finapp.platform.correlation.CorrelationId;
import java.time.Duration;
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

    /** What happened when a claim was attempted. */
    enum ClaimOutcome {
        /** The key is ours; run the command. */
        CLAIMED,
        /** Someone else's claim is committed and readable; resolve against it. */
        ALREADY_CLAIMED,
        /**
         * Someone else holds the key in an uncommitted transaction and we declined to wait.
         *
         * <p>Distinct from {@link #ALREADY_CLAIMED} because there is nothing to read: the other
         * transaction may still commit or roll back, so the outcome is genuinely unknown rather
         * than merely elsewhere.
         */
        CONTENDED
    }

    /**
     * Inserts a new {@code IN_PROGRESS} claim.
     *
     * <p>Not an exception on failure: losing a race is the mechanism working, not a fault.
     */
    ClaimOutcome claim(
            T unitOfWork,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            CorrelationId correlationId,
            Instant now,
            Instant expiresAt,
            Duration lease);

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
     * Takes over a claim whose lease has expired, presumed abandoned by a crashed instance.
     *
     * <p><strong>The lease is decided entirely by the database.</strong> No caller-supplied
     * instant participates: expiry is the server comparing its own {@code now()} against a
     * {@code lease_expires_at} the server itself set. An earlier version compared one
     * instance's {@code created_at} against another instance's notion of "long enough ago",
     * which is a race against clock skew rather than a lease — a fast-running clock would let
     * one instance steal a claim another was still executing, producing two financial effects
     * for one request.
     *
     * <p>The condition is also evaluated inside the {@code UPDATE}, so two instances reclaiming
     * the same abandoned key at once cannot both succeed.
     *
     * @return {@code true} if this caller now owns the claim
     */
    boolean reclaimIfLeaseExpired(
            T unitOfWork,
            IdempotencyKey key,
            CorrelationId correlationId,
            Instant now,
            Instant expiresAt,
            Duration lease);
}
