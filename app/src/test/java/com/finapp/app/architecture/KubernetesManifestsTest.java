package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.eventing.EventingBeans;
import com.finapp.app.eventing.InboxConsumers;
import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.platform.metrics.GaugeRefresher;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.catalina.core.StandardContext;
import org.apache.tomcat.util.net.NioEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The manifests hold what the code decided, and the check that holds them cannot pass by reading nothing (`P15-TSK-009`,
 * ADR-0120, {@code THREAT_MODEL.md} T4, T12 and T14).
 *
 * <p>Every rendered overlay goes through {@code infra/scripts/manifest-check.sh} in CI's {@code manifests} job: Kustomize,
 * {@code kubeconform -strict} and the policies, and then every policy over a planted violation. That needs Docker, so this
 * class holds, on every build, what those tools do not read, on the committed files themselves:
 *
 * <ul>
 *   <li>the shutdown budget the policy sums is the code's own numbers, read from the constants and from production's file;
 *   <li>the base manifests carry the contract, and each overlay's connection arithmetic holds against production's pools;
 *   <li>every policy id in the Rego has a plant, and every plant is refused, so "every policy" is proven against the file
 *       rather than a list kept beside it;
 *   <li>the tools are pinned, and CI runs the check ungated.
 * </ul>
 */
@Tag("architecture")
@DisplayName("the Kubernetes manifests hold what the code decided, and their check cannot pass empty (P15-TSK-009)")
class KubernetesManifestsTest {

    private static final String BUDGET = "infra/scripts/manifest-policy/shutdown-budget.yaml";
    private static final String POLICY = "infra/scripts/manifest-policy/finapp.rego";
    private static final String SCRIPT = "infra/scripts/manifest-check.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String PIN_CHECK = "infra/scripts/check-pinned-images.sh";
    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String CARRIED = "infra/trivy/accepted-misconfigurations.rego";
    private static final List<String> OVERLAYS = List.of("kind", "staging", "production");
    private static final String ZERO_DIGEST = "sha256:" + "0".repeat(64);

    private static Path root;
    private static Map<String, Object> application;
    private static Map<String, Object> deployment;
    private static Map<String, Object> podSpec;
    private static Map<String, Object> container;

    @BeforeAll
    static void readTheManifests() {
        root = EvidenceCitations.repositoryRoot();
        application = document("app/src/main/resources/application.yaml");
        deployment = document("deploy/base/deployment.yaml");
        podSpec = map(deployment, "spec", "template", "spec");
        container = only(podSpec, "containers");
    }

    // ---------------------------------------------------------------------------------------------- the grace

    @Test
    @DisplayName("the shutdown budget the policy sums is the code's own numbers, each read from where it is decided")
    void theShutdownBudgetIsTheCodes() {
        Map<String, Object> budget = map(document(BUDGET), "shutdownBudget");
        assertThat(budget).containsOnlyKeys("httpDrain", "schedules", "consumers", "managementServerStop", "managementServerExecutor",
                "apiServerStop", "apiServerExecutor", "gaugeRefresher", "telemetryFlush", "kafkaProducerClose", "writerPoolClose",
                "reportingPoolClose");

        assertThat(seconds(budget, "httpDrain")).as("spring.lifecycle.timeout-per-shutdown-phase")
                .isEqualTo(DurationStyle.detectAndParse(String.valueOf(value(application, "spring", "lifecycle", "timeout-per-shutdown-phase"))));
        assertThat(seconds(budget, "schedules")).isEqualTo(ScheduleDrain.BOUND.plus(ScheduleDrain.INTERRUPT_GRACE));
        assertThat(seconds(budget, "consumers")).isEqualTo(InboxConsumers.SETTLE_BOUND.plus(InboxConsumers.INTERRUPT_GRACE));
        assertThat(seconds(budget, "gaugeRefresher")).isEqualTo(GaugeRefresher.STOP_BOUND);
        assertThat(seconds(budget, "telemetryFlush")).as("the telemetry SDK's last export").isEqualTo(com.finapp.app.telemetry.TelemetryFlush.BOUND);
        assertThat(seconds(budget, "kafkaProducerClose")).as("the producer's close is finapp.kafka.ack-timeout").isEqualTo(kafkaAckTimeoutDefault());

        Duration tomcatUnload = Duration.ofMillis(new StandardContext().getUnloadDelay());
        assertThat(seconds(budget, "managementServerStop")).as("Tomcat's unload delay").isEqualTo(tomcatUnload);
        assertThat(seconds(budget, "apiServerStop")).as("Tomcat's unload delay").isEqualTo(tomcatUnload);
        Duration workerPool = Duration.ofMillis(new NioEndpoint().getExecutorTerminationTimeoutMillis());
        assertThat(seconds(budget, "managementServerExecutor")).as("Tomcat's wait for its worker pool").isEqualTo(workerPool);
        assertThat(seconds(budget, "apiServerExecutor")).as("Tomcat's wait for its worker pool").isEqualTo(workerPool);
        // HikariPool.shutdown's loop of ten seconds is a literal inside the library, with nothing to read it from.
        assertThat(seconds(budget, "writerPoolClose")).isEqualTo(Duration.ofSeconds(10));
        assertThat(seconds(budget, "reportingPoolClose")).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a rollout cannot lose capacity or stack terminating pods: 0 unavailable, one surge, minReadySeconds at least a grace that covers the stop")
    void theRolloutIsBoundedByTheGrace() {
        Map<String, Object> rolling = map(deployment, "spec", "strategy", "rollingUpdate");
        assertThat(value(deployment, "spec", "strategy", "type")).isEqualTo("RollingUpdate");
        assertThat(rolling).containsEntry("maxUnavailable", 0).containsEntry("maxSurge", 1);
        assertThat(map(deployment, "spec")).as("the HPA owns the count: a manifest stating one resets it").doesNotContainKey("replicas");

        int grace = number(podSpec, "terminationGracePeriodSeconds");
        assertThat(number(deployment, "spec", "minReadySeconds")).as("terminating pods never stack").isGreaterThanOrEqualTo(grace);

        Map<String, Object> budget = map(document(BUDGET), "shutdownBudget");
        int worstCase = number(container, "lifecycle", "preStop", "sleep", "seconds");
        for (String step : budget.keySet()) {
            worstCase += number(budget, step);
        }
        assertThat(grace).as("the preStop pause and every step's worst case").isGreaterThanOrEqualTo(worstCase);
        assertThat(map(container, "lifecycle", "preStop")).as("distroless has no shell and no sleep binary").containsOnlyKeys("sleep");
    }

    @Test
    @DisplayName("readiness can answer before its probe gives up: its timeout outlasts the pool's connection-timeout, read from production's file")
    void readinessOutlastsThePoolsWait() {
        long poolWait = Duration.ofMillis(number(application, "spring", "datasource", "hikari", "connection-timeout")).toSeconds();
        assertThat(number(container, "readinessProbe", "timeoutSeconds")).isGreaterThan((int) poolWait);
        assertThat(number(container, "readinessProbe", "periodSeconds")).as("no two probes overlap")
                .isGreaterThan(number(container, "readinessProbe", "timeoutSeconds"));
        assertThat(value(container, "readinessProbe", "httpGet", "path")).isEqualTo("/actuator/health/readiness");
        assertThat(value(container, "livenessProbe", "httpGet", "path")).isEqualTo("/actuator/health/liveness");
        assertThat(value(container, "startupProbe", "httpGet", "path")).isEqualTo("/actuator/health/liveness");
        assertThat(value(container, "readinessProbe", "httpGet", "port")).isEqualTo("management");
        assertThat(containerPort("management")).isEqualTo(number(application, "management", "server", "port"));
    }

    // ---------------------------------------------------------------------------------------------- the pod profile

    @Test
    @DisplayName("the application runs the restricted profile: non-root by number, read-only root, no capability, no escalation, seccomp, no token")
    void theApplicationRunsTheRestrictedProfile() {
        assertRestricted(podSpec, container);
        assertThat(number(podSpec, "securityContext", "runAsUser")).as("the image's numeric user").isEqualTo(65532);
        assertThat(((List<?>) value(container, "volumeMounts"))).as("the one writable path on the read-only root").hasSize(1);
        assertThat(value(container, "volumeMounts", 0, "mountPath")).isEqualTo("/tmp");
    }

    @Test
    @DisplayName("so does every other workload committed: the migrator Job and the test-only copies; neither ServiceAccount offers a token")
    void everyOtherWorkloadRunsItToo() {
        Map<String, Object> job = document("deploy/base/migrator-job.yaml");
        Map<String, Object> jobSpec = map(job, "spec", "template", "spec");
        assertRestricted(jobSpec, only(jobSpec, "containers"));
        assertThat(jobSpec).containsEntry("restartPolicy", "Never").containsEntry("serviceAccountName", "finapp-migrator");

        List<Map<String, Object>> statefulSets = new ArrayList<>();
        for (String file : List.of("deploy/overlays/kind/postgres.yaml", "deploy/overlays/kind/kafka.yaml")) {
            documents(file).stream().filter(doc -> "StatefulSet".equals(doc.get("kind"))).forEach(statefulSets::add);
        }
        assertThat(statefulSets).hasSize(2);
        for (Map<String, Object> statefulSet : statefulSets) {
            assertThat(map(statefulSet, "metadata", "labels")).containsEntry("finapp/test-only", "true");
            Map<String, Object> spec = map(statefulSet, "spec", "template", "spec");
            assertRestricted(spec, only(spec, "containers"));
        }

        assertThat(documents("deploy/base/serviceaccount.yaml")).hasSize(2)
                .allSatisfy(account -> assertThat(account).containsEntry("automountServiceAccountToken", false));
    }

    @Test
    @DisplayName("every container states requests and limits, its memory request equal to its limit")
    void requestsAndLimitsAreSet() {
        Map<String, Object> resources = map(container, "resources");
        assertThat(map(resources, "requests")).containsOnlyKeys("cpu", "memory");
        assertThat(map(resources, "limits")).containsOnlyKeys("cpu", "memory");
        assertThat(value(resources, "requests", "memory")).as("no overcommit of the JVM heap").isEqualTo(value(resources, "limits", "memory"));
    }

    @Test
    @DisplayName("the management address is the pod's own, from the downward API; the Service exposes 8080 and nothing else")
    void theManagementPortIsOnThePodAndOnNoService() {
        assertThat(value(container, "env", 0, "name")).isEqualTo("FINAPP_MANAGEMENT_ADDRESS");
        assertThat(value(container, "env", 0, "valueFrom", "fieldRef", "fieldPath")).isEqualTo("status.podIP");
        Map<String, Object> service = document("deploy/base/service.yaml");
        assertThat(value(service, "spec", "type")).isEqualTo("ClusterIP");
        assertThat((List<?>) value(service, "spec", "ports")).hasSize(1);
        assertThat(value(service, "spec", "ports", 0, "port")).isEqualTo(8080);
        assertThat(value(service, "spec", "ports", 0, "targetPort")).isEqualTo("http");
        assertThat(containerPort("http")).isEqualTo(8080);
    }

    // ---------------------------------------------------------------------------------------------- the budget

    @Test
    @DisplayName("every overlay's fleet fits: maxReplicas + 2 x maxSurge + 1 within its declared instances, and those instances' pools within its database")
    void everyOverlaysFleetFitsTheBudget() {
        int surge = number(deployment, "spec", "strategy", "rollingUpdate", "maxSurge");
        int writer = number(application, "spring", "datasource", "hikari", "maximum-pool-size");
        int reporting = placeholderDefault(String.valueOf(value(application, "finapp", "database", "reporting", "pool-size")));
        assertThat(writer + reporting).as("production's two pools per instance").isEqualTo(8);
        assertThat(value(application, "spring", "datasource", "hikari", "minimum-idle")).as("a fixed-size pool").isEqualTo(writer);

        for (String overlay : OVERLAYS) {
            String kustomization = "deploy/overlays/" + overlay + "/kustomization.yaml";
            String text = EvidenceCitations.read(root.resolve(kustomization));
            int instances = literal(text, "FINAPP_DB_INSTANCES");
            int serverMax = literal(text, "FINAPP_DB_SERVER_MAX_CONNECTIONS");
            int reserved = literal(text, "FINAPP_DB_RESERVED_CONNECTIONS");
            int maxReplicas = maxReplicas(text);
            assertThat(maxReplicas + 2 * surge + 1).as("%s: pods with open pools at the worst moment of a rollout", overlay)
                    .isLessThanOrEqualTo(instances);
            assertThat(instances * (writer + reporting)).as("%s: the fleet's connections", overlay)
                    .isLessThanOrEqualTo(serverMax - reserved);
        }
        Map<String, Object> hpa = document("deploy/base/horizontalpodautoscaler.yaml");
        assertThat(number(hpa, "spec", "minReplicas")).as("N is never 1 (ADR-0014)").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the kind overlay sets the budget to the formula exactly, and tells the test-only server the same max_connections")
    void kindMeasuresTheRuleNotAMargin() {
        String text = EvidenceCitations.read(root.resolve("deploy/overlays/kind/kustomization.yaml"));
        assertThat(literal(text, "FINAPP_DB_INSTANCES")).isEqualTo(maxReplicas(text) + 2 + 1);
        assertThat(EvidenceCitations.read(root.resolve("deploy/overlays/kind/postgres.yaml")))
                .contains("max_connections=" + literal(text, "FINAPP_DB_SERVER_MAX_CONNECTIONS"));
    }

    @Test
    @DisplayName("C3 decided (ADR-0134): kind admits ten replicas on its larger test-only server, its connections the formula exactly; production's ceiling stays seven, ten taking a larger declared server first")
    void kindAdmitsTenAndProductionKeepsSeven() {
        int surge = number(deployment, "spec", "strategy", "rollingUpdate", "maxSurge");
        int perInstance = number(application, "spring", "datasource", "hikari", "maximum-pool-size")
                + placeholderDefault(String.valueOf(value(application, "finapp", "database", "reporting", "pool-size")));
        String kind = EvidenceCitations.read(root.resolve("deploy/overlays/kind/kustomization.yaml"));
        assertThat(maxReplicas(kind)).as("kind's ceiling: the owner's ten").isEqualTo(10);
        int instances = literal(kind, "FINAPP_DB_INSTANCES");
        assertThat(instances).as("kind: maxReplicas + 2 x maxSurge + 1").isEqualTo(10 + 2 * surge + 1);
        assertThat(literal(kind, "FINAPP_DB_SERVER_MAX_CONNECTIONS")).as("kind's test-only server: instances x pool + reserved, the rule and no margin")
                .isEqualTo(instances * perInstance + literal(kind, "FINAPP_DB_RESERVED_CONNECTIONS"));
        assertThat(number(document("deploy/base/horizontalpodautoscaler.yaml"), "spec", "minReplicas")).as("the minimum every overlay keeps").isEqualTo(3);

        String production = EvidenceCitations.read(root.resolve("deploy/overlays/production/kustomization.yaml"));
        assertThat(maxReplicas(production)).as("production's ceiling stays the base's seven until the ten-instance runs and the calibration (item 56)")
                .isEqualTo(7);
        assertThat(literal(production, "FINAPP_DB_INSTANCES")).isEqualTo(7 + 2 * surge + 1);
        // What ten would need there, read from the same numbers: more than its declared server holds, which is why ten takes a larger one first.
        assertThat((10 + 2 * surge + 1) * perInstance).as("ten in production with today's declaration")
                .isGreaterThan(literal(production, "FINAPP_DB_SERVER_MAX_CONNECTIONS") - literal(production, "FINAPP_DB_RESERVED_CONNECTIONS"));
    }

    @Test
    @DisplayName("scale-down is one pod per period no shorter than the grace, and the disruption budget allows one")
    void scaleDownCannotStackTerminatingPods() {
        Map<String, Object> hpa = document("deploy/base/horizontalpodautoscaler.yaml");
        List<?> policies = (List<?>) value(hpa, "spec", "behavior", "scaleDown", "policies");
        assertThat(policies).hasSize(1);
        assertThat(value(hpa, "spec", "behavior", "scaleDown", "policies", 0, "type")).isEqualTo("Pods");
        assertThat(value(hpa, "spec", "behavior", "scaleDown", "policies", 0, "value")).isEqualTo(1);
        assertThat(number(hpa, "spec", "behavior", "scaleDown", "policies", 0, "periodSeconds"))
                .isGreaterThanOrEqualTo(number(podSpec, "terminationGracePeriodSeconds"));
        assertThat(value(document("deploy/base/poddisruptionbudget.yaml"), "spec", "maxUnavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("the migrator Job is the contract's: bounded, retried only for what a retry can help, its entrypoint its own")
    void theMigratorJobIsTheContracts() {
        Map<String, Object> job = document("deploy/base/migrator-job.yaml");
        Map<String, Object> spec = map(job, "spec");
        assertThat(number(spec, "backoffLimit")).isEqualTo(2);
        assertThat(number(spec, "activeDeadlineSeconds")).isEqualTo(900);
        assertThat(number(spec, "ttlSecondsAfterFinished")).isEqualTo(86400);
        assertThat(value(spec, "podFailurePolicy", "rules", 0, "action")).isEqualTo("FailJob");
        assertThat(String.valueOf(value(spec, "podFailurePolicy", "rules", 0, "onExitCodes", "values"))).isEqualTo("[10, 12]");
        Map<String, Object> migrator = only(map(spec, "template", "spec"), "containers");
        assertThat(migrator).doesNotContainKeys("command", "args");
        assertThat(value(migrator, "resources", "requests", "memory")).isEqualTo("512Mi");
        assertThat(value(migrator, "resources", "limits", "memory")).isEqualTo("512Mi");
        assertThat(value(migrator, "envFrom", 0, "secretRef", "name")).as("a secret only this Job reads").isEqualTo("finapp-migrator-secrets");
    }

    // ---------------------------------------------------------------------------------------------- nothing real

    @Test
    @DisplayName("nothing real is committed: every overlay's own images are placeholder digests with no registry, and no Secret or endpoint is written")
    void nothingRealIsCommitted() {
        for (String overlay : OVERLAYS) {
            Map<String, Object> kustomization = document("deploy/overlays/" + overlay + "/kustomization.yaml");
            List<?> images = (List<?>) kustomization.get("images");
            assertThat(images).hasSize(2);
            for (int index = 0; index < images.size(); index++) {
                Map<String, Object> image = map(kustomization, "images", index);
                assertThat(image).containsOnlyKeys("name", "digest").containsEntry("digest", ZERO_DIGEST);
            }
            assertThat(EvidenceCitations.read(root.resolve("deploy/overlays/" + overlay + "/kustomization.yaml")))
                    .doesNotContain("kind: Secret", "://", "secretGenerator", "newName");
        }
        assertThat(EvidenceCitations.read(root.resolve("deploy/base/kustomization.yaml"))).doesNotContain("://", "secretGenerator");
    }

    @Test
    @DisplayName("the test-only copies run compose.yaml's versions, each pinned by digest")
    void theTestOnlyCopiesRunComposesVersions() {
        String compose = EvidenceCitations.read(root.resolve("compose.yaml"));
        for (String[] pair : new String[][] {{"postgres", "postgres.yaml"}, {"apache/kafka", "kafka.yaml"}}) {
            Matcher composed = Pattern.compile("(?m)^\\s+image:\\s+" + Pattern.quote(pair[0]) + ":(\\S+)\\s*$").matcher(compose);
            assertThat(composed.find()).as("compose.yaml pins %s", pair[0]).isTrue();
            assertThat(EvidenceCitations.read(root.resolve("deploy/overlays/kind/" + pair[1])))
                    .containsPattern("image: " + Pattern.quote(pair[0] + ":" + composed.group(1)) + "@sha256:[0-9a-f]{64}");
        }
    }

    // ---------------------------------------------------------------------------------------------- the check itself

    @Test
    @DisplayName("every policy id in the Rego is refused by a plant, and every plant is refused: read from the two files, never a list kept here")
    void everyPolicyIsRefusedByAPlant() {
        String policy = EvidenceCitations.read(root.resolve(POLICY));
        String script = EvidenceCitations.read(root.resolve(SCRIPT));

        Set<String> ruleIds = new TreeSet<>();
        Matcher rule = Pattern.compile("\"\\[([a-z][a-z-]*)\\]").matcher(policy);
        while (rule.find()) {
            ruleIds.add(rule.group(1));
        }
        assertThat(ruleIds).as("the policy file was read").hasSizeGreaterThan(30);

        Set<String> planted = new TreeSet<>();
        Set<String> refusedPlants = new TreeSet<>();
        Set<String> refusedIds = new TreeSet<>();
        Matcher refused = Pattern.compile("(?m)^refused (\\S+) (\\S+)$").matcher(script);
        while (refused.find()) {
            refusedPlants.add(refused.group(1));
            refusedIds.add(refused.group(2));
        }
        Matcher saying = Pattern.compile("(?m)^refused_saying (\\S+) \"\\[([a-z][a-z-]*)\\] ").matcher(script);
        while (saying.find()) {
            refusedPlants.add(saying.group(1));
            refusedIds.add(saying.group(2));
        }
        // `accepted` is a plant the policies must let through (a controller's role, P15-TSK-010): made, and answered for. `trivy_refused` is a
        // plant the graded scan refuses (P16-TSK-004), whose check is Trivy's, not a rule of this file.
        Matcher other = Pattern.compile("(?m)^(?:schema_refused|render_refused|accepted|trivy_refused) (\\S+)\\b").matcher(script);
        while (other.find()) {
            refusedPlants.add(other.group(1));
        }
        Matcher made = Pattern.compile("(?m)^\\s+(?:plant(?:_on)?|human_rule|break_glass_rule) ([a-z][a-z-]*)\\b").matcher(script);
        while (made.find()) {
            planted.add(made.group(1));
        }
        assertThat(refusedIds).as("every rule the policy file can raise has a plant, and no plant names a rule it lacks").isEqualTo(ruleIds);
        assertThat(refusedPlants).as("every plant made is refused, and every refusal has its plant").isEqualTo(planted);
    }

    @Test
    @DisplayName("the check runs every overlay through kubeconform -strict and the policies against production's own file, and CI runs it ungated with no secret")
    void ciRunsTheCheck() {
        String script = EvidenceCitations.read(root.resolve(SCRIPT));
        assertThat(script).contains("OVERLAYS=\"kind staging production\"")
                .contains("-strict").contains("-schema-location").contains("--combine")
                .contains("application/application.yaml").contains("refuses \"$1\" \"[$2]\" conftest_on")
                .doesNotContain("-ignore-missing-schemas");
        assertThat(root.resolve("deploy/overlays").toFile().list()).containsExactlyInAnyOrderElementsOf(OVERLAYS);

        List<String> job = job("manifests");
        assertThat(job).anySatisfy(line -> assertThat(line).contains("./infra/scripts/manifest-check.sh"));
        assertThat(job).as("ungated: every change to a manifest meets it")
                .noneSatisfy(line -> assertThat(line.trim()).startsWith("if:"));
        assertThat(String.join("\n", job)).doesNotContain("secrets.");
    }

    @Test
    @DisplayName("trivy config grades every kustomize root at HIGH and CRITICAL, found by search, each read as Kubernetes, and refuses a privileged controller, the node's root mounted and a widened carry by the check's id")
    void trivyGradesEveryRenderedRoot() throws IOException {
        String script = EvidenceCitations.read(root.resolve(SCRIPT));
        assertThat(script)
                .as("the pinned image, the embedded checks alone, nothing sent, the bar of criterion 2")
                .contains("TRIVY_IMAGE=\"${TRIVY_IMAGE:-${TRIVY_REPOSITORY}@${TRIVY_DIGEST}}\"")
                .contains("docker run --rm --network none -v \"$VOLUME:/scratch:ro\" -v \"$DIR/trivy:/accepted:ro\" \"$TRIVY_IMAGE\"")
                .contains("config --skip-check-update --disable-telemetry --skip-version-check --quiet --severity HIGH,CRITICAL")
                .as("every root by search, a Component skipped, never vacuous")
                .contains("for file in $(find \"$1\" -name kustomization.yaml | sort); do")
                .contains("grep -q '^kind: Component$' \"$file\" && continue")
                .contains("render_roots /repo/deploy /scratch/trivy")
                .contains("[ -s \"$2/overlays-production.yaml\" ]")
                .as("every stream read as Kubernetes, every finding printed as read, then the verdict with the carry alone")
                .contains("grep -qxF -- \"read $root kubernetes\"")
                .contains("trivy_config /scratch/trivy --exit-code 0")
                .contains("trivy_config \"$1\" --exit-code 1 --ignore-policy /accepted/accepted-misconfigurations.rego")
                .contains("check \"trivy config: no HIGH or CRITICAL finding that is not carried\" trivy_clean /scratch/trivy")
                .as("the planted trees are read as the tree is")
                .contains("case $name in trivy-*) render_roots \"$directory/deploy\" \"$directory/roots\" ;; esac")
                .contains("refuses \"$1\" \"$2\" trivy_clean \"/scratch/plants/$1/roots\"");
        Map<String, String> refusedBy = new TreeMap<>();
        Matcher refused = Pattern.compile("(?m)^trivy_refused (\\S+) (KSV-[0-9]+)$").matcher(script);
        while (refused.find()) {
            refusedBy.put(refused.group(1), refused.group(2));
        }
        assertThat(refusedBy).as("each plant refused by Trivy's own check")
                .containsExactlyInAnyOrderEntriesOf(Map.of("trivy-privileged", "KSV-0017", "trivy-host-root", "KSV-0121", "trivy-carry-widened", "KSV-0109"));

        // "Every root" is the search's, not a list here: it finds each overlay this repository renders, and no overlay is a Component.
        List<String> roots;
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            roots = files.filter(file -> file.getFileName().toString().equals("kustomization.yaml"))
                    .filter(file -> !Pattern.compile("(?m)^kind: Component\\s*$").matcher(EvidenceCitations.read(file)).find())
                    .map(file -> root.resolve("deploy").relativize(file.getParent()).toString().replace('\\', '/'))
                    .toList();
        }
        assertThat(roots).as("every overlay of the release, the edge and the pipeline is a root the search renders")
                .contains("overlays/kind", "overlays/staging", "overlays/production", "edge/overlays/kind", "edge/overlays/staging",
                        "edge/overlays/production", "observability/overlays/kind", "delivery/staging", "delivery/production", "namespaces",
                        "admission");
        assertThat(script).contains("cp /scratch/rendered/delivery-committed.yaml /scratch/trivy/delivery-committed.yaml");
    }

    @Test
    @DisplayName("every finding the manifest scan carries names one finding, has a reason and an unexpired date, and is listed with that date in the register's owner item 48, and nothing else is")
    void everyCarriedMisconfigurationIsInTheRegister() {
        String carried = EvidenceCitations.read(root.resolve(CARRIED));
        String item = ownerItem(EvidenceCitations.read(root.resolve("docs/architecture/THREAT_MODEL.md")), 48);
        assertThat(carried).contains("package trivy").contains("default ignore := false")
                .as("an entry stops applying the day after its date").contains("time.now_ns() < time.add_date(time.parse_ns(\"2006-01-02\", entry.until), 0, 0, 1)")
                .contains("regex.match(entry.message, input.Message)").contains("input.ID == entry.id");

        Matcher entry = Pattern.compile("(?s)\\{\\s*\"id\": \"([^\"]+)\",\\s*\"object\": \"([^\"]+)\",\\s*\"message\": `([^`]+)`,\\s*\"until\": \"([0-9-]+)\",\\s*\"statement\": \"([^\"]+)\",\\s*}")
                .matcher(carried);
        Set<String> entries = new TreeSet<>();
        while (entry.find()) {
            String id = entry.group(1);
            String object = entry.group(2);
            assertThat(entry.group(3)).as("%s on %s: the whole message, anchored", id, object).startsWith("^").endsWith("$");
            assertThat(entry.group(5).strip()).as("%s on %s: a reason", id, object).hasSizeGreaterThan(40);
            assertThat(LocalDate.parse(entry.group(4))).as("%s on %s: unexpired", id, object).isAfterOrEqualTo(LocalDate.now(Clock.systemUTC()));
            assertThat(item).as("%s on %s is listed with its date", id, object)
                    .containsPattern("`" + Pattern.quote(id) + "` on `" + Pattern.quote(object) + "`[^\\n]*?until " + Pattern.quote(entry.group(4)));
            entries.add(id + " " + object);
        }
        assertThat(entries).as("the carry was read").isNotEmpty();
        Set<String> listed = new TreeSet<>();
        Matcher line = Pattern.compile("`(KSV-[0-9]+)` on `([^`]+)`").matcher(item);
        while (line.find()) {
            listed.add(line.group(1) + " " + line.group(2));
        }
        assertThat(listed).as("the register lists what the file carries, and nothing it does not").isEqualTo(entries);
    }

    /** The text of the register's numbered owner item, up to the next. */
    private static String ownerItem(String register, int number) {
        Matcher item = Pattern.compile("(?ms)^" + number + "\\.\\s.*?(?=^\\d+\\.\\s|^## |\\z)").matcher(register);
        assertThat(item.find()).as("owner item %s", number).isTrue();
        return item.group();
    }

    @Test
    @DisplayName("every tool is an image pinned by digest, its version checked by the weekly pin job, and the schemas are read at one commit")
    void theToolsArePinned() {
        String pins = EvidenceCitations.read(root.resolve(PINS));
        String pinCheck = EvidenceCitations.read(root.resolve(PIN_CHECK));
        for (String tool : List.of("KUSTOMIZE", "KUBECONFORM", "CONFTEST")) {
            assertThat(pins).containsPattern("(?m)^" + tool + "_REPOSITORY=\\S+$")
                    .containsPattern("(?m)^" + tool + "_VERSION=v[0-9.]+$")
                    .containsPattern("(?m)^" + tool + "_DIGEST=sha256:[0-9a-f]{64}$");
            assertThat(pinCheck).contains("\"$" + tool + "_REPOSITORY\"").contains("\"$" + tool + "_DIGEST\"");
        }
        assertThat(pins).containsPattern("(?m)^KUBERNETES_SCHEMAS_COMMIT=[0-9a-f]{40}$")
                .containsPattern("(?m)^KUBERNETES_VERSION=1\\.[0-9]+\\.[0-9]+$");
        assertThat(EvidenceCitations.read(root.resolve(SCRIPT))).contains("${KUBERNETES_SCHEMAS_COMMIT}").doesNotContain("kubernetes-json-schema/master");
    }

    @Test
    @DisplayName("the schemas the manifests are checked against are the clusters' own minor, and that minor has the preStop sleep action")
    void theSchemasAreTheClustersMinor() {
        Matcher pinned = Pattern.compile("(?m)^KUBERNETES_VERSION=(1\\.([0-9]+))\\.[0-9]+$").matcher(EvidenceCitations.read(root.resolve(PINS)));
        assertThat(pinned.find()).as("the pin was read").isTrue();
        assertThat(Integer.parseInt(pinned.group(2))).as("sleep is a lifecycle action from Kubernetes 1.30").isGreaterThanOrEqualTo(30);
        for (String environment : List.of("staging", "production")) {
            String main = EvidenceCitations.read(root.resolve("infra/terraform/environments/" + environment + "/main.tf"));
            assertThat(main).as("%s's cluster", environment)
                    .containsPattern("(?m)^\\s*kubernetes_version\\s*=\\s*\"" + Pattern.quote(pinned.group(1)) + "\"\\s*$");
        }
    }

    // ---------------------------------------------------------------------------------------------- reading

    private static void assertRestricted(Map<String, Object> pod, Map<String, Object> main) {
        assertThat(pod).containsEntry("automountServiceAccountToken", false);
        assertThat(map(pod, "securityContext")).containsEntry("runAsNonRoot", true);
        assertThat(number(pod, "securityContext", "runAsUser")).isPositive();
        assertThat(value(pod, "securityContext", "seccompProfile", "type")).isEqualTo("RuntimeDefault");
        assertThat(pod).doesNotContainKeys("hostNetwork", "hostPID", "hostIPC");
        Map<String, Object> context = map(main, "securityContext");
        assertThat(context).containsEntry("allowPrivilegeEscalation", false).containsEntry("privileged", false)
                .containsEntry("readOnlyRootFilesystem", true);
        assertThat(map(context, "capabilities")).containsOnlyKeys("drop");
        assertThat(String.valueOf(value(context, "capabilities", "drop"))).isEqualTo("[ALL]");
    }

    private static int containerPort(String name) {
        for (Object port : (List<?>) container.get("ports")) {
            Map<?, ?> declared = (Map<?, ?>) port;
            if (name.equals(declared.get("name"))) {
                return (Integer) declared.get("containerPort");
            }
        }
        throw new AssertionError("no port named " + name);
    }

    private static Duration seconds(Map<String, Object> budget, String key) {
        return Duration.ofSeconds(number(budget, key));
    }

    /** {@code finapp.kafka.ack-timeout}'s default, from the annotation on the parameter the producer is built with. */
    private static Duration kafkaAckTimeoutDefault() {
        Pattern ackTimeout = Pattern.compile("\\$\\{finapp\\.kafka\\.ack-timeout:(PT\\w+)}");
        for (Method method : EventingBeans.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                Value annotation = parameter.getAnnotation(Value.class);
                if (annotation != null) {
                    Matcher found = ackTimeout.matcher(annotation.value());
                    if (found.matches()) {
                        return Duration.parse(found.group(1));
                    }
                }
            }
        }
        throw new AssertionError("no parameter of EventingBeans reads finapp.kafka.ack-timeout");
    }

    /** {@code ${VARIABLE:n}}'s n: what a deployment that says nothing gets. */
    private static int placeholderDefault(String placeholder) {
        Matcher found = Pattern.compile(":(\\d+)}$").matcher(placeholder);
        assertThat(found.find()).as("%s is a placeholder with a default", placeholder).isTrue();
        return Integer.parseInt(found.group(1));
    }

    private static int literal(String kustomization, String name) {
        Matcher found = Pattern.compile("(?m)^\\s+- " + name + "=(\\d+)\\s*$").matcher(kustomization);
        assertThat(found.find()).as("%s is declared", name).isTrue();
        return Integer.parseInt(found.group(1));
    }

    /** The overlay's patch of {@code maxReplicas}, or the base's. */
    private static int maxReplicas(String kustomization) {
        Matcher patched = Pattern.compile("path: /spec/maxReplicas\\s+value: (\\d+)").matcher(kustomization);
        if (patched.find()) {
            return Integer.parseInt(patched.group(1));
        }
        return number(document("deploy/base/horizontalpodautoscaler.yaml"), "spec", "maxReplicas");
    }

    private static List<String> job(String name) {
        Pattern header = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
        List<String> lines = new ArrayList<>();
        boolean inJobs = false;
        boolean inside = false;
        for (String line : EvidenceCitations.read(root.resolve(WORKFLOW)).split("\\R", -1)) {
            if (line.equals("jobs:")) {
                inJobs = true;
                continue;
            }
            Matcher job = header.matcher(line);
            if (inJobs && job.matches()) {
                inside = job.group(1).equals(name);
                continue;
            }
            if (inside && !line.trim().startsWith("#")) {
                lines.add(line);
            }
        }
        assertThat(lines).as("the workflow's %s job", name).isNotEmpty();
        return lines;
    }

    private static List<Map<String, Object>> documents(String path) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : yaml.loadAll(EvidenceCitations.read(root.resolve(path)))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> read = (Map<String, Object>) document;
            documents.add(read);
        }
        return documents;
    }

    private static Map<String, Object> document(String path) {
        List<Map<String, Object>> documents = documents(path);
        assertThat(documents).as(path).hasSize(1);
        return documents.get(0);
    }

    private static Map<String, Object> only(Map<String, Object> spec, String key) {
        List<?> entries = (List<?>) spec.get(key);
        assertThat(entries).as(key).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) entries.get(0);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> from, Object... path) {
        return (Map<String, Object>) value(from, path);
    }

    private static int number(Map<String, Object> from, Object... path) {
        return (Integer) value(from, path);
    }

    private static Object value(Map<String, Object> from, Object... path) {
        Object current = from;
        for (Object step : path) {
            assertThat(current).as("%s, at %s", Arrays.toString(path), step).isNotNull();
            current = step instanceof Integer index ? ((List<?>) current).get(index) : ((Map<?, ?>) current).get(step);
        }
        assertThat(current).as("%s", Arrays.toString(path)).isNotNull();
        return current;
    }
}
