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
import java.util.TreeMap;
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
 * Signing and admission hold what the threat register counts as built ({@code P15-TSK-007}, ADR-0122 decision 3, C7;
 * {@code THREAT_MODEL.md} T5, T27 and T30).
 *
 * <p>{@code infra/scripts/admission-check.sh} proves the path on a real cluster: the two images signed and attested under a
 * key of the run's own, verified before anything deploys them, and the policy controller refusing an unsigned image, an image
 * signed by another key and the rest of its cases, each beside a control. That needs Docker and runs in CI's
 * {@code admission} job. What it reads is committed here, and this class holds the committed files on every build:
 *
 * <ul>
 *   <li>every image a workload under {@code deploy/} runs in a namespace that opts in is matched as intended - the two
 *       platform images by both platform policies, the edge's proxy by the one exception - read from every manifest and
 *       every overlay's image list, through a port of the controller's own glob matching that is itself tested here;
 *   <li>exactly one exception, and it is one digest: Traefik's pin, equal to the edge's own;
 *   <li>every policy enforces; the platform images need the signature and both attestations; nothing real is committed;
 *   <li>the controller refuses what no policy names and refuses when it cannot answer, at the pinned digest;
 *   <li>CI runs the check with no credential and no {@code id-token}; the script verifies before it deploys, keeps its keys
 *       outside the repository, and refuses every case beside a control; every tool is pinned and watched.
 * </ul>
 */
@Tag("architecture")
@DisplayName("signing and admission hold what the threat register says (P15-TSK-007)")
class ImageAdmissionTest {

    private static final String POLICIES = "deploy/admission/clusterimagepolicies.yaml";
    private static final String VALUES = "deploy/operators/policy-controller/values.yaml";
    private static final String NAMESPACES = "deploy/namespaces/namespaces.yaml";
    private static final String EDGE_CONTROLLERS = "deploy/edge/base/controllers.yaml";
    private static final String SCRIPT = "infra/scripts/admission-check.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String PIN_CHECK = "infra/scripts/check-pinned-images.sh";
    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String OPT_IN = "policy.sigstore.dev/include";
    private static final Set<String> OPTED_IN = Set.of("finapp", "finapp-edge");
    private static final Set<String> PLATFORM_POLICIES = Set.of("finapp-images-signed", "finapp-images-attested");
    private static final String EXCEPTION = "finapp-edge-traefik";
    private static final Set<String> WORKLOAD_KINDS = Set.of("Deployment", "Job", "StatefulSet", "DaemonSet", "ReplicaSet", "Pod", "CronJob");
    private static final String PROVENANCE = "https://slsa.dev/provenance/v1";
    private static final String SBOM = "https://cyclonedx.org/bom";

    private static Path root;
    private static Map<String, Map<String, Object>> policies;
    private static String pins;

    @BeforeAll
    static void readThePolicies() {
        root = EvidenceCitations.repositoryRoot();
        policies = new TreeMap<>();
        for (Map<String, Object> policy : documents(POLICIES)) {
            assertThat(policy.get("kind")).isEqualTo("ClusterImagePolicy");
            policies.put(String.valueOf(map(policy, "metadata").get("name")), policy);
        }
        assertThat(policies).as("the policies were read").containsOnlyKeys("finapp-images-signed", "finapp-images-attested", EXCEPTION);
        pins = EvidenceCitations.read(root.resolve(PINS));
    }

    // ------------------------------------------------------------------------------------------------- what is matched

    @Test
    @DisplayName("every image a workload under deploy/ runs in an opted-in namespace is matched as intended, read from every manifest and overlay")
    void everyImageThePlatformNamespacesRunIsMatched() {
        Map<String, Set<String>> matched = new TreeMap<>();
        Set<String> testOnly = new TreeSet<>();
        for (Workload workload : workloads()) {
            for (String image : workload.images()) {
                if (workload.testOnly()) {
                    testOnly.add(image);
                } else {
                    matched.put(image, matchingPolicies(image));
                }
            }
        }
        assertThat(matched).as("the workloads were read: the application, the migrator and the edge's two controllers, in every overlay")
                .hasSizeGreaterThanOrEqualTo(3);
        Map<String, Set<String>> wrong = new TreeMap<>();
        matched.forEach((image, by) -> {
            Set<String> intended = image.contains("/traefik@") || image.startsWith("traefik@") ? Set.of(EXCEPTION) : PLATFORM_POLICIES;
            if (!by.equals(intended)) {
                wrong.put(image, by);
            }
        });
        assertThat(wrong)
                .as("an image matched by no policy is refused (no-match-policy: deny), one matched by the wrong ones is let in by the"
                        + " wrong rule: the platform images must meet both platform policies, and Traefik only its exception")
                .isEmpty();
        assertThat(matched.keySet()).as("both platform images and the proxy are among them")
                .anySatisfy(image -> assertThat(image).contains("finapp-app@sha256:"))
                .anySatisfy(image -> assertThat(image).contains("finapp-migrator@sha256:"))
                .anySatisfy(image -> assertThat(image).contains("traefik@sha256:"));
        assertThat(testOnly).as("the kind overlay's test-only copies match no policy: a cluster enforcing admission would refuse them,"
                        + " so the kind rehearsal, which runs them, never installs the controller")
                .isNotEmpty()
                .allSatisfy(image -> assertThat(matchingPolicies(image)).as(image).isEmpty());
    }

    @Test
    @DisplayName("the glob port matches as the controller's own glob.go and go-containerregistry do")
    void theGlobPortIsTheControllers() {
        assertThat(normalised("finapp-app@sha256:" + "a".repeat(64))).isEqualTo("index.docker.io/library/finapp-app@sha256:" + "a".repeat(64));
        assertThat(normalised("docker.io/library/traefik:v3@sha256:" + "b".repeat(64)))
                .isEqualTo("index.docker.io/library/traefik@sha256:" + "b".repeat(64));
        assertThat(normalised("172.18.0.3:5000/finapp-app@sha256:" + "c".repeat(64))).isEqualTo("172.18.0.3:5000/finapp-app@sha256:" + "c".repeat(64));
        assertThat(normalised("curlimages/curl@sha256:" + "d".repeat(64))).isEqualTo("index.docker.io/curlimages/curl@sha256:" + "d".repeat(64));
        assertThat(globMatches("**/finapp-app@sha256:*", "172.18.0.3:5000/finapp-app@sha256:" + "c".repeat(64))).isTrue();
        assertThat(globMatches("**/finapp-app@sha256:*", "evil.example/x/finapp-app@sha256:" + "c".repeat(64)))
                .as("any registry: the signature decides, never the name").isTrue();
        assertThat(globMatches("**/finapp-app@sha256:*", "evil.example/finapp-app-evil@sha256:" + "c".repeat(64))).isFalse();
        assertThat(globMatches("**/finapp-app@sha256:*", "evil.example/finapp-app:latest")).isFalse();
        String traefik = "index.docker.io/library/traefik@sha256:" + "e".repeat(64);
        assertThat(globMatches(traefik, "docker.io/library/traefik@sha256:" + "e".repeat(64))).isTrue();
        assertThat(globMatches(traefik, "docker.io/library/traefik@sha256:" + "f".repeat(64))).isFalse();
    }

    // ------------------------------------------------------------------------------------------------- the exception

    @Test
    @DisplayName("exactly one exception, one digest: Traefik's pin, equal to the edge's own controllers, admitted by nothing but that digest")
    void exactlyOneExceptionAndItIsTheEdgesPinnedDigest() {
        List<String> admittedWithoutAuthority = new ArrayList<>();
        policies.forEach((name, policy) -> {
            for (Map<String, Object> authority : authorities(policy)) {
                if (authority.containsKey("static")) {
                    admittedWithoutAuthority.add(name);
                    assertThat(map(authority, "static")).as(name).containsEntry("action", "pass");
                } else {
                    assertThat(authority.containsKey("key") || authority.containsKey("keyless"))
                            .as("%s: every other authority is a key or an identity", name).isTrue();
                }
            }
        });
        assertThat(admittedWithoutAuthority).as("the exception list is one entry").containsExactly(EXCEPTION);
        Map<String, Object> exception = policies.get(EXCEPTION);
        assertThat(authorities(exception)).hasSize(1);
        List<String> globs = globs(exception);
        String digest = pin("TRAEFIK_DIGEST");
        assertThat(globs).as("one image, named by digest with no wildcard").containsExactly("index.docker.io/library/traefik@" + digest);
        assertThat(EvidenceCitations.read(root.resolve(EDGE_CONTROLLERS)))
                .as("the edge's controllers run the digest the exception names")
                .contains("image: docker.io/library/traefik@" + digest);
    }

    // ------------------------------------------------------------------------------------------------- the platform's policies

    @Test
    @DisplayName("every policy enforces, the platform images need the signature and both attestations, and the committed authority is a placeholder")
    void everyPolicyEnforcesAndThePlatformImagesNeedEverything() {
        policies.forEach((name, policy) -> assertThat(map(policy, "spec")).as("%s states its mode, enforce", name).containsEntry("mode", "enforce"));

        Map<String, Object> signed = policies.get("finapp-images-signed");
        Map<String, Object> attested = policies.get("finapp-images-attested");
        assertThat(globs(signed)).containsExactlyInAnyOrder("**/finapp-app@sha256:*", "**/finapp-migrator@sha256:*")
                .as("both platform policies match the same images, so both apply to each").isEqualTo(globs(attested));

        Map<String, Object> signer = single(authorities(signed));
        assertThat(signer).as("an authority that lists attestations checks no signature: the signature is this policy's alone")
                .doesNotContainKey("attestations");
        Map<String, Object> attester = single(authorities(attested));
        Set<String> types = new TreeSet<>();
        for (Object attestation : (List<?>) attester.get("attestations")) {
            types.add(String.valueOf(((Map<?, ?>) attestation).get("predicateType")));
        }
        assertThat(types).as("the provenance and the SBOM").containsExactlyInAnyOrder(PROVENANCE, SBOM);

        for (Map<String, Object> authority : List.of(signer, attester)) {
            assertThat(map(authority, "key")).as("nothing real is committed: a key no parser accepts, so the policy as committed admits nothing")
                    .containsOnlyKeys("data").containsEntry("data", "ADMISSION-AUTHORITY-PUBLIC-KEY");
            List<?> sources = (List<?>) authority.get("source");
            assertThat(sources).as("signatures read from their own repository").hasSize(1);
            assertThat(((Map<?, ?>) sources.get(0)).get("oci")).isEqualTo("ADMISSION-SIGNATURE-REPOSITORY");
        }
    }

    // ------------------------------------------------------------------------------------------------- the controller

    @Test
    @DisplayName("the controller refuses what no policy names and refuses when it cannot answer, two replicas at the pinned digest, watching the opted-in namespaces")
    void theControllerFailsClosed() {
        Map<String, Object> webhook = map(document(VALUES), "webhook");
        assertThat(webhook.get("failurePolicy")).isEqualTo("Fail");
        assertThat(map(webhook, "configData")).containsEntry("no-match-policy", "deny");
        assertThat((Integer) webhook.get("replicaCount")).isGreaterThanOrEqualTo(2);
        assertThat(map(webhook, "podDisruptionBudget")).containsEntry("enabled", true);
        assertThat(webhook).as("TUF is the kind check's to disable, on its own command line, never production's")
                .doesNotContainKey("extraArgs");
        Map<String, Object> image = map(webhook, "image");
        assertThat(image.get("repository")).isEqualTo(pin("POLICY_CONTROLLER_REPOSITORY"));
        assertThat(image.get("version")).isEqualTo(pin("POLICY_CONTROLLER_DIGEST"));
        Map<?, ?> expression = (Map<?, ?>) ((List<?>) map(webhook, "namespaceSelector").get("matchExpressions")).get(0);
        assertThat(expression.get("key")).isEqualTo(OPT_IN);
        assertThat(expression.get("operator")).isEqualTo("In");
        assertThat(String.valueOf(expression.get("values"))).isEqualTo("[true]");

        Map<String, String> optedIn = new TreeMap<>();
        for (Map<String, Object> namespace : documents(NAMESPACES)) {
            Map<String, Object> metadata = map(namespace, "metadata");
            Object label = map(metadata, "labels").get(OPT_IN);
            if (label != null) {
                optedIn.put(String.valueOf(metadata.get("name")), String.valueOf(label));
            }
        }
        assertThat(optedIn).as("the platform's namespaces, and only they, are watched").containsOnlyKeys(OPTED_IN)
                .allSatisfy((name, value) -> assertThat(value).isEqualTo("true"));
    }

    // ------------------------------------------------------------------------------------------------- CI and the check

    @Test
    @DisplayName("CI runs the check on kind with no credential and no id-token; the script verifies before it deploys and refuses every case beside a control")
    void ciProvesItOnKindWithNoCredential() {
        List<String> job = job("admission");
        String body = String.join("\n", job);
        assertThat(body).contains("./infra/scripts/admission-check.sh").contains("actions/upload-artifact")
                .doesNotContain("permissions:").doesNotContain("secrets.");
        assertThat(body).as("it runs where it pays, and whenever what it reads changes")
                .contains("deploy/").contains("infra/scripts/admission-check").contains("scanner-pins").contains("ci\\.yml");
        assertThat(EvidenceCitations.read(root.resolve(WORKFLOW)))
                .as("no job holds an OIDC token: keyless signing is not built, because no identity exists (THREAT_MODEL.md section 6 item 35)")
                .doesNotContain("id-token");

        String script = EvidenceCitations.read(root.resolve(SCRIPT));
        int verify = script.indexOf("step \"verify before deploy\"");
        int controller = script.indexOf("step \"the policy controller\"");
        int cases = script.indexOf("step \"the cases\"");
        assertThat(verify).as("cosign verify runs").isPositive();
        assertThat(verify).as("before the controller, the policies or any workload is applied").isLessThan(controller).isLessThan(cases);
        assertThat(script)
                .as("the keys are this run's, generated outside the repository and never uploaded to a log")
                .contains("WORK=$(mktemp -d)").contains("-v \"$(dpath \"$WORK/keys\"):/keys\"")
                .contains("generate-key-pair --output-key-prefix /keys/ci").contains("--tlog-upload=false")
                .contains("COSIGN_PASSWORD=$(od -An -N24 -tx1 /dev/urandom").contains("rm -rf \"$WORK\"")
                .doesNotContain("$ROOT/build/admission/ci.key").doesNotContain("cp \"$WORK/keys/ci.key\"");
        assertThat(script).as("every refusal sits beside its control, admitted outside the policy")
                .contains("attempt \"$1-control\" \"$4\"").contains("CONTROL=finapp-admission-control");
        for (String refusal : List.of("refused \"app unsigned\"", "refused \"migrator unsigned\"", "refused \"app signed by another key\"",
                "refused \"migrator signed by another key\"", "refused \"app signed, not attested\"", "refused \"init container unsigned\"",
                "refused \"an image no policy names\"", "refused \"edge running another image\"", "admitted \"app signed\"",
                "admitted \"migrator signed\"", "admitted \"init container signed\"", "admitted \"edge at Traefik's pinned digest\"",
                "fail \"controller down: ADMITTED while no webhook could answer, so admission fails open\"")) {
            assertThat(script).as("the case %s", refusal).contains(refusal);
        }
        for (String plant : List.of("refused_by_verify unsigned", "refused_by_verify other-key", "refused_by_verify unattested")) {
            assertThat(script).as("cosign's own refusal, %s", plant).contains(plant);
        }
        assertThat(script).as("the probes it carries")
                .contains("\"\" | warn | second-key | migrator-out | no-match-allow)");
        assertThat(script).as("the policies applied are the committed file, and the chart is rendered from the committed values")
                .contains("cp \"$ROOT/deploy/admission/clusterimagepolicies.yaml\"").contains("-f /values/values.yaml")
                .contains("--set webhook.extraArgs.disable-tuf=true").contains("--no-hooks");
    }

    @Test
    @DisplayName("cosign, the controller, its chart and the registry are pinned by digest, and the weekly pin job reads every page of their releases")
    void theToolsArePinnedAndWatched() {
        String pinCheck = EvidenceCitations.read(root.resolve(PIN_CHECK));
        for (String tool : List.of("COSIGN", "POLICY_CONTROLLER", "POLICY_CONTROLLER_CHART", "REGISTRY")) {
            assertThat(pins).containsPattern("(?m)^" + tool + "_REPOSITORY=\\S+$")
                    .containsPattern("(?m)^" + tool + "_VERSION=v?[0-9][0-9.]*$")
                    .containsPattern("(?m)^" + tool + "_DIGEST=sha256:[0-9a-f]{64}$");
            assertThat(pinCheck).contains("\"$" + tool + "_REPOSITORY\"").contains("\"$" + tool + "_DIGEST\"");
        }
        assertThat(pinCheck).as("ghcr answers a page at a time: the lister follows every next page")
                .contains("rel=\"next\"").contains("tags/list?n=1000");
    }

    // ------------------------------------------------------------------------------------------------- the port and the reading

    private record Workload(String file, boolean testOnly, List<String> images) {}

    /** Every workload in every overlay, its images after that overlay's image list, as Kustomize would render them. */
    private static List<Workload> workloads() {
        List<Workload> found = new ArrayList<>();
        Map<String, Map<String, String>> overlays = new LinkedHashMap<>();
        for (Path kustomization : files("deploy", "kustomization.yaml")) {
            Map<String, Object> read = documents(root.relativize(kustomization).toString().replace('\\', '/')).get(0);
            Map<String, String> digests = new TreeMap<>();
            Object images = read.get("images");
            if (images instanceof List<?> list) {
                for (Object entry : list) {
                    Map<?, ?> image = (Map<?, ?>) entry;
                    if (image.get("digest") != null) {
                        digests.put(String.valueOf(image.get("name")), String.valueOf(image.get("digest")));
                    }
                }
            }
            overlays.put(root.relativize(kustomization.getParent()).toString().replace('\\', '/'), digests);
        }
        for (Path file : files("deploy", ".yaml")) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (relative.endsWith("kustomization.yaml") || relative.startsWith("deploy/operators/") || relative.startsWith("deploy/observability/")) {
                continue;
            }
            for (Map<String, Object> document : documents(relative)) {
                if (document == null || !WORKLOAD_KINDS.contains(String.valueOf(document.get("kind")))) {
                    continue;
                }
                Map<String, Object> metadata = map(document, "metadata");
                Object labels = metadata.get("labels");
                boolean testOnly = labels instanceof Map<?, ?> map && "true".equals(String.valueOf(map.get("finapp/test-only")));
                List<String> raw = images(document);
                // A base workload's bare name is given its digest by each overlay that lists it; the overlays agree on the name.
                List<String> effective = new ArrayList<>();
                for (String image : raw) {
                    if (image.contains("@")) {
                        effective.add(image);
                        continue;
                    }
                    overlays.values().stream().filter(digests -> digests.containsKey(image))
                            .forEach(digests -> effective.add(image + "@" + digests.get(image)));
                    assertThat(effective).as("%s names %s, which no overlay pins by digest", relative, image)
                            .anySatisfy(each -> assertThat(each).startsWith(image + "@sha256:"));
                }
                found.add(new Workload(relative, testOnly, effective));
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static List<String> images(Map<String, Object> workload) {
        Object spec = workload.get("spec");
        if ("CronJob".equals(workload.get("kind"))) {
            spec = ((Map<String, Object>) ((Map<String, Object>) spec).get("jobTemplate")).get("spec");
        }
        Map<String, Object> podSpec = "Pod".equals(workload.get("kind"))
                ? (Map<String, Object>) spec
                : (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec).get("template")).get("spec");
        List<String> images = new ArrayList<>();
        for (String field : List.of("initContainers", "containers", "ephemeralContainers")) {
            Object containers = podSpec.get(field);
            if (containers instanceof List<?> list) {
                for (Object container : list) {
                    images.add(String.valueOf(((Map<?, ?>) container).get("image")));
                }
            }
        }
        return images;
    }

    private static Set<String> matchingPolicies(String image) {
        Set<String> matching = new TreeSet<>();
        policies.forEach((name, policy) -> {
            if (globs(policy).stream().anyMatch(glob -> globMatches(glob, image))) {
                matching.add(name);
            }
        });
        return matching;
    }

    /**
     * The controller's {@code glob.Match}: the glob compiled as {@code glob.Compile} does ({@code .} literal, {@code **} any
     * characters, {@code *} any but {@code /}, anchored), matched against the reference's full name, and against the reference as
     * written when that differs.
     */
    private static boolean globMatches(String glob, String image) {
        String expression = glob.replace(".", "\\.").replace("**", "#").replace("*", "[^/]*").replace("#", ".*");
        Pattern compiled = Pattern.compile("^" + expression + "$");
        String name = normalised(image);
        return compiled.matcher(name).matches() || (!name.equals(image) && compiled.matcher(image).matches());
    }

    /**
     * go-containerregistry's {@code Reference.Name()}: Docker Hub as {@code index.docker.io}, an official image under
     * {@code library/}, and for a digest reference the repository and the digest with any tag dropped.
     */
    private static String normalised(String image) {
        String digest = null;
        String rest = image;
        int at = image.indexOf('@');
        if (at >= 0) {
            digest = image.substring(at + 1);
            rest = image.substring(0, at);
        }
        String tag = null;
        int slash = rest.lastIndexOf('/');
        int colon = rest.lastIndexOf(':');
        if (colon > slash) {
            tag = rest.substring(colon + 1);
            rest = rest.substring(0, colon);
        }
        String registry = "index.docker.io";
        String repository = rest;
        int first = rest.indexOf('/');
        if (first >= 0) {
            String head = rest.substring(0, first);
            if (head.contains(".") || head.contains(":") || head.equals("localhost")) {
                registry = head.equals("docker.io") ? "index.docker.io" : head;
                repository = rest.substring(first + 1);
            }
        }
        if (registry.equals("index.docker.io") && !repository.contains("/")) {
            repository = "library/" + repository;
        }
        String name = registry + "/" + repository;
        return digest != null ? name + "@" + digest : name + ":" + (tag == null ? "latest" : tag);
    }

    private static List<String> globs(Map<String, Object> policy) {
        List<String> globs = new ArrayList<>();
        for (Object image : (List<?>) map(policy, "spec").get("images")) {
            globs.add(String.valueOf(((Map<?, ?>) image).get("glob")));
        }
        return globs;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> authorities(Map<String, Object> policy) {
        return (List<Map<String, Object>>) map(policy, "spec").get("authorities");
    }

    private static Map<String, Object> single(List<Map<String, Object>> authorities) {
        assertThat(authorities).as("one authority: any one of several would suffice").hasSize(1);
        return authorities.get(0);
    }

    private static String pin(String name) {
        Matcher matcher = Pattern.compile("(?m)^" + name + "=(\\S+)$").matcher(pins);
        assertThat(matcher.find()).as("%s in %s", name, PINS).isTrue();
        return matcher.group(1);
    }

    private static List<Path> files(String directory, String suffix) {
        try (Stream<Path> walk = Files.walk(root.resolve(directory))) {
            return walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            if (read != null) {
                documents.add(read);
            }
        }
        return documents;
    }

    private static Map<String, Object> document(String path) {
        List<Map<String, Object>> documents = documents(path);
        assertThat(documents).as(path).hasSize(1);
        return documents.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> from, String key) {
        Object value = from.get(key);
        assertThat(value).as("%s in %s", key, from.keySet()).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
