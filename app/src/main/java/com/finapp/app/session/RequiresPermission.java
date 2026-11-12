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
 * <h2>Where it is declared</h2>
 *
 * <p>On the handlers of the operator surface and every other privileged door, from `PHASE_1_PLAN.md`
 * §7's two administration endpoints - `POST /v1/identities/{id}/suspension` and
 * `POST /v1/identities/{id}/roles`, built by `P1-TSK-028` - onwards. *(Until 2026-11-12 this said
 * that no production endpoint declared one yet and that no backlog task owned those two: true when it
 * was written, stale since `P1-TSK-028`.)*
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    PermissionName value();
}
