package com.finapp.app.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The CSRF check of a cookie-carried request, before any handler (ADR-0156 decision 5, `X-TSK-029`).
 *
 * <p>Registered ahead of {@link SessionAuthenticationInterceptor}, so a refused request reaches no session lookup, no idle
 * extension, no lockout count and no handler - and the {@code @Unauthenticated} sign-in is covered too, which the session
 * interceptor never checks: a sign-in forced into a victim's browser (login CSRF) is refused here.
 */
@RequiredArgsConstructor
public final class SessionCookieGuard implements HandlerInterceptor {

    @NonNull private final SessionCookieCarrier carrier;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod) {
            carrier.refuseCrossSite(request);
        }
        return true;
    }
}
