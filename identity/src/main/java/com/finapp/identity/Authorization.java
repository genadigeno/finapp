package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whether an actor holds a permission, and granting the roles that carry them
 * (`P1-TSK-020`, ADR-0031, {@code INV-IDN-04}).
 *
 * <h2>Authentication grants nothing</h2>
 *
 * <p>{@code INV-IDN-04}: <em>"An authenticated session grants no permission by itself."</em> Holding
 * a session says who you are; it says nothing about what you may do. This is the half that answers
 * the second question, and it is deliberately separate from the ownership check that lives inside
 * each domain operation — collapsing them is the defect ADR-0031 names, where a customer with a
 * legitimate permission uses it against somebody else's resource and every check passes.
 *
 * <h2>Resolved per request, never carried on the session</h2>
 *
 * <p>A role stamped at login survives its own revocation until the session expires. That would make
 * <em>"remove their access now"</em> a promise the architecture cannot keep — {@code INV-IDN-03}'s
 * reasoning, applied to authorization rather than to authentication.
 *
 * <h2>A denial is audited</h2>
 *
 * <p>{@code INV-AUD-03} requires authorization to be tested <em>from the attacker's direction</em>,
 * and a refused privileged attempt is the only trace one leaves. An accepted one is audited by the
 * operation itself; a refused one has no operation to do it.
 */
public final class Authorization {

    /** What an audit record about a role or a denial points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    private final RoleAssignmentStore<Connection> assignments;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;

    public Authorization(
            RoleAssignmentStore<Connection> assignments,
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter) {
        this.assignments = Objects.requireNonNull(assignments, "assignments must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * Whether the identity may perform the operation.
     *
     * <p>Derived from the roles it holds <em>now</em>. A permission nobody's role grants is refused,
     * which is the same statement as deny-by-default read from the other end.
     */
    public boolean permits(
            Connection unitOfWork, IdentityId identityId, PermissionName permission) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        Set<RoleName> roles = assignments.liveRolesOf(unitOfWork, identityId);
        return roles.stream().anyMatch(role -> role.permissions().contains(permission));
    }

    /**
     * Records that a privileged attempt was refused.
     *
     * <p>Separate from {@link #permits} rather than folded into it, and that is deliberate: a
     * boolean query that also writes is one nobody can call twice, and the caller — the boundary —
     * is the only thing that knows whether the refusal was final or whether another rule will be
     * consulted.
     */
    public void recordDenial(
            Connection unitOfWork, IdentityId identityId, PermissionName permission) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");

        audit(
                unitOfWork,
                identityId,
                IdentityAuditAction.AUTHORIZATION_DENIED,
                AuditOutcome.FAILED,
                "permission=" + permission,
                Optional.empty());
    }

    /**
     * Grants a role.
     *
     * @param assignedBy the actor granting it — never derived from the request, always the proven
     *     identity, because {@link PermissionName#ROLE_ASSIGN} is the permission that grants
     *     permissions and its trail is the only thing that makes an escalation visible
     * @param reason required: this is an action taken against somebody else's account
     * @return whether the role was granted, or false if already held
     */
    public boolean assign(
            Connection unitOfWork,
            IdentityId identityId,
            RoleName role,
            IdentityId assignedBy,
            String reason) {
        Objects.requireNonNull(reason, "reason must not be null");

        Instant at = Instant.now(clock);
        boolean assigned = assignments.assign(unitOfWork, identityId, role, assignedBy, at);
        if (assigned) {
            audit(
                    unitOfWork,
                    identityId,
                    IdentityAuditAction.IDENTITY_ROLE_ASSIGNED,
                    AuditOutcome.SUCCEEDED,
                    "granted=" + role,
                    Optional.of(reason));
        }
        return assigned;
    }

    /**
     * Revokes a role.
     *
     * <p>Effective on the identity's <strong>next request</strong>, because permissions are resolved
     * per request. That is the property this whole design exists for, and it is asserted rather than
     * assumed.
     */
    public boolean revoke(
            Connection unitOfWork,
            IdentityId identityId,
            RoleName role,
            IdentityId revokedBy,
            String reason) {
        Objects.requireNonNull(reason, "reason must not be null");

        Instant at = Instant.now(clock);
        boolean revoked = assignments.revoke(unitOfWork, identityId, role, revokedBy, at);
        if (revoked) {
            audit(
                    unitOfWork,
                    identityId,
                    IdentityAuditAction.IDENTITY_ROLE_ASSIGNED,
                    AuditOutcome.SUCCEEDED,
                    "revoked=" + role,
                    Optional.of(reason));
        }
        return revoked;
    }

    // -----------------------------------------------------------------

    private void audit(
            Connection unitOfWork,
            IdentityId identityId,
            IdentityAuditAction action,
            AuditOutcome outcome,
            String changeSummary,
            Optional<String> reason) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "An authorization decision must run inside a"
                                                    + " correlation scope: the audit record carries"
                                                    + " the identifier, and a fabricated one would"
                                                    + " point at no flow at all (P0-TSK-014)"));

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        action,
                        AUDIT_TARGET_TYPE,
                        identityId.value().toString(),
                        reason,
                        outcome,
                        correlation.correlationId(),
                        Optional.of(changeSummary)));
    }
}
