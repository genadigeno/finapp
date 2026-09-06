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
     * Ends one session. Terminal ({@code INV-LIFE-04}).
     *
     * <p>Conditional on the session still being {@code ACTIVE}, so two instances revoking the same
     * session produce one transition and the second is told it lost.
     *
     * <p><strong>Revoking a session that is already revoked, expired or absent is not an error.</strong>
     * It reports that nothing was done. Making it fail would let a caller distinguish <em>"that
     * session existed and was live"</em> from <em>"it did not"</em>, which is an oracle over
     * somebody else's session identifiers.
     *
     * @return whether a live session was ended
     */
    boolean revoke(T unitOfWork, SessionId sessionId, java.time.Instant at);

    /**
     * Ends every live session of an identity — <em>"log out everywhere"</em>.
     *
     * <p>Takes a lock on the identity first, and that is not tidiness: see
     * {@link #revokeAllForExcept} for the race it closes.
     *
     * @return how many sessions were ended
     */
    int revokeAllFor(T unitOfWork, IdentityId identityId, java.time.Instant at);

    /**
     * Ends every live session of an identity except one — what a credential change does.
     *
     * <p>Changing a password whose compromise you suspect must not leave the attacker's session
     * alive, and must not log <em>you</em> out of the session you are changing it from. ADR-0030
     * names this as a cross-aggregate effect inside {@code identity}: one transaction, one module.
     *
     * <h2>The lock, and the race it closes</h2>
     *
     * <p>This takes {@code SELECT … FROM identity.identity WHERE id = ? FOR UPDATE}, and an insert
     * of a session takes {@code FOR KEY SHARE} on the same row <strong>because of its foreign
     * key</strong>. The two conflict, so a session cannot be <strong>issued</strong> concurrently
     * with a revocation and survive it.
     *
     * <p>Without the {@code FOR UPDATE} it does, and that was verified before the lock was written
     * rather than assumed: a login that inserts after the revoke has selected its rows commits a
     * session the revoke never saw, so an attacker holding the old password keeps a live session
     * across the password change — and every test that revokes and then looks up still passes.
     *
     * <p><strong>Only one explicit lock is needed, and finding that out took a surviving
     * mutation.</strong> An explicit lock was written on the issuing side too; removing it changed
     * nothing, while removing this one is caught. The foreign key was already supplying the other
     * half.
     *
     * <p>{@code PHASE_1_PLAN.md} §8 states the requirement as an absolute: <em>"Revocation wins. A
     * session must never survive a concurrent revoke."</em> {@code READ COMMITTED} does not supply
     * that, so the lock does.
     *
     * <p>The cost is stated: authentications for <strong>one</strong> identity serialise. That is
     * rare in normal use, and under credential stuffing it is a feature.
     *
     * @param spare the session to leave alive — the one the change is being made from
     * @return how many sessions were ended
     */
    int revokeAllForExcept(
            T unitOfWork, IdentityId identityId, SessionId spare, java.time.Instant at);

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
