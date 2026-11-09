package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
 * What a compromised pod can reach and what a person can do, read from the committed manifests on every build
 * (`P15-TSK-010`, {@code PHASE_15_PLAN.md} section 6, {@code THREAT_MODEL.md} T2, T4, T6, T14, T15, T22, T28, T29, T33
 * and T36).
 *
 * <p>Each overlay is resolved here the way Kustomize would: its resources followed through the base, and its JSON patches
 * applied, so a number an overlay patches is the number read. CI's {@code manifest-check.sh} holds the same properties on
 * Kustomize's own output, each rule proven by a planted violation.
 *
 * <p><b>This proves what a cluster would be told, not what one does.</b> A NetworkPolicy is enforced by a network plugin and
 * a quota by admission: neither has run here. The rehearsal on {@code kind} owes those.
 */
@Tag("architecture")
@DisplayName("what a compromised pod can reach and what a person can do, on the committed manifests (P15-TSK-010)")
class NamespaceSecurityManifestsTest {

    private static final List<String> OVERLAYS = List.of("kind", "staging", "production");
    private static final Set<String> REAL_OVERLAYS = Set.of("staging", "production");
    private static final String APP = "finapp-app";
    private static final String MIGRATOR = "finapp-migrator";
    private static final String NAME_LABEL = "app.kubernetes.io/name";
    private static final String NAMESPACE_LABEL = "kubernetes.io/metadata.name";
    private static final Set<String> READ_VERBS = Set.of("get", "list", "watch");
    private static final Set<String> DOCUMENTATION_RANGES = Set.of("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24");
    private static final Set<String> WAYS_INSIDE = Set.of("pods/exec", "pods/attach", "pods/portforward", "pods/ephemeralcontainers",
            "pods/proxy", "services/proxy", "nodes/proxy", "serviceaccounts/token");

    private static Path root;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
    }

    // ---------------------------------------------------------------------------------------------- default-deny

    @Test
    @DisplayName("every overlay renders into finapp behind a policy selecting every pod, in both directions, with no rule")
    void everyPodSitsBehindADefaultDenyInBothDirections() {
        for (String overlay : OVERLAYS) {
            assertThat(document("deploy/overlays/" + overlay + "/kustomization.yaml")).as(overlay).containsEntry("namespace", "finapp");
            List<Map<String, Object>> denies = ofKind(rendered(overlay), "NetworkPolicy").stream()
                    .filter(policy -> map(policy, "spec", "podSelector").isEmpty()).toList();
            assertThat(denies).as("%s: the empty selector", overlay).hasSize(1);
            Map<String, Object> spec = map(denies.get(0), "spec");
            assertThat(strings(spec.get("policyTypes"))).as("%s: both directions", overlay).containsExactlyInAnyOrder("Ingress", "Egress");
            assertThat(spec).as("%s: no rule, so nothing is admitted", overlay).doesNotContainKeys("ingress", "egress");
        }
    }

    // ---------------------------------------------------------------------------------------------- reaching the app

    @Test
    @DisplayName("8081 is admitted from one label in the monitoring namespace, 8080 from the ingress controllers, and nothing else reaches the application; no rule names the kubelet")
    void theManagementPortAdmitsTheScraperAlone() {
        for (String overlay : OVERLAYS) {
            Set<String> admitted = new TreeSet<>();
            for (Map<String, Object> rule : rules(rendered(overlay), APP, "ingress")) {
                for (Map<String, Object> peer : list(rule, "from")) {
                    for (Map<String, Object> port : list(rule, "ports")) {
                        admitted.add(protocolAndPort(port) + " from " + describe(peer));
                    }
                }
            }
            assertThat(admitted).as(overlay).containsExactlyInAnyOrder(
                    "TCP/8080 from finapp-edge finapp/role=ingress-controller",
                    "TCP/8081 from finapp-observability finapp/role=metrics-scraper");
        }
    }

    // ---------------------------------------------------------------------------------------------- what it reaches

    @Test
    @DisplayName("the application's egress is the plan's list and no more: the resolver on UDP and TCP, the collector, PostgreSQL, Kafka and the provider list")
    void theApplicationReachesOnlyItsListedFlows() {
        for (String overlay : OVERLAYS) {
            String postgresql = REAL_OVERLAYS.contains(overlay) ? "TCP/5432 to 192.0.2.0/24" : "TCP/5432 to local finapp-postgres";
            String kafka = REAL_OVERLAYS.contains(overlay) ? "TCP/9096 to 198.51.100.0/24" : "TCP/9092 to local finapp-kafka";
            assertThat(egress(overlay, APP)).as(overlay).containsExactlyInAnyOrder(
                    "UDP/53 to kube-system k8s-app=kube-dns",
                    "TCP/53 to kube-system k8s-app=kube-dns",
                    "TCP/4318 to finapp-observability finapp/role=telemetry-collector",
                    postgresql,
                    kafka,
                    "TCP/443 to 203.0.113.0/24");
        }
    }

    @Test
    @DisplayName("the migrator reaches PostgreSQL and the resolver that finds it, and nothing else; nothing reaches it")
    void theMigratorReachesPostgresqlAndTheResolverAlone() {
        for (String overlay : OVERLAYS) {
            String postgresql = REAL_OVERLAYS.contains(overlay) ? "TCP/5432 to 192.0.2.0/24" : "TCP/5432 to local finapp-postgres";
            assertThat(egress(overlay, MIGRATOR)).as(overlay).containsExactlyInAnyOrder(
                    "UDP/53 to kube-system k8s-app=kube-dns", "TCP/53 to kube-system k8s-app=kube-dns", postgresql);
            assertThat(rules(rendered(overlay), MIGRATOR, "ingress")).as("%s: nothing is admitted to it", overlay).isEmpty();
        }
    }

    @Test
    @DisplayName("of everything in finapp, only the application and the migrator hold an egress rule to the database's port")
    void onlyTheTwoWorkloadsHaveEgressToTheDatabase() {
        for (String overlay : OVERLAYS) {
            List<Map<String, Object>> stream = rendered(overlay);
            Set<String> holders = new TreeSet<>();
            for (String kind : List.of("Deployment", "Job", "StatefulSet")) {
                for (Map<String, Object> workload : ofKind(stream, kind)) {
                    String name = String.valueOf(map(workload, "spec", "template", "metadata", "labels").get(NAME_LABEL));
                    for (Map<String, Object> rule : rules(stream, name, "egress")) {
                        if (list(rule, "ports").stream().anyMatch(port -> Integer.valueOf(5432).equals(port.get("port")))) {
                            holders.add(name);
                        }
                    }
                }
            }
            assertThat(holders).as(overlay).containsExactlyInAnyOrder(APP, MIGRATOR);
        }
    }

    @Test
    @DisplayName("no address committed under deploy/ is real: every ipBlock is an RFC 5737 documentation range, or on the operator edge a private class")
    void noCommittedAddressIsReal() {
        int blocks = 0;
        for (Map<String, Object> policy : ofKind(everyCommittedDocument(), "NetworkPolicy")) {
            for (String direction : List.of("ingress", "egress")) {
                for (Map<String, Object> rule : list(map(policy, "spec"), direction)) {
                    for (Map<String, Object> peer : list(rule, "ingress".equals(direction) ? "from" : "to")) {
                        if (peer.containsKey("ipBlock")) {
                            blocks++;
                            assertThat(rangesAllowedOn(policy)).as("%s", policy.get("metadata")).contains(String.valueOf(map(peer, "ipBlock").get("cidr")));
                        }
                    }
                }
            }
        }
        assertThat(blocks).as("the addresses were read").isGreaterThan(8);
    }

    /** The operator edge admits the internal network as a class, the three private ranges; no other policy may name one, and no policy a real address. */
    private static Set<String> rangesAllowedOn(Map<String, Object> policy) {
        Set<String> allowed = new TreeSet<>(DOCUMENTATION_RANGES);
        if ("finapp-edge-operator".equals(map(policy, "metadata").get("name"))) {
            allowed.addAll(Set.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"));
        }
        return allowed;
    }

    // ---------------------------------------------------------------------------------------------- Pod Security

    @Test
    @DisplayName("all three namespaces state restricted at enforce, audit and warn, each version the cluster's own minor")
    void everyNamespaceIsRestrictedAndPinned() {
        Matcher pinned = Pattern.compile("(?m)^KUBERNETES_VERSION=(1\\.[0-9]+)\\.[0-9]+$").matcher(EvidenceCitations.read(root.resolve("infra/scanner-pins.sh")));
        assertThat(pinned.find()).as("the pin was read").isTrue();
        String minor = "v" + pinned.group(1);

        List<Map<String, Object>> namespaces = documents("deploy/namespaces/namespaces.yaml");
        assertThat(namespaces).extracting(namespace -> map(namespace, "metadata").get("name"))
                .containsExactlyInAnyOrder("finapp", "finapp-edge", "finapp-observability");
        for (Map<String, Object> namespace : namespaces) {
            Map<String, Object> labels = map(namespace, "metadata", "labels");
            for (String mode : List.of("enforce", "audit", "warn")) {
                assertThat(labels).as("%s", namespace.get("metadata"))
                        .containsEntry("pod-security.kubernetes.io/" + mode, "restricted")
                        .containsEntry("pod-security.kubernetes.io/" + mode + "-version", minor);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- people

    @Test
    @DisplayName("no role a person could hold names secrets in any verb, a wildcard, or a way inside a pod, read from every role committed; an unbound role counts as a person's")
    void noRoleAPersonCouldHoldReadsASecret() {
        List<Map<String, Object>> everything = everyCommittedDocument();
        List<Map<String, Object>> humans = humanRoles(everything);
        assertThat(humans).as("the roles were read").hasSizeGreaterThanOrEqualTo(2);
        for (Map<String, Object> role : humans) {
            for (Map<String, Object> rule : list(role, "rules")) {
                for (String resource : strings(rule.get("resources"))) {
                    assertThat(resource).as("%s", role.get("metadata")).doesNotStartWith("secrets").doesNotContain("*");
                    assertThat(WAYS_INSIDE).as("%s", role.get("metadata")).doesNotContain(resource);
                }
                assertThat(strings(rule.get("verbs"))).as("%s", role.get("metadata"))
                        .doesNotContain("*", "impersonate", "escalate", "bind");
                assertThat(strings(rule.get("apiGroups"))).as("%s", role.get("metadata")).doesNotContain("*");
            }
        }
    }

    @Test
    @DisplayName("a person's standing role only reads, and is held through its own group: nobody is named, and no binding reaches past the namespace")
    void aPersonsStandingRoleOnlyReads() {
        List<Map<String, Object>> everything = everyCommittedDocument();
        Map<String, Object> readOnly = role(everything, "finapp-read-only");
        for (Map<String, Object> rule : list(readOnly, "rules")) {
            assertThat(READ_VERBS).as("%s", rule).containsAll(strings(rule.get("verbs")));
            if (strings(rule.get("apiGroups")).contains("external-secrets.io")) {
                assertThat(strings(rule.get("resources"))).as("an ExternalSecret names a value and never holds one; a SecretStore is not needed").containsExactly("externalsecrets");
            }
        }
        for (Map<String, Object> binding : bindings(everything)) {
            for (Map<String, Object> subject : list(binding, "subjects")) {
                if (!"ServiceAccount".equals(subject.get("kind"))) {
                    assertThat(subject.get("kind")).as("%s", binding.get("metadata")).isEqualTo("Group");
                    assertThat(subject.get("name")).as("each group is its role's").isEqualTo(map(binding, "roleRef").get("name"));
                    assertThat(binding.get("kind")).as("a person's access is one namespace's").isEqualTo("RoleBinding");
                    assertThat(namespaceOf(binding)).as("and this namespace's alone").isEqualTo("finapp");
                }
            }
        }
    }

    @Test
    @DisplayName("every ServiceAccount a binding names is on the policy's list of controller accounts, and no ClusterRole asks to be merged into a built-in role")
    void everyAccountBoundIsAListedController() {
        List<Map<String, Object>> everything = everyCommittedDocument();
        Set<String> listed = controllerAccounts();
        for (Map<String, Object> binding : bindings(everything)) {
            for (Map<String, Object> subject : list(binding, "subjects")) {
                if ("ServiceAccount".equals(subject.get("kind"))) {
                    assertThat(listed).as("%s", binding.get("metadata")).contains(subject.get("namespace") + "/" + subject.get("name"));
                }
            }
        }
        for (Map<String, Object> role : ofKind(everything, "ClusterRole")) {
            Object labels = map(role, "metadata").get("labels");
            assertThat(String.valueOf(labels)).as("%s", role.get("metadata")).doesNotContain("rbac.authorization.k8s.io/aggregate-to-");
        }
    }

    @Test
    @DisplayName("the break-glass role's writes are exactly: delete a pod, evict a pod, change a Deployment's scale. Nothing that could mount a Secret")
    void breakGlassWritesAreAClosedList() {
        Map<String, Object> breakGlass = role(everyCommittedDocument(), "finapp-break-glass");
        Set<String> writes = new TreeSet<>();
        for (Map<String, Object> rule : list(breakGlass, "rules")) {
            for (String verb : strings(rule.get("verbs"))) {
                if (!READ_VERBS.contains(verb)) {
                    for (String resource : strings(rule.get("resources"))) {
                        writes.add(verb + " " + resource);
                    }
                }
            }
        }
        assertThat(writes).containsExactlyInAnyOrder("delete pods", "create pods/eviction", "patch deployments/scale", "update deployments/scale");
    }

    @Test
    @DisplayName("neither workload is a subject of any binding: it calls no Kubernetes API")
    void neitherWorkloadHoldsAnyRbac() {
        for (Map<String, Object> binding : bindings(everyCommittedDocument())) {
            for (Map<String, Object> subject : list(binding, "subjects")) {
                if ("ServiceAccount".equals(subject.get("kind"))) {
                    assertThat(subject.get("name")).as("%s", binding.get("metadata")).isNotIn(APP, MIGRATOR);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- quota and limits

    @Test
    @DisplayName("each overlay's quota is its worst moment, exactly: maxReplicas + 2 x maxSurge + 1 application pods, the migrator and anything test-only, in pods, CPU and memory")
    void theQuotaAdmitsTheWorstMomentOfARollout() {
        for (String overlay : OVERLAYS) {
            List<Map<String, Object>> stream = rendered(overlay);
            Map<String, Object> hard = map(only(ofKind(stream, "ResourceQuota")), "spec", "hard");

            int pods = 0;
            Map<String, BigDecimal> needed = new LinkedHashMap<>();
            for (String kind : List.of("Deployment", "Job", "StatefulSet")) {
                for (Map<String, Object> workload : ofKind(stream, kind)) {
                    int peak = peak(stream, workload);
                    pods += peak;
                    for (Map<String, Object> container : list(map(workload, "spec", "template", "spec"), "containers")) {
                        for (String bound : List.of("requests", "limits")) {
                            for (String resource : List.of("cpu", "memory")) {
                                needed.merge(bound + "." + resource,
                                        quantity(map(container, "resources", bound).get(resource)).multiply(BigDecimal.valueOf(peak)), BigDecimal::add);
                            }
                        }
                    }
                }
            }
            assertThat(quantity(hard.get("pods"))).as("%s: pods at the worst moment", overlay).isEqualByComparingTo(BigDecimal.valueOf(pods));
            assertThat(needed).as("%s: the four keys were computed", overlay).hasSize(4);
            needed.forEach((key, value) -> assertThat(quantity(hard.get(key))).as("%s: %s", overlay, key).isEqualByComparingTo(value));
        }
    }

    @Test
    @DisplayName("a pod with no requests is refused, not defaulted: the quota states every key unscoped, and the LimitRange is type Pod with neither default")
    void aPodWithoutRequestsIsRefusedRatherThanDefaulted() {
        Map<String, Object> quota = document("deploy/base/resourcequota.yaml");
        assertThat(map(quota, "spec")).as("a scope leaves some pods unmeasured").containsOnlyKeys("hard");
        assertThat(map(quota, "spec", "hard")).containsKeys("pods", "requests.cpu", "requests.memory", "limits.cpu", "limits.memory");

        for (Map<String, Object> range : ofKind(everyCommittedDocument(), "LimitRange")) {
            List<Map<String, Object>> items = list(map(range, "spec"), "limits");
            assertThat(items).as("the items were read").isNotEmpty();
            for (Map<String, Object> item : items) {
                // A Container item's max becomes its default and its defaultRequest at the API server, written or not.
                assertThat(item).containsEntry("type", "Pod").doesNotContainKeys("default", "defaultRequest");
            }
        }
    }

    @Test
    @DisplayName("every committed pod fits the LimitRange it will be judged by, whose max is the largest pod rendered, exactly, so the bound holds something back")
    void everyPodFitsTheLimitRange() {
        for (String overlay : OVERLAYS) {
            List<Map<String, Object>> stream = rendered(overlay);
            Map<String, Object> bounds = only(list(map(only(ofKind(stream, "LimitRange")), "spec"), "limits"));
            Map<String, BigDecimal> largest = new LinkedHashMap<>();
            for (String kind : List.of("Deployment", "Job", "StatefulSet")) {
                for (Map<String, Object> workload : ofKind(stream, kind)) {
                    for (Map<String, Object> container : list(map(workload, "spec", "template", "spec"), "containers")) {
                        for (String resource : List.of("cpu", "memory")) {
                            largest.merge(resource, quantity(map(container, "resources", "limits").get(resource)), BigDecimal::max);
                            assertThat(quantity(map(container, "resources", "requests").get(resource))).as("%s: %s request", overlay, resource)
                                    .isGreaterThanOrEqualTo(quantity(map(bounds, "min").get(resource)));
                        }
                    }
                }
            }
            assertThat(largest).as("%s: both resources were read", overlay).hasSize(2);
            largest.forEach((resource, value) -> assertThat(quantity(map(bounds, "max").get(resource))).as("%s: max %s", overlay, resource).isEqualByComparingTo(value));
        }
    }

    // ---------------------------------------------------------------------------------------------- reading

    /** Maximum replicas + 2 x surge + 1 for the fleet; a Job one at a time; anything else its replica count. */
    private static int peak(List<Map<String, Object>> stream, Map<String, Object> workload) {
        if ("Job".equals(workload.get("kind"))) {
            return (Integer) map(workload, "spec").getOrDefault("parallelism", 1);
        }
        if ("Deployment".equals(workload.get("kind"))) {
            Map<String, Object> autoscaler = only(ofKind(stream, "HorizontalPodAutoscaler"));
            int surge = (Integer) map(workload, "spec", "strategy", "rollingUpdate").get("maxSurge");
            return (Integer) map(autoscaler, "spec").get("maxReplicas") + 2 * surge + 1;
        }
        return (Integer) map(workload, "spec").getOrDefault("replicas", 1);
    }

    private static List<String> egress(String overlay, String workload) {
        List<String> flows = new ArrayList<>();
        for (Map<String, Object> rule : rules(rendered(overlay), workload, "egress")) {
            for (Map<String, Object> peer : list(rule, "to")) {
                for (Map<String, Object> port : list(rule, "ports")) {
                    flows.add(protocolAndPort(port) + " to " + describe(peer));
                }
            }
        }
        return flows;
    }

    /** Every rule of the direction from every policy whose selector matches the workload's own pod labels, any label at all. */
    private static List<Map<String, Object>> rules(List<Map<String, Object>> stream, String workload, String direction) {
        Map<String, Object> labels = podLabels(stream, workload);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> policy : ofKind(stream, "NetworkPolicy")) {
            Map<String, Object> selector = map(policy, "spec", "podSelector");
            assertThat(selector.keySet()).as("a selector this test can read").isSubsetOf("matchLabels");
            Map<String, Object> wanted = selector.isEmpty() ? Map.of() : map(selector, "matchLabels");
            if (labels.entrySet().containsAll(wanted.entrySet())) {
                rules.addAll(list(map(policy, "spec"), direction));
            }
        }
        return rules;
    }

    private static Map<String, Object> podLabels(List<Map<String, Object>> stream, String workload) {
        for (String kind : List.of("Deployment", "Job", "StatefulSet")) {
            for (Map<String, Object> doc : ofKind(stream, kind)) {
                Map<String, Object> labels = map(doc, "spec", "template", "metadata", "labels");
                if (workload.equals(labels.get(NAME_LABEL))) {
                    return labels;
                }
            }
        }
        throw new AssertionError("no workload named " + workload);
    }

    private static String protocolAndPort(Map<String, Object> port) {
        return port.getOrDefault("protocol", "TCP") + "/" + port.get("port");
    }

    private static String describe(Map<String, Object> peer) {
        if (peer.containsKey("ipBlock")) {
            return String.valueOf(map(peer, "ipBlock").get("cidr"));
        }
        Map<String, Object> pod = map(peer, "podSelector", "matchLabels");
        if (!peer.containsKey("namespaceSelector")) {
            return "local " + pod.get(NAME_LABEL);
        }
        assertThat(pod).hasSize(1);
        Map.Entry<String, Object> label = pod.entrySet().iterator().next();
        return map(peer, "namespaceSelector", "matchLabels").get(NAMESPACE_LABEL) + " " + label.getKey() + "=" + label.getValue();
    }

    /** A role is a controller's only if some binding names a listed ServiceAccount and none names anything else; the rest are people's. */
    private static List<Map<String, Object>> humanRoles(List<Map<String, Object>> stream) {
        List<Map<String, Object>> roles = new ArrayList<>(ofKind(stream, "Role"));
        roles.addAll(ofKind(stream, "ClusterRole"));
        Set<String> controllers = controllers(stream);
        return roles.stream().filter(role -> !controllers.contains(roleKey(String.valueOf(role.get("kind")), namespaceOf(role), String.valueOf(map(role, "metadata").get("name"))))).toList();
    }

    private static Set<String> controllers(List<Map<String, Object>> stream) {
        Set<String> listed = controllerAccounts();
        Set<String> serviceAccounts = new HashSet<>();
        Set<String> others = new HashSet<>();
        for (Map<String, Object> binding : bindings(stream)) {
            Map<String, Object> ref = map(binding, "roleRef");
            String role = roleKey(String.valueOf(ref.get("kind")), namespaceOf(binding), String.valueOf(ref.get("name")));
            for (Map<String, Object> subject : list(binding, "subjects")) {
                boolean controller = "ServiceAccount".equals(subject.get("kind")) && listed.contains(subject.get("namespace") + "/" + subject.get("name"));
                (controller ? serviceAccounts : others).add(role);
            }
        }
        serviceAccounts.removeAll(others);
        return serviceAccounts;
    }

    /** A Role is its namespace's and a ClusterRole is nobody's, so a same-named Role elsewhere cannot borrow this one's standing. */
    private static String roleKey(String kind, String namespace, String name) {
        return "ClusterRole".equals(kind) ? "ClusterRole//" + name : kind + "/" + namespace + "/" + name;
    }

    /** A committed overlay resource states no namespace: the overlay's transformer sets it to finapp. */
    private static String namespaceOf(Map<String, Object> doc) {
        return String.valueOf(map(doc, "metadata").getOrDefault("namespace", "finapp"));
    }

    /** The real overlays' list, from the policy itself: namespace/name, one for each line of {@code controller_accounts}. */
    private static Set<String> controllerAccounts() {
        Matcher block = Pattern.compile("(?ms)^controller_accounts := \\[$(.*?)^]$").matcher(EvidenceCitations.read(root.resolve("infra/scripts/manifest-policy/finapp.rego")));
        assertThat(block.find()).as("the policy's list was read").isTrue();
        Set<String> accounts = new TreeSet<>();
        Matcher account = Pattern.compile("\\{\"namespace\": \"([^\"]+)\", \"name\": \"([^\"]+)\"}").matcher(block.group(1));
        while (account.find()) {
            accounts.add(account.group(1) + "/" + account.group(2));
        }
        return accounts;
    }

    private static List<Map<String, Object>> bindings(List<Map<String, Object>> stream) {
        List<Map<String, Object>> bindings = new ArrayList<>(ofKind(stream, "RoleBinding"));
        bindings.addAll(ofKind(stream, "ClusterRoleBinding"));
        return bindings;
    }

    private static Map<String, Object> role(List<Map<String, Object>> stream, String name) {
        return only(ofKind(stream, "Role").stream().filter(role -> name.equals(map(role, "metadata").get("name"))).toList());
    }

    private static BigDecimal quantity(Object value) {
        String text = String.valueOf(value);
        if (text.endsWith("Gi")) {
            return new BigDecimal(text.substring(0, text.length() - 2)).multiply(BigDecimal.valueOf(1024L * 1024L * 1024L));
        }
        if (text.endsWith("Mi")) {
            return new BigDecimal(text.substring(0, text.length() - 2)).multiply(BigDecimal.valueOf(1024L * 1024L));
        }
        if (text.endsWith("m")) {
            return new BigDecimal(text.substring(0, text.length() - 1)).movePointLeft(3);
        }
        return new BigDecimal(text);
    }

    /** One overlay as Kustomize would resolve it: its resources through the base, then its JSON patches. */
    private static List<Map<String, Object>> rendered(String overlay) {
        List<Map<String, Object>> stream = new ArrayList<>();
        follow("deploy/overlays/" + overlay, stream);
        List<?> patches = (List<?>) document("deploy/overlays/" + overlay + "/kustomization.yaml").get("patches");
        if (patches != null) {
            for (Object entry : patches) {
                Map<?, ?> patch = (Map<?, ?>) entry;
                Map<?, ?> target = (Map<?, ?>) patch.get("target");
                for (Map<String, Object> doc : stream) {
                    if (target.get("kind").equals(doc.get("kind")) && target.get("name").equals(map(doc, "metadata").get("name"))) {
                        for (Object operation : (List<?>) yaml().load(String.valueOf(patch.get("patch")))) {
                            apply(doc, (Map<?, ?>) operation);
                        }
                    }
                }
            }
        }
        return stream;
    }

    private static void follow(String directory, List<Map<String, Object>> stream) {
        for (Object resource : (List<?>) document(directory + "/kustomization.yaml").get("resources")) {
            Path target = root.resolve(directory).resolve(String.valueOf(resource)).normalize();
            String relative = root.relativize(target).toString().replace('\\', '/');
            if (Files.isDirectory(target)) {
                follow(relative, stream);
            } else {
                stream.addAll(documents(relative));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply(Map<String, Object> doc, Map<?, ?> operation) {
        assertThat(operation.get("op")).as("this test applies replace, and add at the end of a list").isIn("replace", "add");
        String[] steps = String.valueOf(operation.get("path")).substring(1).split("/");
        Object current = doc;
        for (int index = 0; index < steps.length - 1; index++) {
            current = current instanceof List<?> items ? items.get(Integer.parseInt(steps[index])) : ((Map<String, Object>) current).get(steps[index]);
        }
        String last = steps[steps.length - 1];
        if ("add".equals(operation.get("op"))) {
            assertThat(last).as("%s: only an append to a list", operation.get("path")).isEqualTo("-");
            ((List<Object>) current).add(operation.get("value"));
            return;
        }
        assertThat(((Map<String, Object>) current)).as("%s exists to be replaced", operation.get("path")).containsKey(last);
        ((Map<String, Object>) current).put(last, operation.get("value"));
    }

    private static List<Map<String, Object>> everyCommittedDocument() {
        List<Map<String, Object>> everything = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            files.filter(file -> file.getFileName().toString().endsWith(".yaml")).filter(file -> !file.getFileName().toString().equals("kustomization.yaml"))
                    .forEach(file -> everything.addAll(documents(root.relativize(file).toString().replace('\\', '/'))));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        assertThat(everything).as("the manifests were read").hasSizeGreaterThan(15);
        return everything;
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
        return documents;
    }

    private static Map<String, Object> document(String path) {
        List<Map<String, Object>> documents = documents(path);
        assertThat(documents).as(path).hasSize(1);
        return documents.get(0);
    }

    private static List<Map<String, Object>> ofKind(List<Map<String, Object>> stream, String kind) {
        return stream.stream().filter(doc -> kind.equals(doc.get("kind"))).toList();
    }

    private static <T> T only(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> from, String key) {
        Object value = from.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> from, String... path) {
        Object current = from;
        for (String step : path) {
            assertThat(current).as("reading %s", step).isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(step);
        }
        assertThat(current).as("%s", String.join("/", path)).isInstanceOf(Map.class);
        return (Map<String, Object>) current;
    }
}
