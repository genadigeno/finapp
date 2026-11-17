package com.finapp.app.api;

import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.app.session.RequiresAnyRole;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.Unauthenticated;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.web.method.HandlerMethod;

/**
 * The route register's surface column, and the two allow-lists rendered from it (`P15-TSK-011`, ADR-0120 decision 6,
 * {@code THREAT_MODEL.md} T3, T31).
 *
 * <p><strong>Which surface is derived, never declared.</strong> A route an operator permission or any role guards, or any route under
 * {@code /v1/operator} whatever it declares (T31's by-path note), is the operator surface's. Everything else is public.
 * A second list stating which is which would be a hand-kept copy of the register, which is the defect this class exists to
 * avoid.
 *
 * <p><strong>A route is a method and a template.</strong> The Ingress API holds a path and a path type, which cannot
 * express {@code /v1/payments/{id}/capture}, and which cannot tell a customer's {@code GET} from an operator's {@code POST}
 * on one path. So the edge's rule is written in the proxy's own rule language: the method and the whole path anchored. A
 * variable is {@value #VARIABLE}: identifiers, codes and enum names, a dot only between other characters (a settlement
 * source's code has them), and no {@code ;}, {@code %} or {@code /}, so no value in a variable's place is ever {@code .}
 * or {@code ..}, and none can become a traversal once something behind the edge decodes it.
 *
 * <p><strong>One router per tier, not per route.</strong> The pinned proxy builds a middleware once per router that names
 * it, so a limit shared by name across a hundred routers is a hundred buckets per source (measured: {@code edge-check.sh}'s
 * two-route burst). So every route of a tier is one clause of that tier's one router, and a tier's limits are one bucket per
 * source per controller replica. Each route keeps its own line in the generated file, so a route's change is still one
 * reviewed line.
 *
 * <p><strong>The operator surface also serves the doors that issue a session.</strong> {@link #OPERATOR_SESSION_DOORS} names the
 * controllers that issue, elevate, list and end one, and those that recover an account and name its recovery channel, so an
 * operator can do all of that from the internal network. The same doors stay public because a customer needs them; what keeps
 * staff off them there is the application (`P15-TSK-042`): each edge marks the surface a request arrived on, and a
 * role-holding identity is refused at those doors unless the operator edge marked it.
 *
 * <p><strong>One route is not the application's</strong> (`X-TSK-020`): the Operator Console's static assets, {@link #CONSOLE_PATH},
 * a router of the operator file alone, sent to the console's own Service under the console's own headers. It is the one rule this class
 * states rather than derives, and the one {@code PathPrefix} either file holds; no route the application serves may live under it.
 */
final class EdgeRoutes {

    /** One path segment's worth of a variable, and nothing a traversal could be spelled with. */
    static final String VARIABLE = "[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*";

    /**
     * One clause of a generated router's rule: nothing else may appear in a generated file. {@code PathPrefix} is the Operator Console's
     * static route's alone ({@link #CONSOLE_CLAUSES}); an API route is always a whole path.
     */
    static final Pattern CLAUSE = Pattern.compile("^\\(Method\\(`([A-Z]+)`\\) && (Path|PathRegexp|PathPrefix)\\(`([^`]+)`\\)\\)$");

    /** The application's Service, as both edge files name it. */
    static final String APP_SERVICE = "finapp-app";

    /**
     * The Operator Console's static assets (`X-TSK-020`, finapp-admin-panel `P17-TSK-004`, finapp-admin-panel ADR-C131 decision 4): served on the operator
     * host alone, by a workload of its own ({@code deploy/console}), behind headers of its own ({@code console-headers} in
     * {@code operator-edge.yaml}). <strong>Not an API route</strong>: nothing in the handler mapping serves it, so it is stated here once,
     * never derived, never on the public file, and no API route may be served under its path ({@link #derive} refuses one). {@code GET}
     * alone: the page, its deep links and its content-hashed assets; {@code /console} itself is the console's nginx's redirect to
     * {@code /console/}. The prefix ends in a slash, so {@code /consoles} or {@code /console.json} is not the console's.
     */
    static final String CONSOLE_PATH = "/console";

    static final String CONSOLE_ROUTER = "operator-console";

    static final String CONSOLE_SERVICE = "finapp-console";

    static final String CONSOLE_MIDDLEWARES = "console-headers, internal-only, body-cap";

    static final List<String> CONSOLE_CLAUSES =
            List.of("(Method(`GET`) && Path(`" + CONSOLE_PATH + "`))", "(Method(`GET`) && PathPrefix(`" + CONSOLE_PATH + "/`))");

    /** What a generated router's clauses are joined with, once YAML has folded the lines. */
    static final String CLAUSE_SEPARATOR = " || ";

    private static final Pattern LITERAL_SEGMENT = Pattern.compile("[a-z0-9-]+");

    private static final Pattern VARIABLE_SEGMENT = Pattern.compile("\\{[A-Za-z][A-Za-z0-9]*}");

    static final String HEADER = """
            # GENERATED by EdgeAllowListTest from the handler mapping and the route permission register (P15-TSK-011).
            # Never edit this file: the test fails on any difference, and writes the file it expected to app/build/edge/.
            # A changed route is accepted by copying that file here, and the change is then a reviewed line in this diff.
            """;

    enum Surface {
        PUBLIC,
        OPERATOR
    }

    /** The coarse per-source tier a public route carries, named in the proxy's own configuration. */
    enum Tier {
        DOOR("source-door", "hold-door"),
        SESSION("source-session", "hold-session"),
        MACHINE("source-machine", "hold-machine"),
        CALLBACK("source-callback", "hold-callback");

        final String rate;
        final String hold;

        Tier(String rate, String hold) {
            this.rate = rate;
            this.hold = hold;
        }
    }

    /**
     * The controllers that issue, elevate, list and end a session, and since `P15-TSK-042` those that recover an account and name
     * its recovery channel, which staff may do on the operator surface alone: the only public routes served on the operator host too.
     */
    static final Set<String> OPERATOR_SESSION_DOORS =
            Set.of("AuthenticationController", "MfaChallengeController", "MfaController", "SessionController", "CredentialController",
                    "RecoveryController", "ContactChannelController");

    /** One route, and every fact about it the two rendered files need. */
    record EdgeRoute(String method, String template, Surface surface, Tier tier, boolean sessionDoor) {

        String clause() {
            return "(Method(`" + method + "`) && " + (template.contains("{")
                    ? "PathRegexp(`" + regex(template) + "`)"
                    : "Path(`" + literal(template) + "`)") + ")";
        }

        boolean isSettlementUpload() {
            return "POST".equals(method) && RequestSizeLimitFilter.SETTLEMENT_UPLOAD_PATH.equals(template);
        }
    }

    private EdgeRoutes() {}

    /** Every served route, in a stable order, with its surface and tier read from what it declares. */
    static List<EdgeRoute> derive(Map<String, HandlerMethod> served) {
        List<EdgeRoute> routes = new ArrayList<>();
        served.forEach((route, handler) -> {
            int space = route.indexOf(' ');
            String method = route.substring(0, space);
            String template = route.substring(space + 1);
            if (isConsolePath(template)) {
                throw new IllegalArgumentException(route + " is under " + CONSOLE_PATH + ", the Operator Console's static route: the operator"
                        + " host would send it to the console under the console's headers, never to the application");
            }
            routes.add(new EdgeRoute(method, template, surface(template, handler), tier(template, handler),
                    OPERATOR_SESSION_DOORS.contains(handler.getBeanType().getSimpleName())));
        });
        routes.sort(Comparator.comparing(EdgeRoute::template).thenComparing(EdgeRoute::method));
        return routes;
    }

    static Surface surface(String template, HandlerMethod handler) {
        // A route any live role reaches (X-TSK-019) is staff's as a permission's is, wherever its path is.
        return declares(handler, RequiresPermission.class) || declares(handler, RequiresAnyRole.class) || isOperatorPath(template)
                ? Surface.OPERATOR : Surface.PUBLIC;
    }

    /** By the path, as well as by the permission: T31's routed note. */
    static boolean isOperatorPath(String template) {
        return template.equals(RoutePermissionRegisterTest.OPERATOR_PREFIX)
                || template.startsWith(RoutePermissionRegisterTest.OPERATOR_PREFIX + "/");
    }

    /** The console's path or anything under it, in any case: a path the edge would match as the console's is never an API route's. */
    static boolean isConsolePath(String template) {
        String lower = template.toLowerCase(java.util.Locale.ROOT);
        return lower.equals(CONSOLE_PATH) || lower.startsWith(CONSOLE_PATH + "/");
    }

    static Tier tier(String template, HandlerMethod handler) {
        if (declares(handler, Unauthenticated.class)) {
            return template.startsWith("/v1/providers/") ? Tier.CALLBACK : Tier.DOOR;
        }
        return declares(handler, RequiresMerchantKey.class) ? Tier.MACHINE : Tier.SESSION;
    }

    private static boolean declares(HandlerMethod handler, Class<? extends Annotation> rule) {
        return handler.getMethodAnnotation(rule) != null || handler.getBeanType().getAnnotation(rule) != null;
    }

    /**
     * The whole path, anchored: a {@code {name}} segment is one {@link #VARIABLE}, a literal is exactly itself. A segment that
     * is neither - {@code {id}-x}, or a literal a regex would read as anything but itself - is refused rather than guessed at.
     */
    static String regex(String template) {
        StringBuilder regex = new StringBuilder("^");
        for (String segment : template.substring(1).split("/", -1)) {
            if (VARIABLE_SEGMENT.matcher(segment).matches()) {
                regex.append('/').append(VARIABLE);
            } else {
                regex.append('/').append(plain(segment, template));
            }
        }
        return regex.append('$').toString();
    }

    private static String literal(String template) {
        for (String segment : template.substring(1).split("/", -1)) {
            plain(segment, template);
        }
        return template;
    }

    private static String plain(String segment, String template) {
        if (!LITERAL_SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException("segment '" + segment + "' of " + template
                    + " is neither a whole {variable} nor a plain literal: the edge's rule cannot state it");
        }
        return segment;
    }

    /** The public host's file: a customer's, a merchant's or a provider's routes, and nothing an operator owns. */
    static String renderPublic(List<EdgeRoute> routes) {
        Map<String, List<EdgeRoute>> routers = new TreeMap<>(routes.stream()
                .filter(route -> route.surface() == Surface.PUBLIC)
                .collect(Collectors.groupingBy(route -> "public-" + route.tier().name().toLowerCase(java.util.Locale.ROOT))));
        StringBuilder file = new StringBuilder(HEADER).append("http:\n  routers:\n");
        routers.forEach((name, clauses) -> {
            Tier tier = clauses.get(0).tier();
            render(file, name, clauses(clauses), APP_SERVICE, "edge-headers, " + tier.rate + ", " + tier.hold + ", body-cap");
        });
        return file.toString();
    }

    /**
     * The internal host's file: every operator route, the doors a session is issued through, and (`X-TSK-020`) the Operator Console's
     * static route, first by name, to the console's own service behind the console's own headers and never {@code edge-headers}.
     */
    static String renderOperator(List<EdgeRoute> routes) {
        Map<String, List<EdgeRoute>> routers = new TreeMap<>(routes.stream()
                .filter(route -> route.surface() == Surface.OPERATOR || route.sessionDoor())
                .collect(Collectors.groupingBy(route -> route.isSettlementUpload() ? "operator-settlement-upload" : "operator-default")));
        StringBuilder file = new StringBuilder(HEADER).append("http:\n  routers:\n");
        render(file, CONSOLE_ROUTER, CONSOLE_CLAUSES, CONSOLE_SERVICE, CONSOLE_MIDDLEWARES);
        routers.forEach((name, clauses) -> render(file, name, clauses(clauses), APP_SERVICE,
                "edge-headers, internal-only, " + (clauses.get(0).isSettlementUpload() ? "body-cap-settlement" : "body-cap")));
        return file.toString();
    }

    private static List<String> clauses(List<EdgeRoute> routes) {
        return routes.stream().map(EdgeRoute::clause).toList();
    }

    private static void render(StringBuilder file, String name, List<String> clauses, String service, String middlewares) {
        file.append("    ").append(name).append(":\n")
                .append("      entryPoints: [websecure]\n")
                .append("      rule: >-\n");
        for (int index = 0; index < clauses.size(); index++) {
            file.append("        ").append(index == 0 ? "" : "|| ").append(clauses.get(index)).append('\n');
        }
        file.append("      service: ").append(service).append('\n')
                .append("      middlewares: [").append(middlewares).append("]\n")
                .append("      tls: {}\n");
    }
}
