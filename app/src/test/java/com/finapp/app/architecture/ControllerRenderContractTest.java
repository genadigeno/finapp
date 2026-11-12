package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The controllers' own manifests are rendered and held to a rule (`P15-TSK-032`; `OPERATIONS_RUNBOOK.md` section 11.36): Argo CD's
 * install manifest, the External Secrets chart and (the kind rehearsal's metrics source) metrics-server's release manifest, each fetched at
 * its pinned bytes, rendered as a cluster receives them, and refused when they hold a cluster role outside a named list, a cluster binding
 * other than that role to the controller's own account, a binding to a role neither the stream nor a named list holds, Secrets outside the
 * namespaces named for the controller, an image not pinned by digest, or (Argo CD) the platform's configuration missing or the `default`
 * project open.
 *
 * <p>The check's own result is a container run, {@code infra/scripts/controller-check.sh}, recorded in {@code MUTATION_TESTING.md}. What
 * a hermetic test holds is that a later edit cannot quietly remove a rule, a plant, a pin or the check from CI.
 */
@Tag("architecture")
@DisplayName("the controllers' manifests are rendered at their pinned bytes and held to the named list (P15-TSK-032)")
class ControllerRenderContractTest {

    private static final String SCRIPT = "infra/scripts/controller-check.sh";
    private static final String POLICY = "infra/scripts/manifest-policy/controllers.rego";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String PIN_CHECK = "infra/scripts/check-pinned-images.sh";
    private static final String ROTATIONS = "infra/scripts/kind-rehearsal/rotations.sh";
    private static final String DEFAULT_PROJECT = "deploy/delivery/controller/default-project.yaml";
    private static final String WORKFLOW = ".github/workflows/ci.yml";

    private static Path root;
    private static String script;
    private static String policy;
    private static String pins;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        script = read(SCRIPT);
        policy = read(POLICY);
        pins = read(PINS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path));
    }

    @Test
    @DisplayName("every rule id in controllers.rego is refused by a plant, and every plant is refused: read from the two files, never a list here")
    void everyRuleIsRefusedByAPlant() {
        Set<String> ruleIds = new TreeSet<>();
        Matcher rule = Pattern.compile("\\[(controller-[a-z-]+)\\]").matcher(policy);
        while (rule.find()) {
            ruleIds.add(rule.group(1));
        }
        assertThat(ruleIds).as("the policy file was read").contains("controller-cluster-role", "controller-cluster-binding", "controller-secrets");

        Set<String> refusedIds = new TreeSet<>();
        Set<String> refusedPlants = new TreeSet<>();
        Matcher refused = Pattern.compile("(?m)^refused (\\S+) \"\\[(controller-[a-z-]+)\\]").matcher(script);
        while (refused.find()) {
            refusedPlants.add(refused.group(1));
            refusedIds.add(refused.group(2));
        }
        Set<String> planted = new TreeSet<>();
        Matcher made = Pattern.compile("(?m)^(?:plant|eso_values) ((?:eso|argocd|metrics)-[a-z-]+)\\b").matcher(script);
        while (made.find()) {
            planted.add(made.group(1));
        }
        assertThat(refusedIds).as("every rule of the policy is refused by a plant, and no refusal names a rule the policy lacks").isEqualTo(ruleIds);
        assertThat(refusedPlants).as("every plant is refused and every refusal has its plant").isEqualTo(planted);
        assertThat(script).as("a plant whose mutation found nothing is a failure, not a pass")
                .contains("was not planted").contains("was NOT refused").contains("but not for its reason");
    }

    @Test
    @DisplayName("each controller gets its own refusal of each kind: a cluster role, a cluster binding and Secrets outside its namespace, for Argo CD, the operator and metrics-server")
    void eachControllerIsRefusedOnEachRule() {
        for (String id : List.of("controller-cluster-role", "controller-cluster-binding", "controller-secrets", "controller-images", "controller-stream")) {
            for (String controller : List.of("eso", "argocd", "metrics")) {
                assertThat(script).as("%s has a plant refused by %s", controller, id).containsPattern("(?m)^refused " + controller + "-[a-z-]+ \"\\[" + id + "\\]");
            }
        }
        assertThat(script).as("the three ways to Secrets outside the namespace each refused by name: a Role there, a ClusterRole bound there, a ClusterRole not marked")
                .contains("[controller-secrets] Role/argocd-server reaches Secrets in namespace finapp")
                .contains("[controller-secrets] RoleBinding/argocd-applicationset-controller gives ClusterRole")
                .contains("[controller-secrets] ClusterRole/argocd-secret-reader reaches Secrets in every namespace");
        assertThat(script).as("the operator's own switches are planted: its rights made cluster-wide, its namespace moved")
                .contains("s/^scopedRBAC: true$/scopedRBAC: false/").contains("s/^scopedNamespace: finapp$/scopedNamespace: finapp-edge/");
        assertThat(script).as("a role bound in a namespace that the stream does not hold, and a binding to the cluster's own roles widened, each refused by name")
                .contains("refused eso-admin-in-finapp \"[controller-role-binding] RoleBinding/external-secrets-admin in finapp gives ClusterRole admin\"")
                .contains("refused metrics-reader-elsewhere \"[controller-role-binding] RoleBinding/metrics-server-auth-reader in kube-system gives ClusterRole admin\"")
                .contains("refused metrics-delegator-wider \"[controller-cluster-binding] ClusterRoleBinding/metrics-server:system:auth-delegator\"")
                .contains("refused metrics-aggregated \"[controller-aggregate] ClusterRole/system:aggregated-metrics-reader\"");
    }

    @Test
    @DisplayName("the named list is the decided one: the operator holds no cluster role and reaches Secrets in finapp alone; Argo CD's three cluster roles are named, each with why; metrics-server's one, no Secret, and its two bindings to the cluster's own roles")
    void theNamedListIsTheDecidedOne() {
        Matcher roles = Pattern.compile("(?ms)^cluster_roles := \\{(.*?)^\\}").matcher(policy);
        assertThat(roles.find()).as("the list of cluster roles").isTrue();
        assertThat(roles.group(1)).contains("\"external-secrets\": {},")
                .contains("\"argocd-application-controller\": {\"secrets\": true},")
                .contains("\"argocd-server\": {\"secrets\": true},")
                .contains("\"argocd-applicationset-controller\": {\"secrets\": true},")
                .contains("\"system:metrics-server\": {\"secrets\": false, \"account\": \"metrics-server\"},");
        assertThat(Pattern.compile("\"argocd-[a-z-]+\": \\{\"secrets\"").matcher(roles.group(1)).results().count())
                .as("Argo CD's list is the three its install renders, and no more").isEqualTo(3);
        assertThat(Pattern.compile("\\{\"secrets\"").matcher(roles.group(1)).results().count()).as("four cluster roles in all").isEqualTo(4);
        Matcher held = Pattern.compile("(?ms)^held_bindings := \\{(.*?)^\\}").matcher(policy);
        assertThat(held.find()).as("the list of bindings to the cluster's own roles").isTrue();
        assertThat(held.group(1)).contains("\"argocd\": {},").contains("\"external-secrets\": {},")
                .contains("\"metrics-server:system:auth-delegator\": {\"kind\": \"ClusterRoleBinding\", \"role\": {\"kind\": \"ClusterRole\", \"name\": \"system:auth-delegator\"}, \"account\": \"metrics-server\"},")
                .contains("\"metrics-server-auth-reader\": {\"kind\": \"RoleBinding\", \"namespace\": \"kube-system\", \"role\": {\"kind\": \"Role\", \"name\": \"extension-apiserver-authentication-reader\"}, \"account\": \"metrics-server\"},");
        assertThat(Pattern.compile("\"kind\": \"(?:Cluster)?RoleBinding\"").matcher(held.group(1)).results().count()).as("two, and no more").isEqualTo(2);
        Matcher namespaces = Pattern.compile("(?ms)^secret_namespaces := \\{(.*?)^\\}").matcher(policy);
        assertThat(namespaces.find()).isTrue();
        assertThat(namespaces.group(1)).contains("\"argocd\": {\"argocd\"},").contains("\"external-secrets\": {\"finapp\"},")
                .contains("\"metrics-server\": set(),");
        assertThat(policy).as("a binding on the list is its own role to the account the list names, one subject, in the controller's namespace")
                .contains("count(subjects) == 1").contains("subject.kind == \"ServiceAccount\"").contains("subject.name == account")
                .contains("ref.name == name_of(binding)").contains("only_subject_is(binding, account_of(name_of(binding)))")
                .contains("only_subject_is(binding, entry.account)");
        assertThat(policy).as("data missing a field is a controller no list knows, never an undefined that passes")
                .contains("} else := \"(none named)\"");
    }

    @Test
    @DisplayName("both controllers are fetched at their pinned bytes, their images rewritten to pinned digests, and the pins watched")
    void theControllersArePinned() {
        for (String pin : List.of("ARGOCD", "ARGOCD_DEX", "ARGOCD_REDIS")) {
            assertThat(pins).containsPattern("(?m)^" + pin + "_REPOSITORY=\\S+$").containsPattern("(?m)^" + pin + "_VERSION=\\S+$")
                    .containsPattern("(?m)^" + pin + "_DIGEST=sha256:[0-9a-f]{64}$");
            assertThat(read(PIN_CHECK)).as("the weekly pin job watches %s", pin).contains("\"$" + pin + "_REPOSITORY\"").contains("\"$" + pin + "_DIGEST\"");
        }
        assertThat(pins).containsPattern("(?m)^ARGOCD_INSTALL_SHA256=[0-9a-f]{64}$");
        Matcher chart = Pattern.compile("(?m)^ESO_CHART_SHA256=([0-9a-f]{64})$").matcher(pins);
        assertThat(chart.find()).as("ESO_CHART_SHA256 in the pins").isTrue();
        assertThat(read(ROTATIONS)).as("the rotation rehearsal pulls the same archive the render reads")
                .contains("ESO_CHART_SHA256=" + chart.group(1) + "\n");
        assertThat(script).contains("argo-cd/${ARGOCD_VERSION}/manifests/install.yaml")
                .contains("fetch \"$ARGOCD_INSTALL_URL\" \"$WORK/fetched/install.yaml\" \"$ARGOCD_INSTALL_SHA256\"")
                .contains("fetch \"$ESO_CHART_URL\" \"$WORK/fetched/external-secrets.tgz\" \"$ESO_CHART_SHA256\"")
                .contains("is not the pinned bytes");
        assertThat(script).as("Argo CD's images, every one, by digest in the render and in what the policy allows")
                .contains("    digest: $ARGOCD_DIGEST").contains("    digest: $ARGOCD_DEX_DIGEST").contains("    digest: $ARGOCD_REDIS_DIGEST")
                .contains("ARGOCD_IMAGES=\"${ARGOCD_REPOSITORY}@${ARGOCD_DIGEST} ${ARGOCD_DEX_REPOSITORY}@${ARGOCD_DEX_DIGEST} ${ARGOCD_REDIS_REPOSITORY}@${ARGOCD_REDIS_DIGEST}\"")
                .contains("ESO_IMAGES=\"${ESO_REPOSITORY}:${ESO_VERSION}@${ESO_DIGEST}\"");
        assertThat(pins).as("metrics-server, the kind rehearsal's metrics source, pinned by its manifest's bytes and its image's digest")
                .containsPattern("(?m)^METRICS_SERVER_REPOSITORY=\\S+$").containsPattern("(?m)^METRICS_SERVER_VERSION=\\S+$")
                .containsPattern("(?m)^METRICS_SERVER_DIGEST=sha256:[0-9a-f]{64}$").containsPattern("(?m)^METRICS_SERVER_MANIFEST_SHA256=[0-9a-f]{64}$");
        assertThat(read(PIN_CHECK)).contains("\"$METRICS_SERVER_REPOSITORY\"").contains("\"$METRICS_SERVER_DIGEST\"");
        assertThat(script).contains("fetch \"$METRICS_SERVER_URL\" \"$WORK/fetched/metrics-server.yaml\" \"$METRICS_SERVER_MANIFEST_SHA256\"")
                .contains("    digest: $METRICS_SERVER_DIGEST").contains("METRICS_SERVER_IMAGES=\"${METRICS_SERVER_REPOSITORY}@${METRICS_SERVER_DIGEST}\"")
                .contains("STREAMS=\"argocd external-secrets metrics-server\"");
        assertThat(script).as("its aggregated reader deleted from the render, the kubelets' certificates not verified: the kind rehearsal's two changes")
                .contains("      \\$patch: delete\n").contains("        name: system:aggregated-metrics-reader\n").contains("        value: --kubelet-insecure-tls\n");
    }

    @Test
    @DisplayName("what is rendered is what the repository commits: the argocd-cm keys, the restricted default project, the operator's values")
    void theRenderIsTheCommittedConfiguration() {
        assertThat(script).contains("cp \"$ROOT/deploy/delivery/controller/argocd-cm.yaml\"")
                .contains("cp \"$ROOT/deploy/delivery/controller/default-project.yaml\"")
                .contains("cp \"$ROOT/deploy/operators/external-secrets/values.yaml\"")
                .contains("  - default-project.yaml").contains("  - path: argocd-cm.yaml");
        assertThat(script).as("the render the kind rehearsal installs has passed its policy first, and no plant runs in that mode")
                .contains("if [ \"$MODE\" = render ]; then").contains("[ \"$status\" -eq 0 ] || exit 1");
        assertThat(policy).as("the rendered argocd-cm carries every key the waves wait on, tracking by annotation")
                .contains("\"application.resourceTrackingMethod\",").contains("\"resource.customizations.health.argoproj.io_Application\",")
                .contains("\"resource.customizations.health.policy.sigstore.dev_ClusterImagePolicy\",");
        assertThat(script).as("kubeconform strict, skipping the controllers' own definitions alone")
                .contains("-strict -summary -skip CustomResourceDefinition").doesNotContain("-ignore-missing-schemas");
    }

    @Test
    @DisplayName("the default project reaches nothing: no repository, no source namespace, no destination, every namespaced kind refused, no cluster kind")
    @SuppressWarnings("unchecked")
    void theDefaultProjectReachesNothing() {
        Map<String, Object> project = new Yaml(new SafeConstructor(new LoaderOptions())).load(read(DEFAULT_PROJECT));
        assertThat(project.get("kind")).isEqualTo("AppProject");
        Map<String, Object> metadata = (Map<String, Object>) project.get("metadata");
        assertThat(metadata).containsEntry("name", "default").containsEntry("namespace", "argocd");
        Map<String, Object> spec = (Map<String, Object>) project.get("spec");
        assertThat(spec.get("sourceRepos")).isEqualTo(List.of());
        assertThat(spec.get("sourceNamespaces")).isEqualTo(List.of());
        assertThat(spec.get("destinations")).isEqualTo(List.of());
        assertThat(spec.get("namespaceResourceBlacklist")).isEqualTo(List.of(Map.of("group", "*", "kind", "*")));
        assertThat(spec.get("clusterResourceWhitelist")).as("stated, empty, so applying it over an open default closes the field").isEqualTo(List.of());
        assertThat(spec).doesNotContainKey("namespaceResourceWhitelist");
        assertThat(policy).as("the policy refuses each field stated with anything but none, and the project's absence")
                .contains("\"sourceRepos\", \"sourceNamespaces\", \"destinations\", \"clusterResourceWhitelist\", \"namespaceResourceWhitelist\"")
                .contains("the stream holds no `default` AppProject");
    }

    @Test
    @DisplayName("CI runs the check on every change, with no secret and no condition, and the script is committed executable")
    void ciRunsTheCheck() throws Exception {
        Matcher job = Pattern.compile("(?ms)^  controllers:\\R(.*?)(?=^  [a-z-]+:\\R|\\z)").matcher(read(WORKFLOW));
        assertThat(job.find()).as("the controllers job").isTrue();
        assertThat(job.group(1)).contains("name: Controller manifests").contains("run: ./infra/scripts/controller-check.sh")
                .doesNotContain("secrets.").doesNotContain("permissions:");
        assertThat(job.group(1).lines().map(String::trim)).noneMatch(line -> line.startsWith("if:"));
        Process git = new ProcessBuilder("git", "ls-files", "-s", SCRIPT).directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }
}
