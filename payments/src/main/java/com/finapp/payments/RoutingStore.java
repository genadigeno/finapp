package com.finapp.payments;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for routing (`P7-TSK-003`, ADR-0060): the immutable policy versions, the
 * recorded availability facts, and the pinned per-payment decisions with their step trails.
 *
 * <p>Generic over the unit of work like every store here (ADR-0033): the caller owns the
 * transaction boundary, which for the decision is the confirmation's Tx1 — the decision, the
 * attempt and the intent's transition commit together or not at all.
 */
public interface RoutingStore<T> {

    // ----------------------------------------------------------------- policy versions

    /**
     * The next free version number — {@code max + 1}, read without a lock. The arbiter is
     * `V013`'s {@code UNIQUE (version)}, judged at {@link #insertVersionIfNumberIsFree}; ten
     * instances re-read and take distinct, gapless numbers (the fee schedule's minting
     * discipline).
     */
    int nextVersionNumber(T unitOfWork);

    /**
     * Inserts the version with its rules and candidate rails, or answers {@code false} when
     * another writer took the number first — the unique index answering, not an error.
     */
    boolean insertVersionIfNumberIsFree(T unitOfWork, RoutingPolicyVersion version);

    /**
     * The version in force at {@code at}: newest {@code effective_from} not after it,
     * highest version number breaking the tie (the fee schedule's `EFFECTIVE_ORDER`).
     * Empty only before the `V013` seed has been applied — a wiring fault for a running
     * platform, judged by the caller.
     */
    Optional<RoutingPolicyVersion> findVersionInForce(T unitOfWork, Instant at);

    /** The version a stored decision pins ({@code INV-HIST-04}) — the recomputation's read. */
    Optional<RoutingPolicyVersion> findVersionById(T unitOfWork, RoutingPolicyVersionId id);

    // ----------------------------------------------------------------- availability

    /**
     * Records the operator's availability fact — an upsert: one row per rail, the newest act
     * winning, every act audited by the caller (ADR-0060 §4).
     */
    void recordAvailability(T unitOfWork, RailAvailability availability);

    /** Every recorded availability fact, by rail. An absent rail is available by default. */
    Map<RailId, RailAvailability> availabilityByRail(T unitOfWork);

    // ----------------------------------------------------------------- decisions

    /** Inserts the decision and its steps — the confirmation's Tx1, beside the attempt. */
    void insertDecision(T unitOfWork, RoutingDecision decision);

    /**
     * The newest decision recorded for {@code intent} — the chosen one when a dispatch
     * happened, otherwise the latest recorded refusal (each refused confirm records one).
     */
    Optional<RoutingDecision> findLatestDecisionForIntent(T unitOfWork, PaymentIntentId intent);

    /**
     * Appends one step to a decision's trail — the fallback's abandonment on
     * {@code NOTHING_SENT} (`INV-RAIL-02`; the aggregate door is
     * {@link RoutingDecision#abandonedOnNothingSent}).
     */
    void appendStep(T unitOfWork, RoutingDecisionId decision, RoutingStep step);
}
