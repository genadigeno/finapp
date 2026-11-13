package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.telemetry.TraceAttributes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The telemetry pipeline as committed (`P15-TSK-023`, ADR-0124 decision 1; THREAT_MODEL.md T4, T15, T46; section 6 item 21, decided
 * 2026-11-09): the namespace closed by default with its workloads' flows named, nothing in it reaching the data tier or out, the collector
 * what the application's policy admits and the only endpoint the application names, the second net's allow-list what the platform's
 * {@code Spans} port admits, and the checks that hold all of it running in CI. Read from the files, never a copy of their values.
 */
@DisplayName("the telemetry pipeline's manifests, collector and checks hold what was decided (P15-TSK-023)")
class TelemetryPipelineManifestsTest {

    private static final String BASE = "deploy/observability/base/";
    private static final String KIND = "deploy/observability/overlays/kind/";
    private static final String COLLECTOR = BASE + "collector.yaml";
    private static final String POLICY = "infra/scripts/manifest-policy/observability.rego";
    private static final String SCRIPT = "infra/scripts/telemetry-check.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String ENDPOINT = "http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/";
    private static final Set<Integer> DATA_TIER = Set.of(5432, 9092, 9093, 9094);

    private static Path root;
    private static List<Map<String, Object>> policies;
    private static Map<String, Object> collector;

    @BeforeAll
    static void read() {
        root = EvidenceCitations.repositoryRoot();
        policies = new ArrayList<>();
        for (String file : List.of(BASE + "networkpolicy.yaml", KIND + "networkpolicy-backends.yaml")) {
            policies.addAll(yamlAll(read(file)));
        }
        collector = yaml(read(COLLECTOR));
    }

    @Test
    @DisplayName("finapp-observability is closed: a policy selects every pod, in both directions, with no rule, and each workload's flows are named by label")
    void theNamespaceIsDeniedByDefault() {
        assertThat(policies).as("the base's and kind's policies were read").hasSizeGreaterThanOrEqualTo(5);
        assertThat(policies).anySatisfy(policy -> {
            Map<String, Object> spec = map(policy, "spec");
            assertThat(map(spec, "podSelector")).isEmpty();
            assertThat(stringList(spec.get("policyTypes"))).containsExactlyInAnyOrder("Ingress", "Egress");
            assertThat(spec).doesNotContainKeys("ingress", "egress");
        });
        for (Map<String, Object> policy : policies) {
            Map<String, Object> selector = map(map(policy, "spec"), "podSelector");
            if (!selector.isEmpty()) {
                assertThat(map(selector, "matchLabels")).as("%s selects by role", map(policy, "metadata").get("name")).containsKey("finapp/role");
            }
        }
        assertThat(read(BASE + "kustomization.yaml")).contains("  - networkpolicy.yaml").contains("namespace: finapp-observability");
        assertThat(read(KIND + "kustomization.yaml")).contains("  - networkpolicy-backends.yaml");
    }

    @Test
    @DisplayName("no pod of the telemetry pipeline's namespace holds an egress rule to the database or the broker, to an address block, or to everything")
    @SuppressWarnings("unchecked")
    void noPodOfTheNamespaceReachesTheDataTier() {
        int rules = 0;
        for (Map<String, Object> policy : policies) {
            for (Object rule : (List<Object>) map(policy, "spec").getOrDefault("egress", List.of())) {
                Map<String, Object> egress = (Map<String, Object>) rule;
                rules++;
                List<Object> to = (List<Object>) egress.getOrDefault("to", List.of());
                assertThat(to).as("%s: an egress rule names its peers", map(policy, "metadata").get("name")).isNotEmpty();
                for (Object peer : to) {
                    assertThat((Map<String, Object>) peer).as("an address block reaches outside the cluster").doesNotContainKey("ipBlock");
                }
                List<Object> ports = (List<Object>) egress.getOrDefault("ports", List.of());
                assertThat(ports).as("%s: an egress rule naming no port is every port, the data tier's among them", map(policy, "metadata").get("name"))
                        .isNotEmpty();
                for (Object port : ports) {
                    assertThat(((Number) ((Map<String, Object>) port).get("port")).intValue()).isNotIn(DATA_TIER);
                }
            }
        }
        assertThat(rules).as("the egress rules were read").isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the collector's ingress names its ports, and the peers' unscrubbed 4317 is open to the collectors of the namespace alone")
    @SuppressWarnings("unchecked")
    void thePeersTierIsTheCollectorsAlone() {
        int peerRules = 0;
        for (Map<String, Object> policy : policies) {
            // An empty selector - the default-deny's - selects the collector too.
            Map<String, Object> selector = map(map(policy, "spec"), "podSelector");
            Map<String, Object> labels = selector.containsKey("matchLabels") ? map(selector, "matchLabels") : Map.of();
            if (!labels.isEmpty() && !"telemetry-collector".equals(labels.get("finapp/role"))) {
                continue;
            }
            for (Object rule : (List<Object>) map(policy, "spec").getOrDefault("ingress", List.of())) {
                Map<String, Object> ingress = (Map<String, Object>) rule;
                List<Object> ports = (List<Object>) ingress.getOrDefault("ports", List.of());
                assertThat(ports).as("%s: an ingress rule naming no port is every port", map(policy, "metadata").get("name")).isNotEmpty();
                boolean peers = ports.stream().anyMatch(port -> ((Number) ((Map<String, Object>) port).get("port")).intValue() == 4317);
                if (peers) {
                    peerRules++;
                    assertThat((List<Object>) ingress.getOrDefault("from", List.of())).isNotEmpty()
                            .allSatisfy(peer -> assertThat(peer).isEqualTo(Map.of("podSelector", Map.of("matchLabels", Map.of("finapp/role", "telemetry-collector")))));
                }
            }
        }
        assertThat(peerRules).as("the peers' rule was read").isEqualTo(1);
    }

    @Test
    @DisplayName("the collector is what finapp-app-egress admits, by label on 4318, and the only telemetry endpoint the application names")
    void theCollectorIsTheApplicationsOneEgress() {
        Map<String, Object> deployment = yamlAll(read(BASE + "collector-workload.yaml")).stream()
                .filter(document -> "Deployment".equals(document.get("kind"))).findFirst().orElseThrow();
        assertThat(((Number) map(deployment, "spec").get("replicas")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(map(map(map(map(deployment, "spec"), "template"), "metadata"), "labels"))
                .containsEntry("finapp/role", "telemetry-collector");
        assertThat(read(BASE + "collector-workload.yaml")).contains("containerPort: 4318");
        assertThat(read("deploy/base/networkpolicy-app.yaml"))
                .contains("finapp/role: telemetry-collector").containsPattern("(?s)telemetry-collector.*?port: 4318");
        String app = read("deploy/base/deployment.yaml");
        assertThat(app).contains("value: " + ENDPOINT + "traces").contains("value: " + ENDPOINT + "logs");
        assertThat(Pattern.compile("(?m)^\\s+value: (https?://\\S+)$").matcher(app).results().map(found -> found.group(1)))
                .allMatch(value -> value.startsWith(ENDPOINT));
        // Every overlay takes the base's endpoints unchanged: none names a telemetry variable or an OTLP address of its own. The
        // rendered staging and production overlays are also held to them by observability.rego in infra/scripts/telemetry-check.sh.
        for (String overlay : List.of("kind", "staging", "production")) {
            try (Stream<Path> files = Files.walk(root.resolve("deploy/overlays/" + overlay))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = read(root.relativize(file).toString().replace('\\', '/'));
                    assertThat(text).as(overlay + ": " + file.getFileName()).doesNotContain("FINAPP_TELEMETRY").doesNotContain(":4318")
                            .doesNotContain("/v1/traces").doesNotContain("/v1/logs");
                }
            } catch (java.io.IOException unreadable) {
                throw new java.io.UncheckedIOException(unreadable);
            }
        }
        assertThat(read("infra/scripts/telemetry-check.sh")).contains("for overlay in staging production; do")
                .contains("kustomize build \"/repo/deploy/overlays/$overlay\"")
                .contains("check \"the application's one telemetry endpoint on staging\"")
                .contains("check \"the application's one telemetry endpoint on production\"")
                .contains("obs_refused obs-endpoint-staging obs-endpoint");
    }

    @Test
    @DisplayName("the second net keeps what the Spans port admits and the flow's ids, and drops URLs, messages and stacks; every pipeline from the application scrubs")
    void theAllowListIsWhatThePlatformAdmits() {
        String config = read(COLLECTOR);
        Set<String> kept = keptKeys(config, "context: span\n");
        assertThat(kept).containsAll(Spans.IDENTIFIER_KEYS)
                .contains(TraceAttributes.CORRELATION_ID, TraceAttributes.CAUSATION_ID)
                .doesNotContain("http.url", "url.full", "url.path", "url.query", "exception.message", "exception.stacktrace", "server.address",
                        "user_agent.original", "client.address", "thread.name");
        assertThat(keptKeys(config, "context: spanevent\n")).containsExactly("exception.type");
        assertThat(keptKeys(config, "context: log\n")).containsExactlyInAnyOrder(TraceAttributes.CORRELATION_ID, TraceAttributes.CAUSATION_ID);
        assertThat(config).contains("set(status.message, \"\")").contains("replace_pattern(body,");

        Map<String, Object> pipelines = map(map(collector, "service"), "pipelines");
        int fromTheApplication = 0;
        for (Map.Entry<String, Object> pipeline : pipelines.entrySet()) {
            Map<String, Object> declared = cast(pipeline.getValue());
            if (stringList(declared.get("receivers")).contains("otlp")) {
                fromTheApplication++;
                assertThat(stringList(declared.get("processors"))).as(pipeline.getKey()).contains("transform/scrub");
            }
        }
        assertThat(fromTheApplication).as("traces and logs").isEqualTo(2);
        assertThat(stringList(map(pipelines, "traces/ingress").get("exporters"))).containsExactly("load_balancing");
        assertThat(map(map(collector, "exporters"), "load_balancing")).containsEntry("routing_key", "traceID");
        // P15-TSK-032's review: the tier's own retry and queue re-route a failed batch to the peers the resolver knows by then, so a peer
        // starting, leaving or not yet resolved costs no span; without them each was a drop and a ticket at every rollout.
        Map<String, Object> balancing = map(map(collector, "exporters"), "load_balancing");
        assertThat(map(balancing, "retry_on_failure")).containsEntry("enabled", true).containsEntry("max_elapsed_time", "120s");
        assertThat(map(balancing, "sending_queue")).containsEntry("enabled", true);
        assertThat(stringList(map(pipelines, "traces/sampling").get("processors"))).contains("tail_sampling");
        assertThat(config).contains("name: errors").contains("name: slow").contains("name: writes").contains("name: asynchronous-legs");
    }

    @Test
    @DisplayName("the platform's own lines come in on a receiver of their own, 4319, from the edge's controllers and the cluster watcher alone,"
            + " and leave through a net of their own: the client's, the backend's and the host's names dropped by name, an unrouted path and a"
            + " query cut, the edge's body rebuilt from what was kept, card numbers and amounts scrubbed (P16-TSK-003)")
    @SuppressWarnings("unchecked")
    void thePlatformsOwnLinesHaveTheirOwnNet() {
        String config = read(COLLECTOR);
        Map<String, Object> receivers = map(collector, "receivers");
        assertThat(String.valueOf(map(map(map(receivers, "otlp/platform"), "protocols"), "http").get("endpoint"))).isEqualTo("${env:POD_IP}:4319");
        Map<String, Object> pipelines = map(map(collector, "service"), "pipelines");
        int fromThePlatform = 0;
        for (Map.Entry<String, Object> pipeline : pipelines.entrySet()) {
            Map<String, Object> declared = cast(pipeline.getValue());
            if (stringList(declared.get("receivers")).contains("otlp/platform")) {
                fromThePlatform++;
                assertThat(stringList(declared.get("receivers"))).as(pipeline.getKey()).containsExactly("otlp/platform");
                assertThat(stringList(declared.get("processors"))).as(pipeline.getKey()).contains("transform/scrub-platform")
                        .doesNotContain("transform/scrub");
            }
        }
        assertThat(fromThePlatform).as("one pipeline takes the platform's own lines").isEqualTo(1);
        // The net, as the collector reads it: the statements of the platform's processor, in order.
        List<String> statements = new ArrayList<>();
        for (Object context : (List<Object>) map(map(collector, "processors"), "transform/scrub-platform").get("log_statements")) {
            statements.addAll(stringList(((Map<String, Object>) context).get("statements")));
        }
        Set<String> kept = new TreeSet<>();
        Set<String> resource = new TreeSet<>();
        for (String statement : statements) {
            Matcher keep = Pattern.compile("^keep_keys\\(attributes, \\[([^\\]]*)\\]\\)$").matcher(statement);
            if (keep.find()) {
                Set<String> keys = new TreeSet<>();
                Matcher key = Pattern.compile("\"([^\"]+)\"").matcher(keep.group(1));
                while (key.find()) {
                    keys.add(key.group(1));
                }
                (keys.contains("service.name") ? resource : kept).addAll(keys);
            }
        }
        assertThat(resource).containsExactlyInAnyOrderElementsOf(keptKeys(config, "context: resource\n"))
                .doesNotContain("host.name", "process.command_args", "process.owner", "os.description");
        assertThat(kept).contains("RequestMethod", "RequestPath", "DownstreamStatus", "RouterName", "Duration", "k8s.job.name", "otelcol.component.id")
                .doesNotContain("ClientHost", "ClientAddr", "ClientPort", "ClientUsername", "RequestAddr", "RequestHost", "RequestPort",
                        "ServiceURL", "ServiceAddr", "StartLocal", "StartUTC", "k8s.node.name", "host.name");
        assertThat(statements).contains("delete_key(attributes, \"RequestPath\") where attributes[\"RouterName\"] == nil",
                "replace_pattern(attributes[\"RequestPath\"], \"[?#].*$\", \"\")");
        assertThat(statements).anySatisfy(statement -> assertThat(statement).startsWith("set(body, Format(")
                .endsWith("where attributes[\"DownstreamStatus\"] != nil"));
        // The same card-number and amount scrub as the application's own pipeline, on bodies and values.
        List<String> theApplications = new ArrayList<>();
        for (Object context : (List<Object>) map(map(collector, "processors"), "transform/scrub").get("log_statements")) {
            for (String statement : stringList(((Map<String, Object>) context).get("statements"))) {
                if (statement.startsWith("replace_pattern(body,")) {
                    theApplications.add(statement);
                }
            }
        }
        assertThat(theApplications).hasSize(2);
        assertThat(statements).containsAll(theApplications);
        assertThat(statements.stream().filter(statement -> statement.startsWith("replace_all_patterns(attributes, \"value\", \"(^|")).count())
                .as("the card number and the amount in every value").isEqualTo(2);
        // An edge record's text can carry a client's address (its own log's refused handshakes): every address cut, from bodies and values.
        assertThat(statements.stream().filter(statement -> statement.contains("\"[address]\")")
                && statement.endsWith("where resource.attributes[\"service.name\"] == \"finapp-edge\"")).count()).isEqualTo(4);
        // The order that makes it a net: the path cut and the body rebuilt before the allow-list, the scrub after it.
        assertThat(statements.indexOf("delete_key(attributes, \"RequestPath\") where attributes[\"RouterName\"] == nil"))
                .isLessThan(statements.indexOf(theApplications.get(0)));

        // Its senders: the edge's controllers and the watcher, each exactly, on 4319 and nothing else there.
        int platformRules = 0;
        for (Map<String, Object> policy : policies) {
            if (!"finapp-collector".equals(map(policy, "metadata").get("name"))) {
                continue;
            }
            for (Object rule : (List<Object>) map(policy, "spec").get("ingress")) {
                Map<String, Object> ingress = (Map<String, Object>) rule;
                if (String.valueOf(ingress.get("ports")).contains("port=4319")) {
                    platformRules++;
                    assertThat(ingress.get("ports")).isEqualTo(List.of(Map.of("protocol", "TCP", "port", 4319)));
                    assertThat(ingress.get("from")).isEqualTo(List.of(
                            Map.of("namespaceSelector", Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", "finapp-edge")),
                                    "podSelector", Map.of("matchLabels", Map.of("finapp/role", "ingress-controller"))),
                            Map.of("podSelector", Map.of("matchLabels", Map.of("finapp/role", "cluster-watch")))));
                }
            }
        }
        assertThat(platformRules).isEqualTo(1);
        // And each sender names that port, never the application's: both edges' static files, the watcher's exporter, the collector's own log.
        String platformEndpoint = "http://finapp-collector.finapp-observability.svc.cluster.local:4319";
        for (String edge : List.of("deploy/edge/base/traefik.yaml", "deploy/edge/base/traefik-operator.yaml")) {
            Map<String, Object> traefik = yaml(read(edge));
            assertThat(map(traefik, "experimental")).containsEntry("otlpLogs", true);
            assertThat(String.valueOf(map(map(map(traefik, "log"), "otlp"), "http").get("endpoint"))).isEqualTo(platformEndpoint + "/v1/logs");
            Map<String, Object> access = map(traefik, "accessLog");
            assertThat(String.valueOf(map(map(access, "otlp"), "http").get("endpoint"))).isEqualTo(platformEndpoint + "/v1/logs");
            assertThat(map(map(access, "fields"), "queryParameters")).containsEntry("defaultMode", "drop");
            assertThat(map(map(access, "fields"), "headers")).containsEntry("defaultMode", "drop");
            assertThat(read(edge)).doesNotContain(":4318");
        }
        Map<String, Object> watcher = yaml(read(BASE + "cluster-watch.yaml"));
        assertThat(String.valueOf(map(map(watcher, "exporters"), "otlp_http/platform").get("endpoint"))).isEqualTo(platformEndpoint);
        assertThat(config).contains("endpoint: http://${env:POD_IP}:4319/v1/logs");
    }

    @Test
    @DisplayName("the cluster watcher is the one pod of the namespace with a Kubernetes credential: the pinned collector, one replica, its"
            + " account the controller list's one entry, its Role in finapp list and watch on the kinds its receiver reads, reached by the"
            + " scraper alone and reaching the collector's platform receiver and DNS alone (P16-TSK-003)")
    @SuppressWarnings("unchecked")
    void theClusterWatcherReadsFinappAndNothingElse() {
        List<Map<String, Object>> documents = yamlAll(read(BASE + "cluster-watch-workload.yaml"));
        Map<String, Object> account = documents.stream().filter(document -> "ServiceAccount".equals(document.get("kind"))).findFirst().orElseThrow();
        assertThat(account).containsEntry("automountServiceAccountToken", true);
        Map<String, Object> deployment = documents.stream().filter(document -> "Deployment".equals(document.get("kind"))).findFirst().orElseThrow();
        Map<String, Object> pod = map(map(map(deployment, "spec"), "template"), "spec");
        assertThat(pod).containsEntry("serviceAccountName", "finapp-cluster-watch").containsEntry("automountServiceAccountToken", true);
        assertThat(map(map(map(map(deployment, "spec"), "template"), "metadata"), "labels")).containsEntry("finapp/role", "cluster-watch");
        assertThat(read(BASE + "cluster-watch-workload.yaml")).contains("opentelemetry-collector-contrib@" + pin(read(PINS), "COLLECTOR_DIGEST"));
        // No other workload of the namespace holds a token.
        for (String file : List.of(BASE + "collector-workload.yaml", KIND + "backends.yaml")) {
            for (Map<String, Object> document : yamlAll(read(file))) {
                if ("ServiceAccount".equals(document.get("kind"))) {
                    assertThat(document).as(file).containsEntry("automountServiceAccountToken", false);
                }
            }
        }
        String policy = read("infra/scripts/manifest-policy/finapp.rego");
        assertThat(policy).contains("controller_accounts := [\n\t{\"namespace\": \"finapp-observability\", \"name\": \"finapp-cluster-watch\"},\n]");
        // Its read of finapp, in the application's own release.
        List<Map<String, Object>> rbac = yamlAll(read("deploy/base/rbac-cluster-watch.yaml"));
        Map<String, Object> role = rbac.stream().filter(document -> "Role".equals(document.get("kind"))).findFirst().orElseThrow();
        Set<String> granted = new TreeSet<>();
        for (Object rule : (List<Object>) role.get("rules")) {
            Map<String, Object> declared = (Map<String, Object>) rule;
            assertThat(stringList(declared.get("verbs"))).containsExactly("list", "watch");
            for (String group : stringList(declared.get("apiGroups"))) {
                for (String resource : stringList(declared.get("resources"))) {
                    granted.add(group + "/" + resource);
                }
            }
        }
        assertThat(granted).containsExactlyInAnyOrder("/pods", "/events", "/services", "/replicationcontrollers", "/resourcequotas",
                "apps/deployments", "apps/replicasets", "apps/statefulsets", "apps/daemonsets", "batch/jobs", "batch/cronjobs",
                "autoscaling/horizontalpodautoscalers");
        Map<String, Object> binding = rbac.stream().filter(document -> "RoleBinding".equals(document.get("kind"))).findFirst().orElseThrow();
        assertThat(binding.get("subjects")).isEqualTo(List.of(
                Map.of("kind", "ServiceAccount", "name", "finapp-cluster-watch", "namespace", "finapp-observability")));
        assertThat(read("deploy/base/kustomization.yaml")).contains("  - rbac-cluster-watch.yaml");
        assertThat(read(BASE + "kustomization.yaml")).contains("  - cluster-watch-workload.yaml").contains("config.yaml=cluster-watch.yaml");
        // Its network: the scraper in, the collector's 4319 and DNS out, and no address block (the API server is its node's).
        Map<String, Object> watch = policies.stream().filter(candidate -> "finapp-cluster-watch".equals(map(candidate, "metadata").get("name")))
                .findFirst().orElseThrow();
        assertThat(map(watch, "spec").get("ingress")).isEqualTo(List.of(Map.of(
                "from", List.of(Map.of("podSelector", Map.of("matchLabels", Map.of("finapp/role", "metrics-scraper")))),
                "ports", List.of(Map.of("protocol", "TCP", "port", 8889)))));
        assertThat(String.valueOf(map(watch, "spec").get("egress"))).contains("port=4319").contains("k8s-app=kube-dns").doesNotContain("ipBlock");
        assertThat(read(POLICY)).contains("[obs-cluster-watch-egress]").contains("[obs-platform-ingress]").contains("\"cluster-watch\": [{\"role\": \"metrics-scraper\"");
        assertThat(policy).contains("[rbac-cluster-watch]").contains("not runs_as_a_listed_controller(doc, spec)");
    }

    @Test
    @DisplayName("the card-number pattern never matches inside a UUID, so a correlation id is never cut, and still matches every card shape")
    void thePatternLeavesIdentifiersAlone() {
        // The statement as the collector receives it, the pattern between the call's first two quotes.
        String statement = null;
        List<Object> contexts = cast(map(map(collector, "processors"), "transform/scrub").get("log_statements"));
        for (Object context : contexts) {
            Map<String, Object> declared = cast(context);
            for (String text : stringList(declared.get("statements"))) {
                if (statement == null && text.startsWith("replace_pattern(body, \"") && text.contains("13,19")) {
                    statement = text;
                }
            }
        }
        assertThat(statement).as("the card-number scrub of a log body").isNotNull();
        Pattern card = patternOf(statement);
        // A JSON-escaped body: "\n" before the digits is a backslash and a letter, which a word boundary alone would not see past.
        for (String number : List.of("4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "3782 822463 10005",
                "4111.1111.1111.1111", "3056 930902 5904")) {
            assertThat(card.matcher("card " + number + " end").find()).as(number).isTrue();
            assertThat(card.matcher("line\\n" + number + "\\t").find()).as("after an escape: " + number).isTrue();
        }
        assertThat(card.matcher("a run 41111111111111111111 of twenty").find()).as("twenty digits are no card number").isFalse();
        for (String identifier : List.of("01937a2c-1234-5678-9012-3456789abcde", "12345678-1234-1234-1234-123456789012",
                "019a7c3e-4123-8456-1234-bcde12345678")) {
            assertThat(card.matcher("correlation " + identifier).find()).as(identifier).isFalse();
        }

        String money = null;
        for (Object context : contexts) {
            Map<String, Object> declared = cast(context);
            for (String text : stringList(declared.get("statements"))) {
                if (money == null && text.startsWith("replace_pattern(body, \"") && text.contains("[A-Z]{3}")) {
                    money = text;
                }
            }
        }
        assertThat(money).as("the amount scrub of a log body").isNotNull();
        Pattern amount = patternOf(money);
        assertThat(amount.matcher("an amount 731.94 EUR").find()).isTrue();
        assertThat(amount.matcher("{\"message\":\"first\\n731.94 EUR\"}").find()).as("after an escaped newline").isTrue();
        assertThat(amount.matcher("{\"message\":\"x\\t12 USD\"}").find()).as("after an escaped tab").isTrue();
        assertThat(amount.matcher("version v2 EUR").find()).as("a word's digits are no amount").isFalse();
    }

    /** The pattern between a statement's first two quotes: a plain YAML scalar keeps its backslashes, OTTL reads each doubled one as one. */
    private static Pattern patternOf(String statement) {
        String written = statement.substring(statement.indexOf('"') + 1, statement.indexOf("\", \"$$1[scrubbed]\""));
        return Pattern.compile(written.replace("\\\\", "\\"));
    }

    @Test
    @DisplayName("each image is its pin, the weekly pin job reads each, and compose runs the pinned versions")
    void theImagesAreThePins() {
        String pins = read(PINS);
        assertThat(read(BASE + "collector-workload.yaml")).contains("opentelemetry-collector-contrib@" + pin(pins, "COLLECTOR_DIGEST"));
        String backends = read(KIND + "backends.yaml");
        assertThat(backends).contains("grafana/tempo@" + pin(pins, "TEMPO_DIGEST")).contains("grafana/loki@" + pin(pins, "LOKI_DIGEST"))
                .contains("prom/prometheus@" + pin(pins, "PROMETHEUS_DIGEST")).contains("prom/alertmanager@" + pin(pins, "ALERTMANAGER_DIGEST"));
        for (String tool : List.of("COLLECTOR", "TEMPO", "LOKI")) {
            assertThat(read("infra/scripts/check-pinned-images.sh")).contains("\"$" + tool + "_REPOSITORY\"").contains("\"$" + tool + "_DIGEST\"");
        }
        String compose = read("compose.yaml");
        assertThat(compose).contains("otel/opentelemetry-collector-contrib:" + pin(pins, "COLLECTOR_VERSION"))
                .contains("grafana/tempo:" + pin(pins, "TEMPO_VERSION")).contains("grafana/loki:" + pin(pins, "LOKI_VERSION"))
                .contains("./deploy/observability/base/collector.yaml:/etc/otelcol/config.yaml:ro");
    }

    @Test
    @DisplayName("the deployed scrape keeps job finapp, names its environment and is never env=\"local\", and finds every replica, ready or not, through a Service on the API port alone")
    void theDeployedScrapeKeepsTheContract() {
        Map<String, Object> prometheus = yaml(read(KIND + "prometheus.yml"));
        List<Object> jobs = cast(prometheus.get("scrape_configs"));
        Map<String, Object> finapp = jobs.stream().map(TelemetryPipelineManifestsTest::<Map<String, Object>>cast)
                .filter(job -> "finapp".equals(job.get("job_name"))).findFirst().orElseThrow();
        assertThat(finapp).containsEntry("metrics_path", "/actuator/prometheus");
        assertThat(read(KIND + "prometheus.yml")).contains("target_label: env\n        replacement: kind").doesNotContain("replacement: local");
        Map<String, Object> service = yaml(read("deploy/base/service-scrape.yaml"));
        assertThat(map(service, "spec")).containsEntry("clusterIP", "None").containsEntry("publishNotReadyAddresses", true);
        List<Object> ports = cast(map(service, "spec").get("ports"));
        assertThat(ports).hasSize(1);
        Map<String, Object> only = cast(ports.get(0));
        assertThat(only.get("port")).isEqualTo(8080);
        assertThat(read("deploy/base/kustomization.yaml")).contains("  - service-scrape.yaml");
    }

    @Test
    @DisplayName("kind's Prometheus loads every rule file from its own mount and sends to the Alertmanager beside it, which listens off the"
            + " broker's port and routes as committed; it reloads on a POST, on kind alone, from a ConfigMap of a fixed name; it scrapes the"
            + " back ends and cert-manager's controller, and its policy reaches that controller alone (P15-TSK-032)")
    @SuppressWarnings("unchecked")
    void kindRaisesAndRoutesTheAlerts() throws IOException {
        Map<String, Object> prometheus = yaml(read(KIND + "prometheus.yml"));
        assertThat(stringList(prometheus.get("rule_files"))).containsExactly("/etc/prometheus-rules/*.yml");
        Map<String, Object> manager = cast(((List<Object>) map(prometheus, "alerting").get("alertmanagers")).get(0));
        Map<String, Object> targets = cast(((List<Object>) manager.get("static_configs")).get(0));
        assertThat(stringList(targets.get("targets"))).containsExactly("finapp-alertmanager.finapp-observability.svc.cluster.local:9093");
        Map<String, String> jobs = new java.util.TreeMap<>();
        for (Object job : (List<Object>) prometheus.get("scrape_configs")) {
            Map<String, Object> scrape = cast(job);
            jobs.put(String.valueOf(scrape.get("job_name")), String.valueOf(scrape.get("static_configs")));
        }
        assertThat(jobs).containsKeys("finapp", "finapp-collector", "finapp-tempo", "finapp-loki", "cert-manager");
        assertThat(jobs.get("cert-manager")).contains("cert-manager.cert-manager.svc.cluster.local:9402");

        List<Map<String, Object>> backends = yamlAll(read(KIND + "backends.yaml"));
        Map<String, Object> scraper = pod(backends, "finapp-prometheus");
        Map<String, Object> container = cast(((List<Object>) scraper.get("containers")).get(0));
        assertThat(stringList(container.get("args"))).contains("--config.file=/etc/prometheus/prometheus.yml", "--web.enable-lifecycle");
        List<Map<String, Object>> mounts = (List<Map<String, Object>>) container.get("volumeMounts");
        assertThat(mounts).as("directories, never a subPath: an edited ConfigMap reaches the pod only so")
                .allSatisfy(mount -> assertThat(mount).doesNotContainKey("subPath"))
                .anySatisfy(mount -> assertThat(mount).containsEntry("name", "rules").containsEntry("mountPath", "/etc/prometheus-rules"))
                .anySatisfy(mount -> assertThat(mount).containsEntry("name", "config").containsEntry("mountPath", "/etc/prometheus"));
        assertThat(String.valueOf(scraper.get("volumes"))).contains("name=finapp-prometheus-rules").contains("name=finapp-prometheus-config");
        // The rules in two ConfigMaps (2026-11-13), one directory: the rules volume projects every rules ConfigMap the overlay generates, and
        // nothing else, so a ConfigMap generated and never mounted is no rule loaded.
        Map<String, Object> rules = ((List<Map<String, Object>>) scraper.get("volumes")).stream()
                .filter(volume -> "rules".equals(volume.get("name"))).findFirst().orElseThrow();
        List<String> projected = ((List<Object>) map(rules, "projected").get("sources")).stream()
                .map(source -> String.valueOf(map(cast(source), "configMap").get("name"))).toList();
        List<String> generated = ((List<Object>) yaml(read(KIND + "kustomization.yaml")).get("configMapGenerator")).stream()
                .map(generator -> String.valueOf(((Map<String, Object>) cast(generator)).get("name")))
                .filter(name -> name.startsWith("finapp-prometheus-rules")).toList();
        assertThat(projected).as("the rules volume projects every rules ConfigMap, once, and nothing else")
                .containsExactlyInAnyOrderElementsOf(generated).contains("finapp-prometheus-rules", "finapp-prometheus-rules-domain");
        assertThat(read(KIND + "kustomization.yaml")).contains("  - name: finapp-prometheus-config\n    files:\n      - prometheus.yml\n"
                + "    options:\n      disableNameSuffixHash: true\n");

        Map<String, Object> router = pod(backends, "finapp-alertmanager");
        Map<String, Object> alertmanager = cast(((List<Object>) router.get("containers")).get(0));
        assertThat(stringList(alertmanager.get("args"))).contains("--config.file=/etc/alertmanager/alertmanager.yml", "--web.listen-address=:9193",
                "--cluster.listen-address=");
        assertThat(String.valueOf(alertmanager.get("ports"))).contains("containerPort=9193").doesNotContain("9093");
        assertThat(String.valueOf(router.get("volumes"))).contains("name=finapp-alertmanager-config");
        Map<String, Object> service = backends.stream().filter(document -> "Service".equals(document.get("kind"))
                && "finapp-alertmanager".equals(map(document, "metadata").get("name"))).findFirst().orElseThrow();
        assertThat(String.valueOf(map(service, "spec").get("ports"))).contains("port=9093").contains("targetPort=http");

        // The lifecycle endpoints are unauthenticated: the flag is kind's, and no other manifest under deploy/ carries it.
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!relative.equals(KIND + "backends.yaml")) {
                    assertThat(read(relative).lines().filter(line -> !line.strip().startsWith("#"))).as(relative)
                            .noneMatch(line -> line.contains("enable-lifecycle"));
                }
            }
        }

        Map<String, Object> reach = policies.stream().filter(policy -> "finapp-scraper-to-backends".equals(map(policy, "metadata").get("name")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(map(reach, "spec").get("egress")))
                .contains("{namespaceSelector={matchLabels={kubernetes.io/metadata.name=cert-manager}}, podSelector={matchLabels="
                        + "{app.kubernetes.io/name=cert-manager, app.kubernetes.io/component=controller}}}")
                .contains("port=9402").contains("port=9193").doesNotContain("port=9093");
    }

    private static Map<String, Object> pod(List<Map<String, Object>> documents, String deployment) {
        Map<String, Object> found = documents.stream().filter(document -> "Deployment".equals(document.get("kind"))
                && deployment.equals(map(document, "metadata").get("name"))).findFirst().orElseThrow();
        return map(map(map(found, "spec"), "template"), "spec");
    }

    @Test
    @DisplayName("a person reads the pipeline's pods, their logs, Deployments and events, and break-glass here evicts a pod and nothing else,"
            + " each role bound to its own group, rendered by the pipeline's release and held by finapp.rego's rules (P15-TSK-032, item 42 (k))")
    void aPersonReadsHereAndBreakGlassEvicts() {
        Map<String, Map<String, Object>> roles = new java.util.TreeMap<>();
        Map<String, Map<String, Object>> bindings = new java.util.TreeMap<>();
        for (Map<String, Object> document : yamlAll(read(BASE + "rbac-humans.yaml"))) {
            Map<String, Object> metadata = map(document, "metadata");
            assertThat(metadata).as("stated, so the committed file says whose role it is").containsEntry("namespace", "finapp-observability");
            ("Role".equals(document.get("kind")) ? roles : bindings).put(String.valueOf(metadata.get("name")), document);
        }
        assertThat(roles.keySet()).containsExactly("finapp-break-glass", "finapp-read-only");
        assertThat(roles.get("finapp-read-only").get("rules")).isEqualTo(List.of(
                Map.of("apiGroups", List.of(""), "resources", List.of("pods", "pods/log", "events"), "verbs", List.of("get", "list", "watch")),
                Map.of("apiGroups", List.of("apps"), "resources", List.of("deployments"), "verbs", List.of("get", "list", "watch"))));
        assertThat(roles.get("finapp-break-glass").get("rules")).isEqualTo(List.of(
                Map.of("apiGroups", List.of(""), "resources", List.of("pods/eviction"), "verbs", List.of("create"))));
        assertThat(bindings.keySet()).containsExactly("finapp-break-glass", "finapp-read-only");
        for (Map.Entry<String, Map<String, Object>> binding : bindings.entrySet()) {
            assertThat(map(binding.getValue(), "roleRef")).containsEntry("kind", "Role").containsEntry("name", binding.getKey());
            assertThat(binding.getValue().get("subjects")).isEqualTo(List.of(
                    Map.of("apiGroup", "rbac.authorization.k8s.io", "kind", "Group", "name", binding.getKey())));
        }
        assertThat(read(BASE + "kustomization.yaml")).contains("  - rbac-humans.yaml");
        assertThat(read(POLICY)).contains("\"[rbac-\"}").contains("[obs-break-glass-evictions]").contains("[obs-person-reads]");
        // The eviction break-glass makes is admitted for an unready collector too, or both unready would refuse it with 429 for ever.
        Map<String, Object> budget = yamlAll(read(BASE + "collector-workload.yaml")).stream()
                .filter(document -> "PodDisruptionBudget".equals(document.get("kind"))).findFirst().orElseThrow();
        assertThat(map(budget, "spec")).containsEntry("maxUnavailable", 1).containsEntry("unhealthyPodEvictionPolicy", "AlwaysAllow");
        assertThat(read("infra/scripts/manifest-policy/finapp.rego")).contains("person_namespaces := {\"finapp\", \"finapp-observability\"}");
    }

    @Test
    @DisplayName("every policy id in observability.rego is refused by a plant, and every plant is refused: read from the two files, never a list here")
    void everyPolicyIsRefusedByAPlant() {
        Set<String> ruleIds = new TreeSet<>();
        Matcher rule = Pattern.compile("\\[(obs-[a-z-]+)\\]").matcher(read(POLICY));
        while (rule.find()) {
            ruleIds.add(rule.group(1));
        }
        assertThat(ruleIds).as("the policy file was read").hasSizeGreaterThanOrEqualTo(7);
        String script = read(SCRIPT);
        Set<String> refusedPlants = new TreeSet<>();
        Set<String> refusedIds = new TreeSet<>();
        Matcher refused = Pattern.compile("(?m)^obs_refused (\\S+) (\\S+)$").matcher(script);
        while (refused.find()) {
            refusedPlants.add(refused.group(1));
            refusedIds.add(refused.group(2));
        }
        // `obs_accepted` is a plant the policies must let through (the quota re-counted with a back end, P16-TSK-004): made, and answered for.
        Matcher accepted = Pattern.compile("(?m)^obs_accepted (\\S+)$").matcher(script);
        while (accepted.find()) {
            refusedPlants.add(accepted.group(1));
        }
        Set<String> planted = new TreeSet<>();
        Matcher made = Pattern.compile("(?m)^\\s+obs_plant(?:_on)? (obs-[a-z-]+)\\b").matcher(script);
        while (made.find()) {
            planted.add(made.group(1));
        }
        assertThat(refusedIds).as("every rule is refused by a plant").containsAll(ruleIds);
        refusedIds.removeAll(ruleIds);
        assertThat(refusedIds).as("the rest are finapp.rego's, reused")
                .containsExactlyInAnyOrder("security-token", "images-digest", "security-non-root", "probes-present", "rbac-human-namespace",
                        "rbac-human-secrets", "rbac-human-subresource");
        assertThat(refusedPlants).as("every plant is refused and every refusal has its plant").isEqualTo(planted);
    }

    @Test
    @DisplayName("finapp-observability's one quota is its worst moment, exactly: the base's the collector's alone, kind's patched to its four back ends, in pods, CPU and memory")
    void theNamespaceQuotaIsItsWorstMoment() {
        List<Map<String, Object>> base = new ArrayList<>(yamlAll(read(BASE + "collector-workload.yaml")));
        // The cluster watcher (P16-TSK-003), the base's second workload.
        base.addAll(yamlAll(read(BASE + "cluster-watch-workload.yaml")));
        List<Map<String, Object>> quotas = yamlAll(read(BASE + "resourcequota.yaml"));
        assertThat(quotas).as("exactly one quota, or the tightest decides").hasSize(1);
        assertThat(read(BASE + "kustomization.yaml")).containsPattern("(?m)^  - resourcequota\\.yaml$");
        Map<String, BigDecimal> stated = WorstMoment.stated(quotas.get(0));
        Map<String, BigDecimal> needed = WorstMoment.of(base);
        assertThat(needed.get("pods")).as("the collector, 2 x 2 + 1, and the watcher, 2 x 1 + 1").isEqualByComparingTo("8");
        needed.forEach((key, value) -> assertThat(stated.get(key)).as("the base's %s is the collector's worst moment", key).isEqualByComparingTo(value));

        // kind's overlay: the base, Alertmanager's ConfigMap alone, and the back ends; the quota replaced key by key, nothing else patched.
        Map<String, Object> kind = yaml(read(KIND + "kustomization.yaml"));
        assertThat(stringList(kind.get("resources"))).containsExactly("../../base", "../../alertmanager", "backends.yaml", "networkpolicy-backends.yaml");
        assertThat(read("deploy/observability/alertmanager/kustomization.yaml")).doesNotContain("resources:");
        List<Map<String, Object>> onKind = new ArrayList<>(base);
        onKind.addAll(yamlAll(read(KIND + "backends.yaml")));
        Map<String, BigDecimal> patched = new LinkedHashMap<>(stated);
        List<?> patches = (List<?>) kind.get("patches");
        assertThat(patches).as("the quota is the one thing kind patches").hasSize(1);
        Map<String, Object> patch = cast(patches.get(0));
        assertThat(map(patch, "target")).containsEntry("kind", "ResourceQuota").containsEntry("name", "finapp-observability");
        for (Map<String, Object> operation : operations(patch)) {
            assertThat(operation.get("op")).isEqualTo("replace");
            String key = String.valueOf(operation.get("path")).replace("/spec/hard/", "");
            assertThat(patched).as("a key the quota states").containsKey(key);
            patched.put(key, WorstMoment.quantity(operation.get("value")));
        }
        Map<String, BigDecimal> neededOnKind = WorstMoment.of(onKind);
        assertThat(neededOnKind.get("pods")).as("the collector's 5, the watcher's 3, and each back end's pod and one replacing it").isEqualByComparingTo("16");
        neededOnKind.forEach((key, value) -> assertThat(patched.get(key)).as("kind's %s is its worst moment", key).isEqualByComparingTo(value));
    }

    private static List<Map<String, Object>> operations(Map<String, Object> patch) {
        List<Map<String, Object>> operations = cast(new Yaml(new SafeConstructor(new LoaderOptions())).load(String.valueOf(patch.get("patch"))));
        assertThat(operations).as("the patch's operations were read").hasSize(5);
        return operations;
    }

    @Test
    @DisplayName("the check runs the collector with the committed configuration and requires its probe red; CI runs it ungated with no secret, and it is committed executable")
    void ciRunsTheCheck() throws IOException {
        String script = read(SCRIPT);
        assertThat(script).contains("cp \"$ROOT/deploy/observability/base/collector.yaml\"").contains("sed -e 's/transform\\/scrub, //'")
                .contains("-e 's/transform\\/scrub-platform, //'")
                .contains("THE PROBE").contains("the probe stayed green").contains("-strict").contains("--namespace observability");
        // The platform's own lines (P16-TSK-003): planted through 4319, none out, then the platform's net removed and the needles out.
        assertThat(script).contains("http://$1:4319/v1/logs").contains("the platform probe stayed green")
                .contains("needles exported from the platform's own lines");
        String workflow = read(".github/workflows/ci.yml");
        Matcher job = Pattern.compile("(?ms)^  manifests:\\R(.*?)(?=^  [a-z-]+:\\R|\\z)").matcher(workflow);
        assertThat(job.find()).isTrue();
        assertThat(job.group(1)).contains("./infra/scripts/telemetry-check.sh").doesNotContain("secrets.");
        assertThat(job.group(1).lines().map(String::trim)).noneMatch(line -> line.startsWith("if:"));
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            assertThat(Files.isExecutable(root.resolve(SCRIPT))).as("CI runs it by path").isTrue();
        }
        try (Stream<Path> files = Files.list(root.resolve("infra/scripts/kind-rehearsal"))) {
            assertThat(files.map(path -> path.getFileName().toString())).contains("telemetry.sh");
        }
        assertThat(read("infra/scripts/kind-rehearsal.sh")).contains(". \"$DIR/telemetry.sh\"").contains("step telemetry-trace")
                .contains("step telemetry-outage").contains("step telemetry-sweep").contains("step telemetry-scrape");
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** The keys the {@code keep_keys} statement of the first transform context {@code context} keeps. */
    private static Set<String> keptKeys(String config, String context) {
        int at = config.indexOf(context);
        assertThat(at).as("the collector's %s context", context.trim()).isPositive();
        Matcher keep = Pattern.compile("keep_keys\\(attributes, \\[([^\\]]*)\\]\\)").matcher(config);
        assertThat(keep.find(at)).as("a keep_keys in %s", context.trim()).isTrue();
        Set<String> keys = new TreeSet<>();
        Matcher key = Pattern.compile("\"([^\"]+)\"").matcher(keep.group(1));
        while (key.find()) {
            keys.add(key.group(1));
        }
        return keys;
    }

    private static String pin(String pins, String name) {
        Matcher value = Pattern.compile("(?m)^" + name + "=(\\S+)$").matcher(pins);
        assertThat(value.find()).as(name).isTrue();
        return value.group(1);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path));
    }

    private static Map<String, Object> yaml(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    private static List<Map<String, Object>> yamlAll(String text) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(text)) {
            documents.add(cast(document));
        }
        return documents;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent).as("key %s", key).containsKey(key);
        return cast(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
