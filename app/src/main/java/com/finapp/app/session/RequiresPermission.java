package com.finapp.app.session;

import com.finapp.identity.PermissionName;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission a handler requires (`P1-TSK-020`, ADR-0031, `INV-IDN-04`).
 *
 * <h2>Half of the check, and the other half is not here</h2>
 *
 * <p>ADR-0031 requires **both**: *may an actor of this kind do this at all?* — declared here, at the
 * boundary — and *may THIS actor do it to THIS resource?* — checked inside the domain operation,
 * against authoritative state. Collapsing them is the most common authorization defect in financial
 * software, where somebody with a legitimate `transfer:create` permission uses it against another
 * person's account and every check passes.
 *
 * <p>Ownership is never checked here, because the boundary knows only an identifier from the
 * request and trusting that **is** the defect.
 *
 * <h2>It implies {@link RequiresSession}</h2>
 *
 * <p>There is no permission without an actor to hold one. A handler declaring only a permission
 * would otherwise be reachable unauthenticated, which is the inversion of what its author asked for
 * - the same trap `@RequiresAssurance` closes.
 *
 * <h2>No production endpoint declares one yet</h2>
 *
 * <p>`PHASE_1_PLAN.md` §7 marks two endpoints `session, admin role` -
 * `POST /v1/identities/{id}/suspension` and `POST /v1/identities/{id}/roles` - and **no backlog task
 * owns either**. Recorded rather than absorbed. Inventing a privileged endpoint to give this
 * annotation something to do would be a security control chosen to suit a test.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    PermissionName value();
}
