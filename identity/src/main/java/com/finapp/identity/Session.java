package com.finapp.identity;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A person's authenticated presence (`P1-TSK-013`, ADR-0030).
 *
 * <h2>Expiry is derived, never stored, and that is the sharpest decision here</h2>
 *
 * <p>There is no {@code EXPIRED} status. A stored one needs something to write it — a sweep — and
 * until that sweep runs the session is expired in fact and {@code ACTIVE} in the database. Every
 * consumer would have to check the bounds <em>anyway</em>, so the status would be a second answer to
 * a question the row already answers, free to disagree with it. Derived, there is one answer and it
 * is always current.
 *
 * <h2>Two bounds, both recorded on the session</h2>
 *
 * <p>An <strong>idle</strong> timeout and an <strong>absolute</strong> lifetime. Idle alone lets an
 * active attacker hold a session for ever by using it; absolute alone logs a working customer out
 * mid-task. Both are needed and neither substitutes for the other.
 *
 * <p>They are stored on the row rather than read from policy at check time, which is
 * {@code INV-HIST-04}'s reasoning: <strong>a change of policy must not retroactively extend
 * sessions issued under the old one</strong>. Shortening the idle timeout should protect the
 * sessions issued from that moment; it must not silently lengthen anything, and it cannot, because
 * nothing re-reads the policy for a session that already exists.
 *
 * <h2>What it does not carry</h2>
 *
 * <p><strong>No permissions.</strong> {@code INV-IDN-04}: a session says <em>who</em>, never
 * <em>what</em>. Authorization is evaluated per operation ({@code P1-TSK-020}), and a session that
 * carried a permission set would be a stale copy of it the moment a role changed.
 */
public final class Session {

    private final SessionId id;
    private final IdentityId identityId;
    private final Sensitive<String> tokenHash;
    private final AssuranceLevel assurance;
    private final SessionStatus status;
    private final Instant issuedAt;
    private final Instant idleExpiresAt;
    private final Instant absoluteExpiresAt;
    private final String device;
    private final Instant revokedAt;

    private Session(
            SessionId id,
            IdentityId identityId,
            Sensitive<String> tokenHash,
            AssuranceLevel assurance,
            SessionStatus status,
            Instant issuedAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            String device,
            Instant revokedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.identityId = Objects.requireNonNull(identityId, "identityId must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.assurance = Objects.requireNonNull(assurance, "assurance must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.idleExpiresAt = Objects.requireNonNull(idleExpiresAt, "idleExpiresAt must not be null");
        this.absoluteExpiresAt =
                Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt must not be null");
        this.device = device;
        this.revokedAt = revokedAt;

        if (!idleExpiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A session that is idle-expired when issued is not a session");
        }
        if (!absoluteExpiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A session that is expired when issued is not a session");
        }
        if (idleExpiresAt.isAfter(absoluteExpiresAt)) {
            // The idle bound may equal the absolute one - a very short-lived session - but never
            // exceed it, or the absolute lifetime is not a lifetime.
            throw new IllegalArgumentException(
                    "The idle bound may not outlast the absolute bound, or the absolute bound means"
                            + " nothing");
        }
    }

    /** Issues a new session at the given assurance, under the given policy. */
    public static Session issue(
            IdGenerator ids,
            Clock clock,
            IdentityId identityId,
            SessionToken token,
            AssuranceLevel assurance,
            SessionPolicy policy) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Instant now = Instant.now(clock);
        return new Session(
                SessionId.next(ids),
                identityId,
                token.hash(),
                assurance,
                SessionStatus.ACTIVE,
                now,
                now.plus(policy.idleTimeout()),
                now.plus(policy.absoluteLifetime()),
                null,
                null);
    }

    /** Rebuilds a session the database has already validated. */
    public static Session rehydrate(
            SessionId id,
            IdentityId identityId,
            Sensitive<String> tokenHash,
            AssuranceLevel assurance,
            SessionStatus status,
            Instant issuedAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            String device,
            Instant revokedAt) {
        return new Session(
                id,
                identityId,
                tokenHash,
                assurance,
                status,
                issuedAt,
                idleExpiresAt,
                absoluteExpiresAt,
                device,
                revokedAt);
    }

    /**
     * Whether this session may be used at {@code at}.
     *
     * <p>Three ways to be unusable — revoked, idle-expired, absolutely expired — and the caller is
     * told only that it cannot be used. A caller that could tell them apart would eventually
     * <em>report</em> them apart, and "your session expired" versus "your session was revoked" tells
     * somebody holding a stolen identifier which of the two happened.
     */
    public boolean isLiveAt(Instant at) {
        return status == SessionStatus.ACTIVE
                && at.isBefore(idleExpiresAt)
                && at.isBefore(absoluteExpiresAt);
    }

    /**
     * The idle bound this session would have after being used at {@code at}.
     *
     * <p><strong>Never beyond the absolute bound.</strong> Extending past it would make the absolute
     * lifetime advisory, and an attacker holding a stolen identifier and using it steadily would
     * keep the session alive for ever — which is the exact failure the second bound exists to
     * prevent.
     */
    public Instant idleBoundAfterUseAt(Instant at, SessionPolicy policy) {
        Instant extended = at.plus(policy.idleTimeout());
        return extended.isAfter(absoluteExpiresAt) ? absoluteExpiresAt : extended;
    }

    public SessionId id() {
        return id;
    }

    public IdentityId identityId() {
        return identityId;
    }

    public Sensitive<String> tokenHash() {
        return tokenHash;
    }

    public AssuranceLevel assurance() {
        return assurance;
    }

    public SessionStatus status() {
        return status;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant idleExpiresAt() {
        return idleExpiresAt;
    }

    public Instant absoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public Optional<String> device() {
        return Optional.ofNullable(device);
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    /**
     * Never the token hash.
     *
     * <p>The hash is not the secret, but it is the single most useful thing to an attacker reading
     * logs: presenting it does nothing, and it identifies which session to go looking for. The
     * aggregate's own identifier says everything an operator needs.
     */
    @Override
    public String toString() {
        return "Session[id=" + id + ", identity=" + identityId + ", assurance=" + assurance
                + ", status=" + status + "]";
    }
}
