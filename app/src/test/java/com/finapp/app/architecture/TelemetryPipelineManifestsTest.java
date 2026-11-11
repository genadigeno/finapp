package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.telemetry.TraceAttributes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(stringList(map(pipelines, "traces/sampling").get("processors"))).contains("tail_sampling");
        assertThat(config).contains("name: errors").contains("name: slow").contains("name: writes").contains("name: asynchronous-legs");
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
                .contains("prom/prometheus@" + pin(pins, "PROMETHEUS_DIGEST"));
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
        Set<String> planted = new TreeSet<>();
        Matcher made = Pattern.compile("(?m)^\\s+obs_plant(?:_on)? (obs-[a-z-]+)\\b").matcher(script);
        while (made.find()) {
            planted.add(made.group(1));
        }
        assertThat(refusedIds).as("every rule is refused by a plant").containsAll(ruleIds);
        refusedIds.removeAll(ruleIds);
        assertThat(refusedIds).as("the rest are finapp.rego's, reused")
                .containsExactlyInAnyOrder("security-token", "images-digest", "security-non-root", "probes-present");
        assertThat(refusedPlants).as("every plant is refused and every refusal has its plant").isEqualTo(planted);
    }

    @Test
    @DisplayName("the check runs the collector with the committed configuration and requires its probe red; CI runs it ungated with no secret, and it is committed executable")
    void ciRunsTheCheck() throws IOException {
        String script = read(SCRIPT);
        assertThat(script).contains("cp \"$ROOT/deploy/observability/base/collector.yaml\"").contains("sed 's/transform\\/scrub, //'")
                .contains("THE PROBE").contains("the probe stayed green").contains("-strict").contains("--namespace observability");
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
