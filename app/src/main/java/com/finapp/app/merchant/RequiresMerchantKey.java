package com.finapp.app.merchant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler may only be entered by an authenticated <strong>merchant</strong>
 * acting on its own API key (`P6-TSK-002`, ADR-0052) — the platform's fifth authorization
 * declaration and its fourth authentication vocabulary.
 *
 * <h2>Why a declaration of its own rather than a variant of {@code @RequiresSession}</h2>
 *
 * <p>The populations are disjoint and must stay so. A merchant key is not a session: it has no
 * expiry, no assurance level, no second factor and no {@code identity} row behind it — and the
 * subject it authenticates is a counterparty the platform does not own. Folding it into the
 * session annotation would make every {@code @RequiresSession} handler reachable by a merchant
 * key the day somebody widened the lookup, which is a failure nobody would see in review.
 *
 * <p><strong>The two are refused together</strong>: a handler carrying this annotation and any
 * session rule is a contradiction {@code SessionAuthenticationInterceptor} refuses outright,
 * exactly as it refuses {@code @Unauthenticated} beside a protective rule. A route is a
 * customer's, an operator's, a provider's or a merchant's — never two at once.
 *
 * <p>Read from the method first and then the class, so annotating a controller covers its
 * handlers — the {@code RequiresSession} idiom.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresMerchantKey {}
