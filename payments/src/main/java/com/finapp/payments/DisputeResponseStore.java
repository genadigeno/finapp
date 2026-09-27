package com.finapp.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link DisputeResponse} (`P7-TSK-014`, over `V022`) — the refund store's
 * dispatch protocols restated for an answer that moves no money: the insert keyed by its dispatch
 * key (a takeover converges on it), the send permit renewed conditionally and forward-only (the
 * wire-noise arbiter among instances), the conditional transition with its trail row, and a
 * lock-first read for whoever applies an outcome.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface DisputeResponseStore<T> {

    /**
     * A response read under its row lock, with the send permit as stored — the value a refused
     * connection on the FIRST send is judged against (a later permit means a later send may have
     * reached the PSP, ADR-0057 §3).
     */
    record Locked(DisputeResponse response, Instant sendPermit) {}

    /**
     * Stores a freshly dispatched response under its dispatch key, its birth permit its
     * {@code createdAt}. The partial unique index is the arbiter of one LIVE response per dispute
     * for every writer — the caller has already judged it under the dispute's lock.
     */
    void insert(T unitOfWork, DisputeResponse response, String dispatchScope, String dispatchKey);

    /** The response a dispatch key names, if a flight committed one — a takeover's first look. */
    Optional<DisputeResponse> findByDispatchKey(
            T unitOfWork, String dispatchScope, String dispatchKey);

    /** The response, locked for the rest of the transaction, with its stored permit. */
    Optional<Locked> lockForOutcome(T unitOfWork, DisputeResponseId id);

    /**
     * A new send permit, stamped forward CONDITIONALLY on the response still being resolvable —
     * the conditional IS the permit: a response another resolver moved to a terminal status since
     * the caller looked matches no row, and then nothing may be sent. Returns the permit as
     * stored; empty when no send may follow.
     */
    Optional<Instant> renewSendPermit(T unitOfWork, DisputeResponseId id, Instant at);

    /**
     * Moves the response {@code before → after}, conditional on {@code before}'s status, with its
     * trail row stamped {@code at}. {@code false} when another writer moved it first.
     */
    boolean transition(T unitOfWork, DisputeResponse before, DisputeResponse after, Instant at);

    /** The dispute's live (non-{@code FAILED}) response, if one answers it. */
    Optional<DisputeResponse> findLive(T unitOfWork, DisputeId dispute);

    /** The dispute's responses, oldest first. */
    List<DisputeResponse> listFor(T unitOfWork, DisputeId dispute);

    /**
     * What the resolution sweep may look at: {@code DISPATCHED} responses whose latest permit is
     * older than {@code dispatchedBefore} (a flight that crashed between its dispatch and its
     * outcome) and {@code UNKNOWN} ones older than {@code unknownBefore}, oldest first.
     */
    List<DisputeResponse> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * How many dispute answers wait past their due for the PSP's word, and how long the oldest
     * has waited (`P7-TSK-015`) — {@code INV-LIFE-03}'s own "unknown-state age metric" for the
     * response machine, the withdrawal's shape: {@link #findSweepable}'s candidacy with the
     * {@code UNKNOWN} bound at zero. Every {@code UNKNOWN} response counts, and every
     * {@code DISPATCHED} one whose send permit is older than the sweep's {@code dispatchedBound}.
     * A count and an age — never an identifier.
     */
    PaymentAttemptStore.UnknownReading unknownReading(
            T unitOfWork, java.time.Duration dispatchedBound);
}
