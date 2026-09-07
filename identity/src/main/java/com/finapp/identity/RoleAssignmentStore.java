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
     * instances revoking at once produce one transition and one audit record.
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
