package com.finapp.payments;

import com.finapp.platform.security.Actor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PaymentIntent} ({@code P5-TSK-009}, over {@code V002}).
 *
 * <p><strong>Every status move is a conditional transition</strong>: {@code UPDATE … WHERE id
 * AND status = from}, the row count the answer — the cross-instance arbiter
 * {@code PHASE_5_PLAN.md} §7 names for two instances confirming one intent, with {@code V002}'s
 * trigger as the layer beneath for the writers this store never sees. The one granted column is
 * {@code status} ({@code P5-TSK-008}); everything else was written at birth and never again.
 */
public interface PaymentIntentStore<T> {

    void insert(T unitOfWork, PaymentIntent intent);

    Optional<PaymentIntent> findById(T unitOfWork, PaymentIntentId intent);

    /**
     * The intent, if — and only if — it belongs to {@code partyId}: the ownership predicate in
     * the statement (ADR-0031, the {@code P1-TSK-016} shape), never a load-then-compare.
     * Not-yours and does-not-exist are one empty answer.
     */
    Optional<PaymentIntent> findOwned(T unitOfWork, PaymentIntentId intent, UUID partyId);

    /**
     * Moves {@code from → to} conditionally; {@code false} means another writer got there
     * first (or the state never held), and the caller converges on what it finds.
     */
    boolean transition(
            T unitOfWork, PaymentIntentId intent, PaymentIntentStatus from,
            PaymentIntentStatus to);

    /** The append-only history row — evidence of the path, actor model per {@code V006}. */
    void recordTransition(
            T unitOfWork,
            PaymentIntentId intent,
            PaymentIntentStatus from,
            PaymentIntentStatus to,
            Actor actor,
            Instant occurredAt);
}
