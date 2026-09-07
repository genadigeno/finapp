package com.finapp.app.session;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler may only be entered by an authenticated session (`P1-TSK-016`).
 *
 * <h2>Declared rather than defaulted, and the deny-by-default half is deliberately not here</h2>
 *
 * <p>{@code P1-TSK-020} states its own requirement — <em>"an endpoint with no declaration is
 * refused"</em> — and building it now would be doing that task's work under another name. This
 * annotation is the seam it will tighten.
 *
 * <p>An endpoint that forgets the annotation nonetheless <strong>fails closed</strong>, and that is
 * asserted rather than claimed: no scope is established, so {@code SecurityContext.require()}
 * throws and the request becomes a 500. Ugly, and safe — the failure is a refusal rather than
 * somebody else's data.
 *
 * <p>Read from the method first and then the class, so annotating a controller covers its handlers
 * without each declaring it — the {@code RequiresIdempotencyKey} idiom.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresSession {}
