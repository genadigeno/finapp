package com.finapp.app.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * How a log line names the route a request took (`P11-TSK-030`; PHASE_11_PLAN.md section 11, {@code INV-AUD-02}): the
 * matched handler's path TEMPLATE - {@code /me/loans/{id}/repayments} - never the request's URI, whose path variables
 * are identifiers (a loan, an account, a party's resource) a log line must not carry. Before a handler is matched - a
 * filter refusing a request - the URI is written with every UUID-shaped segment masked {@code {id}}. The problem body a
 * caller receives still names their own URI: it is their answer, not a log.
 */
public final class LoggedRoutes {

    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private LoggedRoutes() {}

    /** The route {@code request} took, safe for a log line. */
    public static String of(HttpServletRequest request) {
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : masked(request.getRequestURI());
    }

    /** {@code uri} with every UUID-shaped segment masked. */
    static String masked(String uri) {
        return uri == null ? "an unknown route" : UUID_SEGMENT.matcher(uri).replaceAll("{id}");
    }
}
