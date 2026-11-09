package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.EdgeRoutes.EdgeRoute;
import com.finapp.app.api.EdgeRoutes.Surface;
import com.finapp.app.api.EdgeRoutes.Tier;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.RequiresSession;
import com.finapp.identity.PermissionName;
import com.finapp.kyc.DocumentBytes;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the internet can reach is derived from what the application serves, and held on the file the proxy loads
 * (`P15-TSK-011`, {@code THREAT_MODEL.md} T1, T3, T8, T31).
 *
 * <p><strong>Both ways, on the committed bytes.</strong> The two route files are parsed back and asked: is any operator route
 * listed publicly, and is every public route listed. The answer comes from the rule strings the proxy will read, not from
 * the generator's model of them.
 *
 * <p><strong>Booted with every provider's URL.</strong> Two of the five provider callbacks are conditional on a URL this
 * module's test properties do not set; the OpenAPI document omits them for the same reason. A list derived from the default
 * test context would refuse them at the edge in production. {@link #everyControllerIsInThisContext} is what stops the next
 * conditional controller being missed the same way.
 */
@Tag("slice")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "finapp.fx.provider.url=http://127.0.0.1:9",
            "finapp.corridor.provider.url=http://127.0.0.1:9"
        })
@DisplayName("the edge serves exactly what the register says, on each surface (P15-TSK-011)")
class EdgeAllowListTest {

    private static final String PUBLIC_FILE = "deploy/edge/base/public-routes.yaml";
    private static final String OPERATOR_FILE = "deploy/edge/base/operator-routes.yaml";
    private static final String PUBLIC_EDGE = "deploy/edge/base/public-edge.yaml";
    private static final String OPERATOR_EDGE = "deploy/edge/base/operator-edge.yaml";
    private static final String STATIC_CONFIG = "deploy/edge/base/traefik.yaml";
    private static final String CONTROLLERS = "deploy/edge/base/controllers.yaml";

    /** The two callback routes the published contract's own test context does not carry: see the class comment. */
    private static final Set<String> CONDITIONAL_CALLBACKS =
            Set.of("POST /v1/providers/fx/webhooks", "POST /v1/providers/payments/corridor/webhooks");

    private static final String SAMPLE = "abc-123_X";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired private ServerProperties server;

    @Value("${finapp.api.max-request-bytes:1048576}")
    private long maxRequestBytes;

    @Value("${finapp.api.settlement-upload-max-request-bytes:12582912}")
    private long settlementUploadBytes;

    private Map<String, HandlerMethod> served;
    private List<EdgeRoute> routes;

    @BeforeEach
    void readTheRoutes() {
        served = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> route : mappings.getHandlerMethods().entrySet()) {
            if (!route.getValue().getBeanType().getName().startsWith("com.finapp.")) {
                continue;
            }
            for (RequestMethod method : route.getKey().getMethodsCondition().getMethods()) {
                for (String path : route.getKey().getPatternValues()) {
                    served.put(method.name() + " " + path, route.getValue());
                }
            }
        }
        routes = EdgeRoutes.derive(served);
    }

    // ------------------------------------------------------------------------------------------ generated, never hand-kept

    @Test
    @DisplayName("the committed route files are, byte for byte, what the register and the handler mapping generate")
    void theRouteFilesAreGenerated() throws IOException {
        // Both are written before either is compared, so a changed route needs one run, not two.
        Path publicWritten = writeGenerated(PUBLIC_FILE, EdgeRoutes.renderPublic(routes));
        Path operatorWritten = writeGenerated(OPERATOR_FILE, EdgeRoutes.renderOperator(routes));
        assertGenerated(PUBLIC_FILE, publicWritten);
        assertGenerated(OPERATOR_FILE, operatorWritten);
    }

    private static Path writeGenerated(String committedPath, String generated) throws IOException {
        Path written = Path.of("build", "edge").resolve(Path.of(committedPath).getFileName());
        Files.createDirectories(written.getParent());
        Files.writeString(written, generated, StandardCharsets.UTF_8);
        return written;
    }

    private static void assertGenerated(String committedPath, Path written) throws IOException {
        String generated = Files.readString(written, StandardCharsets.UTF_8);
        assertThat(Files.readString(RepositoryPaths.locate(committedPath), StandardCharsets.UTF_8))
                .as("%s is generated, never hand-kept. A route changed: review %s and copy it over the committed file",
                        committedPath, written.toAbsolutePath())
                .isEqualTo(generated);
    }

    @Test
    @DisplayName("the operator surface is the permission register's every route, and the surface derives from nothing else")
    void theOperatorSurfaceIsTheRegister() {
        Set<String> byPermission = routes.stream()
                .filter(route -> route.surface() == Surface.OPERATOR)
                .filter(route -> served.get(route.method() + " " + route.template()).getMethodAnnotation(RequiresPermission.class) != null
                        || served.get(route.method() + " " + route.template()).getBeanType().getAnnotation(RequiresPermission.class) != null)
                .map(route -> route.method() + " " + route.template())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(byPermission).as("the guarded routes are the register's").isEqualTo(RoutePermissionRegisterTest.REGISTER.keySet());
        assertThat(routes.stream().filter(route -> route.surface() == Surface.OPERATOR)).hasSizeGreaterThanOrEqualTo(RoutePermissionRegisterTest.REGISTER.size());
    }

    // ----------------------------------------------------------------------------------------------- both ways, on the files

    @Test
    @DisplayName("no operator route is on the public list, by path or by permission")
    void noOperatorRouteIsOnThePublicList() {
        List<Rule> publicRules = rules(PUBLIC_FILE);
        assertThat(publicRules).as("the file was read").hasSizeGreaterThan(100);
        for (EdgeRoute route : routes) {
            if (route.surface() == Surface.OPERATOR) {
                assertThat(publicRules)
                        .as("%s %s answers an operator: it never reaches the public host", route.method(), route.template())
                        .noneMatch(rule -> rule.admits(route.method(), instance(route.template())));
            }
        }
        assertThat(RepositoryPaths.read(PUBLIC_FILE)).as("by path as well as by permission").doesNotContain("/v1/operator");
    }

    @Test
    @DisplayName("every public route is listed: a customer's, a merchant's and a provider's routes are all reachable")
    void everyPublicRouteIsListed() {
        List<Rule> publicRules = rules(PUBLIC_FILE);
        List<String> missing = new ArrayList<>();
        for (EdgeRoute route : routes) {
            if (route.surface() == Surface.PUBLIC && publicRules.stream().noneMatch(rule -> rule.admits(route.method(), instance(route.template())))) {
                missing.add(route.method() + " " + route.template());
            }
        }
        assertThat(missing).as("a route the application serves publicly and the edge refuses").isEmpty();
    }

    @Test
    @DisplayName("every operator route is on the operator list, and that list holds nothing else but the session doors")
    void everyOperatorRouteIsOnTheOperatorList() {
        List<Rule> operatorRules = rules(OPERATOR_FILE);
        for (EdgeRoute route : routes) {
            boolean expected = route.surface() == Surface.OPERATOR || route.sessionDoor();
            assertThat(operatorRules.stream().anyMatch(rule -> rule.admits(route.method(), instance(route.template()))))
                    .as("%s %s on the operator list", route.method(), route.template())
                    .isEqualTo(expected);
        }
        Set<String> doors = served.values().stream().map(handler -> handler.getBeanType().getSimpleName()).collect(Collectors.toSet());
        assertThat(doors).as("every session door still exists, by the name the operator surface states it").containsAll(EdgeRoutes.OPERATOR_SESSION_DOORS);
    }

    @Test
    @DisplayName("every provider callback is on the public list by its exact path and POST alone")
    void everyCallbackIsListedByName() {
        List<EdgeRoute> callbacks = routes.stream().filter(route -> route.tier() == Tier.CALLBACK).toList();
        assertThat(callbacks.stream().map(route -> route.method() + " " + route.template()).toList())
                .as("the five providers' callbacks, conditional ones included")
                .containsExactlyInAnyOrder(
                        "POST /v1/providers/kyc/callbacks",
                        "POST /v1/providers/payments/webhooks",
                        "POST /v1/providers/payments/instant/webhooks",
                        "POST /v1/providers/fx/webhooks",
                        "POST /v1/providers/payments/corridor/webhooks");
        for (EdgeRoute callback : callbacks) {
            assertThat(RepositoryPaths.read(PUBLIC_FILE)).contains("Method(`POST`) && Path(`" + callback.template() + "`)");
        }
    }

    // --------------------------------------------------------------------------------------------- where the routes come from

    @Test
    @DisplayName("no controller on the classpath is missing from this context: a conditional one would silently leave the list")
    void everyControllerIsInThisContext() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<Class<?>> scanned = new java.util.HashSet<>();
        for (var definition : scan.findCandidateComponents("com.finapp")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            // Controllers declared inside test classes are imported by their own tests and are nothing the edge serves.
            if (!type.getProtectionDomain().getCodeSource().getLocation().toString().contains("/test/")) {
                scanned.add(type);
            }
        }
        assertThat(scanned).as("the scan found the application's controllers").hasSizeGreaterThan(100);
        Set<Class<?>> inThisContext = served.values().stream().map(HandlerMethod::getBeanType).collect(Collectors.toSet());
        assertThat(scanned.stream().filter(type -> !inThisContext.contains(type)).map(Class::getName).toList())
                .as("set the property its @ConditionalOnProperty names in this class's @SpringBootTest")
                .isEmpty();
    }

    @Test
    @DisplayName("the routes served are the published contract's, and the only difference is the two conditional callbacks")
    void theRoutesAreThePublishedContract() throws IOException {
        JsonNode paths = new ObjectMapper().readTree(Files.readString(RepositoryPaths.locate("docs/api/openapi.json"))).get("paths");
        Set<String> published = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (Set.of("get", "post", "put", "patch", "delete").contains(operation.getKey())) {
                    published.add(operation.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        assertThat(published).as("the contract was read").hasSizeGreaterThan(400);
        Set<String> servedNames = new TreeSet<>(served.keySet());
        assertThat(servedNames).as("every published operation is served").containsAll(published);
        servedNames.removeAll(published);
        assertThat(servedNames).as("served and unpublished: exactly the conditional callbacks").isEqualTo(new TreeSet<>(CONDITIONAL_CALLBACKS));
    }

    // ------------------------------------------------------------------------------------------------- two matchers, one answer

    @Test
    @DisplayName("the proxy's rule and Spring's matcher agree on every route's concrete paths: a plain identifier and a dotted code in each variable")
    void theMatchersAgree() {
        PathPatternParser parser = new PathPatternParser();
        List<String> corpus = routes.stream()
                .flatMap(route -> java.util.stream.Stream.of(instance(route.template()), route.template().replaceAll("\\{[^}]+}", "sim.psp.card-clearing")))
                .distinct()
                .toList();
        assertThat(corpus).hasSizeGreaterThan(300);
        for (EdgeRoute route : routes) {
            var spring = parser.parse(route.template());
            Pattern proxy = Pattern.compile(route.template().contains("{") ? EdgeRoutes.regex(route.template())
                    : "^" + Pattern.quote(route.template()) + "$");
            for (String path : corpus) {
                assertThat(proxy.matcher(path).matches())
                        .as("%s against %s: the two matchers disagree", route.template(), path)
                        .isEqualTo(spring.matches(PathContainer.parsePath(path)));
            }
        }
    }

    @Test
    @DisplayName("each hostile spelling of an operator path - encoded slashes, //, .., trailing slash, case, ';' - is admitted by no public rule")
    void aHostileSpellingIsAdmittedByNoPublicRule() {
        List<Rule> publicRules = rules(PUBLIC_FILE);
        String path = "/v1/operator/merchants/" + SAMPLE;
        List<String> shapes = List.of(
                "//" + path.substring(1),
                "/v1//operator/merchants/" + SAMPLE,
                "/v1/./operator/merchants/" + SAMPLE,
                "/v1/me/../operator/merchants/" + SAMPLE,
                "/v1/operator%2Fmerchants/" + SAMPLE,
                "/v1%2Foperator/merchants/" + SAMPLE,
                "/v1/%6Fperator/merchants/" + SAMPLE,
                "/v1/operator%252Fmerchants/" + SAMPLE,
                "/v1/operator/merchants/" + SAMPLE + "/",
                "/V1/OPERATOR/MERCHANTS/" + SAMPLE,
                "/v1/operator;x=1/merchants/" + SAMPLE,
                "/v1/me/..;/operator/merchants/" + SAMPLE,
                "/v1/operator%00/merchants/" + SAMPLE,
                "/v1/operator\\merchants/" + SAMPLE,
                "/v1/payments/..;",
                "/v1/payments/%2e%2e");
        for (String shape : shapes) {
            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                assertThat(publicRules).as("%s %s", method, shape).noneMatch(rule -> rule.admits(method, shape));
            }
        }
    }

    @Test
    @DisplayName("a variable is one safe segment: identifiers, codes and a settlement source's dotted code pass, and nothing that spells a traversal does")
    void aVariableIsOneSafeSegment() {
        Pattern variable = Pattern.compile("^" + EdgeRoutes.VARIABLE + "$");
        for (String value : List.of("019ba5b1-7c3a-7c3a-8c3a-3a3a3a3a3a3a", "EUR-USD", "MONTHLY_SUMMARY", "sim.psp.card-clearing")) {
            assertThat(variable.matcher(value).matches()).as(value).isTrue();
        }
        for (String value : List.of("", ".", "..", "..;", "a..b", ".a", "a.", "a;b", "a%2fb", "a/b", "a\\b", "a b")) {
            assertThat(variable.matcher(value).matches()).as(value).isFalse();
        }
    }

    @Test
    @DisplayName("a template the rule language cannot state is refused: a literal that is not plain, a segment that is only partly a variable")
    void aSegmentTheRuleCannotStateIsRefused() {
        assertThat(EdgeRoutes.regex("/v1/things/{id}/state")).isEqualTo("^/v1/things/" + EdgeRoutes.VARIABLE + "/state$");
        for (String template : List.of("/v1/things/{id}-x", "/v1/things/x{id}", "/v1/things.json/{id}", "/v1/Things/{id}", "/v1/things/{id}{other}")) {
            assertThatThrownBy(() -> EdgeRoutes.regex(template)).as(template).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------------------------------------- the derivation itself

    @Test
    @DisplayName("a planted operator route declaring only a session is the operator surface's, by its path")
    void aSessionOnlyOperatorRouteIsStillOperator() throws NoSuchMethodException {
        Map<String, HandlerMethod> planted = new TreeMap<>();
        planted.put("GET /v1/operator/planted", new HandlerMethod(new Planted(), Planted.class.getMethod("sessionOnly")));
        planted.put("GET /v1/me/planted", new HandlerMethod(new Planted(), Planted.class.getMethod("sessionOnly")));
        List<EdgeRoute> derived = EdgeRoutes.derive(planted);
        assertThat(derived).filteredOn(route -> route.template().equals("/v1/operator/planted")).singleElement()
                .satisfies(route -> assertThat(route.surface()).isEqualTo(Surface.OPERATOR));
        assertThat(EdgeRoutes.renderPublic(derived)).doesNotContain("/v1/operator").contains("/v1/me/planted");
        assertThat(EdgeRoutes.renderOperator(derived)).contains("/v1/operator/planted").doesNotContain("/v1/me/planted");
    }

    @Test
    @DisplayName("one path, a customer's GET and an operator's POST: the method decides, and each list holds its own alone")
    void aPathSharedByTwoSurfacesIsSplitByMethod() throws NoSuchMethodException {
        Map<String, HandlerMethod> planted = new TreeMap<>();
        planted.put("GET /v1/widgets/{id}", new HandlerMethod(new Planted(), Planted.class.getMethod("sessionOnly")));
        planted.put("POST /v1/widgets/{id}", new HandlerMethod(new Planted(), Planted.class.getMethod("guarded")));
        List<EdgeRoute> derived = EdgeRoutes.derive(planted);
        String publicFile = EdgeRoutes.renderPublic(derived);
        String operatorFile = EdgeRoutes.renderOperator(derived);
        assertThat(publicFile).contains("Method(`GET`)").doesNotContain("Method(`POST`)");
        assertThat(operatorFile).contains("Method(`POST`)").doesNotContain("Method(`GET`)");
    }

    // ----------------------------------------------------------------------------------------------------------- the limits

    @Test
    @DisplayName("every public route carries a tier's rate and in-flight limits, each keyed on the connection and never on a header or the Host")
    @SuppressWarnings("unchecked")
    void everyPublicRouteCarriesACoarseSourceLimit() {
        Map<String, Object> middlewares = (Map<String, Object>) ((Map<String, Object>) yaml(PUBLIC_EDGE).get("http")).get("middlewares");
        for (Tier tier : Tier.values()) {
            Map<String, Object> rate = (Map<String, Object>) ((Map<String, Object>) middlewares.get(tier.rate)).get("rateLimit");
            assertThat(rate).as("%s is a limit", tier.rate).containsKeys("average", "burst", "period");
            assertKeyedOnTheConnection(tier.rate, rate);
            Map<String, Object> hold = (Map<String, Object>) ((Map<String, Object>) middlewares.get(tier.hold)).get("inFlightReq");
            assertThat(((Number) hold.get("amount")).intValue()).as("%s bounds what one source holds", tier.hold).isPositive();
            assertKeyedOnTheConnection(tier.hold, hold);
        }
        Set<String> rates = java.util.Arrays.stream(Tier.values()).map(tier -> tier.rate).collect(Collectors.toSet());
        Set<String> holds = java.util.Arrays.stream(Tier.values()).map(tier -> tier.hold).collect(Collectors.toSet());
        for (Map.Entry<String, Map<String, Object>> router : routers(PUBLIC_FILE).entrySet()) {
            List<String> named = (List<String>) router.getValue().get("middlewares");
            assertThat(named).as("%s", router.getKey()).anyMatch(rates::contains).anyMatch(holds::contains);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertKeyedOnTheConnection(String name, Map<String, Object> limit) {
        Map<String, Object> criterion = (Map<String, Object>) limit.get("sourceCriterion");
        assertThat(criterion).as("%s states its source: the in-flight default is the Host header, which the client chooses", name).containsOnlyKeys("ipStrategy");
        assertThat((Map<String, Object>) criterion.get("ipStrategy")).as("%s: the connection, grouped by /64 on IPv6", name).containsOnlyKeys("ipv6Subnet");
    }

    @Test
    @DisplayName("a tier is one router, so its limit is one bucket per source and not one per route")
    void aTierIsOneRouter() {
        assertThat(routers(PUBLIC_FILE).keySet()).containsExactlyInAnyOrder("public-door", "public-session", "public-machine", "public-callback");
        assertThat(routers(OPERATOR_FILE).keySet()).containsExactlyInAnyOrder("operator-default", "operator-settlement-upload");
        assertThat(rules(PUBLIC_FILE)).as("every clause still names one route").hasSize((int) routes.stream().filter(route -> route.surface() == Surface.PUBLIC).count());
    }

    @Test
    @DisplayName("what one source can hold at the body cap, in flight across every tier, fits the spool, and the spool fits the memory limit")
    @SuppressWarnings("unchecked")
    void oneSourceCannotFillTheSpool() {
        Map<String, Object> middlewares = (Map<String, Object>) ((Map<String, Object>) yaml(PUBLIC_EDGE).get("http")).get("middlewares");
        long held = 0;
        for (Tier tier : Tier.values()) {
            held += ((Number) ((Map<String, Object>) ((Map<String, Object>) middlewares.get(tier.hold)).get("inFlightReq")).get("amount")).longValue();
        }
        long heldBytes = held * cap(PUBLIC_EDGE, "body-cap");
        for (Map<String, Object> document : deployments()) {
            Map<String, Object> pod = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) document.get("spec")).get("template")).get("spec");
            Map<String, Object> tmp = ((List<Map<String, Object>>) pod.get("volumes")).stream().filter(volume -> "tmp".equals(volume.get("name"))).findFirst().orElseThrow();
            Map<String, Object> emptyDir = (Map<String, Object>) tmp.get("emptyDir");
            assertThat(emptyDir).as("a disk-backed emptyDir gets the pod evicted at its sizeLimit; tmpfs answers ENOSPC").containsEntry("medium", "Memory");
            long spool = org.springframework.util.unit.DataSize.parse(((String) emptyDir.get("sizeLimit")).replace("Mi", "MB")).toBytes();
            assertThat(heldBytes).as("%s in flight from one source at the cap", held).isLessThan(spool);
            Map<String, Object> container = ((List<Map<String, Object>>) pod.get("containers")).get(0);
            String limit = (String) ((Map<String, Object>) ((Map<String, Object>) container.get("resources")).get("limits")).get("memory");
            assertThat(org.springframework.util.unit.DataSize.parse(limit.replace("Mi", "MB")).toBytes())
                    .as("the spool counts against the container's memory limit").isGreaterThan(spool);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deployments() {
        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(RepositoryPaths.read(CONTROLLERS))) {
            if ("Deployment".equals(((Map<String, Object>) document).get("kind"))) {
                deployments.add((Map<String, Object>) document);
            }
        }
        assertThat(deployments).as("both controllers were read").hasSize(2);
        return deployments;
    }

    @Test
    @DisplayName("no edge limit is tighter than the application's own, so a body at the application's limit is never cut")
    @SuppressWarnings("unchecked")
    void noEdgeCapCutsWhatTheApplicationAccepts() {
        long publicCap = cap(PUBLIC_EDGE, "body-cap");
        long operatorCap = cap(OPERATOR_EDGE, "body-cap");
        long settlementCap = cap(OPERATOR_EDGE, "body-cap-settlement");
        assertThat(publicCap)
                .as("the public cap passes the global limit, and every provider's own limit")
                .isGreaterThanOrEqualTo(maxRequestBytes)
                .isGreaterThanOrEqualTo(ProviderEvidenceStore.MAX_PAYLOAD_BYTES)
                .isGreaterThanOrEqualTo(DocumentBytes.MAX_BYTES);
        assertThat(operatorCap).as("the operator surface's default").isGreaterThanOrEqualTo(maxRequestBytes);
        assertThat(settlementCap).as("the settlement upload's envelope").isGreaterThanOrEqualTo(settlementUploadBytes);
        assertThat(routers(OPERATOR_FILE).entrySet())
                .as("the larger cap on the upload alone")
                .filteredOn(entry -> ((List<String>) entry.getValue().get("middlewares")).contains("body-cap-settlement"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getKey()).isEqualTo("operator-settlement-upload"));
        assertThat(rules(OPERATOR_FILE)).as("the settlement upload has a router of its own").anyMatch(rule -> rule.admits("POST", RequestSizeLimitFilter.SETTLEMENT_UPLOAD_PATH));
        assertThat(responseCap(PUBLIC_EDGE, "body-cap")).as("a response cap is stated: unset, a large response spools without bound").isPositive();
        assertThat(responseCap(OPERATOR_EDGE, "body-cap")).as("above a settlement file's content read").isGreaterThanOrEqualTo(settlementUploadBytes);
        assertThat(((Map<String, Map<String, Object>>) yaml(STATIC_CONFIG).get("entryPoints")).get("websecure"))
                .extracting(entry -> ((Map<String, Object>) entry.get("http")).get("maxHeaderBytes"))
                .satisfies(value -> assertThat(((Number) value).longValue()).as("Tomcat's own header limit")
                        .isGreaterThanOrEqualTo(server.getMaxHttpRequestHeaderSize().toBytes()));
    }

    @SuppressWarnings("unchecked")
    private static long responseCap(String file, String name) {
        Map<String, Object> http = (Map<String, Object>) yaml(file).get("http");
        Map<String, Object> middleware = (Map<String, Object>) ((Map<String, Object>) http.get("middlewares")).get(name);
        return ((Number) ((Map<String, Object>) middleware.get("buffering")).get("maxResponseBodyBytes")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static long cap(String file, String name) {
        Map<String, Object> http = (Map<String, Object>) yaml(file).get("http");
        Map<String, Object> middleware = (Map<String, Object>) ((Map<String, Object>) http.get("middlewares")).get(name);
        return ((Number) ((Map<String, Object>) middleware.get("buffering")).get("maxRequestBodyBytes")).longValue();
    }

    // --------------------------------------------------------------------------------------------------------- reading

    /** A concrete path of a template: each variable a value the variable's class accepts. */
    private static String instance(String template) {
        return template.replaceAll("\\{[^}]+}", SAMPLE);
    }

    private static List<Rule> rules(String file) {
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> router : routers(file).entrySet()) {
            for (String clause : ((String) router.getValue().get("rule")).split(Pattern.quote(EdgeRoutes.CLAUSE_SEPARATOR))) {
                Matcher parsed = EdgeRoutes.CLAUSE.matcher(clause);
                assertThat(parsed.matches()).as("%s: a clause outside the generated grammar is refused, not guessed at: %s", router.getKey(), clause).isTrue();
                rules.add(new Rule(parsed.group(1), parsed.group(2), parsed.group(3)));
            }
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> routers(String file) {
        return (Map<String, Map<String, Object>>) ((Map<String, Object>) yaml(file).get("http")).get("routers");
    }

    private static Map<String, Object> yaml(String file) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(RepositoryPaths.read(file));
    }

    /** One generated router's matcher, evaluated as the proxy evaluates it: exact path, or a regex over the whole path. */
    private record Rule(String method, String matcher, String argument) {

        boolean admits(String askedMethod, String path) {
            if (!method.equals(askedMethod)) {
                return false;
            }
            return "Path".equals(matcher) ? argument.equals(path) : Pattern.compile(argument).matcher(path).matches();
        }
    }

    /** A planted controller: a session-only handler, and a guarded one. */
    static final class Planted {
        @RequiresSession
        public void sessionOnly() {}

        @RequiresPermission(PermissionName.LEDGER_ADJUST)
        public void guarded() {}
    }
}
