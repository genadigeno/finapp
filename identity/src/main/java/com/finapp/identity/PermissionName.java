package com.finapp.identity;

/**
 * What an actor may do (`P1-TSK-020`, ADR-0031).
 *
 * <h2>Three values, and none invents a capability</h2>
 *
 * <p>Each names privileged actions `AUDITABLE_ACTIONS.md` already declares - identity suspension,
 * role assignment, and the KYC review actions `P2-TSK-003` catalogued - so the vocabulary follows
 * the registry rather than anticipating it. A permission for an action nobody has catalogued would
 * be a claim about a capability that does not exist.
 *
 * <p><em>(This javadoc said "two values" and that neither admin endpoint existed - true when
 * written, closed by `P1-TSK-028` and widened by `P2-TSK-004`.)</em> The permissions exist because
 * the ACTIONS do, and because a check with no vocabulary cannot be tested at all.
 *
 * <h2>There is deliberately no `sqlValueList()`</h2>
 *
 * <p>{@link RoleName} has one because a `CHECK` constraint persists a role. **A permission is never
 * a column**: ADR-0031 puts the role-to-permission mapping in code, so there is no constraint this
 * could generate and there will not be one. This enum shipped with the method, and the completion
 * gate removed it as dead - a helper that produces a SQL fragment for a column that does not exist
 * implies permissions are persisted somewhere, which is the opposite of the decision.
 */
public enum PermissionName {

    /**
     * Suspend an identity so it can no longer authenticate — and lift a suspension.
     *
     * <p>The most consequential thing one person can do to another's account here: it does not
     * merely deny a request, it ends the person's ability to log in at all.
     *
     * <p><strong>One permission for both directions, not two</strong> (`P1-TSK-032`), which is
     * {@link #ROLE_ASSIGN}'s own shape — "grant or revoke a role". The administrator trusted to
     * impose a suspension is the administrator trusted to lift one, and a separate
     * {@code IDENTITY_REINSTATE} held by the only role that exists would be vocabulary with no
     * decision behind it. The audit trail distinguishes the two actions; the permission need not.
     */
    IDENTITY_SUSPEND,

    /**
     * Grant or revoke a role.
     *
     * <p>**The permission that grants permissions**, which is why it is the one to watch: anybody
     * holding it can give themselves any other, so an audit record naming the actor is the only
     * thing that makes the escalation visible afterwards.
     */
    ROLE_ASSIGN,

    /**
     * Review KYC/KYB cases: read a case with its evidence, resolve screening hits, record
     * decisions (`P2-TSK-004`).
     *
     * <p>The first permission whose actions live outside {@code identity} — the vocabulary is
     * still this module's, because ADR-0031 keeps authorization here as a recorded merge, and one
     * foreign-domain permission does not reach its split trigger. It names actions the registry
     * already declares ({@code kyc.DecisionRecorded}, {@code kyc.ScreeningHitResolved},
     * {@code kyc.DocumentContentRead}); the endpoints that check it are {@code P2-TSK-012}'s,
     * which is why it ships before them — a check with no vocabulary cannot be built, let alone
     * tested.
     *
     * <p><strong>Deliberately not folded into what an administrator holds.</strong> Reviewing a
     * person's identity documents and managing identities are different trust decisions taken
     * about different people — the first real least-privilege split between administrative
     * populations. An administrator holding {@code ROLE_ASSIGN} can still grant themselves
     * {@code KYC_REVIEWER}; what the split buys is that the escalation is a recorded grant in
     * the trail rather than a capability that was silently always there ({@code P1-TSK-028}'s
     * honesty about what refusing self-elevation buys).
     */
    KYC_REVIEW
}
