package com.finapp.checkout;

import java.util.Optional;
import java.util.UUID;

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
     * A session <strong>this merchant's</strong>, by identifier — the tenant-scoped read
     * (`P6-TSK-007`, {@code INV-MER-01}).
     *
     * <p>The predicate is in the statement, so an unknown session, a malformed identifier and
     * another merchant's are one empty answer produced by the database rather than by a check
     * a caller has to remember. That is the difference between a tenancy rule and a habit.
     */
    Optional<CheckoutSession> findOwnedBy(T unitOfWork, UUID merchantRef, CheckoutSessionId id);

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
     * The session a payment intent belongs to, or empty — <strong>the completion's own
     * lookup</strong> (`P6-TSK-007`).
     *
     * <p>The reference points one way, from checkout INTO payments (ADR-0053 §3), so this is
     * the only direction the link can be followed: {@code payments} does not know sessions and
     * must not. Locked, because the caller is about to transition the row it finds.
     */
    Optional<CheckoutSession> findByIntentForUpdate(T unitOfWork, java.util.UUID intentRef);

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
    /**
     * Sessions whose offer has run out and that nothing has ended (`P6-TSK-008`) — the
     * sweeper's candidate list, bounded and deterministically ordered.
     *
     * <p><strong>Two bounds, because the two states deserve different patience.</strong> An
     * {@code OPEN} session expires at its own deadline: nobody is paying and nothing is in
     * flight. A {@code PAYMENT_PENDING} session has a payment with a provider, and the provider
     * answers on its own schedule (ADR-0046) — expiring it at the same instant would be
     * <em>harmless</em> (the {@code EXPIRED → COMPLETED_LATE} edge catches the late capture)
     * but would turn ordinary provider latency into {@code COMPLETED_LATE} churn and destroy
     * the point of that state being countable. The grace is a safety margin, not the
     * correctness ({@code PaymentSweeper}'s recorded phrasing, inherited).
     *
     * <p>The candidates are a <em>suggestion</em>: each is re-read and re-judged under its own
     * lock, because another writer may have moved it since the list was taken.
     *
     * @param openBefore an {@code OPEN} session with {@code expires_at} at or before this is
     *     overdue
     * @param pendingBefore the same for {@code PAYMENT_PENDING}, already reduced by the grace
     */
    java.util.List<CheckoutSession> findExpirable(
            T unitOfWork, java.time.Instant openBefore, java.time.Instant pendingBefore, int limit);

    boolean transition(T unitOfWork, CheckoutSession before, CheckoutSession transitioned);
}
