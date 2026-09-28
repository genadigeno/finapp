package com.finapp.identity;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Duration;
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
 * sessions issued under the old one</strong>. That holds for the absolute bound. The idle bound is
 * extended on use by the policy the touching instance holds, which is recorded against
 * {@code X-TSK-008} rather than claimed here.
 *
 * <h2>Two clocks, and every column belongs to exactly one (`X-TSK-007`)</h2>
 *
 * <p><strong>The bounds are coordination time.</strong> One instance writes them and any other
 * judges them, so both halves belong to the one clock every instance shares, the database's
 * (ADR-0014). The store stamps them from {@code now()} and judges them against {@code now()}.
 * Nothing in this class computes one, which is why issuing produces a {@link Draft} and only the
 * store produces a {@code Session}: whoever holds one holds the bounds the database will judge.
 *
 * <p><strong>{@link #issuedAt()} and {@link #revokedAt()} are business time</strong>: when the
 * instance that acted says it happened, from its injected {@code Clock} ({@code P0-TSK-013}), which
 * is the same reading its audit record carries. They decide nothing. Under skew they disagree with
 * the bounds and with each other, and a session issued by a fast instance can carry an
 * {@code issuedAt} after its own idle bound. That is two clocks describing one row, not an
 * incoherent session, so nothing here compares them.
 *
 * <p><strong>Whether a session is live is therefore the database's question</strong>:
 * {@code ACTIVE}, and inside both bounds at the database's {@code now()}. There is deliberately no
 * Java predicate taking an instant. {@code isLiveAt} was removed by {@code X-TSK-007} for the reason
 * {@code V004} removed {@code IdempotencyRecord.isStaleAt}: a predicate on a caller's clock invites
 * the defect straight back.
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

        // No comparison of issuedAt with either bound (X-TSK-007). They are read from two clocks,
        // the issuing instance's and the database's, so such a check would refuse a legitimate row
        // whenever the issuer ran fast. "Not expired when issued" is V016's
        // session_bounds_follow_liveness, which compares the bounds with live_from on one clock.
        if (idleExpiresAt.isAfter(absoluteExpiresAt)) {
            // The idle bound may equal the absolute one - a very short-lived session - but never
            // exceed it, or the absolute lifetime is not a lifetime.
            throw new IllegalArgumentException(
                    "The idle bound may not outlast the absolute bound, or the absolute bound means"
                            + " nothing");
        }
    }

    /** Decides a new session at the given assurance, under the given policy, with no device. */
    public static Draft issue(
            IdGenerator ids,
            Clock clock,
            IdentityId identityId,
            SessionToken token,
            AssuranceLevel assurance,
            SessionPolicy policy) {
        return issue(ids, clock, identityId, token, assurance, policy, null);
    }

    /**
     * Decides a new session, recording what it was established from.
     *
     * <p><strong>A draft, not a session</strong> ({@code X-TSK-007}). It carries the policy's two
     * lifetimes and no bound: {@link SessionStore#insert} has the database stamp both from its own
     * {@code now()} and returns the stored session. The clock here supplies {@code issuedAt} only,
     * which is business time.
     *
     * <p>The device is <strong>optional and never scored</strong> ({@code V005}): it is a label its
     * owner can recognise their own sessions by, and nothing else reads it. A null device is
     * entirely ordinary — a client that sent no {@code User-Agent}, or one whose header held
     * nothing displayable.
     */
    public static Draft issue(
            IdGenerator ids,
            Clock clock,
            IdentityId identityId,
            SessionToken token,
            AssuranceLevel assurance,
            SessionPolicy policy,
            DeviceDescription device) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        return new Draft(
                SessionId.next(ids),
                identityId,
                token.hash(),
                assurance,
                Instant.now(clock),
                device == null ? null : device.value(),
                policy.idleTimeout(),
                new SessionAbsoluteBound.Lifetime(policy.absoluteLifetime()));
    }

    /**
     * Decides the session that replaces {@code current} on a privilege change ({@link
     * SessionRotation}).
     *
     * <p>It inherits the absolute bound of {@code current} <strong>verbatim</strong>, copied from
     * {@code current}'s row by the statement that writes the replacement. The bound is already on
     * the database's clock, so nothing is re-derived: a rotation cannot extend how long a person
     * stays logged in (ADR-0030), and no instance's clock can move the bound. Package-private,
     * because only rotation has a bound to inherit.
     *
     * @param issuedAt the rotating instance's business time, the same instant its audit record
     *     and the revocation of {@code current} carry
     */
    static Draft replacement(
            SessionId id,
            Session current,
            SessionToken token,
            AssuranceLevel toAssurance,
            Instant issuedAt,
            Duration idleTimeout) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(token, "token must not be null");
        return new Draft(
                id,
                current.identityId(),
                token.hash(),
                toAssurance,
                issuedAt,
                current.device().orElse(null),
                idleTimeout,
                new SessionAbsoluteBound.Inherited(current.id()));
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

    /**
     * A session decided but not yet written ({@code X-TSK-007}).
     *
     * <p>Everything the deciding instance may decide: who, which token, what assurance, which
     * device, its own record of when, and how long the session may live. <strong>No bound</strong>:
     * the bounds are the database's to stamp, from its own clock, in the statement that writes the
     * row. The caller then holds the {@code Session} read back from that row, so the
     * {@code expiresAt} a client is told is the bound the database will judge.
     *
     * <p>A class rather than a record so that the constructor can be package-private: outside
     * {@code identity} a draft is made only by {@link Session#issue}, under a {@link SessionPolicy}.
     */
    public static final class Draft {

        private final SessionId id;
        private final IdentityId identityId;
        private final Sensitive<String> tokenHash;
        private final AssuranceLevel assurance;
        private final Instant issuedAt;
        private final String device;
        private final Duration idleTimeout;
        private final SessionAbsoluteBound absolute;

        Draft(
                SessionId id,
                IdentityId identityId,
                Sensitive<String> tokenHash,
                AssuranceLevel assurance,
                Instant issuedAt,
                String device,
                Duration idleTimeout,
                SessionAbsoluteBound absolute) {
            this.id = Objects.requireNonNull(id, "id must not be null");
            this.identityId = Objects.requireNonNull(identityId, "identityId must not be null");
            this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
            this.assurance = Objects.requireNonNull(assurance, "assurance must not be null");
            this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            this.device = device;
            this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
            this.absolute = Objects.requireNonNull(absolute, "absolute must not be null");
            if (idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "An idle timeout of zero writes a session that is idle-expired when issued");
            }
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

        /** Business time: the deciding instance's own reading, never compared with a bound. */
        public Instant issuedAt() {
            return issuedAt;
        }

        public Optional<String> device() {
            return Optional.ofNullable(device);
        }

        /** How long the session may sit unused, measured from the database's {@code now()}. */
        public Duration idleTimeout() {
            return idleTimeout;
        }

        public SessionAbsoluteBound absolute() {
            return absolute;
        }

        /** Never the token hash, for {@link Session#toString}'s reason. */
        @Override
        public String toString() {
            return "Session.Draft[id=" + id + ", identity=" + identityId + ", assurance="
                    + assurance + "]";
        }
    }
}
