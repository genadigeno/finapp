package com.finapp.payments;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link CaptureHold} (`P13-TSK-010`, slice 2; payments {@code V034}). Every resolution is ONE
 * conditional statement on the row - {@code resolution IS NULL} the arbiter among a capture door, a void door and the
 * expiry leg on any number of instances - with {@code V034}'s trigger beneath for every writer (the resolution frozen
 * once set, {@code CAPTURE} only before {@code held_until}, {@code EXPIRED} only from it, both on the database clock).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CaptureHoldStore<T> {

    /**
     * The hold born open beside its attempt, in the confirmation's first transaction: {@code held_at} the insert's own
     * instant on the database clock and {@code held_until} that plus {@code validity} in whole microseconds - the same
     * clock the expiry leg and the capture door compare with.
     */
    void insert(T unitOfWork, PaymentIntentId intent, PaymentAttemptId attempt, Duration validity);

    /** The intent's hold, unlocked. */
    Optional<CaptureHold> findForIntent(T unitOfWork, PaymentIntentId intent);

    /** The intent's hold {@code FOR UPDATE} - a door's first act on it. */
    Optional<CaptureHold> lock(T unitOfWork, PaymentIntentId intent);

    /**
     * {@code OPEN → CAPTURE} with the door's {@code key}, only while {@code held_until} has not passed on the database
     * clock; {@code false} when the hold is resolved or lapsed.
     */
    boolean resolveCapture(T unitOfWork, PaymentIntentId intent, String key);

    /** {@code OPEN → VOID} with the door's {@code key}; {@code false} when the hold is resolved. */
    boolean resolveVoid(T unitOfWork, PaymentIntentId intent, String key);

    /**
     * ONE open hold whose {@code held_until} has passed on the database clock, {@code FOR UPDATE SKIP LOCKED} - the
     * expiry leg's claim: a hold a door is resolving is passed over, never waited on; so is every intent in
     * {@code passedOver} (the rows this pass already failed on - one bad row never stalls the queue).
     */
    Optional<CaptureHold> claimExpired(T unitOfWork, java.util.Collection<PaymentIntentId> passedOver);

    /** {@code OPEN → EXPIRED}, only from {@code held_until} on the database clock; {@code false} when resolved. */
    boolean expire(T unitOfWork, PaymentIntentId intent);

    /**
     * The holds resolved {@code VOID} or {@code EXPIRED} whose attempt still stands {@code AUTHORIZED} - a void the
     * resolution decided and no command has dispatched yet (the door's or the leg's crash between the two commits): the
     * expiry leg's finisher pass, oldest first, at most {@code limit}.
     */
    List<CaptureHold> voidsToFinish(T unitOfWork, int limit);
}
