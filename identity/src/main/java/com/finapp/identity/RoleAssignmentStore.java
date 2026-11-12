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
     * {@link #liveRolesOf}, each live row read locked {@code FOR SHARE} until the caller's transaction ends (`P15-TSK-032`): the
     * grant's read of whether this is the identity's first role. A revocation of one of these rows locks it for update first
     * ({@link #lockForRevocation}), so the two serialise: a grant that read a role live finishes before that role's revocation
     * reads what is left, and one that waited reads the revocation committed. Without it a grant reading a role a concurrent
     * revocation is removing would call its own role a second one, and the revocation, not seeing the uncommitted grant, would
     * call the removed role the last: the sessions would end before the grant committed, and a sign-in in between would serve
     * the new member of staff. Readers that decide nothing - the interceptor, the public doors - keep {@link #liveRolesOf}.
     */
    Set<RoleName> lockLiveRolesOf(T unitOfWork, IdentityId identityId);

    /**
     * What a revocation decides by, read in <strong>one statement</strong> with every row it returns locked {@code FOR UPDATE}
     * until the caller's transaction ends (`P15-TSK-032`): the identity's own live holdings, and every live holding of
     * {@code granting} - the roles that can grant roles - with whether its holder is {@code ACTIVE}. Empty {@code granting}
     * reads the identity's own holdings alone.
     *
     * <p><strong>"Last" is read after any revocation it raced.</strong> The rows are locked in their primary key's order, so
     * two revocations contend for the same rows in the same order and never deadlock; the second waits for the first to
     * commit, and PostgreSQL then re-reads each row it waited on and drops one the first revoked ({@code READ COMMITTED}'s
     * re-check of a locked row). So two administrators revoking each other's role at once see each other's revocation: one
     * is the last and is refused. A grant committed after the statement began is not seen, which errs towards "last", the
     * refusing side. A holder's status is read with the row, not locked: a suspension committed before the statement is seen.
     */
    RevocationHoldings lockForRevocation(T unitOfWork, IdentityId identityId, Set<RoleName> granting);

    /**
     * What {@link #lockForRevocation} read.
     *
     * @param held the identity's live roles
     * @param activeGranting each live holding of a granting role whose identity is {@code ACTIVE}, the identity's own among them
     */
    record RevocationHoldings(Set<RoleName> held, Set<Holding> activeGranting) {

        public RevocationHoldings {
            held = Set.copyOf(java.util.Objects.requireNonNull(held, "held must not be null"));
            activeGranting = Set.copyOf(java.util.Objects.requireNonNull(activeGranting, "activeGranting must not be null"));
        }
    }

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
     * instances revoking at once produce one transition and one audit record. The revocation door reads
     * {@link #lockForRevocation} first, so its decision and this write see the same rows.
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
