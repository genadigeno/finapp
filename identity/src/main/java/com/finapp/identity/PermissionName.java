package com.finapp.identity;

/**
 * What an actor may do (`P1-TSK-020`, ADR-0031).
 *
 * <h2>Two values, and neither invents a capability</h2>
 *
 * <p>Each names a privileged action `AUDITABLE_ACTIONS.md` already declares - identity suspension
 * and role assignment - so the vocabulary follows the registry rather than anticipating it. A
 * permission for an action nobody has catalogued would be a claim about a capability that does not
 * exist.
 *
 * <p>`PHASE_1_PLAN.md` §7 marks both endpoints `session, admin role`. **Neither endpoint exists**,
 * and no backlog task owns them - recorded rather than absorbed as `P1-TSK-028`. The permissions
 * exist because the ACTIONS do, and because a check with no vocabulary cannot be tested at all.
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
    ROLE_ASSIGN
}
