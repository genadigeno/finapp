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

    /**
     * An identity was locked after repeated failed authentications (`P1-TSK-011`).
     *
     * <p>No reason required, and no actor to ask for one: nobody <em>chose</em> this. It is the
     * platform reacting to a pattern, which is precisely why it must be recorded - a lock is the
     * first evidence that somebody is being attacked, and a spike across many identities is a
     * credential-stuffing campaign in progress.
     *
     * <p>Written by the attempt that <strong>crosses</strong> the threshold, not by every attempt
     * afterwards: repeating the record while an account stays locked buries the event that matters
     * under copies of it.
     */
    AUTHENTICATION_LOCKED(
            "identity.AuthenticationLocked",
            "An identity was locked after repeated failed authentications.",
            false),

    /**
     * One or more sessions were ended (`P1-TSK-014`).
     *
     * <p><strong>One record per operation, not per session.</strong> Revoking forty writes one, with
     * the count in the change summary — the session rows carry {@code revoked_at} and answer
     * <em>when</em> each ended, while this answers <em>who decided</em>. Forty records would bury the
     * decision under its consequences, which is the argument {@code AUTHENTICATION_LOCKED} already
     * makes for recording the crossing rather than every attempt after it.
     *
     * <p>No reason required: logging yourself out, or having your other sessions ended because you
     * changed your password, is not an action taken against anybody. {@code PHASE_1_PLAN.md} §5
     * lists <em>"forced session revocation"</em> separately among privileged actions — an
     * administrator ending somebody else's sessions is a different action, and it will need a reason
     * for the same reason {@code IDENTITY_SUSPENDED} does.
     */
    SESSION_REVOKED(
            "identity.SessionRevoked",
            "One or more sessions were ended.",
            false),

    /**
     * A session was replaced by a new one on a privilege change (`P1-TSK-015`).
     *
     * <p><strong>Distinct from {@link #SESSION_REVOKED}, and that distinction is the reason it
     * exists.</strong> A rotation revokes the old session, so it would be easy to record it as one —
     * and an investigator would then read a logout that never happened. <em>"This session was
     * ended"</em> and <em>"this session was replaced"</em> are different facts, and only the second
     * leaves the person still logged in.
     *
     * <p>The record names both identifiers, so the chain from a session to its successor is
     * followable. No reason required: nobody chose it in the sense a justification would answer, it
     * is the automatic consequence of a privilege change.
     */
    SESSION_ROTATED(
            "identity.SessionRotated",
            "A session was replaced by a new one on a privilege change.",
            false),

    /**
     * An identity began adding a second factor (`P1-TSK-017`).
     *
     * <p><strong>Recorded even though nothing has changed yet</strong>, and that is the point: an
     * enrolment that is started and never confirmed is exactly what an account-takeover attempt
     * looks like from the inside. An attacker who reached a session and began attaching their own
     * factor leaves this record whether or not they finished.
     *
     * <p>No reason required: it is the account holder acting on their own account.
     */
    MFA_ENROLMENT_STARTED(
            "identity.MfaEnrolmentStarted",
            "A second factor enrolment was begun.",
            false),

    /**
     * A second factor was confirmed and is now usable (`P1-TSK-017`).
     *
     * <p>Separate from {@link #MFA_ENROLMENT_STARTED} because they are different facts about the
     * account: one says somebody was offered a secret, the other says somebody <em>proved they hold
     * it</em>. Only the second changes what can authenticate, and collapsing them would make a
     * started-and-abandoned enrolment indistinguishable from a completed one in the trail.
     */
    MFA_ENROLMENT_CONFIRMED(
            "identity.MfaEnrolmentConfirmed",
            "A second factor was confirmed and is now usable.",
            false),

    /**
     * A second factor was proven, and the session was elevated (`P1-TSK-018`).
     *
     * <p>Recorded <strong>beside</strong> the {@link #SESSION_ROTATED} the elevation produces, not
     * instead of it: <em>"the factor was proven"</em> and <em>"the session was replaced"</em> are
     * different facts, which is the distinction `P1-TSK-015` established. One says the person is
     * who they claim; the other says which identifier now speaks for them.
     */
    MFA_CHALLENGE_SUCCEEDED(
            "identity.MfaChallengeSucceeded",
            "A second factor was proven and the session was elevated.",
            false),

    /**
     * A second-factor challenge was refused (`P1-TSK-018`).
     *
     * <p><strong>The only durable trace of somebody guessing codes.</strong> The response is one
     * uniform refusal whatever the cause, so the *reason* lives here and nowhere else — an
     * investigator needs to tell a wrong code from a challenge against an account with no factor
     * at all, because the second is somebody probing rather than somebody mistyping.
     */
    MFA_CHALLENGE_FAILED(
            "identity.MfaChallengeFailed",
            "A second-factor challenge was refused.",
            false),

    /**
     * A privileged action was refused for want of a permission (`P1-TSK-020`, `INV-AUD-03`).
     *
     * <p><strong>The only trace an attacker leaves.</strong> An action that succeeds is audited by
     * the operation itself; one that is refused has no operation to do it, so without this record a
     * probe for privileged endpoints is indistinguishable from silence.
     *
     * <p>`INV-AUD-03` asks for authorization to be tested *from the attacker's direction*, and this
     * is what that looks like in the trail rather than in the suite.
     *
     * <p>No reason required: nobody chose it. It is the automatic consequence of a rule.
     */
    AUTHORIZATION_DENIED(
            "identity.AuthorizationDenied",
            "A privileged action was refused because the actor lacked the permission.",
            false),

    IDENTITY_SUSPENDED(
            "identity.IdentitySuspended",
            "An identity was suspended by an administrator and can no longer authenticate.",
            true),

    /**
     * A suspension was lifted (`P1-TSK-032`).
     *
     * <p>A reason is required for the same argument as the suspension it undoes: it is an action
     * taken against somebody else's account, and the trail's answer to <em>why was this person let
     * back in?</em> matters exactly as much as why they were locked out — a quiet reinstatement is
     * how an accomplice undoes an incident response.
     */
    IDENTITY_REINSTATED(
            "identity.IdentityReinstated",
            "A suspension was lifted by an administrator and the identity can authenticate again.",
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
            true),

    /**
     * A contact channel was registered against an identity.
     *
     * <p>Recorded because <strong>this is the first move in a takeover</strong>. An attacker who has
     * a stolen password registers a mailbox they control, waits for it to verify, and then recovers
     * the account through the front door. The trail must show when the address the platform trusts
     * became the address it trusts.
     *
     * <p>No reason: the customer chose it, and demanding a justification for adding your own email
     * would be a control on the wrong person.
     */
    CONTACT_CHANNEL_ADDED(
            "identity.ContactChannelAdded",
            "A contact channel was registered against an identity, unverified.",
            false),

    /**
     * Control of a contact channel was proven.
     *
     * <p>The moment {@code INV-IDN-06}'s precondition becomes true for an account, so it is the row
     * an investigator reads to answer <em>"could this account have been recovered on that day, and
     * to where?"</em>
     */
    CONTACT_CHANNEL_VERIFIED(
            "identity.ContactChannelVerified",
            "Control of a contact channel was proven, making it usable for account recovery.",
            false),

    /**
     * Account recovery was begun.
     *
     * <p>Recovery is the account-takeover vector by construction, so its rate is a security signal
     * and each initiation is evidence. The actor is the <strong>platform</strong>: the caller is
     * unauthenticated by definition — somebody who cannot log in — so there is no proven identity to
     * attribute it to. What carries the information is the target.
     */
    RECOVERY_INITIATED(
            "identity.RecoveryInitiated",
            "Account recovery was begun for an identity holding a verified channel.",
            false),

    /**
     * A credential was replaced through recovery.
     *
     * <p>The most consequential thing that can happen to an account without anybody proving they
     * knew the password. It ends every session, and the record says how many — because an
     * investigator asking <em>"was somebody signed in when this happened?"</em> has no other source
     * once the rows are revoked.
     */
    RECOVERY_COMPLETED(
            "identity.RecoveryCompleted",
            "A credential was replaced through account recovery, ending every session.",
            false);

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
