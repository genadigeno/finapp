package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The edge's own configuration holds what ADR-0120 decision 6 says, on the committed files (`P15-TSK-011`, {@code THREAT_MODEL.md}
 * T3, T20, T21, T40).
 *
 * <p>{@code infra/scripts/edge-check.sh} runs these bytes through the pinned proxy and the policies, which needs Docker. This class holds on
 * every build what would be cheap to lose: the unsafe defaults stated, the controllers holding no credential, the plants complete.
 */
@Tag("architecture")
@DisplayName("the edge's configuration holds what was decided, and its check cannot pass empty (P15-TSK-011)")
class EdgeManifestsTest {

    private static final String STATIC = "deploy/edge/base/traefik.yaml";
    private static final String OPERATOR_STATIC = "deploy/edge/base/traefik-operator.yaml";
    private static final String CONTROLLERS = "deploy/edge/base/controllers.yaml";
    private static final String QUOTA = "deploy/edge/base/resourcequota.yaml";
    private static final String PUBLIC_EDGE = "deploy/edge/base/public-edge.yaml";
    private static final String OPERATOR_EDGE = "deploy/edge/base/operator-edge.yaml";
    private static final String POLICY = "infra/scripts/manifest-policy/edge.rego";
    private static final String SCRIPT = "infra/scripts/edge-check.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String PIN_CHECK = "infra/scripts/check-pinned-images.sh";
    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final List<String> OVERLAYS = List.of("kind", "staging", "production");
    private static final Set<String> ENCODED =
            Set.of("allowEncodedSlash", "allowEncodedBackSlash", "allowEncodedNullCharacter", "allowEncodedSemicolon",
                    "allowEncodedPercent", "allowEncodedQuestionMark", "allowEncodedHash");

    private static Path root;
    private static Map<String, Object> websecure;
    private static Map<String, Object> staticConfig;
    private static List<Map<String, Object>> controllers;

    @BeforeAll
    static void read() {
        root = EvidenceCitations.repositoryRoot();
        staticConfig = yaml(read(STATIC));
        websecure = map(map(staticConfig, "entryPoints"), "websecure");
        controllers = yamlAll(read(CONTROLLERS));
    }

    @Test
    @DisplayName("the edge believes no forwarded header: not insecure, no trusted address, aliases deleted, and the seven encoded characters refused")
    void theEdgeDropsEveryForwardedHeader() {
        Map<String, Object> forwarded = map(websecure, "forwardedHeaders");
        assertThat(forwarded).containsEntry("insecure", false).doesNotContainKey("trustedIPs");
        Map<String, Object> http = map(websecure, "http");
        assertThat(http).containsEntry("aliasHeadersStrategy", "delete").containsEntry("sanitizePath", true);
        Map<String, Object> encoded = map(http, "encodedCharacters");
        assertThat(encoded.keySet()).as("all seven, each defaulting to allowed").containsExactlyInAnyOrderElementsOf(ENCODED);
        assertThat(encoded.values()).as("every one refused").containsOnly(false);
        for (String file : List.of(PUBLIC_EDGE, OPERATOR_EDGE)) {
            Map<String, Object> custom = map(map(map(map(yaml(read(file)), "http"), "middlewares"), "edge-headers"), "headers");
            assertThat(map(custom, "customRequestHeaders")).containsEntry("Forwarded", "").containsEntry("X-Forwarded-Prefix", "").containsEntry("Upgrade", "");
        }
        assertThat(read("infra/scripts/manifest-policy/edge.rego")).contains("[edge-trace-context]");
        assertThat(read("infra/scripts/edge-check.sh")).contains("edge-trace-context").contains("traceparent");
    }

    @Test
    @DisplayName("neither edge forwards a caller's traceparent, tracestate or baggage, staff's included, and neither sets or removes the"
            + " correlation id staff's tooling finds a request by (P15-TSK-023's review, P15-TSK-043)")
    void neitherEdgeForwardsACallersTrace() {
        // The platform joins an inbound W3C trace context and samples by its flag, so a caller's would switch tracing off for its own
        // requests or choose the trace ids the collectors route by. Staff are callers too (THREAT_MODEL.md section 6 item 37 (f)).
        for (String file : List.of(PUBLIC_EDGE, OPERATOR_EDGE)) {
            Map<String, Object> headers = map(map(map(map(yaml(read(file)), "http"), "middlewares"), "edge-headers"), "headers");
            assertThat(map(headers, "customRequestHeaders")).as("%s: each set empty, so the proxy removes whatever a client sent", file)
                    .containsEntry("traceparent", "").containsEntry("tracestate", "").containsEntry("baggage", "");
            // Read from the parsed file, every key at every depth: a comment may name the header, a setting may not.
            assertThat(keysOf(yaml(read(file)))).as("%s: the answer's X-Correlation-Id and a tool's own reach staff untouched", file)
                    .isNotEmpty().noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("correlation"));
        }
        assertThat(read(POLICY).replace("\r\n", "\n")).as("the rule asks every edge of the set, never the public one alone")
                .contains("some prefix in route_config_prefixes\n\tnot trace_context_is_stripped(prefix)")
                .doesNotContain("trace_context_is_stripped if");
        String script = read(SCRIPT);
        assertThat(script).as("a plant on the operator edge, a name removed and a name left null")
                .contains("edge_refused edge-trace-context-operator-forwarded edge-trace-context")
                .contains("edge_refused edge-trace-context-operator-null edge-trace-context")
                .as("the pinned proxy: the operator host's backend sees none of the three")
                .contains("trace_context_seen \"the operator host\" 0 ")
                .doesNotContain("which keeps them");
    }

    @Test
    @DisplayName("each edge states its own surface in the header the application reads, in the word the application reads, on every router (P15-TSK-042)")
    void eachEdgeStatesItsSurface() {
        Map<String, com.finapp.app.surface.Surface> edges = Map.of(
                PUBLIC_EDGE, com.finapp.app.surface.Surface.PUBLIC,
                OPERATOR_EDGE, com.finapp.app.surface.Surface.OPERATOR);
        edges.forEach((file, surface) -> {
            Map<String, Object> custom = map(map(map(map(map(yaml(read(file)), "http"), "middlewares"), "edge-headers"), "headers"),
                    "customRequestHeaders");
            assertThat(custom).as("%s: set, so the proxy replaces every value a client sent under the name", file)
                    .containsEntry(com.finapp.app.surface.RequestSurfaces.HEADER, surface.tag());
            assertThat(custom.keySet().stream().filter(name -> name.equalsIgnoreCase(com.finapp.app.surface.RequestSurfaces.HEADER)))
                    .as("%s: one statement of it, so no second spelling can say otherwise", file).hasSize(1);
        });
        assertThat(com.finapp.app.surface.Surface.PUBLIC.admitsRoleHolders()).as("the public edge's word refuses staff").isFalse();
        assertThat(com.finapp.app.surface.Surface.OPERATOR.admitsRoleHolders()).as("the operator edge's word admits them").isTrue();
        for (String routes : List.of("deploy/edge/base/public-routes.yaml", "deploy/edge/base/operator-routes.yaml")) {
            map(map(yaml(read(routes)), "http"), "routers").forEach((name, router) ->
                    assertThat(stringList(((Map<?, ?>) router).get("middlewares"))).as("%s: %s marks its surface", routes, name)
                            .contains("edge-headers"));
        }
    }

    @Test
    @DisplayName("TLS ends at the edge: one TLS listener, no plain port, TLS 1.2 at least, no default certificate, and HSTS and the security headers on every route")
    void tlsEndsAtTheEdgeWithHsts() {
        assertThat(map(staticConfig, "entryPoints").keySet()).containsExactlyInAnyOrder("websecure", "management");
        assertThat(map(websecure, "http")).containsKey("tls");
        assertThat(websecure).containsEntry("address", ":8443");
        for (String file : List.of(PUBLIC_EDGE, OPERATOR_EDGE)) {
            Map<String, Object> document = yaml(read(file));
            Map<String, Object> options = map(map(map(document, "tls"), "options"), "default");
            assertThat(options).containsEntry("minVersion", "VersionTLS12").containsEntry("sniStrict", true);
            Map<String, Object> headers = map(map(map(map(document, "http"), "middlewares"), "edge-headers"), "headers");
            assertThat(((Number) headers.get("stsSeconds")).longValue()).isGreaterThanOrEqualTo(31_536_000L);
            assertThat(headers).containsEntry("contentTypeNosniff", true).containsEntry("frameDeny", true)
                    .containsEntry("referrerPolicy", "no-referrer").containsKey("contentSecurityPolicy");
        }
        for (String routes : List.of("deploy/edge/base/public-routes.yaml", "deploy/edge/base/operator-routes.yaml")) {
            Map<String, Object> routers = map(map(yaml(read(routes)), "http"), "routers");
            assertThat(routers).isNotEmpty();
            routers.forEach((name, router) -> {
                assertThat(cast(router)).as("%s serves TLS only", name).containsKey("tls");
                assertThat(stringList(((Map<?, ?>) router).get("middlewares"))).as("%s", name).contains("edge-headers");
            });
        }
    }

    @Test
    @DisplayName("the two static files differ in their read and write timeouts alone, and a write always outlasts a read and the replica's answer")
    void aHungReplicaCannotHoldARequest() {
        assertThat(comparable(read(OPERATOR_STATIC))).as("everything but the two timeouts and the comments").isEqualTo(comparable(read(STATIC)));
        Duration drain = DurationStyle.detectAndParse(string(map(map(application(), "spring"), "lifecycle"), "timeout-per-shutdown-phase"));
        Map<String, String> edges = Map.of(STATIC, PUBLIC_EDGE, OPERATOR_STATIC, OPERATOR_EDGE);
        edges.forEach((staticFile, edgeFile) -> {
            Map<String, Object> responding = map(map(map(map(yaml(read(staticFile)), "entryPoints"), "websecure"), "transport"), "respondingTimeouts");
            Duration readTimeout = DurationStyle.detectAndParse(string(responding, "readTimeout"));
            Duration writeTimeout = DurationStyle.detectAndParse(string(responding, "writeTimeout"));
            Map<String, Object> forwarding = map(map(map(map(yaml(read(edgeFile)), "http"), "serversTransports"), "no-reuse"), "forwardingTimeouts");
            Duration answer = DurationStyle.detectAndParse(string(forwarding, "responseHeaderTimeout"));
            assertThat(answer).as("%s: above the longest a request is expected to run, the drain", edgeFile).isGreaterThan(drain);
            assertThat(writeTimeout).as("%s: Go starts this at the request's headers", staticFile).isGreaterThan(readTimeout.plus(answer));
        });
        assertThat(DurationStyle.detectAndParse(string(map(map(map(map(yaml(read(STATIC)), "entryPoints"), "websecure"), "transport"), "respondingTimeouts"), "readTimeout")))
                .as("what one source can hold a body open for, on the public host").isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(map(application(), "server")).as("no HTTP/2 in the clear behind the edge").doesNotContainKey("http2");
    }

    private static String comparable(String config) {
        return config.lines().filter(line -> !line.trim().startsWith("#"))
                .filter(line -> !line.trim().startsWith("readTimeout:") && !line.trim().startsWith("writeTimeout:"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Map<String, Object> application() {
        return yaml(read("app/src/main/resources/application.yaml"));
    }

    private static String string(Map<String, Object> parent, String key) {
        assertThat(parent).as("key %s", key).containsKey(key);
        return String.valueOf(parent.get(key));
    }

    @Test
    @DisplayName("no production class sets HSTS or a security header: the edge's is the only one, and is never answered twice")
    void noProductionCodeSetsAnEdgeHeader() throws IOException {
        List<String> names = List.of("Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Content-Security-Policy");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(root)) {
            sources.filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/") && path.toString().endsWith(".java"))
                    .forEach(path -> names.stream().filter(read(root.relativize(path).toString())::contains)
                            .forEach(name -> offenders.add(path + ": " + name)));
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("no controller holds a Kubernetes credential: the file provider alone, tokenless, and no role, binding or routing object rendered")
    void theControllersHoldNoCredential() {
        assertThat(map(staticConfig, "providers").keySet()).containsExactly("file");
        assertThat(map(yaml(read(OPERATOR_STATIC)), "providers").keySet()).containsExactly("file");
        assertThat(staticConfig).as("no dashboard or API on any listener").doesNotContainKey("api");
        List<String> kinds = new ArrayList<>();
        for (Map<String, Object> document : controllers) {
            kinds.add((String) document.get("kind"));
            if ("ServiceAccount".equals(document.get("kind"))) {
                assertThat(document).containsEntry("automountServiceAccountToken", false);
            }
            if ("Deployment".equals(document.get("kind"))) {
                assertThat(map(map(map(document, "spec"), "template"), "spec")).containsEntry("automountServiceAccountToken", false);
            }
        }
        assertThat(kinds).containsOnly("ServiceAccount", "Deployment", "PodDisruptionBudget", "Service").doesNotContain("Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding");
    }

    @Test
    @DisplayName("the operator edge is internal in a real overlay, and limited to the private ranges at the proxy as well")
    void theOperatorEdgeIsInternal() {
        for (String overlay : List.of("staging", "production")) {
            String file = read("deploy/edge/overlays/" + overlay + "/kustomization.yaml");
            int operator = file.indexOf("name: finapp-edge-operator");
            assertThat(file.substring(operator)).contains("aws-load-balancer-scheme: internal");
            assertThat(file.substring(0, operator)).contains("aws-load-balancer-scheme: internet-facing").doesNotContain("scheme: internal");
            for (String half : List.of(file.substring(0, operator), file.substring(operator))) {
                assertThat(half).as("%s: on an ip target the attribute keeps the client's address; AWS leaves it off for TCP", overlay)
                        .contains("aws-load-balancer-nlb-target-type: ip")
                        .contains("aws-load-balancer-target-group-attributes: preserve_client_ip.enabled=true");
            }
            assertThat(file).as("%s: externalTrafficPolicy does nothing for an ip target, so it is not claimed", overlay).doesNotContain("externalTrafficPolicy");
        }
        assertThat(read("deploy/edge/overlays/kind/kustomization.yaml")).as("kind's NodePort goes through kube-proxy, where the field is what keeps it")
                .contains("externalTrafficPolicy").contains("Local");
        Map<String, Object> middlewares = map(map(yaml(read(OPERATOR_EDGE)), "http"), "middlewares");
        Map<String, Object> allowList = map(map(middlewares, "internal-only"), "ipAllowList");
        assertThat(stringList(allowList.get("sourceRange"))).containsExactly("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");
        assertThat(middlewares).doesNotContainKeys("source-door", "source-session", "source-machine", "source-callback");
    }

    @Test
    @DisplayName("the edge's namespace is closed: everything denied, the controllers' own flows named, and the application's policy can select them by role")
    void theEdgeNamespaceIsDeniedByDefault() {
        List<Map<String, Object>> policies = yamlAll(read("deploy/edge/base/networkpolicy.yaml"));
        assertThat(policies).anySatisfy(policy -> {
            Map<String, Object> spec = map(policy, "spec");
            assertThat(map(spec, "podSelector")).isEmpty();
            assertThat(spec).doesNotContainKeys("ingress", "egress");
        });
        for (Map<String, Object> document : controllers) {
            if ("Deployment".equals(document.get("kind"))) {
                assertThat(map(map(map(map(document, "spec"), "template"), "metadata"), "labels"))
                        .containsEntry("finapp/role", "ingress-controller");
            }
        }
        assertThat(read("deploy/edge/base/kustomization.yaml")).contains("  - networkpolicy.yaml");
    }

    @Test
    @DisplayName("each overlay names its own pod network as the only source of a forwarded address; a real environment's is Terraform's own block")
    void eachOverlayNamesItsOwnNetwork() {
        for (String overlay : OVERLAYS) {
            String kustomization = read("deploy/overlays/" + overlay + "/kustomization.yaml");
            Matcher named = Pattern.compile("(?m)^\\s+- FINAPP_EDGE_PROXIES=(\\S+)$").matcher(kustomization);
            assertThat(named.find()).as("%s names its edge network", overlay).isTrue();
            String cidr = named.group(1);
            if ("kind".equals(overlay)) {
                assertThat(cidr).as("kind's default pod subnet").isEqualTo("10.244.0.0/16");
            } else {
                String main = read("infra/terraform/environments/" + overlay + "/main.tf");
                Matcher block = Pattern.compile("(?m)^\\s*cidr_block\\s*=\\s*\"([^\"]+)\"").matcher(main);
                assertThat(block.find()).as("%s's network", overlay).isTrue();
                assertThat(cidr).as("%s: only a connection from the controllers' own network has its X-Forwarded-For read", overlay).isEqualTo(block.group(1));
            }
        }
        assertThat(read("deploy/base/kustomization.yaml")).as("a base value would apply to every overlay").doesNotContain("FINAPP_EDGE_PROXIES");
    }

    @Test
    @DisplayName("nothing real is committed: no hostname, secret, certificate or issuer outside kind's test-only overlay")
    void nothingRealIsCommitted() throws IOException {
        List<Path> files;
        try (Stream<Path> tree = Files.walk(root.resolve("deploy/edge"))) {
            files = tree.filter(Files::isRegularFile).toList();
        }
        assertThat(files).as("the tree was read").hasSizeGreaterThan(8);
        for (Path file : files) {
            String text = EvidenceCitations.read(file);
            assertThat(text).as("%s", file).doesNotContain("Host(`").doesNotContain("HostRegexp").doesNotContain("kind: Secret").doesNotContain("secretGenerator");
            assertThat(text.replace("cert-manager.io", "")).as("%s holds a real-looking hostname", file).doesNotContainPattern("[a-z0-9-]+\\.(com|net|org|io|bank|cloud)\\b(?!/)");
            if (!file.toString().replace('\\', '/').contains("/overlays/kind/")) {
                assertThat(text).as("the local CA is kind's alone: %s", file).doesNotContain("cert-manager.io").doesNotContain("finapp.test");
            }
        }
        assertThat(read("deploy/edge/overlays/kind/certificates.yaml")).contains("finapp/test-only").contains(".finapp.test");
    }

    @Test
    @DisplayName("the controllers run the pinned digest, the tag is checked by the weekly pin job, and a controller's grace outlasts its drain")
    void theControllersAreThePin() {
        String pins = read(PINS);
        Matcher digest = Pattern.compile("(?m)^TRAEFIK_DIGEST=(sha256:[0-9a-f]{64})$").matcher(pins);
        assertThat(digest.find()).isTrue();
        long images = Pattern.compile("(?m)^\\s+image:\\s+docker\\.io/library/traefik@" + Pattern.quote(digest.group(1)) + "$")
                .matcher(read(CONTROLLERS)).results().count();
        assertThat(images).as("both controllers, the same digest").isEqualTo(2);
        assertThat(pins).containsPattern("(?m)^TRAEFIK_VERSION=v3\\.[0-9.]+$");
        for (String tool : List.of("TRAEFIK", "WHOAMI", "CURL")) {
            assertThat(read(PIN_CHECK)).contains("\"$" + tool + "_REPOSITORY\"").contains("\"$" + tool + "_DIGEST\"");
        }
        assertThat(pins).containsPattern("(?m)^CRD_SCHEMAS_COMMIT=[0-9a-f]{40}$").containsPattern("(?m)^CERT_MANAGER_VERSION=v[0-9.]+$")
                .containsPattern("(?m)^CERT_MANAGER_DIGEST=sha256:[0-9a-f]{64}$");

        Map<String, Object> lifeCycle = map(map(websecure, "transport"), "lifeCycle");
        long drain = seconds(lifeCycle.get("requestAcceptGraceTimeout")) + seconds(lifeCycle.get("graceTimeOut"));
        for (Map<String, Object> document : controllers) {
            if ("Deployment".equals(document.get("kind"))) {
                assertThat(((Number) map(map(map(document, "spec"), "template"), "spec").get("terminationGracePeriodSeconds")).longValue())
                        .as("%s", document.get("metadata")).isGreaterThan(drain);
            }
        }
    }

    @Test
    @DisplayName("each edge is scraped by a headless Service of its own naming the TLS port alone, and each controller ships its lines to the"
            + " collector's platform receiver alone, query strings and headers dropped at the proxy (P16-TSK-003)")
    @SuppressWarnings("unchecked")
    void eachEdgeIsScrapedAndShipsItsLines() {
        List<Map<String, Object>> services = yamlAll(read("deploy/edge/base/scrape-services.yaml"));
        Map<String, String> selected = new java.util.TreeMap<>();
        for (Map<String, Object> service : services) {
            Map<String, Object> spec = map(service, "spec");
            assertThat(spec).as("headless, so it carries no traffic; ready or not, so a controller that cannot answer is a failed scrape")
                    .containsEntry("clusterIP", "None").containsEntry("publishNotReadyAddresses", true);
            assertThat((List<Object>) spec.get("ports")).isEqualTo(List.of(Map.of("name", "https", "port", 8443, "targetPort", "https", "protocol", "TCP")));
            selected.put(String.valueOf(map(service, "metadata").get("name")), String.valueOf(map(spec, "selector").get("app.kubernetes.io/name")));
        }
        assertThat(selected).containsExactly(Map.entry("finapp-edge-operator-scrape", "finapp-edge-operator"),
                Map.entry("finapp-edge-public-scrape", "finapp-edge-public"));
        assertThat(read("deploy/edge/base/kustomization.yaml")).contains("  - scrape-services.yaml");
        assertThat(read(POLICY)).contains("[edge-scrape-service]");

        // Both controllers reach the collector's platform receiver, and nothing else of the namespace, by label on 4319.
        Map<String, Object> collectorPeer = Map.of(
                "namespaceSelector", Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", "finapp-observability")),
                "podSelector", Map.of("matchLabels", Map.of("finapp/role", "telemetry-collector")));
        int shipping = 0;
        for (Map<String, Object> policy : yamlAll(read("deploy/edge/base/networkpolicy.yaml"))) {
            for (Object rule : (List<Object>) map(policy, "spec").getOrDefault("egress", List.of())) {
                Map<String, Object> egress = (Map<String, Object>) rule;
                if (((List<Object>) egress.get("to")).contains(collectorPeer)) {
                    shipping++;
                    assertThat(egress.get("to")).isEqualTo(List.of(collectorPeer));
                    assertThat(egress.get("ports")).isEqualTo(List.of(Map.of("protocol", "TCP", "port", 4319)));
                }
            }
        }
        assertThat(shipping).as("the public and the operator controllers").isEqualTo(2);
        for (String file : List.of(STATIC, OPERATOR_STATIC)) {
            Map<String, Object> access = map(yaml(read(file)), "accessLog");
            assertThat(map(map(access, "fields"), "queryParameters")).as(file).containsEntry("defaultMode", "drop");
            assertThat(access).as("%s: the console's copy kept while the collector is away", file).containsEntry("dualOutput", true);
            assertThat(read(file)).as(file).contains(":4319/v1/logs").doesNotContain(":4318");
        }
    }

    @Test
    @DisplayName("finapp-edge's one quota is its worst moment, exactly: each controller's rollout, old and new together, and the eviction its budget allows, in pods, CPU and memory, in every overlay")
    void theNamespaceQuotaIsItsWorstMoment() {
        List<Map<String, Object>> documents = new ArrayList<>(controllers);
        List<Map<String, Object>> quotas = yamlAll(read(QUOTA));
        documents.addAll(quotas);
        assertThat(WorstMoment.ofKind(documents, "ResourceQuota")).as("exactly one quota, or the tightest decides").hasSize(1);
        assertThat(read("deploy/edge/base/kustomization.yaml")).containsPattern("(?m)^  - resourcequota\\.yaml$");
        // The base's numbers are every overlay's: an overlay patching a controller or the quota would need its own count here.
        for (String overlay : OVERLAYS) {
            Map<String, Object> kustomization = yaml(read("deploy/edge/overlays/" + overlay + "/kustomization.yaml"));
            for (Object patch : (List<?>) kustomization.getOrDefault("patches", List.of())) {
                assertThat(map(cast(patch), "target").get("kind")).as("%s patches only Services", overlay).isEqualTo("Service");
            }
            assertThat((List<?>) kustomization.get("resources")).as("%s adds no workload", overlay)
                    .allSatisfy(resource -> assertThat(String.valueOf(resource)).isIn("../../base", "certificates.yaml"));
        }

        Map<String, BigDecimal> needed = WorstMoment.of(documents);
        assertThat(needed.get("pods")).as("two controllers, each 2 x 2 + 1").isEqualByComparingTo("10");
        Map<String, BigDecimal> stated = WorstMoment.stated(quotas.get(0));
        needed.forEach((key, value) -> assertThat(stated.get(key)).as("the quota's %s is the worst moment, both ways", key).isEqualByComparingTo(value));
    }

    @Test
    @DisplayName("every policy id in edge.rego is refused by a plant, and every plant is refused: read from the two files, never a list here")
    void everyEdgePolicyIsRefusedByAPlant() {
        Set<String> ruleIds = new TreeSet<>();
        Matcher rule = Pattern.compile("\\[(edge-[a-z-]+)\\]").matcher(read(POLICY));
        while (rule.find()) {
            ruleIds.add(rule.group(1));
        }
        assertThat(ruleIds).as("the policy file was read").hasSizeGreaterThan(10);

        String script = read(SCRIPT);
        Set<String> refusedPlants = new TreeSet<>();
        Set<String> refusedIds = new TreeSet<>();
        Matcher refused = Pattern.compile("(?m)^edge_refused (\\S+) (\\S+)$").matcher(script);
        while (refused.find()) {
            refusedPlants.add(refused.group(1));
            refusedIds.add(refused.group(2));
        }
        // `edge_accepted` is a plant the policies must let through (the quota re-counted with a controller, P16-TSK-004): made, and answered for.
        Matcher accepted = Pattern.compile("(?m)^edge_accepted (\\S+)$").matcher(script);
        while (accepted.find()) {
            refusedPlants.add(accepted.group(1));
        }
        Set<String> planted = new TreeSet<>();
        Matcher made = Pattern.compile("(?m)^\\s+edge_plant(?:_on)? (edge-[a-z-]+)\\b").matcher(script);
        while (made.find()) {
            planted.add(made.group(1));
        }
        assertThat(refusedIds).as("every edge rule is refused by a plant, and no plant names a rule the file lacks").containsAll(ruleIds);
        refusedIds.removeAll(ruleIds);
        assertThat(refusedIds).as("the rest are finapp.rego's, reused").containsExactlyInAnyOrder("security-token", "images-digest", "security-non-root", "security-read-only",
                "rbac-human-namespace");
        assertThat(refusedPlants).as("every plant is refused and every refusal has its plant").isEqualTo(planted);
    }

    @Test
    @DisplayName("the script runs every overlay and the proxy, CI runs it ungated with no secret, and it is committed executable")
    void ciRunsTheCheck() throws IOException {
        String script = read(SCRIPT);
        assertThat(script).contains("OVERLAYS=\"kind staging production\"").contains("-strict").contains("--namespace edge")
                .contains("${CRD_SCHEMAS_COMMIT}").contains("$TRAEFIK_IMAGE").doesNotContain("-ignore-missing-schemas");
        String workflow = read(WORKFLOW);
        Matcher job = Pattern.compile("(?ms)^  manifests:\\R(.*?)(?=^  [a-z-]+:\\R|\\z)").matcher(workflow);
        assertThat(job.find()).as("the job").isTrue();
        assertThat(job.group(1)).contains("./infra/scripts/edge-check.sh").doesNotContain("secrets.");
        assertThat(job.group(1).lines().map(String::trim)).noneMatch(line -> line.startsWith("if:"));
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            assertThat(Files.isExecutable(root.resolve(SCRIPT))).as("CI runs it by path").isTrue();
        }
    }

    private static long seconds(Object duration) {
        return Long.parseLong(((String) duration).replace("s", ""));
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path));
    }

    /** Every key of a parsed document, at every depth, and every string a list holds. */
    private static List<String> keysOf(Object node) {
        List<String> keys = new ArrayList<>();
        if (node instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> {
                keys.add(String.valueOf(key));
                keys.addAll(keysOf(value));
            });
        } else if (node instanceof List<?> items) {
            items.forEach(item -> keys.addAll(item instanceof String text ? List.of(text) : keysOf(item)));
        }
        return keys;
    }

    private static Map<String, Object> yaml(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> yamlAll(String text) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(text)) {
            documents.add((Map<String, Object>) document);
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent).as("key %s", key).containsKey(key);
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
