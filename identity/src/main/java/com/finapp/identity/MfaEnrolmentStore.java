package com.finapp.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Authoritative storage for MFA enrolments (`P1-TSK-017`, ADR-0033).
 *
 * <p>Every method takes the caller's unit of work, so an enrolment, its audit record and its event
 * commit together or not at all — the `P1-TSK-006` shape.
 *
 * @param <T> the unit of work: a JDBC {@link java.sql.Connection} (ADR-0033)
 */
public interface MfaEnrolmentStore<T> {

    /**
     * Records a new enrolment.
     *
     * @throws IdentityStorageException if one of the same status already exists for this identity
     *     and type. Two live secrets, only one of which the customer scanned, is a state nobody
     *     could resolve — so the partial unique indexes refuse it rather than the application
     *     checking first, which two instances would both pass
     */
    void insert(T unitOfWork, MfaEnrolment enrolment);

    /** The identity's pending enrolment of a type, if there is one. */
    Optional<MfaEnrolment> findPending(T unitOfWork, IdentityId identityId, MfaFactorType type);

    /**
     * The identity's usable factor of a type, if there is one.
     *
     * <p><strong>`status = 'ACTIVE'` is in the statement, and that is this task's acceptance
     * criterion.</strong> A challenge that loaded any enrolment and checked the status afterwards
     * would be one refactor away from satisfying a challenge with a factor nobody confirmed. A
     * predicate cannot be forgotten by the code that runs the query.
     */
    Optional<MfaEnrolment> findActive(T unitOfWork, IdentityId identityId, MfaFactorType type);

    /**
     * Moves a pending enrolment to active.
     *
     * <p>Conditional, and the row count is the outcome: ten instances confirming the same enrolment
     * produce <strong>one</strong> transition and nine are told they lost. No read-then-write, so
     * there is nothing to lose — and a code presented twice finds no pending row the second time,
     * which is what makes the state machine the replay defence for this path.
     *
     * @return whether a pending enrolment was confirmed
     */
    boolean confirm(T unitOfWork, MfaEnrolmentId id, Instant at);

    /**
     * Records that a code at {@code step} has been accepted, refusing anything at or before the
     * last one.
     *
     * <p><strong>This is the challenge's replay defence, and it has to be its own mechanism.</strong>
     * Enrolment consumes its {@code PENDING} row, so a second confirmation finds nothing; a
     * challenge leaves the factor {@code ACTIVE} and has nothing to consume. Without this a code is
     * replayable for as long as it is valid — about ninety seconds — and "one-time password" is
     * false.
     *
     * <p>Conditional, and the row count is the outcome: two instances presenting the same code
     * produce <strong>one</strong> success. It refuses <em>earlier</em> steps too, not merely the
     * same one, which is what RFC 6238 §5.2 requires.
     *
     * @return whether the step was accepted and recorded
     */
    boolean consumeStep(T unitOfWork, MfaEnrolmentId id, long step);

    /**
     * Marks an identity's pending enrolment of a type as discarded, if there is one.
     *
     * <p>Starting a second enrolment must replace the first rather than fail: a customer who closed
     * the page before scanning the QR code would otherwise hold a pending secret they can neither
     * confirm nor remove.
     *
     * <p><strong>Only PENDING is affected, and that boundary is a security property.</strong> An
     * ACTIVE factor is never discarded by starting a new enrolment — otherwise anyone who reached
     * this endpoint could disable somebody's second factor without proving anything at all, which
     * is `INV-IDN-05`'s bypass wearing an ordinary feature's clothes.
     *
     * <p>The row is <strong>marked, never deleted</strong>: the application role holds no `DELETE`,
     * and an abandoned enrolment is evidence that somebody began adding a factor.
     *
     * @return how many were discarded: zero or one
     */
    int discardPending(T unitOfWork, IdentityId identityId, MfaFactorType type, Instant at);
}
