package com.finapp.identity;

import java.time.Instant;
import java.util.Set;

/**
 * Authoritative storage for role assignments (`P1-TSK-020`, ADR-0033).
 *
 * @param <T> the unit of work: a JDBC {@link java.sql.Connection} (ADR-0033)
 */
public interface RoleAssignmentStore<T> {

    /**
     * The roles an identity currently holds.
     *
     * <p><strong>Read on every request that declares a permission, and never cached on a
     * session.</strong> A role stamped at login survives its own revocation until that session
     * expires — so an administrator stripped of their role would keep it, and <em>"remove their
     * access now"</em> would be a promise the architecture cannot keep. That is
     * {@code INV-IDN-03}'s reasoning applied to authorization.
     *
     * <p>{@code revoked_at IS NULL} is in the statement rather than filtered afterwards: a filter is
     * something a later caller can forget, and a predicate is not.
     */
    Set<RoleName> liveRolesOf(T unitOfWork, IdentityId identityId);

    /**
     * Takes the lock every change to an identity's roles takes first (`P15-TSK-032`): its row in {@code identity.identity},
     * {@code FOR NO KEY UPDATE}, until the caller's transaction ends. A grant and a revocation of one identity therefore never
     * interleave: the second waits for the first to commit, and its next statement, under {@code READ COMMITTED}, reads the
     * roles the first left. Without it a grant reading a role a concurrent revocation is removing would call its own role a
     * second one, while the revocation, not seeing the uncommitted grant, would call the removed role the last - and two
     * revocations of an identity's last two roles would each see the other's role still live, and neither end the sessions.
     *
     * <p>{@code NO KEY UPDATE}, not {@code UPDATE}: a session's insert, whose foreign key takes {@code KEY SHARE} on the row, is
     * not held back by a role change, only by a bulk session revocation's own {@code FOR UPDATE} ({@link
     * SessionStore#revokeAllFor}), as before. A suspension's status write takes {@code NO KEY UPDATE} too, so it and a role
     * change of the same identity serialise as well. An identity that does not exist locks nothing; the caller has read it.
     * Readers that decide nothing - the interceptor, the public doors - take no lock.
     */
    void lockForRoleChange(T unitOfWork, IdentityId identityId);

    /**
     * Locks every live holding of {@code roles}, each row {@code FOR UPDATE} until the caller's transaction ends, in the primary
     * key's order (`P15-TSK-032`): the lock a revocation of a granting role and an administrator's suspension of a holder take
     * before they count who else can grant roles, the one lock that spans identities. Two of them lock the same rows in the same
     * order, so they never deadlock on them: the second waits for the first to commit.
     *
     * <p><strong>It reads nothing a caller decides by.</strong> The statement that waited answers from the snapshot it began
     * with: PostgreSQL re-checks only the rows another transaction updated, so a holder's status joined to it would be the
     * status before a suspension that committed while it waited. The count is {@link #activeHoldingsOf}, a statement of its
     * own taken after this one returns.
     */
    void lockHoldingsOf(T unitOfWork, Set<RoleName> roles);

    /**
     * Every live holding of {@code roles} whose identity is {@code ACTIVE}, read by a statement of its own (`P15-TSK-032`). After
     * {@link #lockHoldingsOf}, under {@code READ COMMITTED}, it sees every revocation and suspension that committed while the
     * lock waited, and none can commit after it until the caller's transaction ends: each of them takes the same lock first.
     * A grant committed later is not seen, which errs towards "last", the refusing side; risk's automated suspension takes no
     * such lock (it is not bound by the rule).
     */
    Set<Holding> activeHoldingsOf(T unitOfWork, Set<RoleName> roles);

    /** One live holding: who, which role. */
    record Holding(IdentityId identityId, RoleName role) {}

    /**
     * The identity signing in as {@code login}, if it holds a live role now (`P15-TSK-042`): one statement, whatever the
     * identifier names, so an unknown identifier, a customer's and a role-holder's cost the same read.
     *
     * <p>For a door that knows only what the caller typed. Read from the grants, as {@link #liveRolesOf} is: every role is
     * an operational one ({@code Authorization.Standing}), so any live role makes the identity staff.
     */
    java.util.Optional<IdentityId> liveHolderByLogin(T unitOfWork, LoginIdentifier login);

    /**
     * The identity a recovery request was made for, if it holds a live role now (`P15-TSK-042`): one statement, read before
     * the request's token is judged, so a refusal spends nothing.
     */
    java.util.Optional<IdentityId> liveHolderOfRecovery(T unitOfWork, RecoveryRequestId request);

    /**
     * The live holdings of {@code roles} whose identity has no active factor, oldest first, at most {@code limit}
     * (`P15-TSK-039`): one read of both tables, so no holding is judged against a factor read at another moment.
     */
    java.util.List<FactorlessHolding> holdingsWithoutFactor(T unitOfWork, Set<RoleName> roles, int limit);

    /** One such holding: who, which role, since when. */
    record FactorlessHolding(IdentityId identityId, RoleName role, Instant assignedAt) {}

    /**
     * Grants a role.
     *
     * @param assignedBy the identity that granted it. Recorded on the row, not only in the audit
     *     trail: an escalation is only explicable afterwards if the current state names its author
     * @return whether it was granted. False when the identity already holds it live — reported
     *     rather than thrown, because granting a role somebody already has is not an error
     */
    boolean assign(T unitOfWork, IdentityId identityId, RoleName role, IdentityId assignedBy,
            Instant at);

    /**
     * Revokes a role.
     *
     * <p>Conditional on the assignment still being live, and the row count is the outcome — so two
     * instances revoking at once produce one transition and one audit record. The revocation door takes
     * {@link #lockForRoleChange} first, so its decision and this write see the same rows.
     *
     * <p>The row is <strong>marked, never deleted</strong>: the application role holds no
     * {@code DELETE}, and a role somebody held for a month is a fact about the past that an
     * investigator will need.
     *
     * @return whether a live assignment was revoked
     */
    boolean revoke(T unitOfWork, IdentityId identityId, RoleName role, IdentityId revokedBy,
            Instant at);
}
