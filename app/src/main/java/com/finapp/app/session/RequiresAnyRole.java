package com.finapp.app.session;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a handler any identity holding a live role may reach, and nobody else (`X-TSK-019`, ADR-0031's dated note of
 * 2026-11-13, {@code INV-IDN-04}).
 *
 * <h2>Why a sixth declaration rather than a permission or an exemption</h2>
 *
 * <p>{@code GET /v1/operator/me} tells an operator which roles and permissions they hold. Every operator may ask it, so no
 * one {@link com.finapp.identity.PermissionName} fits: inventing one every role carries would be a permission that grants
 * nothing, and choosing an existing one would hide the route from every role without it. Exempting the route by name from
 * {@code RoutePermissionRegisterTest#everyOperatorRouteIsPermissionGuarded} would leave a list-of-one exception a copy-paste
 * widens silently. So the rule is a declaration of its own, as explicit as a permission and refused the same way:
 *
 * <ul>
 *   <li><strong>It implies {@link RequiresSession}</strong>: no session, {@code 401 api.Unauthenticated}.
 *   <li><strong>The roles are read with the session</strong>, in its transaction, by the same read an operator act's
 *       permission is decided from ({@code Authorization.standingOf}) - never from the session, never cached. A role revoked
 *       is gone on the next request on every replica.
 *   <li><strong>No live role: {@code 403 api.Forbidden}</strong>, the permission's refusal, recorded as
 *       {@code identity.AuthorizationDenied} ({@code requirement=ANY_ROLE}) in a transaction of its own before it is
 *       answered. A customer learns nothing about the operator surface.
 *   <li><strong>The actor is {@code EMPLOYEE}</strong>, as at any operator act: the same read types it.
 * </ul>
 *
 * <p>It never stands beside {@link Unauthenticated}, {@link com.finapp.app.merchant.RequiresMerchantKey} or
 * {@link RequiresPermission}: a permission already implies a role, so the pair would be one rule written twice, and the
 * register pins each route under one of them. The interceptor refuses such a handler and {@code EveryEndpointDeclaresARuleTest}
 * fails the build for it. {@code RoutePermissionRegisterTest#ANY_ROLE_REGISTER} pins which routes carry this declaration, so
 * a new one is a reviewed line.
 *
 * <p><strong>Not a way around a permission.</strong> It is for an operator's reads about themselves. An act on anything
 * else - another identity, money, a merchant, a case - declares the permission that act needs.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAnyRole {}
