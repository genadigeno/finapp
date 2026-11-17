package com.finapp.app.surface;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells every browser and proxy not to store an operator response (`P17-TSK-101`; gap G-05; finapp-admin-panel ADR-C131
 * decision 5; {@code THREAT_MODEL.md}, the browser ↔ operator edge boundary).
 *
 * <p><strong>Why.</strong> An operator response carries identifiers, amounts, statuses and the reasons people gave for acts on
 * them, and nothing told a browser, a corporate proxy or the operator console's own HTTP cache not to keep it: no response of the
 * application set {@code Cache-Control} at all. A shared workstation's disk cache, or a proxy between it and the operator edge,
 * then holds a copy of what a session read after the session has ended. {@code no-store} is the one directive that forbids
 * keeping it anywhere (RFC 9111 §5.2.2.5); {@code no-cache} and {@code private} still allow storage.
 *
 * <p><strong>Which responses.</strong> Those of the operator surface, as the edge states it ({@link RequestSurfaces}): every one,
 * success or refusal, a handler's answer or the container's error dispatch. {@link Surface#SINGLE_PORT} as well - where nothing is
 * deployed, the one port <em>is</em> the operator surface, and storing nothing there costs nothing. The public surface and an
 * unmarked request are left as they were: the customer API's caching is not this task's decision. The console's own files never
 * reach this application - the operator edge routes {@code /console/**} to the console's server, which sets their caching per path,
 * and {@code ConsoleHostingManifestsTest} and {@code edge.rego} refuse a {@code Cache-Control} in its headers middleware.
 *
 * <p><strong>Where it runs.</strong> Next after {@code CorrelationFilter}, before the size limit and everything else that may
 * answer, and set <em>before</em> the chain runs, so a response committed early still carries it. It runs on the error dispatch too
 * ({@link #shouldNotFilterErrorDispatch()}): the container's {@code /error} answer for an unknown route or a body that never
 * parsed is a second dispatch, and {@code OncePerRequestFilter} skips that by default. Nothing in the application resets a
 * response or sets {@code Cache-Control} itself, and {@code OperatorResponsesAreNotStoredTest} holds both.
 *
 * <p>Stateless and the same on every replica: one header, from the request's own marker.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class OperatorResponsesAreNotStored extends OncePerRequestFilter {

    /** The header and its one directive, as RFC 9111 names them. */
    public static final String HEADER = "Cache-Control";

    public static final String NO_STORE = "no-store";

    private final RequestSurfaces surfaces;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        mark(request, response);
        chain.doFilter(request, response);
    }

    /** The error dispatch is filtered as well: it is where the container answers what never reached a handler. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * An error dispatch nested inside the request's own (a forward to the error page while this filter is still on the stack):
     * {@code OncePerRequestFilter} hands it here instead of {@link #doFilterInternal}, and by default only continues the chain.
     */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        mark(request, response);
        chain.doFilter(request, response);
    }

    private void mark(HttpServletRequest request, HttpServletResponse response) {
        if (isOperatorSurface(surfaces.of(request))) {
            response.setHeader(HEADER, NO_STORE);
        }
    }

    /** The surfaces whose responses are never stored: the operator edge's, and the single port that is both. */
    static boolean isOperatorSurface(Surface surface) {
        return surface == Surface.OPERATOR || surface == Surface.SINGLE_PORT;
    }
}
