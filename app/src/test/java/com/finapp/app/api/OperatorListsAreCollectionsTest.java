package com.finapp.app.api;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.testing.RepositoryPaths;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * No operator list answers a capped list (`P17-TSK-042`, gaps G-03 and G-121; finapp-admin-panel ADR-C133 decisions 1 and 2;
 * {@code API_CONVENTIONS.md} section 7).
 *
 * <p>A list that stops at a cap tells an operator "the newest 100" and lets them read "all of them": ADR-C133's 101st break,
 * missed. Section 7's collection is the cure - {@code { items, nextCursor }} in a declared order, the limit refused above its
 * maximum, the cursor opaque - and this guard holds the operator surface to it, over the routes the application actually serves
 * (the MVC mapping, never a list of them), on four rules:
 *
 * <ol>
 *   <li><strong>no bare array</strong>: a GET on the operator surface does not answer a JSON array, which can neither say it
 *       stopped nor how to go on;
 *   <li><strong>no {@code truncated}</strong>: nor an answer whose top level carries a {@code truncated} flag, which says it
 *       stopped and gives no way past;
 *   <li><strong>no {@code after}</strong>: nor take an {@code after} parameter, the transparent cursor section 7 replaced;
 *   <li><strong>a page is whole</strong>: an answer in the page's shape - a record of {@code items} and {@code nextCursor} -
 *       takes {@code cursor} and {@code limit}, leaves {@code nextCursor} out at the end rather than writing a null, and is
 *       listed in section 7's table, as every route that table lists answers one.
 * </ol>
 *
 * <p>Each of the first three has a register of the routes that answer that way today, each with its reason: bounded by what it
 * is (a closed vocabulary, one subject's children, a report of one period), or a list section 7 has not reached yet, named so
 * it is owed rather than forgotten. A new route answering that way fails the build until it is a collection or is registered
 * with a reason; a registered route that no longer answers that way fails it too, so the register never outlives its reason.
 * Each rule is shown to fail on planted handlers.
 *
 * <p>The operator surface is {@code EdgeRoutes}'s: a route under {@code /v1/operator}, or one declaring a permission or any
 * role wherever its path is.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("no operator list answers a capped list: every one a section 7 collection or registered with its reason (P17-TSK-042)")
class OperatorListsAreCollectionsTest {

    /** Routes on the operator surface answering a bare JSON array, each with why it may. */
    static final Map<String, String> BARE_ARRAYS = new TreeMap<>(Map.ofEntries(
            entry("GET /v1/operator/roles",
                    "a closed vocabulary: every RoleName, what it grants - complete as served, never capped"),
            entry("GET /v1/operator/merchants/{id}/api-keys",
                    "one merchant's keys, served whole - a subject's children, never capped"),
            entry("GET /v1/operator/fee-schedules",
                    "the platform's own price list, every schedule served whole - never capped (ADR-C133's 'unbounded', complete)"),
            entry("GET /v1/operator/accounting/periods/{periodId}/prior-period-adjustments",
                    "one month's corrections, served whole - a subject's children, never capped")));

    /** Routes on the operator surface answering a top-level {@code truncated}, each with why it may. */
    static final Map<String, String> TRUNCATED = new TreeMap<>(Map.ofEntries(
            entry("GET /v1/operator/reconciliation/breaks/{id}/trace",
                    "one break's stored chain, bounded per break - a detail's children, not a list"),
            entry("GET /v1/operator/reconciliation/settlement-status",
                    "one operation's settlement status and its trail - a detail, bounded per operation"),
            entry("GET /v1/operator/lending/capital",
                    "the lending book's capital at one moment - a report (ADR-C133 decision 5), not a collection"),
            entry("GET /v1/operator/reports/chargeback-ratio",
                    "a report of one period - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/credit/outcomes",
                    "a report of one month - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/credit/reasons",
                    "a report of one month - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/credit/sources",
                    "a report of one month - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/cross-border/corridors",
                    "a report of one period - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/fx/position",
                    "the FX position at one moment - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/fx/revenue",
                    "a report of one period - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/reconciliation/positions",
                    "a report as of one date - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/reconciliation/provider-costs",
                    "a report of one period - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/reconciliation/summary",
                    "a report of one date - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/reconciliation/suspense",
                    "a report as of one date - ADR-C133 decision 5's report, not a collection"),
            entry("GET /v1/operator/reports/reconciliation/unmatched",
                    "a report as of one date - ADR-C133 decision 5's report, not a collection")));

    /** Routes on the operator surface taking {@code after}, each with why it may. */
    static final Map<String, String> AFTER_STYLE = new TreeMap<>();

    private static final String CONVENTIONS = "docs/architecture/API_CONVENTIONS.md";

    /** A row of section 7's table: its first cell names one GET route. */
    private static final Pattern TABLE_ROUTE = Pattern.compile("^\\| `GET (/v1/[^`]+)`", Pattern.MULTILINE);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    // ------------------------------------------------------------------ the rules, over what is served

    @Test
    @DisplayName("no operator GET answers a bare array unless registered with its reason, and no registered route has stopped")
    void noBareArrays() {
        Map<String, HandlerMethod> served = operatorGets();
        assertThat(served).as("the census: the operator surface was read").hasSizeGreaterThan(100);
        assertThat(bareArrays(served)).as("answer a section 7 collection (OperatorCollection), or register the route in"
                + " BARE_ARRAYS with why it is bounded").containsExactlyInAnyOrderElementsOf(BARE_ARRAYS.keySet());
    }

    @Test
    @DisplayName("no operator GET answers a top-level truncated unless registered with its reason")
    void noTruncatedLists() {
        assertThat(truncated(operatorGets())).as("a capped list with a flag still cannot be paged past: answer a section 7"
                + " collection, or register the route in TRUNCATED with why it is bounded").containsExactlyInAnyOrderElementsOf(
                TRUNCATED.keySet());
    }

    @Test
    @DisplayName("no operator GET takes after unless registered with its reason")
    void noAfterStyleLists() {
        assertThat(afterStyle(operatorGets())).as("section 7's cursor is opaque and named cursor: take cursor and limit, or"
                + " register the route in AFTER_STYLE").containsExactlyInAnyOrderElementsOf(AFTER_STYLE.keySet());
    }

    @Test
    @DisplayName("every page takes cursor and limit and leaves nextCursor out at the end; section 7's table lists exactly the"
            + " routes answering one")
    void everyPageIsWholeAndListed() {
        Map<String, HandlerMethod> served = operatorGets();
        assertThat(unwholePages(served)).as("a page takes cursor and limit, and its record is @JsonInclude(NON_NULL)").isEmpty();
        Set<String> pages = new TreeSet<>(pages(served));
        assertThat(pages).as("the retrofitted lists answer pages (P17-TSK-042)").contains(
                "GET /v1/operator/reconciliation/breaks",
                "GET /v1/operator/events/abandoned",
                "GET /v1/operator/events/parked",
                "GET /v1/operator/events/discards",
                "GET /v1/operator/settlement/files",
                "GET /v1/operator/settlement/refused-deliveries",
                "GET /v1/operator/access-review/factorless-holdings",
                "GET /v1/operator/credit/review-cases",
                "GET /v1/operator/risk/alerts",
                "GET /v1/operator/risk/cases",
                "GET /v1/operator/accounting/periods");
        assertThat(pages).as("the second batch answers pages (P17-TSK-122)").contains(
                "GET /v1/operator/cross-border/corridor-policies",
                "GET /v1/operator/access-review/sod-conflicts",
                "GET /v1/operator/reconciliation/runs",
                "GET /v1/operator/reconciliation/expectations",
                "GET /v1/operator/reconciliation/rule-sets",
                "GET /v1/operator/regulatory/obligations",
                "GET /v1/operator/accounting/breaks",
                "GET /v1/operator/bnpl-disputes",
                "GET /v1/operator/regulatory/submissions");
        assertThat(sectionSevenRoutes()).as("section 7's table and the pages served are one list")
                .containsExactlyInAnyOrderElementsOf(pages);
    }

    // ------------------------------------------------------------------ the rules can fail

    @Test
    @DisplayName("each rule can fail: planted handlers answering an array, a truncated list, an after cursor and a page missing"
            + " its cursor are each caught, and a whole page is not")
    void eachRuleCanFail() throws NoSuchMethodException {
        Map<String, HandlerMethod> planted = new TreeMap<>();
        planted.put("GET /v1/operator/planted/array", handler("array"));
        planted.put("GET /v1/operator/planted/wrapped-array", handler("wrappedArray"));
        planted.put("GET /v1/operator/planted/truncated", handler("truncated"));
        planted.put("GET /v1/operator/planted/after", handler("after"));
        planted.put("GET /v1/operator/planted/no-cursor", handler("noCursor"));
        planted.put("GET /v1/operator/planted/null-cursor", handler("nullCursor"));
        planted.put("GET /v1/operator/planted/whole", handler("whole"));
        planted.put("GET /v1/me/planted/array", handler("array"));
        planted.put("GET /v1/elsewhere/guarded-array", handler("guardedArray"));

        assertThat(bareArrays(planted)).containsExactlyInAnyOrder("GET /v1/operator/planted/array",
                "GET /v1/operator/planted/wrapped-array", "GET /v1/elsewhere/guarded-array");
        assertThat(truncated(planted)).containsExactly("GET /v1/operator/planted/truncated");
        assertThat(afterStyle(planted)).containsExactly("GET /v1/operator/planted/after");
        assertThat(unwholePages(planted)).containsExactlyInAnyOrder("GET /v1/operator/planted/no-cursor",
                "GET /v1/operator/planted/null-cursor");
        assertThat(pages(planted)).containsExactlyInAnyOrder("GET /v1/operator/planted/after",
                "GET /v1/operator/planted/no-cursor", "GET /v1/operator/planted/null-cursor", "GET /v1/operator/planted/whole");
    }

    // ------------------------------------------------------------------ the predicates

    /** The routes answering a JSON array: a collection or an array, bare or in a {@code ResponseEntity}. */
    static List<String> bareArrays(Map<String, HandlerMethod> routes) {
        return matching(routes, handler -> {
            Class<?> answer = answered(handler.getMethod());
            return answer.isArray() || Collection.class.isAssignableFrom(answer);
        });
    }

    /** The routes whose answer is a record carrying a {@code truncated} component. */
    static List<String> truncated(Map<String, HandlerMethod> routes) {
        return matching(routes, handler -> components(answered(handler.getMethod())).contains("truncated"));
    }

    /** The routes taking a request parameter named {@code after}. */
    static List<String> afterStyle(Map<String, HandlerMethod> routes) {
        return matching(routes, handler -> parameters(handler.getMethod()).contains("after"));
    }

    /** The routes answering section 7's page: a record of exactly {@code items} and {@code nextCursor}. */
    static List<String> pages(Map<String, HandlerMethod> routes) {
        return matching(routes, handler -> isPage(answered(handler.getMethod())));
    }

    /** The pages missing a part of the contract: {@code cursor}, {@code limit}, or {@code nextCursor} left out at the end. */
    static List<String> unwholePages(Map<String, HandlerMethod> routes) {
        return matching(routes, handler -> {
            Class<?> answer = answered(handler.getMethod());
            if (!isPage(answer)) {
                return false;
            }
            JsonInclude include = answer.getAnnotation(JsonInclude.class);
            return !parameters(handler.getMethod()).containsAll(Set.of("cursor", "limit"))
                    || include == null || include.value() != JsonInclude.Include.NON_NULL;
        });
    }

    private static boolean isPage(Class<?> answer) {
        return answer.isRecord() && components(answer).equals(List.of("items", "nextCursor"))
                && Collection.class.isAssignableFrom(answer.getRecordComponents()[0].getType())
                && answer.getRecordComponents()[1].getType() == String.class;
    }

    private static List<String> matching(Map<String, HandlerMethod> routes, java.util.function.Predicate<HandlerMethod> rule) {
        return routes.entrySet().stream()
                .filter(route -> route.getKey().startsWith("GET "))
                .filter(route -> EdgeRoutes.surface(route.getKey().substring(4), route.getValue()) == EdgeRoutes.Surface.OPERATOR)
                .filter(route -> rule.test(route.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** What the handler's body is: its return type, or the body type of a {@code ResponseEntity}. */
    private static Class<?> answered(Method method) {
        if (ResponseEntity.class.isAssignableFrom(method.getReturnType())) {
            Class<?> body = ResolvableType.forMethodReturnType(method).getGeneric(0).resolve();
            return body == null ? Object.class : body;
        }
        return method.getReturnType();
    }

    private static List<String> components(Class<?> type) {
        return type.isRecord() ? Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList() : List.of();
    }

    private static Set<String> parameters(Method method) {
        Set<String> names = new TreeSet<>();
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            RequestParam param = parameter.getAnnotation(RequestParam.class);
            if (param != null) {
                names.add(!param.name().isEmpty() ? param.name() : !param.value().isEmpty() ? param.value() : parameter.getName());
            }
        }
        return names;
    }

    /** The routes section 7's table lists, as {@code GET path}. */
    private static Set<String> sectionSevenRoutes() {
        String document = RepositoryPaths.read(CONVENTIONS);
        int start = document.indexOf("## 7. Pagination");
        int end = document.indexOf("\n## 8.", start);
        assertThat(start).as("section 7 is where the guard reads it").isNotNegative();
        Set<String> routes = new TreeSet<>();
        Matcher row = TABLE_ROUTE.matcher(document.substring(start, end));
        while (row.find()) {
            routes.add("GET " + row.group(1));
        }
        assertThat(routes).as("the census: section 7's table was read").hasSizeGreaterThan(10);
        return routes;
    }

    /** Every GET the MVC mapping serves from {@code com.finapp.}, as {@code GET path}. */
    private Map<String, HandlerMethod> operatorGets() {
        Map<String, HandlerMethod> served = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> route : mappings.getHandlerMethods().entrySet()) {
            if (!route.getValue().getBeanType().getName().startsWith("com.finapp.")
                    || !route.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
                continue;
            }
            for (String path : route.getKey().getPatternValues()) {
                if (EdgeRoutes.surface(path, route.getValue()) == EdgeRoutes.Surface.OPERATOR) {
                    served.put("GET " + path, route.getValue());
                }
            }
        }
        return served;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        for (Method method : Planted.class.getMethods()) {
            if (method.getName().equals(name)) {
                return new HandlerMethod(new Planted(), method);
            }
        }
        throw new NoSuchMethodException(name);
    }

    // ------------------------------------------------------------------ planted handlers

    /** A planted controller: each rule's offender, and one whole page. */
    static final class Planted {

        record Row(String id) {}

        record Capped(List<Row> rows, boolean truncated) {}

        record PageWithoutNonNull(List<Row> items, String nextCursor) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Page(List<Row> items, String nextCursor) {}

        public List<Row> array() {
            return List.of();
        }

        public ResponseEntity<Row[]> wrappedArray() {
            return ResponseEntity.ok(new Row[0]);
        }

        @RequiresPermission(PermissionName.LEDGER_ADJUST)
        public List<Row> guardedArray() {
            return List.of();
        }

        public Capped truncated() {
            return new Capped(List.of(), false);
        }

        public Page after(@RequestParam(name = "after", required = false) String after,
                @RequestParam(name = "cursor", required = false) String cursor,
                @RequestParam(name = "limit", required = false) String limit) {
            return new Page(List.of(), null);
        }

        public Page noCursor(@RequestParam(name = "limit", required = false) String limit) {
            return new Page(List.of(), null);
        }

        public PageWithoutNonNull nullCursor(@RequestParam(name = "cursor", required = false) String cursor,
                @RequestParam(name = "limit", required = false) String limit) {
            return new PageWithoutNonNull(List.of(), null);
        }

        public Page whole(@RequestParam(name = "cursor", required = false) String cursor,
                @RequestParam(name = "limit", required = false) String limit) {
            return new Page(List.of(), null);
        }
    }
}
