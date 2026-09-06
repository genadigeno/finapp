package com.finapp.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Where sessions live (`P1-TSK-013`, ADR-0030).
 *
 * <h2>A port, and an interface that cannot be implemented by a cache</h2>
 *
 * <p>Every method takes the caller's unit of work, so an implementation has nowhere to keep state
 * of its own between calls without being obviously wrong. That is not a coincidence of the shape:
 * {@code INV-IDN-03} requires a revoked session to be refused <strong>on the next request, on every
 * instance</strong>, and a process-local cache in front of this would make revocation eventually
 * consistent — which is to say, not revocation.
 *
 * <p>ADR-0030's follow-up leaves room for a cache <em>in front of an authority</em> if lookup is
 * ever measured to be a bottleneck. That is an additive change behind this interface, and it is not
 * the same thing as an implementation that answers from memory.
 *
 * @param <T> the unit of work. A JDBC {@link java.sql.Connection} (ADR-0033)
 */
public interface SessionStore<T> {

    /** Writes a newly issued session on the caller's unit of work. */
    void insert(T unitOfWork, Session session);

    /**
     * The session a token identifies, if it may be used at {@code at}.
     *
     * <p><strong>One answer for four different situations</strong> — no such token, revoked,
     * idle-expired, absolutely expired. A caller that could tell them apart would eventually report
     * them apart, and <em>"your session expired"</em> versus <em>"your session was revoked"</em>
     * tells somebody holding a stolen identifier which of the two happened. It also tells them the
     * identifier was real, which is the more valuable fact.
     */
    Optional<Session> findLive(T unitOfWork, SessionToken token, Instant at);

    /**
     * Extends the idle bound of a live session, never past its absolute bound.
     *
     * <p>Conditional: it moves the row only while the session is still live, and its row count is
     * the outcome. Two instances touching the same session at once is the normal case (ADR-0014),
     * and neither may resurrect a session the other has just revoked.
     *
     * <p><strong>Nothing calls this per request yet.</strong> The idle bound is meaningless without
     * it — a session whose idle bound is never extended dies at the idle timeout regardless of use,
     * which collapses two bounds into one — so it belongs to the aggregate that owns the bound. The
     * per-request call arrives with the authenticated endpoints (`P1-TSK-016`, `P1-TSK-020`).
     *
     * @return whether a live session was extended
     */
    boolean touch(T unitOfWork, SessionId sessionId, Instant at, SessionPolicy policy);
}
