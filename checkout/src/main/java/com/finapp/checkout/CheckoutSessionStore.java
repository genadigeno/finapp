package com.finapp.checkout;

import java.util.Optional;

/**
 * Persistence port for {@link CheckoutSession} (`P6-TSK-006`). A port on ADR-0033's recorded
 * reasoning; the unit of work is the caller's, because a confirmation writes the session, the
 * payment intent and the audit record in one transaction, and a completion writes the session
 * and the order in one.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CheckoutSessionStore<T> {

    /** Inserts the freshly opened offer. */
    void insert(T unitOfWork, CheckoutSession session);

    /** The session, or empty — unknown and malformed are the surface's one 404. */
    Optional<CheckoutSession> findById(T unitOfWork, CheckoutSessionId id);

    /**
     * The session, locked — the serialization point for a transition that must judge before it
     * writes ({@code P3-TSK-014}'s closers idiom): read {@code FOR UPDATE}, transition through
     * the aggregate, write conditionally.
     */
    Optional<CheckoutSession> findByIdForUpdate(T unitOfWork, CheckoutSessionId id);

    /**
     * The session a token opens, or empty.
     *
     * <p>Looked up <strong>by hash</strong> — there is no token column to scan (the unique index
     * on {@code token_hash} makes this one row by index). Unknown, malformed and
     * somebody-else's are one empty answer, so the surface can keep its one refusal and never
     * become an oracle over other people's purchases ({@code INV-IDN-07}'s reasoning).
     */
    Optional<CheckoutSession> findByToken(T unitOfWork, CheckoutSessionToken presented);

    /**
     * Applies {@code transitioned}'s status conditionally ({@code WHERE status = ?} on the
     * from-state), attaches the payment intent reference when the transition set one, and
     * appends the history row when the write landed.
     *
     * <p>The row count converges the racers: {@code false} means another writer moved the row
     * first — the expiry sweeper beating a confirmation, or a second completion arriving — and
     * the caller re-reads rather than assumes ({@code INV-CON-01}). This is the arbiter
     * ADR-0053 §5 names, and it is the only one this phase needs.
     */
    boolean transition(T unitOfWork, CheckoutSession before, CheckoutSession transitioned);
}
