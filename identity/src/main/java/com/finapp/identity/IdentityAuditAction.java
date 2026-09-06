package com.finapp.identity;

import com.finapp.platform.audit.AuditableAction;

/**
 * What the {@code identity} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally, for the reason in {@code AUDITABLE_ACTIONS.md} §2.
 *
 * <p><strong>Deliberately small.</strong> Only the two actions {@code PHASE_1_PLAN.md} §7 declares
 * as privileged endpoints requiring an admin role are here. Authentication, session revocation,
 * credential change and MFA enrolment are all audited too, and are <em>not</em> declared yet:
 * each belongs to the task that builds it, where the decision about {@code requiresReason} can be
 * made against real behaviour rather than guessed. A registry may list an action before its code
 * exists; it should not list one before its <em>design</em> does.
 *
 * <p><strong>Both of these require a reason</strong>, and that is the distinction {@code §4} draws:
 * they are things a human chose to do that the system would not have done by itself. For an action
 * taken against someone else's account, the justification is the only evidence the decision was
 * legitimate — and this module is where an insider with a legitimate permission does the most
 * damage.
 *
 * <p><strong>No credential material appears in an audit record</strong>, as it appears nowhere else
 * ({@code INV-IDN-01}, {@code INV-AUD-02}). An audit record names the action and its target, never
 * the secret involved in it.
 *
 * <p><strong>Nothing here is emitted yet.</strong> The module has no aggregates and no endpoints;
 * these arrive with {@code P1-TSK-020} and {@code P1-TSK-022}.
 */
public enum IdentityAuditAction implements AuditableAction {

    /**
     * An identity was suspended, so it can no longer authenticate.
     *
     * <p>A reason is required. Suspension is a person deciding to deny someone access to their own
     * account — legitimate when it follows a fraud signal or a support request, and indisputably an
     * abuse vector otherwise. The justification is what separates the two afterwards, and there is
     * no later moment at which it can be reconstructed.
     */
    /**
     * A login was created for a party (`P1-TSK-006`).
     *
     * <p>Recorded in the registration's own transaction, so an identity that exists always has the
     * record of its creation and one that does not exist has none. {@code INV-AUD-01} calls for
     * every action of consequence to be attributable, and creating the thing that will later
     * authenticate as a person is one.
     *
     * <p>No reason required. The two actions below are taken <em>against</em> somebody else's
     * account by an administrator, which is the case a justification exists for; creating your own
     * login is not.
     */
    IDENTITY_CREATED(
            "identity.IdentityCreated",
            "A login was created for a party.",
            false),

    /**
     * Somebody proved they know an identity's secret (`P1-TSK-010`).
     *
     * <p>No reason required: authenticating to your own account is not a decision anybody has to
     * justify. It is recorded because {@code INV-AUD-01} asks for every action of consequence to be
     * attributable, and *when an account was used, and from which flow* is the first question asked
     * after a compromise.
     *
     * <p>This is the platform's first audit record naming a <strong>real actor</strong>: the
     * identity is proven at the moment it is written, so it is attributed to
     * {@code ActorType.CUSTOMER} rather than to the platform. {@code PHASE_1_PLAN.md} §5 says the
     * {@code enterSystem()} call sites are revisited in this phase, and this is the first one that
     * had a better answer available.
     */
    AUTHENTICATION_SUCCEEDED(
            "identity.AuthenticationSucceeded",
            "An identity was authenticated.",
            false),

    /**
     * An authentication attempt did not succeed (`P1-TSK-010`).
     *
     * <p>No reason required, and it is recorded for identities that <strong>do not exist</strong> -
     * which is the point. A failure rate against identifiers nobody registered is credential
     * stuffing, and it is invisible if only real accounts are recorded.
     *
     * <p>The actor is the platform: on this path there is no established identity, and there may be
     * no identity at all. The information a reader needs is carried by the record's
     * <strong>target</strong>, which is the attempted login identifier - the one place
     * {@code PHASE_1_PLAN.md} §10 permits an attempted identifier to appear, because the audit trail
     * is the regulatory artefact and is not client-visible.
     */
    AUTHENTICATION_FAILED(
            "identity.AuthenticationFailed",
            "An authentication attempt failed.",
            false),

    IDENTITY_SUSPENDED(
            "identity.IdentitySuspended",
            "An identity was suspended by an administrator and can no longer authenticate.",
            true),

    /**
     * A role was assigned to or removed from an identity.
     *
     * <p>A reason is required, and this is the most consequential action the module has: a role
     * assignment is a change to what someone is permitted to do, so it is the action by which every
     * other authorization decision can be quietly widened. An assignment nobody has to justify is
     * privilege escalation with a clean audit trail.
     *
     * <p>{@code INV-AUD-04}'s four-eyes requirement is not yet modelled — {@code audit_record}
     * records one actor — and that is recorded debt rather than an omission here. The reason
     * requirement is the part that is enforceable today.
     */
    IDENTITY_ROLE_ASSIGNED(
            "identity.RoleAssigned",
            "An administrator changed the roles held by an identity, altering what it is permitted "
                    + "to do.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    IdentityAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
