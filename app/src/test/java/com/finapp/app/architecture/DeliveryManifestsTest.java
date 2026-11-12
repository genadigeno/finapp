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
import org.junit.jupiter.api.io.TempDir;
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

    @TempDir
    Path scratch;

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
                assertThat(spec.get("project")).as("%s: the cluster's objects in one project, the release and the edge in another", name)
                        .isEqualTo(entry.getKey().equals("finapp-admission") || entry.getKey().equals("finapp-namespaces") ? "finapp-cluster" : "finapp");
                Map<String, Object> source = map(spec, "source");
                assertThat(source).as(name).containsEntry("repoURL", REPOSITORY).containsEntry("targetRevision", "master")
                        .containsEntry("path", paths.get(entry.getKey())).containsOnlyKeys("repoURL", "targetRevision", "path");
                assertThat(root.resolve(paths.get(entry.getKey())).resolve("kustomization.yaml")).as(name).exists();
                assertThat(map(spec, "destination")).as(name).containsEntry("server", IN_CLUSTER)
                        .containsEntry("namespace", entry.getKey().equals("finapp-edge") ? "finapp-edge" : "finapp");
                Map<String, Object> policy = map(spec, "syncPolicy");
                assertThat(policy).as("%s: no retry - a failed migration is fixed forward, never run again by the controller", name)
                        .containsOnlyKeys("automated", "syncOptions", "retry");
                // Stated, because absent is not none: Argo CD 3.6.1 retries an automated sync that states no retry (P15-TSK-032's kind run).
                assertThat(map(policy, "retry")).as("%s: retry.limit 0, stated", name).containsExactly(Map.entry("limit", 0));
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
        Map<String, Object> component = documents(ORDER).get(0);
        assertThat(component).as("annotation transformers alone: a patch would re-serialise the objects the policies read")
                .doesNotContainKeys("patches", "patchesStrategicMerge", "patchesJson6902");
        for (Object transformer : (List<?>) component.get("transformers")) {
            Map<?, ?> entry = (Map<?, ?>) yaml().load(String.valueOf(transformer));
            assertThat(entry.get("kind")).isEqualTo("AnnotationsTransformer");
            Map<?, ?> annotations = (Map<?, ?>) entry.get("annotations");
            for (Object fieldSpec : (List<?>) entry.get("fieldSpecs")) {
                Map<?, ?> spec = (Map<?, ?>) fieldSpec;
                assertThat(spec.get("path")).as("the object's own annotations, never a pod template's, which would roll the fleet")
                        .isEqualTo("metadata/annotations");
                String kind = String.valueOf(spec.get("kind"));
                assertThat(waves).as("%s is placed once", kind).doesNotContainKey(kind);
                waves.put(kind, String.valueOf(annotations.get("argocd.argoproj.io/sync-wave")));
                if (kind.equals("Job")) {
                    migratorHook = String.valueOf(annotations.get("argocd.argoproj.io/hook"));
                    migratorDeletion = String.valueOf(annotations.get("argocd.argoproj.io/hook-delete-policy"));
                } else {
                    assertThat(annotations.containsKey("argocd.argoproj.io/hook")).as("%s is no hook", kind).isFalse();
                }
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
        Set<String> jobs = new TreeSet<>();
        for (String directory : List.of("deploy/base", "deploy/components/secret-delivery", "deploy/overlays/staging", "deploy/overlays/production")) {
            try (Stream<Path> files = Files.list(root.resolve(directory))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.equals("kustomization.yaml") && !name.equals("release.yaml")) {
                        for (Map<String, Object> document : documents(root.relativize(file).toString().replace('\\', '/'))) {
                            rendered.add(String.valueOf(document.get("kind")));
                            if ("Job".equals(document.get("kind"))) {
                                jobs.add(String.valueOf(map(document, "metadata").get("name")));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(waves.keySet()).as("every kind the release renders has its wave").containsAll(rendered);
        assertThat(jobs).as("every Job the release renders becomes a hook, so the migrator must be the only one").containsExactly("finapp-migrator");

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
    @DisplayName("two projects bound the Applications: the release and the edge to their kinds in two namespaces and nothing outside one, the cluster's objects to Namespaces and admission policies alone, never a Secret")
    void theProjectBoundsWhatTheApplicationsMayApply() {
        Map<String, Object> project = documents("deploy/delivery/base/project.yaml").get(0);
        assertThat(map(project, "metadata")).containsEntry("name", "finapp").containsEntry("namespace", "argocd");
        Map<String, Object> spec = map(project, "spec");
        assertThat(spec.get("sourceRepos")).isEqualTo(List.of(REPOSITORY));
        assertThat(spec.get("destinations")).isEqualTo(List.of(Map.of("server", IN_CLUSTER, "namespace", "finapp"),
                Map.of("server", IN_CLUSTER, "namespace", "finapp-edge")));
        assertThat(spec.get("clusterResourceWhitelist")).as("the release and the edge create nothing outside a namespace").isEqualTo(List.of());
        assertThat(spec).doesNotContainKeys("namespaceResourceBlacklist", "sourceNamespaces", "roles", "signatureKeys");

        Map<String, Object> cluster = documents("deploy/delivery/base/project-cluster.yaml").get(0);
        assertThat(map(cluster, "metadata")).containsEntry("name", "finapp-cluster").containsEntry("namespace", "argocd");
        Map<String, Object> clusterSpec = map(cluster, "spec");
        assertThat(clusterSpec.get("sourceRepos")).isEqualTo(List.of(REPOSITORY));
        assertThat(clusterSpec.get("destinations")).isEqualTo(List.of(Map.of("server", IN_CLUSTER, "namespace", "finapp")));
        assertThat(clusterSpec.get("clusterResourceWhitelist")).isEqualTo(List.of(Map.of("group", "", "kind", "Namespace"),
                Map.of("group", "policy.sigstore.dev", "kind", "ClusterImagePolicy")));
        assertThat(clusterSpec.get("namespaceResourceBlacklist")).as("nothing inside a namespace").isEqualTo(List.of(Map.of("group", "*", "kind", "*")));
        assertThat(clusterSpec).doesNotContainKeys("namespaceResourceWhitelist", "sourceNamespaces", "roles", "signatureKeys");
        Set<String> allowed = new TreeSet<>();
        for (Object entry : (List<?>) spec.get("namespaceResourceWhitelist")) {
            Map<?, ?> kind = (Map<?, ?>) entry;
            assertThat(new TreeSet<>(kind.keySet().stream().map(String::valueOf).toList())).as("a group and a kind, never a wildcard")
                    .containsExactly("group", "kind");
            assertThat(String.valueOf(kind.get("kind"))).doesNotContain("*").isNotEqualTo("Secret");
            allowed.add(String.valueOf(kind.get("kind")));
        }
        // Exactly what the release and the edge render, read from their files: a kind the project lists and nothing renders is a door
        // left open, and a kind rendered and not listed would be refused at the first sync.
        Set<String> rendered = new TreeSet<>(List.of("ConfigMap"));
        for (String directory : List.of("deploy/base", "deploy/components/secret-delivery", "deploy/overlays/staging", "deploy/overlays/production",
                "deploy/edge/base")) {
            try (Stream<Path> files = Files.list(root.resolve(directory))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.equals("kustomization.yaml") && !name.equals("release.yaml")) {
                        // A Kubernetes object has an apiVersion; the edge's route and controller files are configuration a generator reads.
                        documents(root.relativize(file).toString().replace('\\', '/')).stream()
                                .filter(document -> document != null && document.get("apiVersion") != null)
                                .forEach(document -> rendered.add(String.valueOf(document.get("kind"))));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(allowed).as("the project admits exactly the kinds the release and the edge render, a Secret never among them").isEqualTo(rendered);

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
    @DisplayName("the controller's configuration judges an Application and an admission policy healthy only on the positive fact, leaves the ExternalSecret to Argo CD's own check, and tracks by annotation")
    void theControllerJudgesWhatTheWavesWaitFor() {
        Map<String, Object> data = map(documents("deploy/delivery/controller/argocd-cm.yaml").get(0), "data");
        assertThat(data).containsEntry("application.resourceTrackingMethod", "annotation");
        String application = String.valueOf(data.get("resource.customizations.health.argoproj.io_Application"));
        assertThat(application).startsWith("hs = {}\nhs.status = \"Progressing\"")
                .contains("elseif obj.status.sync.status == \"Synced\" and obj.status.health.status == \"Healthy\" then\n    hs.status = \"Healthy\"");
        assertThat(application.split("hs.status = \"Healthy\"", -1)).as("one way to Healthy").hasSize(2);
        String policy = String.valueOf(data.get("resource.customizations.health.policy.sigstore.dev_ClusterImagePolicy"));
        assertThat(policy).startsWith("hs = {}\nhs.status = \"Progressing\"")
                .contains("obj.status.observedGeneration == obj.metadata.generation then")
                .contains("if condition.type == \"Ready\" then\n      if condition.status == \"True\" then\n        hs.status = \"Healthy\"");
        assertThat(policy.split("hs.status = \"Healthy\"", -1)).as("one way to Healthy").hasSize(2);
        assertThat(data).as("Argo CD's own ExternalSecret check is the one the release's wave reads; a second would be a second to keep right")
                .doesNotContainKey("resource.customizations.health.external-secrets.io_ExternalSecret");
    }

    @Test
    @DisplayName("each root renders exactly the two projects and its environment's four Applications, each in its project, through no transformer; a planted extra is refused")
    void theRootRendersExactlyTheProjectsAndTheApplications() throws IOException {
        for (String environment : ENVIRONMENTS) {
            assertThat(judgeRoot(root.resolve("deploy/delivery"), environment)).as(environment).isEmpty();
        }
        // The plants: an Application in `default`, a third project, and a transformer in the root's kustomization, each in a copy.
        String extra = "---\napiVersion: argoproj.io/v1alpha1\nkind: Application\nmetadata:\n  name: anything\n  namespace: argocd\nspec:\n"
                + "  project: default\n  source:\n    repoURL: https://example.invalid/any.git\n    path: .\n  destination:\n"
                + "    server: https://kubernetes.default.svc\n    namespace: kube-system\n";
        Path copy = plantedDelivery("application", "production/applications.yaml", extra);
        assertThat(judgeRoot(copy, "production")).anySatisfy(offence -> assertThat(offence).contains("Application/anything"));
        copy = plantedDelivery("project", "base/project.yaml", "---\napiVersion: argoproj.io/v1alpha1\nkind: AppProject\nmetadata:\n"
                + "  name: wide\n  namespace: argocd\nspec:\n  sourceRepos: ['*']\n");
        assertThat(judgeRoot(copy, "staging")).anySatisfy(offence -> assertThat(offence).contains("AppProject/wide"));
        copy = plantedDelivery("transformer", "production/kustomization.yaml", "patches:\n  - path: anything.yaml\n");
        assertThat(judgeRoot(copy, "production")).anySatisfy(offence -> assertThat(offence).contains("patches"));
    }

    /** What an environment's root renders, judged: exactly the two projects and the four Applications, and no kustomization but resources. */
    private static List<String> judgeRoot(Path delivery, String environment) throws IOException {
        List<String> offences = new ArrayList<>();
        Set<String> rendered = new TreeSet<>();
        List<Path> roots = List.of(delivery.resolve(environment), delivery.resolve("base"));
        for (Path directory : roots) {
            Map<String, Object> kustomization = documentsAt(directory.resolve("kustomization.yaml")).get(0);
            for (String key : kustomization.keySet()) {
                if (!List.of("apiVersion", "kind", "resources").contains(key)) {
                    offences.add(directory.getFileName() + "/kustomization.yaml holds " + key + ": the root renders its files as written, nothing more");
                }
            }
            for (Object resource : (List<?>) kustomization.get("resources")) {
                Path target = directory.resolve(String.valueOf(resource)).normalize();
                if (Files.isDirectory(target)) {
                    if (!target.equals(delivery.resolve("base").normalize())) {
                        offences.add(directory.getFileName() + " includes " + resource + ", a root other than the delivery's own base");
                    }
                    continue;
                }
                for (Map<String, Object> document : documentsAt(target)) {
                    Map<String, Object> metadata = map(document, "metadata");
                    String named = document.get("kind") + "/" + metadata.get("name");
                    if ("Application".equals(document.get("kind"))) {
                        named += "@" + map(document, "spec").get("project");
                    }
                    rendered.add(named);
                }
            }
        }
        Set<String> expected = new TreeSet<>(List.of("AppProject/finapp", "AppProject/finapp-cluster", "Application/finapp-admission@finapp-cluster",
                "Application/finapp-namespaces@finapp-cluster", "Application/finapp@finapp", "Application/finapp-edge@finapp"));
        for (String named : rendered) {
            if (!expected.contains(named)) {
                offences.add(environment + "'s root renders " + named + ", which is not one of the two projects or the four Applications");
            }
        }
        for (String named : expected) {
            if (!rendered.contains(named)) {
                offences.add(environment + "'s root does not render " + named);
            }
        }
        return offences;
    }

    /** A copy of deploy/delivery with text appended to one of its files. */
    private Path plantedDelivery(String name, String file, String appended) throws IOException {
        Path copy = Files.createTempDirectory(scratch, "delivery-" + name);
        try (Stream<Path> files = Files.walk(root.resolve("deploy/delivery"))) {
            for (Path source : files.toList()) {
                Path target = copy.resolve(root.resolve("deploy/delivery").relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        Path planted = copy.resolve(file);
        Files.writeString(planted, Files.readString(planted) + appended);
        return copy;
    }

    private static List<Map<String, Object>> documentsAt(Path file) throws IOException {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : yaml().loadAll(Files.readString(file))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> read = (Map<String, Object>) document;
            documents.add(read);
        }
        return documents;
    }

    // ------------------------------------------------------------------------------------------- what is committed

    @Test
    @DisplayName("each release record names the overlay's own two digests: the placeholders before the first promotion, and after it the record promote.sh writes")
    void theReleaseRecordIsTheOverlays() {
        for (String environment : ENVIRONMENTS) {
            Map<String, Object> record = documents("deploy/overlays/" + environment + "/release.yaml").get(0);
            Map<String, String> digests = new LinkedHashMap<>();
            for (Object image : (List<?>) documents("deploy/overlays/" + environment + "/kustomization.yaml").get(0).get("images")) {
                digests.put(String.valueOf(((Map<?, ?>) image).get("name")), String.valueOf(((Map<?, ?>) image).get("digest")));
            }
            assertThat(record.get("app")).as(environment).isEqualTo(digests.get("finapp-app"));
            assertThat(record.get("migrator")).as(environment).isEqualTo(digests.get("finapp-migrator"));
            if ("none".equals(record.get("release"))) {
                assertThat(record).as("%s before its first promotion", environment).containsEntry("revision", "none")
                        .containsOnlyKeys("release", "revision", "app", "migrator");
                assertThat(digests.values()).as(environment).containsOnly("sha256:" + "0".repeat(64));
            } else {
                assertThat(record).as("%s as promote.sh writes it", environment).containsOnlyKeys("release", "revision", "app", "migrator",
                        "migrator-revision");
                assertThat(String.valueOf(record.get("release"))).matches("[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?");
                assertThat(String.valueOf(record.get("revision"))).matches("[0-9a-f]{40}");
                assertThat(String.valueOf(record.get("migrator-revision"))).matches("[0-9a-f]{40}");
            }
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
