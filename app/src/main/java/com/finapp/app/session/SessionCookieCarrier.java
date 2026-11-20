package com.finapp.app.session;

import com.finapp.app.surface.RequestSurfaces;
import com.finapp.app.surface.Surface;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.platform.api.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The customer's session carried in an HttpOnly cookie on the public surface (ADR-0156, `X-TSK-029`; finapp-front-end ADR-F02).
 *
 * <p><strong>A carrier, never a store.</strong> The cookie holds the same opaque token ADR-0030 issues in a body; the session is
 * still judged in PostgreSQL on every request, on every replica. Nothing here is held between requests: the allow-list is
 * configuration, read once at start-up, and every decision is a function of the request alone - so ten instances answer one
 * request identically.
 *
 * <p>Three rules, each fail-closed:
 *
 * <ul>
 *   <li><strong>The public surface only</strong>: elsewhere the cookie is ignored, as if absent, so staff never authenticate by it;
 *   <li><strong>an opt-in to receive it</strong> ({@value #CARRIER_HEADER}{@code : cookie}), without which every answer is
 *       today's;
 *   <li><strong>a CSRF check before any handler</strong> on every unsafe method that presents the cookie or opts in: the
 *       {@value #CSRF_HEADER} header and an allowed {@code Origin}, or {@code 403 identity.CsrfRejected}.
 * </ul>
 */
public final class SessionCookieCarrier {

    /**
     * The cookie. The {@code __Host-} prefix makes the browser refuse it unless it is {@code Secure}, host-only and
     * {@code Path=/}: a sibling host or a plain-HTTP response cannot plant or overwrite it (ADR-0156 decision 1).
     */
    public static final String COOKIE = "__Host-fa_session";

    /** The opt-in: a request carrying it with value {@code cookie} receives the session as the cookie. */
    public static final String CARRIER_HEADER = "X-Finapp-Session-Carrier";

    /** The custom header a cross-site form cannot send, nor a cross-origin script without a preflight finapp never grants. */
    public static final String CSRF_HEADER = "X-Finapp-Request";

    /** The customer application's local origins: its dev server and its preview (finapp-front-end {@code vite.config.ts}). */
    static final List<String> LOCAL_ORIGINS = List.of("http://localhost:5180", "http://localhost:4180");

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final RequestSurfaces surfaces;
    private final Set<String> allowedOrigins;

    /**
     * @param allowedOrigins exact origins ({@code scheme://host[:port]}) an unsafe cookie request may come from. Empty means none:
     *     every such request is refused, which is what a deployed instance without the setting gets (ADR-0156 decision 6)
     */
    public SessionCookieCarrier(RequestSurfaces surfaces, List<String> allowedOrigins) {
        this.surfaces = Objects.requireNonNull(surfaces, "surfaces must not be null");
        Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
        this.allowedOrigins =
                allowedOrigins.stream()
                        .map(String::strip)
                        .filter(origin -> !origin.isEmpty())
                        .map(SessionCookieCarrier::normalised)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The allow-list a configuration value names: the configured origins, or - unset, where nothing is deployed - the customer
     * application's local ones; unset on a deployed instance, none.
     */
    public static List<String> allowedOrigins(String configured, boolean nothingDeployed) {
        if (configured != null && !configured.isBlank()) {
            return java.util.Arrays.stream(configured.split(",")).map(String::strip).filter(o -> !o.isEmpty()).toList();
        }
        return nothingDeployed ? LOCAL_ORIGINS : List.of();
    }

    // -----------------------------------------------------------------

    /** Whether this request is on the public surface, the one surface that reads or issues the cookie. */
    public boolean onPublicSurface(HttpServletRequest request) {
        return surfaces.of(request) == Surface.PUBLIC;
    }

    /** Whether the request asked to receive its session as the cookie - honoured on the public surface only. */
    public boolean issuesCookie(HttpServletRequest request) {
        return onPublicSurface(request) && optsIn(request);
    }

    /**
     * Refuses a possible cross-site request before any handler runs (ADR-0156 decision 5): on the public surface, an unsafe
     * method that presents the cookie or opts in needs {@value #CSRF_HEADER}{@code : 1} and an {@code Origin} in the allow-list.
     *
     * <p>One answer for every reason, so a probe learns nothing about which check it failed.
     */
    public void refuseCrossSite(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT)) || !onPublicSurface(request)) {
            return;
        }
        if (cookieValue(request).isEmpty() && !optsIn(request)) {
            return;
        }
        String origin = request.getHeader("Origin");
        boolean sameSite =
                "1".equals(request.getHeader(CSRF_HEADER))
                        && origin != null
                        && allowedOrigins.contains(normalised(origin));
        if (!sameSite) {
            throw new ApiException(
                    IdentityErrorCode.CSRF_REJECTED,
                    "A cookie-carried request lacked the custom header or an allowed Origin");
        }
    }

    // -----------------------------------------------------------------

    /** The {@code Set-Cookie} value that issues {@code token}: no {@code Max-Age}, so the server's bounds are the only expiry. */
    public static String issued(String token) {
        Objects.requireNonNull(token, "token must not be null");
        return COOKIE + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict";
    }

    /** The {@code Set-Cookie} value that clears it. */
    public static String cleared() {
        return COOKIE + "=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict";
    }

    private static boolean optsIn(HttpServletRequest request) {
        return "cookie".equals(request.getHeader(CARRIER_HEADER));
    }

    /**
     * The cookie's value as the browser sent it, whatever the surface - the caller decides whether the surface reads it
     * ({@link SessionAuthenticationInterceptor} reads it on the public surface only).
     */
    static Optional<String> cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue().strip());
            }
        }
        return Optional.empty();
    }

    /** Origins compare case-insensitively and without a trailing slash: {@code HTTP://LocalHost:5180/} is the same origin. */
    private static String normalised(String origin) {
        String value = origin.strip().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
