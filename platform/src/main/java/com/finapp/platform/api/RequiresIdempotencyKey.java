package com.finapp.platform.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This endpoint refuses a request that carries no {@code Idempotency-Key}.
 *
 * <p>{@code API_CONVENTIONS.md} §6: <em>every money-moving command requires
 * {@code Idempotency-Key}, and a command that can move money and does not require it is a defect,
 * not a relaxation.</em> This is how an endpoint says so.
 *
 * <p><strong>Why a declaration rather than a default.</strong> Requiring the header everywhere
 * would force it onto reads, where it means nothing and would train clients to send a value nobody
 * uses. Requiring it nowhere leaves each handler to remember. A declaration makes the requirement
 * greppable — {@code RequiresIdempotencyKey} is the list of endpoints that claim to move money —
 * and reviewable against that list.
 *
 * <p><strong>This is not idempotency.</strong> It is the boundary contract for it. The guarantee
 * itself is a unique database constraint (ADR-0004, {@code INV-IDEM-01}), because what must be
 * deduplicated is a *financial effect* and an HTTP filter can only deduplicate a request. This
 * annotation makes the caller supply the key that constraint is keyed on; it does not make
 * anything idempotent by itself.
 *
 * <p>Declared in {@code platform} rather than {@code app} for the reason {@code P0-TSK-024}
 * established: the contract belongs beside {@link ErrorCode}, and a published contract must not be
 * a function of the web stack that happens to render it. The enforcement lives in {@code app}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresIdempotencyKey {}
