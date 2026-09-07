package com.finapp.app.session;

import com.finapp.identity.AssuranceLevel;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the minimum assurance a handler requires (`P1-TSK-018`, `INV-IDN-05`).
 *
 * <h2>A level, never a boolean, and that is the invariant's own wording</h2>
 *
 * <p>ADR-0030 chose a level because *"every real MFA bypass is a route that produces a session a
 * boolean says is fine"*. A boolean moves the question to every place that could *set* it; a level
 * moves it to every place that *consumes* it - and the consumers are the ones with the requirement.
 *
 * <p>The comparison is {@code AssuranceLevel.atLeast}, so declaring `MULTI_FACTOR` admits `STRONG`
 * too. A handler that demanded equality would refuse a session that is *more* assured than it asked
 * for, which is the kind of rule people work around.
 *
 * <h2>No production endpoint declares it, and that is the plan</h2>
 *
 * <p>`PHASE_1_PLAN.md` §66: *"Nothing in Phase 1 requires `MULTI_FACTOR` for a specific action,
 * because Phase 1 has no high-value action. Consumed by Phase 4."* So the mechanism ships with the
 * test that proves it and no caller - a seam, declared as one, rather than a requirement invented
 * to give the annotation something to do.
 *
 * <p>Read from the method first and then the class, the `RequiresSession` idiom. It implies
 * {@link RequiresSession}: there is no assurance without a session to carry it.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAssurance {
    AssuranceLevel value();
}
