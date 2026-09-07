package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

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
 * and no backlog task owns them - recorded rather than absorbed. The permissions exist because the
 * ACTIONS do, and because a check with no vocabulary cannot be tested at all.
 */
public enum PermissionName {

    /**
     * Suspend an identity so it can no longer authenticate.
     *
     * <p>The most consequential thing one person can do to another's account here: it does not
     * merely deny a request, it ends the person's ability to log in at all.
     */
    IDENTITY_SUSPEND,

    /**
     * Grant or revoke a role.
     *
     * <p>**The permission that grants permissions**, which is why it is the one to watch: anybody
     * holding it can give themselves any other, so an audit record naming the actor is the only
     * thing that makes the escalation visible afterwards.
     */
    ROLE_ASSIGN;

    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
