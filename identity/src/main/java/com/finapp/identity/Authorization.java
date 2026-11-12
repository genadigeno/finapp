package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public final class Authorization {

    /** What an audit record about a role or a denial points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    @NonNull private final RoleAssignmentStore<Connection> assignments;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;

    /**
     * Whether the identity may perform the operation.
     *
     * <p>Derived from the roles it holds <em>now</em>. A permission nobody's role grants is refused,
     * which is the same statement as deny-by-default read from the other end.
     */
    public boolean permits(
            Connection unitOfWork, IdentityId identityId, PermissionName permission) {
        Objects.requireNonNull(permission, "permission must not be null");
        return standingOf(unitOfWork, identityId).permits(permission);
    }

    /**
     * What the identity holds <em>now</em>, read once (`P15-TSK-021`): the roles that decide both whether it may perform
     * an operator act and the population its act is recorded in.
     *
     * <p>One read for both, deliberately. Read twice, a role granted between the reads would admit an act typed by the
     * standing before it, and a role revoked between them would type an act by a standing it no longer has. Read once,
     * the permission and the type are always the same instant's.
     */
    public Standing standingOf(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        return new Standing(assignments.liveRolesOf(unitOfWork, identityId));
    }

    /**
     * An identity's live roles at one instant (`P15-TSK-021`; {@code INV-IDN-03}, {@code INV-IDN-04}).
     *
     * <p><strong>Holding a role makes a person staff.</strong> Every {@link RoleName} is an operational role - a customer
     * acts on their own account and holds none - so an identity holding any live role is the operator population,
     * {@link ActorType#EMPLOYEE}, and one holding none is a customer. The session interceptor applies this on an operator
     * act only (a handler declaring a permission): the same identity acting on its own wallet or its own loan
     * application acts as a customer, which is what {@link ActorType#CUSTOMER} means.
     *
     * @param roles the roles held, never {@code null}
     */
    public record Standing(Set<RoleName> roles) {

        public Standing {
            roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        }

        /** Whether any role held grants {@code permission}: deny-by-default read from the other end. */
        public boolean permits(PermissionName permission) {
            Objects.requireNonNull(permission, "permission must not be null");
            return roles.stream().anyMatch(role -> role.permissions().contains(permission));
        }

        /** The population an operator act by this identity is recorded in: {@code EMPLOYEE} with any live role. */
        public ActorType actorType() {
            return roles.isEmpty() ? ActorType.CUSTOMER : ActorType.EMPLOYEE;
        }
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
     * Records that a request was refused because its session had not stepped up (`P15-TSK-021`;
     * {@link IdentityAuditAction#STEP_UP_REFUSED}).
     *
     * <p>The caller commits it in a transaction of its own <em>before</em> the refusal is answered, as
     * {@link #recordDenial} is: the refused request has no operation to record it, and the handler's own transaction,
     * where there is one, rolls back with the refusal.
     *
     * @param operation the refused operation as {@code <METHOD> <route pattern>} - a pattern, never a path's values, and
     *     never the presented code, the factor or the session
     */
    public void recordStepUpRefusal(Connection unitOfWork, IdentityId identityId, String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        audit(
                unitOfWork,
                identityId,
                IdentityAuditAction.STEP_UP_REFUSED,
                AuditOutcome.FAILED,
                "operation=" + operation,
                Optional.empty());
    }

    /**
     * The identity signing in as {@code login}, when it holds a live role now (`P15-TSK-042`): for a door that knows only
     * what the caller typed. One statement whatever the identifier names.
     */
    public Optional<IdentityId> roleHolderSigningInAs(Connection unitOfWork, LoginIdentifier login) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        return assignments.liveHolderByLogin(unitOfWork, Objects.requireNonNull(login, "login must not be null"));
    }

    /** The identity a recovery request was made for, when it holds a live role now (`P15-TSK-042`). */
    public Optional<IdentityId> roleHolderRecovering(Connection unitOfWork, RecoveryRequestId request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        return assignments.liveHolderOfRecovery(unitOfWork, Objects.requireNonNull(request, "request must not be null"));
    }

    /**
     * Records that a role-holding identity was refused on the public surface (`P15-TSK-042`;
     * {@link IdentityAuditAction#PUBLIC_SURFACE_REFUSED}).
     *
     * @param operation the door as {@code <METHOD> <route pattern>}: never a path's values
     * @param surface where the request arrived: {@code public}, or {@code unmarked} where no usable marker came
     * @param detail anything further the door may say, such as whether the presented password was the identity's; empty
     *     for none, and never a secret
     */
    public void recordPublicSurfaceRefusal(
            Connection unitOfWork, IdentityId identityId, String operation, String surface, String detail) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(detail, "detail must not be null");

        audit(
                unitOfWork,
                identityId,
                IdentityAuditAction.PUBLIC_SURFACE_REFUSED,
                AuditOutcome.FAILED,
                "operation=" + operation + " surface=" + surface + (detail.isEmpty() ? "" : " " + detail),
                Optional.empty());
    }

    /** The live holdings of a role that needs a factor whose holder has none (`P15-TSK-039`). */
    public List<RoleAssignmentStore.FactorlessHolding> holdingsWithoutFactor(Connection unitOfWork, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Set<RoleName> needingAFactor = java.util.Arrays.stream(RoleName.values())
                .filter(RoleName::requiresEnrolledFactor)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return assignments.holdingsWithoutFactor(unitOfWork, needingAFactor, limit);
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
        return grant(unitOfWork, identityId, role, assignedBy, reason, granted -> "");
    }

    /**
     * Grants a role, and runs {@code consequence} on the same unit of work after the row is written and before the record
     * is (`P15-TSK-043`): what the grant did beside the row, which the record then names. A first role ends the grantee's
     * sessions here, so one record says both, and a consequence that fails leaves no record and rolls the grant back with
     * the caller's transaction. Not run when the role was already held: nothing was granted.
     *
     * @param consequence given the unit of work; answers the words the record appends to {@code granted=<role>}, or empty
     */
    public boolean assign(
            Connection unitOfWork,
            IdentityId identityId,
            RoleName role,
            IdentityId assignedBy,
            String reason,
            java.util.function.Function<Connection, String> consequence) {
        return grant(unitOfWork, identityId, role, assignedBy, reason, consequence);
    }

    /**
     * Both {@code assign}s' one body. Named apart from them on purpose: {@code RoleGrantDoorTest} holds that nothing in
     * production but {@code IdentityAdministration} calls a method named {@code assign} here, and an overload delegating to
     * its sibling by that name would be a second caller the rule must then explain away.
     */
    private boolean grant(
            Connection unitOfWork,
            IdentityId identityId,
            RoleName role,
            IdentityId assignedBy,
            String reason,
            java.util.function.Function<Connection, String> consequence) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(consequence, "consequence must not be null");

        Instant at = Instant.now(clock);
        boolean assigned = assignments.assign(unitOfWork, identityId, role, assignedBy, at);
        if (assigned) {
            String also = Objects.requireNonNull(consequence.apply(unitOfWork), "a consequence answers its words, or empty");
            audit(
                    unitOfWork,
                    identityId,
                    IdentityAuditAction.IDENTITY_ROLE_ASSIGNED,
                    AuditOutcome.SUCCEEDED,
                    "granted=" + role + (also.isEmpty() ? "" : ", " + also),
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
