package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The Operator Console is served same-origin on the operator edge alone, under headers of its own, from a workload that holds nothing and
 * reaches nothing (`X-TSK-020`; finapp-admin-panel `P17-TSK-004`, finapp-admin-panel ADR-C130 decision 2 and ADR-C131 decision 4; ADR-0120 decision 6's note).
 *
 * <p>Held on the committed files on every build. {@code infra/scripts/edge-check.sh} renders the console's overlays beside the application's
 * and the edge's and runs them through {@code edge.rego}'s {@code [edge-console-*]} rules, each refused by a plant, and asks the pinned proxy
 * itself which host serves {@code /console} with which headers; that needs Docker. The routes are {@code EdgeAllowListTest}'s.
 *
 * <p><strong>The policy is a copy.</strong> Its one definition is finapp-admin-panel's {@code config/console-headers.mjs}
 * ({@code CONSOLE_CSP}), which this repository cannot read on a build. {@link #CONSOLE_CSP} is that string as of `X-TSK-020`; a change there
 * is a change here, in {@code edge.rego}, in {@code edge-check.sh} and (since `P17-TSK-104`) in {@code kind-rehearsal.sh}'s console step,
 * each failing until the others agree ({@code KindRehearsalContractTest} holds the last).
 */
@Tag("architecture")
@DisplayName("the Operator Console is served on the operator edge alone, under its own headers, from a workload that holds and reaches nothing (X-TSK-020)")
class ConsoleHostingManifestsTest {

    /** finapp-admin-panel config/console-headers.mjs, CONSOLE_CSP, joined as it joins them. */
    static final String CONSOLE_CSP = String.join("; ",
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "form-action 'self'",
            "base-uri 'none'",
            "frame-ancestors 'none'",
            "require-trusted-types-for 'script'");

    /** The application's routes' policy on both hosts, unchanged by the console. */
    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    private static final String PUBLIC_EDGE = "deploy/edge/base/public-edge.yaml";
    private static final String OPERATOR_EDGE = "deploy/edge/base/operator-edge.yaml";
    private static final String OPERATOR_ROUTES = "deploy/edge/base/operator-routes.yaml";
    private static final String EDGE_POLICY = "deploy/edge/base/networkpolicy.yaml";
    private static final String BASE = "deploy/console/base/";
    private static final String NAMESPACE = "finapp-console";
    private static final List<String> OVERLAYS = List.of("kind", "staging", "production");
    private static final String NAME_LABEL = "app.kubernetes.io/name";

    private static Path root;
    private static List<Map<String, Object>> console;

    @BeforeAll
    static void read() {
        root = EvidenceCitations.repositoryRoot();
        console = yamlAll(read(BASE + "console.yaml"));
    }

    // ------------------------------------------------------------------------------------------------------------- the headers

    @Test
    @DisplayName("console-headers carries exactly the console's policy, with HSTS, nosniff, frameDeny and no-referrer as the API's own, and no Cache-Control at all")
    void theConsoleHeadersAreTheConsolesOwnDefinition() {
        Map<String, Object> middlewares = map(map(yaml(read(OPERATOR_EDGE)), "http"), "middlewares");
        Map<String, Object> headers = map(map(middlewares, "console-headers"), "headers");
        Map<String, Object> api = map(map(middlewares, "edge-headers"), "headers");
        assertThat(headers.get("contentSecurityPolicy")).as("the console's one definition, exactly").isEqualTo(CONSOLE_CSP);
        assertThat(headers).as("HSTS the way the operator edge already states it, and its three siblings")
                .containsEntry("stsSeconds", api.get("stsSeconds")).containsEntry("contentTypeNosniff", true)
                .containsEntry("frameDeny", true).containsEntry("referrerPolicy", "no-referrer");
        assertThat(((Number) headers.get("stsSeconds")).longValue()).isGreaterThanOrEqualTo(31_536_000L);
        // The console's nginx sets Cache-Control per path (index.html no-cache, hashed assets immutable): any value here overwrites both.
        assertThat(keysOf(headers)).as("no Cache-Control, by any key or spelling, anywhere in console-headers")
                .noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("cache"));
        assertThat(headers).as("no response header of its own beyond the named options").doesNotContainKey("customResponseHeaders");
        assertThat(map(headers, "customRequestHeaders")).as("what edge-headers removes, removed here too")
                .containsEntry("Forwarded", "").containsEntry("X-Forwarded-Prefix", "").containsEntry("Upgrade", "")
                .containsEntry("traceparent", "").containsEntry("tracestate", "").containsEntry("baggage", "");
        assertThat(read(OPERATOR_EDGE)).as("the choice is recorded where it is made").contains("NO Cache-Control HERE, deliberately");
    }

    @Test
    @DisplayName("every application route keeps default-src 'none' on both hosts, and the public edge defines no console headers and no console service")
    void theApiRoutesKeepTheStrictPolicy() {
        for (String file : List.of(PUBLIC_EDGE, OPERATOR_EDGE)) {
            Map<String, Object> http = map(yaml(read(file)), "http");
            assertThat(map(map(map(http, "middlewares"), "edge-headers"), "headers").get("contentSecurityPolicy")).as(file).isEqualTo(API_CSP);
        }
        Map<String, Object> publicHttp = map(yaml(read(PUBLIC_EDGE)), "http");
        assertThat(map(publicHttp, "middlewares")).doesNotContainKey("console-headers");
        assertThat(map(publicHttp, "services")).doesNotContainKey("finapp-console");
        Map<String, Object> routers = map(map(yaml(read(OPERATOR_ROUTES)), "http"), "routers");
        routers.forEach((name, router) -> {
            List<String> named = stringList(cast(router).get("middlewares"));
            boolean toConsole = "finapp-console".equals(cast(router).get("service"));
            assertThat(named).as("%s: one headers middleware, the one its service's pages need", name)
                    .contains(toConsole ? "console-headers" : "edge-headers").doesNotContain(toConsole ? "edge-headers" : "console-headers");
        });
        assertThat(routers.values().stream().filter(router -> "finapp-console".equals(cast(router).get("service")))).as("one console router").hasSize(1);
    }

    @Test
    @DisplayName("the operator edge addresses the console by its Service's own name, namespace and port")
    void theOperatorEdgeAddressesTheConsolesService() {
        Map<String, Object> service = map(map(map(map(yaml(read(OPERATOR_EDGE)), "http"), "services"), "finapp-console"), "loadBalancer");
        assertThat(service).containsEntry("serversTransport", "no-reuse").containsEntry("passHostHeader", false).doesNotContainKey("sticky");
        assertThat(service.get("servers")).isEqualTo(List.of(Map.of("url", "http://finapp-console." + NAMESPACE + ".svc.cluster.local:8080")));
        Map<String, Object> declared = only(console, "Service");
        assertThat(map(declared, "metadata")).containsEntry("name", "finapp-console");
        assertThat(map(declared, "spec")).containsEntry("type", "ClusterIP")
                .containsEntry("selector", Map.of(NAME_LABEL, "finapp-console"))
                .containsEntry("ports", List.of(Map.of("name", "http", "port", 8080, "targetPort", "http", "protocol", "TCP")));
        assertThat(yaml(read(BASE + "kustomization.yaml"))).containsEntry("namespace", NAMESPACE);
    }

    // ------------------------------------------------------------------------------------------------------------ the workload

    @Test
    @DisplayName("the workload: two replicas, uid 101, a read-only root with /tmp in memory, every capability dropped, no token, probes on GET /console/, requests and limits")
    void theConsoleWorkloadIsHardened() {
        Map<String, Object> deployment = only(console, "Deployment");
        Map<String, Object> spec = map(deployment, "spec");
        assertThat(spec).containsEntry("replicas", 2);
        assertThat(map(map(spec, "strategy"), "rollingUpdate")).containsEntry("maxUnavailable", 0).containsEntry("maxSurge", 1);
        Map<String, Object> template = map(spec, "template");
        assertThat(map(map(template, "metadata"), "labels")).as("not the role the application admits to its port")
                .containsEntry(NAME_LABEL, "finapp-console").doesNotContainKey("finapp/role");
        Map<String, Object> pod = map(template, "spec");
        assertThat(pod).containsEntry("serviceAccountName", "finapp-console").containsEntry("automountServiceAccountToken", false)
                .containsEntry("enableServiceLinks", false).doesNotContainKeys("hostNetwork", "hostPID", "hostIPC");
        assertThat(map(pod, "securityContext")).containsEntry("runAsNonRoot", true).containsEntry("runAsUser", 101)
                .containsEntry("runAsGroup", 101).containsEntry("seccompProfile", Map.of("type", "RuntimeDefault"));
        assertThat(only(console, "ServiceAccount")).containsEntry("automountServiceAccountToken", false);

        List<Map<String, Object>> containers = list(pod, "containers");
        assertThat(containers).hasSize(1);
        Map<String, Object> container = containers.get(0);
        assertThat(container.get("image")).as("named for the overlays' digest, never a tag").isEqualTo("finapp-console");
        assertThat(map(container, "securityContext")).containsEntry("allowPrivilegeEscalation", false)
                .containsEntry("readOnlyRootFilesystem", true).containsEntry("privileged", false)
                .containsEntry("capabilities", Map.of("drop", List.of("ALL")));
        assertThat(container.get("ports")).isEqualTo(List.of(Map.of("name", "http", "containerPort", 8080, "protocol", "TCP")));
        for (String probe : List.of("startupProbe", "livenessProbe", "readinessProbe")) {
            assertThat(map(map(container, probe), "httpGet")).as(probe).isEqualTo(Map.of("path", "/console/", "port", "http"));
        }
        Map<String, Object> resources = map(container, "resources");
        assertThat(map(resources, "requests").get("memory")).as("memory requested is its limit").isEqualTo(map(resources, "limits").get("memory"));
        assertThat(map(resources, "requests")).containsKeys("cpu", "memory");
        assertThat(map(resources, "limits")).containsKeys("cpu", "memory");

        assertThat(list(container, "volumeMounts")).isEqualTo(List.of(Map.of("name", "tmp", "mountPath", "/tmp")));
        Map<String, Object> tmp = list(pod, "volumes").stream().filter(volume -> "tmp".equals(volume.get("name"))).findFirst().orElseThrow();
        assertThat(map(tmp, "emptyDir")).as("in memory and sized: nothing reaches the node's disk").containsEntry("medium", "Memory").containsKey("sizeLimit");
        assertThat(list(pod, "volumes")).as("no other volume: no Secret, ConfigMap or token").hasSize(1);
        assertThat(map(only(console, "PodDisruptionBudget"), "spec")).containsEntry("maxUnavailable", 1);
    }

    @Test
    @DisplayName("each overlay renders the console into its own namespace by digest: the base names no digest, and the namespace is restricted and admission-watched")
    void eachOverlayPinsTheConsoleByDigest() {
        for (String overlay : OVERLAYS) {
            Map<String, Object> kustomization = yaml(read("deploy/console/overlays/" + overlay + "/kustomization.yaml"));
            assertThat(kustomization.get("resources")).as(overlay).isEqualTo(List.of("../../base"));
            assertThat(kustomization).as("%s: nothing but the image is the overlay's", overlay).doesNotContainKeys("patches", "namespace", "configMapGenerator");
            List<Map<String, Object>> images = list(kustomization, "images");
            assertThat(images).as(overlay).hasSize(1);
            assertThat(images.get(0)).containsEntry("name", "finapp-console").containsOnlyKeys("name", "digest");
            assertThat(String.valueOf(images.get(0).get("digest"))).as("%s: by digest, nothing real committed", overlay).matches("sha256:0{64}");
        }
        Map<String, Object> namespace = yamlAll(read("deploy/namespaces/namespaces.yaml")).stream()
                .filter(document -> NAMESPACE.equals(map(document, "metadata").get("name"))).findFirst().orElseThrow();
        assertThat(map(map(namespace, "metadata"), "labels")).containsEntry("policy.sigstore.dev/include", "true")
                .containsEntry("pod-security.kubernetes.io/enforce", "restricted");
    }

    // ------------------------------------------------------------------------------------------------------------- the network

    @Test
    @DisplayName("the console's namespace denies everything; its pods admit the operator controller alone on 8080 and open no connection; the public controller cannot reach them")
    void theConsoleIsReachedFromTheOperatorEdgeAlone() {
        List<Map<String, Object>> policies = yamlAll(read(BASE + "networkpolicy.yaml"));
        assertThat(policies).anySatisfy(policy -> {
            Map<String, Object> spec = map(policy, "spec");
            assertThat(map(spec, "podSelector")).isEmpty();
            assertThat(stringList(spec.get("policyTypes"))).containsExactlyInAnyOrder("Ingress", "Egress");
            assertThat(spec).doesNotContainKeys("ingress", "egress");
        });
        Map<String, Object> operatorPeer = Map.of(
                "namespaceSelector", Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", "finapp-edge")),
                "podSelector", Map.of("matchLabels", Map.of(NAME_LABEL, "finapp-edge-operator")));
        Map<String, Object> consolePolicy = policies.stream().filter(policy -> "finapp-console".equals(map(policy, "metadata").get("name")))
                .findFirst().orElseThrow();
        Map<String, Object> spec = map(consolePolicy, "spec");
        assertThat(spec.get("podSelector")).isEqualTo(Map.of("matchLabels", Map.of(NAME_LABEL, "finapp-console")));
        assertThat(stringList(spec.get("policyTypes"))).as("egress stated, so no rule means no connection").containsExactlyInAnyOrder("Ingress", "Egress");
        assertThat(spec).doesNotContainKey("egress");
        assertThat(spec.get("ingress")).isEqualTo(List.of(Map.of("from", List.of(operatorPeer), "ports", List.of(Map.of("protocol", "TCP", "port", 8080)))));
        assertThat(policies).as("the default-deny and the console's one flow, nothing else").hasSize(2);

        Map<String, Object> consolePeer = Map.of(
                "namespaceSelector", Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", NAMESPACE)),
                "podSelector", Map.of("matchLabels", Map.of(NAME_LABEL, "finapp-console")));
        for (Map<String, Object> policy : yamlAll(read(EDGE_POLICY))) {
            String name = String.valueOf(map(policy, "metadata").get("name"));
            List<Map<String, Object>> toConsole = list(map(policy, "spec"), "egress").stream()
                    .filter(rule -> String.valueOf(rule.get("to")).contains(NAMESPACE)).toList();
            if ("finapp-edge-operator".equals(name)) {
                assertThat(toConsole).as("the operator controller reaches the console on its one port")
                        .containsExactly(Map.of("to", List.of(consolePeer), "ports", List.of(Map.of("protocol", "TCP", "port", 8080))));
            } else {
                assertThat(toConsole).as("%s never reaches the console", name).isEmpty();
            }
        }
    }

    // --------------------------------------------------------------------------------------------------------------- the quota

    @Test
    @DisplayName("finapp-console's one quota is its worst moment, exactly: a rollout's old and new pods together and the eviction its budget allows")
    void theConsoleQuotaIsItsWorstMoment() {
        List<Map<String, Object>> documents = new ArrayList<>(console);
        List<Map<String, Object>> quotas = yamlAll(read(BASE + "resourcequota.yaml"));
        documents.addAll(quotas);
        assertThat(WorstMoment.ofKind(documents, "ResourceQuota")).hasSize(1);
        assertThat(read(BASE + "kustomization.yaml")).containsPattern("(?m)^  - resourcequota\\.yaml$");
        Map<String, BigDecimal> needed = WorstMoment.of(documents);
        assertThat(needed.get("pods")).as("2 x 2 + 1").isEqualByComparingTo("5");
        Map<String, BigDecimal> stated = WorstMoment.stated(quotas.get(0));
        needed.forEach((key, value) -> assertThat(stated.get(key)).as("the quota's %s is the worst moment, both ways", key).isEqualByComparingTo(value));
    }

    // ------------------------------------------------------------------------------------------------------------- the check

    @Test
    @DisplayName("the edge's check renders the console with every overlay, its policy file holds the console's string, and the proxy is asked for /console on both hosts")
    void theEdgeCheckRendersAndAsksTheConsole() {
        String script = read("infra/scripts/edge-check.sh");
        assertThat(script).contains("kustomize build \"$1/console/overlays/$2\"")
                .contains("--network-alias finapp-console.finapp-console.svc.cluster.local")
                .contains("CONSOLE_CSP=\"" + CONSOLE_CSP + "\"")
                .contains("expect_code \"the console on the public host: $path\" 404 \"$PUBLIC$path\"")
                .contains("header_once \"an operator API route\" \"$headers\" \"content-security-policy: " + API_CSP + "\"");
        String policy = read("infra/scripts/manifest-policy/edge.rego");
        assertThat(policy).contains("console_csp := \"" + CONSOLE_CSP + "\"").contains("api_csp := \"" + API_CSP + "\"");
        for (String id : List.of("edge-console-routes", "edge-console-headers", "edge-api-csp", "edge-console-network", "edge-console-workload")) {
            assertThat(policy).as(id).contains("[" + id + "]");
            assertThat(Pattern.compile("(?m)^edge_refused \\S+ " + Pattern.quote(id) + "$").matcher(script).find()).as("%s is refused by a plant", id).isTrue();
        }
    }

    /**
     * `P17-TSK-104`'s fourth item, {@code kubeconform} over {@code deploy/console}: the edge's check renders each console overlay into the very
     * stream it hands {@code kubeconform -strict}, for every overlay there is, so no console manifest reaches a cluster unread by the schemas.
     * Running it needs Docker; that it reads the console is held here.
     */
    @Test
    @DisplayName("kubeconform -strict reads every console overlay: the edge's check appends each to the stream it validates, for kind, staging and production (P17-TSK-104)")
    void kubeconformReadsEveryConsoleOverlay() {
        String script = read("infra/scripts/edge-check.sh");
        assertThat(script).as("the console's stream appended to the overlay's, which is what is validated")
                .contains("kustomize build \"$1/console/overlays/$2\" >> \"$3\"")
                .contains("render_both /repo/deploy \"$overlay\" \"/scratch/rendered/$overlay.yaml\"")
                .contains("check \"kubeconform $overlay\" kubeconform_on \"/scratch/rendered/$overlay.yaml\"")
                .contains("-strict -summary").doesNotContain("-ignore-missing-schemas");
        assertThat(script).as("every overlay").contains("OVERLAYS=\"" + String.join(" ", OVERLAYS) + "\"");
        assertThat(root.resolve("deploy/console/overlays").toFile().list()).as("and the console has no other").containsExactlyInAnyOrderElementsOf(OVERLAYS);
    }

    // ------------------------------------------------------------------------------------------------------------- reading

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path));
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

    private static Map<String, Object> only(List<Map<String, Object>> documents, String kind) {
        List<Map<String, Object>> found = WorstMoment.ofKind(documents, kind);
        assertThat(found).as("one %s", kind).hasSize(1);
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent).as("key %s", key).containsKey(key);
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.getOrDefault(key, List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    /** Every key of a parsed node, at every depth. */
    private static List<String> keysOf(Object node) {
        List<String> keys = new ArrayList<>();
        if (node instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> {
                keys.add(String.valueOf(key));
                keys.addAll(keysOf(value));
            });
        } else if (node instanceof List<?> items) {
            items.forEach(item -> keys.addAll(keysOf(item)));
        }
        return keys;
    }
}
