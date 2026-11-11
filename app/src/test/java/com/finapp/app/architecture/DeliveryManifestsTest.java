package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The GitOps delivery as committed (`P15-TSK-017`, ADR-0122 decision 4, C8; {@code OPERATIONS_RUNBOOK.md} section 11.36): each
 * environment's Applications, their waves, the release's own order, the project that bounds them and the controller configuration
 * the waves depend on. CI's manifest check validates every one of these files against Argo CD's own schemas; this holds what a schema
 * cannot. It holds the committed declaration, and nothing here has run on a controller: what Argo CD does with it is read from its
 * documentation, and the rehearsal of a promotion through a controller is recorded in the runbook when it runs.
 */
@Tag("architecture")
@DisplayName("the delivery syncs each environment in the rehearsed order, bounded by its project, with no CreateNamespace (P15-TSK-017)")
class DeliveryManifestsTest {

    private static final String REPOSITORY = "https://github.com/genadigeno/finapp.git";
    private static final String IN_CLUSTER = "https://kubernetes.default.svc";
    private static final List<String> ENVIRONMENTS = List.of("staging", "production");
    private static final String ORDER = "deploy/components/delivery-order/kustomization.yaml";
    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";

    private static Path root;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
    }

    // ------------------------------------------------------------------------------------------- the Applications

    @Test
    @DisplayName("each environment has four Applications from this repository's master, each syncing by itself and healing drift, none retried, none with a finalizer")
    void everyApplicationSyncsByItselfAndHealsDrift() {
        for (String environment : ENVIRONMENTS) {
            Map<String, Map<String, Object>> applications = applications("deploy/delivery/" + environment + "/applications.yaml");
            assertThat(applications.keySet()).containsExactly("finapp-admission", "finapp-namespaces", "finapp", "finapp-edge");
            Map<String, String> paths = Map.of("finapp-admission", "deploy/admission", "finapp-namespaces", "deploy/namespaces",
                    "finapp", "deploy/overlays/" + environment, "finapp-edge", "deploy/edge/overlays/" + environment);
            for (Map.Entry<String, Map<String, Object>> entry : applications.entrySet()) {
                String name = environment + "/" + entry.getKey();
                Map<String, Object> application = entry.getValue();
                Map<String, Object> spec = map(application, "spec");
                assertThat(spec.get("project")).as(name).isEqualTo("finapp");
                Map<String, Object> source = map(spec, "source");
                assertThat(source).as(name).containsEntry("repoURL", REPOSITORY).containsEntry("targetRevision", "master")
                        .containsEntry("path", paths.get(entry.getKey())).containsOnlyKeys("repoURL", "targetRevision", "path");
                assertThat(root.resolve(paths.get(entry.getKey())).resolve("kustomization.yaml")).as(name).exists();
                assertThat(map(spec, "destination")).as(name).containsEntry("server", IN_CLUSTER)
                        .containsEntry("namespace", entry.getKey().equals("finapp-edge") ? "finapp-edge" : "finapp");
                Map<String, Object> policy = map(spec, "syncPolicy");
                assertThat(policy).as("%s: no retry - a failed migration is fixed forward, never run again by the controller", name)
                        .containsOnlyKeys("automated", "syncOptions");
                Map<String, Object> automated = map(policy, "automated");
                assertThat(automated).as(name).containsEntry("selfHeal", true)
                        .containsEntry("prune", !entry.getKey().equals("finapp-namespaces"));
                assertThat(map(application, "metadata")).as("%s: deleting an Application must leave what it applied running", name)
                        .doesNotContainKey("finalizers");
            }
        }
    }

    @Test
    @DisplayName("nothing in the delivery creates a namespace: no CreateNamespace and no managed namespace metadata, anywhere under deploy/")
    void noApplicationCreatesANamespace() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".yaml")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.strip().startsWith("#") && (line.contains("CreateNamespace") || line.contains("managedNamespaceMetadata"))) {
                        found.add(root.relativize(file) + ": " + line.strip());
                    }
                }
            }
        }
        assertThat(found).as("CreateNamespace would create finapp with neither its Pod Security nor its admission labels").isEmpty();
        for (String environment : ENVIRONMENTS) {
            for (Map<String, Object> application : applications("deploy/delivery/" + environment + "/applications.yaml").values()) {
                Object options = map(map(application, "spec"), "syncPolicy").get("syncOptions");
                assertThat(String.valueOf(options)).doesNotContain("CreateNamespace");
            }
        }
    }

    @Test
    @DisplayName("the root syncs the four in the install order: the admission policies, then the namespaces, then the release, then the edge")
    void theApplicationsAreInTheInstallOrder() {
        for (String environment : ENVIRONMENTS) {
            Map<String, Integer> waves = new LinkedHashMap<>();
            applications("deploy/delivery/" + environment + "/applications.yaml")
                    .forEach((name, application) -> waves.put(name, Integer.parseInt(String.valueOf(map(map(application, "metadata"), "annotations")
                            .get("argocd.argoproj.io/sync-wave")))));
            assertThat(waves.get("finapp-admission")).isLessThan(waves.get("finapp-namespaces"));
            assertThat(waves.get("finapp-namespaces")).isLessThan(waves.get("finapp"));
            assertThat(waves.get("finapp")).isLessThan(waves.get("finapp-edge"));

            List<Map<String, Object>> roots = documents("deploy/delivery/roots/" + environment + ".yaml");
            Map<String, Object> rootApplication = roots.stream().filter(document -> "Application".equals(document.get("kind"))).findFirst().orElseThrow();
            assertThat(map(map(rootApplication, "spec"), "source")).containsEntry("path", "deploy/delivery/" + environment)
                    .containsEntry("repoURL", REPOSITORY);
            assertThat(map(rootApplication, "spec").get("project")).isEqualTo("finapp-delivery");
            assertThat(documents("deploy/delivery/" + environment + "/kustomization.yaml").get(0).get("resources").toString())
                    .isEqualTo("[../base, applications.yaml]");
        }
    }

    // ------------------------------------------------------------------------------------------- the release's own order

    @Test
    @DisplayName("the release is applied in the rehearsal's order: what its pods need, the ExternalSecrets, the migrator awaited as a Sync hook, then the rollout")
    void theReleaseIsOrderedAsTheRehearsalRunsIt() {
        Map<String, String> waves = new LinkedHashMap<>();
        String migratorHook = null;
        String migratorDeletion = null;
        for (Object patch : (List<?>) documents(ORDER).get(0).get("patches")) {
            Map<?, ?> entry = (Map<?, ?>) patch;
            String kinds = String.valueOf(((Map<?, ?>) entry.get("target")).get("kind"));
            Map<?, ?> annotations = (Map<?, ?>) ((Map<?, ?>) yaml().load(String.valueOf(entry.get("patch")))).get("metadata");
            annotations = (Map<?, ?>) annotations.get("annotations");
            for (String kind : kinds.replaceAll("^\\(|\\)$", "").split("\\|")) {
                waves.put(kind, String.valueOf(annotations.get("argocd.argoproj.io/sync-wave")));
            }
            if (kinds.equals("Job")) {
                migratorHook = String.valueOf(annotations.get("argocd.argoproj.io/hook"));
                migratorDeletion = String.valueOf(annotations.get("argocd.argoproj.io/hook-delete-policy"));
            }
        }
        assertThat(migratorHook).as("a Sync hook in its wave, never PreSync, which runs before everything the migrator needs").isEqualTo("Sync");
        assertThat(migratorDeletion).as("one Job per release, its immutable template replaced by name").isEqualTo("BeforeHookCreation");

        int migrator = Integer.parseInt(waves.get("Job"));
        for (String foundation : List.of("ServiceAccount", "Role", "RoleBinding", "NetworkPolicy", "ResourceQuota", "LimitRange", "SecretStore",
                "Service", "PodDisruptionBudget")) {
            assertThat(Integer.parseInt(waves.get(foundation))).as("%s before the ExternalSecrets and the migrator", foundation)
                    .isLessThan(Integer.parseInt(waves.get("ExternalSecret")));
        }
        assertThat(Integer.parseInt(waves.get("ExternalSecret"))).as("the Secrets exist before the migrator reads one").isLessThan(migrator);
        for (String rollout : List.of("ConfigMap", "Deployment", "HorizontalPodAutoscaler")) {
            assertThat(Integer.parseInt(waves.get(rollout))).as("%s after the migrator has succeeded", rollout).isGreaterThan(migrator);
        }

        // Every kind the two overlays render is placed explicitly: a new kind would otherwise land in wave 0, after the migrator.
        Set<String> rendered = new TreeSet<>(List.of("ConfigMap"));
        for (String directory : List.of("deploy/base", "deploy/components/secret-delivery", "deploy/overlays/staging", "deploy/overlays/production")) {
            try (Stream<Path> files = Files.list(root.resolve(directory))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.equals("kustomization.yaml") && !name.equals("release.yaml")) {
                        documents(root.relativize(file).toString().replace('\\', '/')).forEach(document -> rendered.add(String.valueOf(document.get("kind"))));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(waves.keySet()).as("every kind the release renders has its wave").containsAll(rendered);

        // The rehearsal, which ran and was recorded (OPERATIONS_RUNBOOK.md section 11.28), applies in that order by script.
        String harness = EvidenceCitations.read(root.resolve(HARNESS));
        int foundation = harness.indexOf("all.yaml | Deployment.finapp-app.yaml | HorizontalPodAutoscaler.finapp-app.yaml | Job.finapp-migrator.yaml) ;;");
        int awaited = harness.indexOf("wait_job finapp finapp-migrator 900 migrator-nminus1");
        int rolled = harness.indexOf("apply_files \"$A/Deployment.finapp-app.yaml\" \"$A/HorizontalPodAutoscaler.finapp-app.yaml\"");
        assertThat(foundation).as("the rehearsal applies everything but the Job, the Deployment and its autoscaler first").isPositive();
        assertThat(awaited).as("then the migrator, awaited").isGreaterThan(foundation);
        assertThat(rolled).as("then the rollout").isGreaterThan(awaited);
    }

    @Test
    @DisplayName("both GitOps overlays take the order, and the kind overlay, which the rehearsal applies by script, does not")
    void theGitOpsOverlaysTakeTheOrder() {
        for (String environment : ENVIRONMENTS) {
            assertThat(String.valueOf(documents("deploy/overlays/" + environment + "/kustomization.yaml").get(0).get("components")))
                    .contains("../../components/delivery-order");
        }
        assertThat(EvidenceCitations.read(root.resolve("deploy/overlays/kind/kustomization.yaml"))).doesNotContain("delivery-order");
        assertThat(EvidenceCitations.read(root.resolve("deploy/base/migrator-job.yaml"))).doesNotContain("argocd.argoproj.io");
    }

    // ------------------------------------------------------------------------------------------- what bounds them

    @Test
    @DisplayName("the project bounds every Application: one repository, two namespaces, only Namespaces and admission policies outside them, never a Secret")
    void theProjectBoundsWhatTheApplicationsMayApply() {
        Map<String, Object> project = documents("deploy/delivery/base/project.yaml").get(0);
        assertThat(map(project, "metadata")).containsEntry("name", "finapp").containsEntry("namespace", "argocd");
        Map<String, Object> spec = map(project, "spec");
        assertThat(spec.get("sourceRepos")).isEqualTo(List.of(REPOSITORY));
        assertThat(spec.get("destinations")).isEqualTo(List.of(Map.of("server", IN_CLUSTER, "namespace", "finapp"),
                Map.of("server", IN_CLUSTER, "namespace", "finapp-edge")));
        assertThat(spec.get("clusterResourceWhitelist")).isEqualTo(List.of(Map.of("group", "", "kind", "Namespace"),
                Map.of("group", "policy.sigstore.dev", "kind", "ClusterImagePolicy")));
        assertThat(spec.get("namespaceResourceBlacklist")).isEqualTo(List.of(Map.of("group", "", "kind", "Secret")));
        assertThat(spec).doesNotContainKeys("namespaceResourceWhitelist", "sourceNamespaces", "roles", "signatureKeys");

        for (String environment : ENVIRONMENTS) {
            Map<String, Object> delivery = documents("deploy/delivery/roots/" + environment + ".yaml").stream()
                    .filter(document -> "AppProject".equals(document.get("kind"))).findFirst().orElseThrow();
            Map<String, Object> rootSpec = map(delivery, "spec");
            assertThat(rootSpec.get("clusterResourceWhitelist")).as("the root reaches nothing outside a namespace").isEqualTo(List.of());
            assertThat(rootSpec.get("destinations")).isEqualTo(List.of(Map.of("server", IN_CLUSTER, "namespace", "argocd")));
            assertThat(rootSpec.get("namespaceResourceWhitelist")).isEqualTo(List.of(Map.of("group", "argoproj.io", "kind", "Application"),
                    Map.of("group", "argoproj.io", "kind", "AppProject")));
        }
    }

    @Test
    @DisplayName("the controller's configuration judges an Application and an ExternalSecret healthy only on the positive fact, and tracks by annotation")
    void theControllerJudgesWhatTheWavesWaitFor() {
        Map<String, Object> data = map(documents("deploy/delivery/controller/argocd-cm.yaml").get(0), "data");
        assertThat(data).containsEntry("application.resourceTrackingMethod", "annotation");
        String application = String.valueOf(data.get("resource.customizations.health.argoproj.io_Application"));
        assertThat(application).startsWith("hs = {}\nhs.status = \"Progressing\"")
                .contains("elseif obj.status.sync.status == \"Synced\" and obj.status.health.status == \"Healthy\" then\n    hs.status = \"Healthy\"");
        assertThat(application.split("hs.status = \"Healthy\"", -1)).as("one way to Healthy").hasSize(2);
        String externalSecret = String.valueOf(data.get("resource.customizations.health.external-secrets.io_ExternalSecret"));
        assertThat(externalSecret).startsWith("hs = {}\nhs.status = \"Progressing\"")
                .contains("if condition.type == \"Ready\" then\n      if condition.status == \"True\" then\n        hs.status = \"Healthy\"");
        assertThat(externalSecret.split("hs.status = \"Healthy\"", -1)).as("one way to Healthy").hasSize(2);
    }

    // ------------------------------------------------------------------------------------------- what is committed

    @Test
    @DisplayName("each release record names the overlay's own two digests, and both are still the placeholders: nothing has been promoted")
    void theReleaseRecordIsTheOverlays() {
        for (String environment : ENVIRONMENTS) {
            Map<String, Object> record = documents("deploy/overlays/" + environment + "/release.yaml").get(0);
            Map<String, String> digests = new LinkedHashMap<>();
            for (Object image : (List<?>) documents("deploy/overlays/" + environment + "/kustomization.yaml").get(0).get("images")) {
                digests.put(String.valueOf(((Map<?, ?>) image).get("name")), String.valueOf(((Map<?, ?>) image).get("digest")));
            }
            assertThat(record.get("app")).as(environment).isEqualTo(digests.get("finapp-app"));
            assertThat(record.get("migrator")).as(environment).isEqualTo(digests.get("finapp-migrator"));
            assertThat(record).as(environment).containsEntry("release", "none").containsEntry("revision", "none").containsOnlyKeys("release",
                    "revision", "app", "migrator");
        }
    }

    @Test
    @DisplayName("nothing real is committed in the delivery: no account, region, registry or role, and the one address is this repository's")
    void nothingRealIsCommittedInTheDelivery() throws IOException {
        Pattern address = Pattern.compile("https?://[^\\s\"']+");
        try (Stream<Path> files = Files.walk(root.resolve("deploy/delivery"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(file);
                assertThat(text).as(file.toString()).doesNotContainPattern("arn:aws").doesNotContainPattern("[0-9]{12}")
                        .doesNotContain("amazonaws.com").doesNotContain("dkr.ecr").doesNotContainPattern("\\b(us|eu|ap|sa|ca|me|af)-[a-z]+-[0-9]\\b");
                Matcher found = address.matcher(text);
                while (found.find()) {
                    assertThat(found.group()).as(file.toString()).isIn(REPOSITORY, IN_CLUSTER);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- reading

    private static Map<String, Map<String, Object>> applications(String path) {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (Map<String, Object> document : documents(path)) {
            assertThat(document.get("kind")).as(path).isEqualTo("Application");
            found.put(String.valueOf(map(document, "metadata").get("name")), document);
        }
        return found;
    }

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static List<Map<String, Object>> documents(String path) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : yaml().loadAll(EvidenceCitations.read(root.resolve(path)))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> read = (Map<String, Object>) document;
            documents.add(read);
        }
        assertThat(documents).as(path).isNotEmpty();
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
