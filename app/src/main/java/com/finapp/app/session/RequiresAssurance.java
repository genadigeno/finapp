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
 * <h2>Who declares it</h2>
 *
 * <p>Phase 1 shipped it with the test that proves it and no caller (`PHASE_1_PLAN.md` §66: nothing
 * in Phase 1 was a high-value action). Recounted from the code at `P15-TSK-021` (2026-11-08):
 * <strong>21 handlers</strong> declare it, every one on the method and every one at
 * `MULTI_FACTOR` - Phase 13's risk doors and Phase 14's accounting, ledger and write-off approvals.
 * (The plan's "25" was a text count that also matched comments and the interceptor's message.) The
 * conditional step-ups - `MULTI_FACTOR` exactly when the caller has a factor - are each operation's
 * own and do not use it. <strong>35</strong> since `P15-TSK-039`, with the dead-letter doors, a payout destination's
 * proposal and approval, and Phase 9's approvals, restarts and reversals.
 *
 * <h2>A refusal is recorded</h2>
 *
 * <p>Since `P15-TSK-021` a session below the level is refused <em>and recorded</em>
 * ({@code identity.StepUpRefused}), by the interceptor, in a transaction of its own that commits
 * before the refusal is answered; an operation's own step-up refusal is recorded by the error
 * contract ({@link StepUpRefusals}). The level is judged before the permission is read.
 *
 * <p>Read from the method first and then the class, the `RequiresSession` idiom. It implies
 * {@link RequiresSession}: there is no assurance without a session to carry it.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAssurance {
    AssuranceLevel value();
}
