package com.finapp.app.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A request body whose fields are exactly its declared components (`P9-TSK-008`, PHASE_9_PLAN.md
 * section 9): an unknown field - {@code rate} included - is refused {@code 422 api.ValidationFailed}
 * before anything runs, rather than silently ignored as the platform's mapper otherwise does.
 *
 * <p>Opt-in per type, so no existing door changes behaviour: every {@code fx} and
 * {@code crossborder} request record carries it ({@code RatesAreNeverClientSuppliedTest} holds
 * that), because a client that believes it sent a rate must be told it did not.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ClosedBody {}
