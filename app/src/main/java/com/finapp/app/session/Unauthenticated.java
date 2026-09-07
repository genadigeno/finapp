package com.finapp.app.session;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler is deliberately reachable with no session (`P1-TSK-020`, `INV-IDN-04`).
 *
 * <h2>An affirmative statement, which is the entire point</h2>
 *
 * <p>`INV-IDN-04` is enforced by *deny by default; no operation is permitted by the absence of a
 * rule*, and ADR-0031 says it plainly: **an operation with no declared permission is refused, not
 * permitted.**
 *
 * <p>So being public cannot be an absence. Before this, `POST /v1/registrations` and
 * `POST /v1/authentications` were reachable because nobody had said otherwise - and an endpoint
 * added next year with the annotation forgotten would have been reachable for exactly the same
 * reason, with nothing to notice. `P1-TSK-016` recorded that as failing closed *by accident*; this
 * makes it fail closed *by rule*.
 *
 * <h2>Two endpoints carry it, and both must</h2>
 *
 * <p>Registration creates the identity a session would name, and authentication is how a session is
 * obtained. Neither can require one without being unreachable. Every other declaration is a
 * narrowing of this one.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Unauthenticated {}
