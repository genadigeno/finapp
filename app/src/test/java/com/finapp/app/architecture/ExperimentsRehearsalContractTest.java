package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the failure-injection toolkit holds about itself ({@code P16-TSK-019}, ADR-0127; {@code OPERATIONS_RUNBOOK.md} section 11.46): the
 * kind rehearsal's {@code experiments} scope, its topology, its safety boundary, its fault registry and its first experiment, E01.
 *
 * <p>An experiment's result is a cluster's behaviour, recorded in the resilience register and the runbook, and nothing here claims it.
 * What a hermetic test can hold is that a later edit does not quietly take away what makes a run safe and its verdict a measurement. A
 * fault injector fails dangerously in two ways - acting on something the run did not create, or leaving a fault on - and uselessly in a
 * third, passing an experiment nobody measured. Each member refuses one way of doing that:
 *
 * <ol>
 *   <li>the scope is wired, on release N alone, on a cluster of one control plane and three workers each a zone, every image in every node;
 *   <li>the fault proxy is pinned by digest, watched, run by its digest and outside the build graph;
 *   <li>it sits on the application's three hops alone, its admin API on its own loopback, and the clients' TLS is unchanged through it;
 *   <li>the standby is synchronous, made at verify-full, in another zone;
 *   <li>nothing of the toolkit reaches deploy/;
 *   <li>the boundary refuses a context, a credential and an endpoint the run did not make, each proven by a plant in every run;
 *   <li>a cluster the scope did not mark is neither reused nor deleted;
 *   <li>no fault goes on without the boundary, and every fault comes off on every way out;
 *   <li>every experiment keeps the step shape, and E01 measures what its register row names.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the failure-injection toolkit holds what it says it holds (P16-TSK-019)")
class ExperimentsRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String CLUSTER = "infra/scripts/kind-rehearsal/experiments/kind-cluster.yaml";
    private static final String PROXY = "infra/scripts/kind-rehearsal/experiments/toxiproxy.yaml";
    private static final String STANDBY = "infra/scripts/kind-rehearsal/experiments/standby.yaml";
    private static final String SECRETS = "infra/scripts/kind-secrets.sh";
    private static final String PINS = "infra/scanner-pins.sh";

    private static Path root;
    private static String harness;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        experiments = read(EXPERIMENTS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String text, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(text);
        assertThat(start.find()).as("the script defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(text);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return text.substring(start.end(), end.start());
    }

    private static String function(String name) {
        return function(experiments, name);
    }

    @Test
    @DisplayName("the scope is wired on release N alone, on one control plane and three workers each its own zone, every image in every node")
    void theScopeIsWiredOnSeveralNodes() {
        assertThat(harness).as("a scope of the harness, deploying release N alone, sourced as load.sh is")
                .contains("rolls) ;; load) ;; experiments) ;;").contains("[ \"$SCOPE\" != experiments ] || N_ONLY=1")
                .contains(". \"$DIR/experiments.sh\"")
                .contains("if [ \"$SCOPE\" = experiments ]; then\n    experiments_scope\n    step end\n    finish\nfi");
        assertThat(experiments.strip().lines().reduce((first, second) -> second).orElseThrow()).as("a file of functions: it ends in one's brace")
                .isEqualTo("}");
        String cluster = read(CLUSTER);
        assertThat(cluster).contains("podSubnet: 10.244.0.0/16").contains("  - role: control-plane\n");
        Matcher worker = Pattern.compile("(?m)^  - role: worker\\n    labels:\\n      topology\\.kubernetes\\.io/zone: (\\S+)$").matcher(cluster);
        TreeSet<String> zones = new TreeSet<>();
        int workers = 0;
        while (worker.find()) {
            workers++;
            zones.add(worker.group(1));
        }
        assertThat(workers).as("three workers").isEqualTo(3);
        assertThat(zones).as("each its own zone").hasSize(3);
        assertThat(cluster.split("- role: ", -1)).as("one control plane and three workers, no more").hasSize(5);
        assertThat(harness).as("the experiments' cluster made from its own file").contains("[ \"$SCOPE\" != experiments ] || CLUSTER_CONFIG=$EXPERIMENTS_CONFIG");
        assertThat(experiments).contains("EXPERIMENTS_CONFIG=\"$DIR/experiments/kind-cluster.yaml\"");
        assertThat(harness).as("every node of the cluster takes every image").contains("NODES=$NODE")
                .contains("[ \"$SCOPE\" != experiments ] || NODES=$(experiments_nodes | tr '\\n' ' ')");
        for (String loader : List.of("load_tagged", "load_built")) {
            String body = function(harness, loader);
            assertThat(body).as("%s works on every node", loader).contains("for node in $NODES; do").doesNotContain("dock exec \"$NODE\"")
                    .doesNotContain("dock exec -i \"$NODE\"");
        }
        assertThat(harness).as("cert-manager's images and the CA bundle on every node")
                .contains("        dock exec \"$node\" crictl pull \"quay.io/jetstack/$component:$CERT_MANAGER_VERSION\"")
                .contains("        dock exec \"$node\" systemctl restart containerd");
        assertThat(function(read("infra/scripts/kind-rehearsal/telemetry.sh"), "telemetry_image")).contains("for node in ${NODES:-$NODE}; do");
        assertThat(function("experiments_render")).as("the base's DoNotSchedule on the zone again, which the kind overlay relaxes for one node")
                .contains("sub(/ScheduleAnyway/, \"DoNotSchedule\")").contains("cannot \"the application's zone constraint could not be restored");
        assertThat(function("experiments_topology_check")).as("three replicas ready on three nodes in three zones, read")
                .contains("[ \"$ready\" = 3 ] || fail").contains("the three replicas share nodes").contains("are not in three zones")
                .contains("topology.kubernetes.io/zone=DoNotSchedule");
        assertThat(function("experiments_edge_addresses")).as("each edge reached on a worker running one of its own controllers")
                .contains("public) NODE_IP=$address ;; operator) OPERATOR_IP=$address ;;");
        assertThat(harness).contains("--add-host \"operator.finapp.test:$OPERATOR_IP\"").contains("OPERATOR_IP=$NODE_IP");
    }

    @Test
    @DisplayName("the fault proxy is Toxiproxy, pinned by digest, watched for a moved tag or a newer release, run by its digest, outside the build graph")
    void theFaultProxyIsPinnedAndWatched() throws IOException {
        String pins = read(PINS);
        assertThat(pins).containsPattern("(?m)^TOXIPROXY_REPOSITORY=ghcr\\.io/shopify/toxiproxy$")
                .containsPattern("(?m)^TOXIPROXY_VERSION=[0-9]+\\.[0-9]+\\.[0-9]+$").containsPattern("(?m)^TOXIPROXY_DIGEST=sha256:[0-9a-f]{64}$");
        assertThat(read("infra/scripts/check-pinned-images.sh"))
                .contains("check toxiproxy \"$TOXIPROXY_REPOSITORY\" \"$TOXIPROXY_VERSION\" \"$TOXIPROXY_DIGEST\" list_tags_ghcr");
        assertThat(experiments).as("run by the digest, never by a tag").contains("TOXIPROXY_IMAGE=\"$TOXIPROXY_REPOSITORY@$TOXIPROXY_DIGEST\"")
                .contains("sed \"s#TOXIPROXY_IMAGE#$TOXIPROXY_IMAGE#\"");
        assertThat(harness).contains("load_tagged \"$TOXIPROXY_REPOSITORY\" \"$TOXIPROXY_VERSION\" \"$TOXIPROXY_DIGEST\"");
        assertThat(read(PROXY)).contains("image: TOXIPROXY_IMAGE").doesNotContain("toxiproxy:");
        assertThat(read("gradle/libs.versions.toml") + read("gradle/verification-metadata.xml")).as("nothing of Toxiproxy in the build graph")
                .doesNotContainIgnoringCase("toxiproxy");
    }

    @Test
    @DisplayName("the proxy is on the application's three hops alone, its admin API on its own loopback, and the clients' URLs and verify-full unchanged")
    void theProxyIsOnTheApplicationsHopsAndTlsIsUnchanged() {
        String proxy = read(PROXY);
        assertThat(proxy).as("its three proxies are the run's three services")
                .contains("\"upstream\": \"finapp-postgres.finapp.svc.cluster.local:5432\"")
                .contains("\"upstream\": \"finapp-kafka.finapp.svc.cluster.local:9092\"")
                .contains("\"upstream\": \"finapp-simulator.finapp-rehearsal.svc.cluster.local:8443\"");
        assertThat(proxy.split("\"upstream\": ", -1)).as("three, and no fourth").hasSize(4);
        assertThat(proxy).as("the admin API on the pod's loopback")
                .contains("args: [\"-host=127.0.0.1\", \"-config=/etc/finapp-toxiproxy/proxies.json\"]");
        assertThat(proxy.lines().filter(line -> !line.stripLeading().startsWith("#")).toList()).as("exposed by no Service and no policy")
                .noneMatch(line -> line.contains("8474"));
        assertThat(proxy).as("admitting the application's pods alone").contains("pod-security.kubernetes.io/enforce: restricted")
                .contains("name: default-deny\n  namespace: finapp-experiments").contains("runAsNonRoot: true").contains("readOnlyRootFilesystem: true");
        String data = function("experiments_data_tier");
        assertThat(data).as("the application's own names resolved to the proxy in its pods, and only those three")
                .contains("      hostAliases:").contains("            - $PG_HOST\n            - $KAFKA_HOST\n            - $SIM_HOST\n")
                .contains("\"$A/Deployment.finapp-app.yaml\"").doesNotContain("Job.finapp-migrator");
        assertThat(harness).as("the client's URL the same bytes in every scope, verify-full against the run's CA")
                .contains("DB_URL=\"jdbc:postgresql://$PG_HOST:5432/finapp?sslmode=verify-full&sslrootcert=/etc/finapp-local-ca/ca.crt\"")
                .contains("FINAPP_DB_URL=\"$DB_URL\"").contains("FINAPP_KAFKA_BOOTSTRAPSERVERS=\"$KAFKA_HOST:9092\"").contains("SIM_URL=\"https://$SIM_HOST:8443\"");
        String topology = function("experiments_topology_check");
        assertThat(topology).as("every application session arrives from the proxy, and every one over TLS")
                .contains("JOIN pg_stat_ssl s USING (pid) WHERE a.application_name = 'finapp'").contains("count(*) FILTER (WHERE s.ssl)")
                .contains("host(a.client_addr) = '$proxy_pod'").contains("[ \"$tls\" = \"$all\" ] && [ \"$proxied\" = \"$all\" ]");
        assertThat(topology).as("the consumer groups' members at the proxy's address and none elsewhere; every replica accepted on every hop")
                .contains("kafka-consumer-groups.sh --describe --all-groups --members").contains("[ \"$other\" = 0 ] || fail")
                .contains("for proxy in postgres kafka; do").contains("'\"message\":\"Accepted client\"'")
                .contains("[ \"$callers\" -ge 1 ] || fail \"topology: no replica's provider call went through the proxy");
    }

    @Test
    @DisplayName("the standby is PostgreSQL's own, made at verify-full through a slot, synchronous (FIRST 1), in another zone, a committed write replayed")
    void theStandbyIsSynchronousInAnotherZone() {
        String standby = read(STANDBY);
        assertThat(standby).as("in another zone than the primary, required")
                .contains("requiredDuringSchedulingIgnoredDuringExecution:\n            - labelSelector:\n                matchLabels:\n"
                        + "                  app.kubernetes.io/name: finapp-postgres\n              topologyKey: topology.kubernetes.io/zone");
        assertThat(standby).as("a base backup at verify-full against the run's CA, through the slot, its password in a file of mode 0600")
                .contains("pg_basebackup -D \"$data\" -X stream -c fast -S finapp_standby -R")
                .contains("sslmode=verify-full sslrootcert=/etc/finapp-local-ca/ca.crt").contains("umask 077")
                .contains("application_name=finapp_standby").contains("test -f \"$data/standby.signal\"");
        assertThat(standby).as("the primary's settings, a hot standby and its slot")
                .contains("- max_connections=60").contains("- hot_standby=on").contains("- primary_slot_name=finapp_standby");
        String made = function("experiments_standby");
        assertThat(made).as("its password generated, in the ledger, on the SQL's stdin; the primary made synchronous only once it streams")
                .contains("od -An -N32 -tx1 /dev/urandom").contains(">> \"$LEDGER\"").contains("psql_query -f - >>")
                .contains("CREATE ROLE finapp_replicator WITH REPLICATION LOGIN PASSWORD '$phrase';")
                .contains("pg_create_physical_replication_slot('finapp_standby')")
                .contains("ALTER SYSTEM SET synchronous_standby_names = 'FIRST 1 (finapp_standby)'");
        assertThat(made.indexOf("\"finapp_standby|streaming|async\"")).as("streaming first, synchronous after")
                .isLessThan(made.indexOf("ALTER SYSTEM SET synchronous_standby_names"));
        assertThat(function("experiments_standby_check")).as("sync as the primary reports it, another zone, a WAL-writing commit replayed")
                .contains("[ \"$replication\" = \"finapp_standby|streaming|sync\" ] || fail").contains("[ \"$primary_zone\" != \"$standby_zone\" ]")
                .contains("pg_logical_emit_message(true, ").contains("pg_last_wal_replay_lsn() >= '$lsn'::pg_lsn AND pg_is_in_recovery()");
        assertThat(function("experiments_render")).as("the replication line in the scope's own copy of the hba, the quota grown by one pod")
                .contains("hostssl replication finapp_replicator all scram-sha-256").contains("pods:*) grown=$((value + 1))");
    }

    @Test
    @DisplayName("nothing of the toolkit reaches deploy/: no proxy, no standby, no experiments namespace in any overlay, and the kind overlay as it was")
    void nothingReachesADeployedOverlay() throws IOException {
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = EvidenceCitations.read(file);
                assertThat(text).as("%s names nothing of the toolkit", root.relativize(file)).doesNotContainIgnoringCase("toxiproxy")
                        .doesNotContain("finapp-postgres-standby").doesNotContain("finapp-experiments").doesNotContain("synchronous_standby_names");
            }
        }
        assertThat(read("deploy/overlays/kind/kustomization.yaml")).as("the kind overlay keeps its one node's relaxation; the scope restores the rule in its copy")
                .contains("path: /spec/template/spec/topologySpreadConstraints/0/whenUnsatisfiable\n        value: ScheduleAnyway");
        for (String manifest : List.of(PROXY, STANDBY)) {
            String text = read(manifest);
            assertThat(text.replace("\n# ", " ")).as("%s says it is test-only, and every object of it is labelled so", manifest)
                    .contains("Test-only and never rendered by any overlay").doesNotContain("kind: ClusterRole").doesNotContain("kind: DaemonSet").doesNotContain("privileged: true");
            // Every object's metadata carries the label, and so does the one pod template each file has.
            assertThat(text.split("finapp/test-only: \"true\"\n", -1).length - 2).as("%s: every object labelled finapp/test-only", manifest)
                    .isEqualTo(text.split("\nkind: ", -1).length - 1);
        }
    }

    @Test
    @DisplayName("the boundary refuses a context, a credential and an endpoint the run did not make, reading the cluster, never trusting the run's word")
    void theBoundaryRefusesWhatTheRunDidNotMake() {
        assertThat(function("experiments_refuses")).contains("experiments_context_check || return 1")
                .contains("experiments_credentials_check || return 1").contains("experiments_endpoints_check || return 1");
        String context = function("experiments_context_check");
        assertThat(context).as("the node a kind control plane of this cluster, by its labels; its own context and server; the API's nodes the containers;"
                        + " the marker")
                .contains("[ \"$labels\" = \"$CLUSTER|control-plane\" ]").contains("\"kubernetes-admin@$CLUSTER|https://$NODE:6443\"")
                .contains("[ \"$listed\" = \"$containers\" ]").contains("experiments_marked ||");
        assertThat(function("experiments_marked")).contains("[ \"${marker%|*}\" = \"$CLUSTER|experiments\" ]");
        String credentials = function("experiments_credentials_check");
        assertThat(credentials).as("every Secret of the run's namespaces hashed inside the node and found in the run's ledger, above a floor")
                .contains("for namespace in finapp finapp-rehearsal finapp-experiments; do").contains("base64 -d | sha256sum | cut -c1-64")
                .contains("dock exec -i \"$NODE\" sh -s <<'NODE'").contains("if (!($4 in made))").contains("[ \"$cred_values\" -ge 60 ] ||")
                .contains("[ -s \"$LEDGER\" ] ||");
        String endpoints = function("experiments_endpoints_check");
        assertThat(endpoints).as("the application's sources the run's two; every URL the simulator's; the database, the broker and the collector the run's own")
                .contains("\"configmap/finapp-app-config/- secret/finapp-app-secrets/- \"").contains("[ \"$value\" = \"$SIM_URL\" ] ||")
                .contains("[ \"$value\" = \"$DB_URL\" ] ||").contains("[ \"$value\" = \"$KAFKA_HOST:9092\" ] ||").contains("\"$EXPERIMENTS_COLLECTOR\"*")
                .contains("other) EXP_REASON=\"endpoint: $key is read from a source this check does not read\"")
                .contains("[ \"$end_providers\" -ge 3 ] ||");
        assertThat(endpoints).as("the hostAliases the run's three names to its proxy, and the proxy's upstreams the run's three services")
                .contains("[ \"$aliases\" = \"$proxy $PG_HOST $KAFKA_HOST $SIM_HOST\" ]")
                .contains("\"kafka=$KAFKA_HOST:9092 postgres=$PG_HOST:5432 simulator=$SIM_HOST:8443 \"");
        String secrets = read(SECRETS);
        assertThat(secrets).as("kind-secrets.sh's ledger holds hashes alone, and never inside the repository")
                .contains("[ -z \"$LEDGER\" ] || printf '%s' \"$fresh\" | sha256sum | cut -c1-64 >> \"$LEDGER\"")
                .contains("refusing to write the ledger inside the repository");
        assertThat(function(harness, "secret_of")).contains("sha256sum < \"${entry#*=}\" | cut -c1-64 >> \"$WORK/ledger\"");
        assertThat(harness).contains("KIND_SECRETS_LEDGER=\"$WORK/ledger\"");
        assertThat(experiments).as("the boundary speaks to the cluster only through kube and the node, never a host kubeconfig").doesNotContain("--kubeconfig=$")
                .doesNotContain("KUBECONFIG");
    }

    @Test
    @DisplayName("each refusal is proven by its plant in every run: refused by its own check, with no fault on, and passing again once put back")
    void eachRefusalIsProvenByItsPlant() {
        String boundary = function("experiments_boundary");
        assertThat(boundary).as("the control first: the run's own cluster passes")
                .startsWith("\n    experiments_refuses || fail \"the safety boundary refused the run's own cluster");
        assertThat(boundary).as("an unmarked context, a credential the run did not make, a provider that is not the simulator, each with its own reason")
                .contains("kube -n kube-system delete configmap \"$EXPERIMENTS_MARKER\"")
                .contains("experiments_plant_judged unmarked \"context: the cluster carries no marker\"")
                .contains("experiments_patch_secret FINAPP_PAYMENTS_WEBHOOK_KEY \"$(printf 'planted, not generated by this run'")
                .contains("experiments_plant_judged foreign-credential \"credential: finapp/finapp-app-secrets holds under FINAPP_PAYMENTS_WEBHOOK_KEY\"")
                .contains("experiments_patch_secret FINAPP_PAYMENTS_PROVIDER_URL \"$(printf 'https://psp.example.invalid'")
                .contains("experiments_plant_judged foreign-endpoint \"endpoint: FINAPP_PAYMENTS_PROVIDER_URL names an endpoint other than the simulator\"")
                .contains("[ ! -s \"$FAULTS\" ] || fail \"a fault was registered during the boundary's plants");
        for (String plant : List.of("unmarked", "foreign-credential", "foreign-endpoint")) {
            int read = boundary.indexOf("experiments_plant " + plant + "\n");
            int judged = boundary.indexOf("experiments_plant_judged " + plant + " ");
            assertThat(read).as("%s is read with the plant in place", plant).isPositive();
            assertThat(boundary.substring(read, judged)).as("%s is put back before it is judged", plant)
                    .containsAnyOf("kube -n kube-system create configmap \"$EXPERIMENTS_MARKER\" \"$@\"", "experiments_patch_secret ");
        }
        String judged = function("experiments_plant_judged");
        assertThat(judged).contains("[ -n \"$PLANT_REFUSED\" ] || fail \"plant $1: the safety boundary did not refuse it")
                .contains("*) fail \"plant $1 was refused for another reason than its own").contains("experiments_refuses || fail \"plant $1 removed");
        assertThat(function("experiments_patch_secret")).as("a planted value travels on stdin, never on a command line")
                .contains("dock exec -i \"$NODE\" sh -c 'cat > /tmp/experiments-patch.json").contains("rm -f /tmp/experiments-patch.json");
        assertThat(experiments.indexOf("        step experiments-boundary\n")).as("the plants before any experiment")
                .isPositive().isLessThan(experiments.indexOf("            experiment_run \"$id\""));
    }

    @Test
    @DisplayName("a cluster of the scope's name it did not mark is neither reused nor deleted: refused before anything in it changes")
    void anUnmarkedClusterIsNeitherReusedNorDeleted() {
        int reuse = harness.indexOf("if [ -n \"$REUSING\" ]; then\n    # The experiments scope");
        assertThat(reuse).as("the reuse branch").isPositive();
        String branch = harness.substring(reuse, harness.indexOf("\nelse\n", reuse));
        assertThat(branch.indexOf("[ \"$SCOPE\" != experiments ] || experiments_cluster_owned")).as("checked before the cluster is counted as up")
                .isPositive().isLessThan(branch.indexOf("CLUSTER_UP=1")).isLessThan(branch.indexOf("kube delete namespace"));
        int replace = harness.indexOf("if [ \"$SCOPE\" = experiments ] && \"$KIND_BIN\" get clusters 2>/dev/null | grep -qx \"$CLUSTER\"; then\n    experiments_cluster_owned");
        assertThat(replace).as("a cluster of the name checked before it is deleted").isPositive()
                .isLessThan(harness.indexOf("\"$KIND_BIN\" delete cluster --name \"$CLUSTER\" >/dev/null 2>&1 || true"));
        String owned = function("experiments_cluster_owned");
        assertThat(owned).as("refused as a failure, the cluster never counted as the run's, so finish neither clears nor deletes it")
                .contains("if ! experiments_marked; then").contains("FAILED=1").contains("finish").doesNotContain("CLUSTER_UP");
        assertThat(harness.indexOf("[ \"$SCOPE\" != experiments ] || experiments_cluster_made")).as("the marker written once kind has made the cluster")
                .isGreaterThan(harness.indexOf("--kubeconfig \"$(dpath \"$WORK/kubeconfig\")\" --wait 180s"));
        assertThat(function("experiments_cluster_made")).contains("create configmap \"$EXPERIMENTS_MARKER\" --from-literal=\"cluster=$CLUSTER\" --from-literal=scope=experiments");
        assertThat(harness).as("the reuse branch's namespaces include the toolkit's").contains("finapp-rehearsal finapp-experiments cert-manager --ignore-not-found");
    }

    @Test
    @DisplayName("no fault goes on without the boundary passing first and its registration, and every fault comes off on every way out, read after")
    void everyFaultIsGuardedRegisteredAndClearedOnEveryWayOut() {
        String on = function("experiments_fault_on");
        assertThat(on).as("the boundary, then the registry, then the fault")
                .startsWith("\n    experiments_guard\n    printf '%s\\n' \"$*\" >> \"$FAULTS\"\n");
        for (String kind : List.of("toxic)", "proxy-down)", "hold)", "partition)", "node-paused)", "scaled)")) {
            assertThat(on).as("put on: %s", kind).contains("        " + kind);
            assertThat(function("experiments_undo")).as("taken off: %s", kind).contains("        " + kind);
        }
        assertThat(on).as("a node paused only if it is this cluster's").contains("|| fail \"the safety boundary refused to pause $1: it is not a node of $CLUSTER\"");
        String finish = function(harness, "finish");
        assertThat(finish.indexOf("    experiments_clear_faults\n")).as("the faults cleared on every way out, before the cluster may go")
                .isPositive().isLessThan(finish.indexOf("\"$KIND_BIN\" delete cluster --name \"$CLUSTER\""));
        assertThat(harness).as("the exit and signal traps both lead to finish").contains("trap 'FAILED=2; finish' INT TERM")
                .contains("trap 'status=$?; if [ -z \"$FINISHED\" ]; then [ \"$status\" = 0 ] || FAILED=2; finish; fi' EXIT");
        String clear = function("experiments_clear_faults");
        assertThat(clear.indexOf("dock unpause \"$node\"")).as("a paused node first: every exec after it goes through a kubelet")
                .isPositive().isLessThan(clear.indexOf("experiments_undo $fault"));
        assertThat(clear).as("the registry newest first, then a reset that needs no record, then what is left read from the cluster")
                .contains("for (i = NR; i >= 1; i--)").contains("experiments_toxiproxy toxic remove -n \"$toxic\" \"$proxy\"")
                .contains("experiments_undo proxy-down \"$proxy\"").contains("for flag in hold-payouts hold-inquiries; do")
                .contains("delete networkpolicy -l finapp/experiment-fault=true").contains("left=$(experiments_faults_left)")
                .contains("[ \"$FAILED\" != 0 ] || FAILED=1");
        String left = function("experiments_faults_left");
        assertThat(left).as("read, never trusted: an unreadable proxy or simulator is a fault left on")
                .contains("|| echo \"the proxy could not be read\"").contains("|| echo \"the simulator could not be read\"")
                .contains("not its three").contains("--filter status=paused");
        String stop = function("experiments_stop");
        assertThat(stop).as("an experiment's stop removes its faults and reads that none is left").contains("experiments_fault_off $fault")
                .contains("[ -z \"$left\" ] || fail");
    }

    @Test
    @DisplayName("every experiment keeps the step shape - steady, fault, observe, stop, recover, census - and fails on its stop condition or a page")
    void everyExperimentKeepsTheStepShape() {
        String run = function("experiment_run");
        List<String> order = List.of("step \"$1-steady\"", "experiments_guard", "experiments_steady", "step \"$1-fault\"", "step \"$1-observe\"",
                "step \"$1-stop\"", "experiments_stop", "step \"$1-recover\"", "step \"$1-census\"", "census \"$1\"", "firing=$(experiments_pages_firing)");
        int at = -1;
        for (String part : order) {
            int next = run.indexOf(part, at + 1);
            assertThat(next).as("%s after the step before it", part).isGreaterThan(at);
            at = next;
        }
        assertThat(run).as("a stop condition or a page fails it, after the recovery and the census have run")
                .contains("fail \"$1 FAILED: $EXP_STOPPED").contains("experiments_record verdict PASSED");
        assertThat(run.indexOf("fail \"$1 FAILED")).isGreaterThan(run.indexOf("census \"$1\""));
        assertThat(function("experiments_steady")).as("the steady state read, never assumed")
                .contains("experiments_topology_check").contains("correctness pages firing before the fault").contains("a fault is on");
        assertThat(function("experiments_pages_firing")).as("a Prometheus that did not answer is not a silent page").contains("echo UNREADABLE");
        assertThat(function("experiments_pages_setup")).as("SLOS.md section 3's pages, each a rule the cluster loaded")
                .contains("sed -n '/^## 3\\. /,/^## 4\\. /s/^| `\\([A-Za-z]*\\)` |.*/\\1/p'").contains("is not a rule the cluster's Prometheus loaded");
    }

    @Test
    @DisplayName("E01 kills the JVM with SIGKILL while a claim is in flight on it, and measures the takeover, the stop condition and the edge's failures")
    void e01MeasuresWhatItsRowNames() {
        assertThat(function("experiments_kill_jvm")).as("SIGKILL from the node, the PID namespace's parent, to the application container's process")
                .contains("crictl inspect --output go-template --template '{{.info.pid}}'").contains("dock exec \"$node\" kill -9 \"$pid\"")
                .contains("|| fail \"the safety boundary refused to kill on $node: it is not a node of $CLUSTER\"");
        String fault = function("E01_fault");
        assertThat(fault).as("a payout held at the provider, its claim IN_PROGRESS on the victim, before the kill")
                .contains("experiments_fault_on hold hold-payouts").contains("e01_payout \"$E01_VICTIM_IP\" first &")
                .contains("[ \"$claim\" = IN_PROGRESS ] || cannot").contains("experiments_context_check || fail");
        assertThat(fault.indexOf("[ \"$claim\" = IN_PROGRESS ]")).isLessThan(fault.indexOf("experiments_kill_jvm"));
        String observe = function("E01_observe");
        assertThat(observe).as("the stop condition: a second replica unready while the killed one is replaced")
                .contains("$3 != v && $4 != \"True\"").contains("EXP_STOPPED=\"a second replica unready while the killed one was replaced");
        assertThat(observe).as("a SIGKILL seen as one, the replica back, its claim taken over and completed once by another")
                .contains("[ \"$exit_code\" = 137 ] || fail").contains("e01_payout \"$E01_OTHER_IP\" \"retry-$tries\"")
                .contains("[ \"$(e01_claim)\" = COMPLETED ] || fail").contains("EXPERIMENTS_PAYOUTS=$((EXPERIMENTS_PAYOUTS + 1))");
        assertThat(observe).as("a correctness page during the experiment stops it").contains("EXP_STOPPED=\"a correctness page during the experiment");
        String recover = function("E01_recover");
        assertThat(recover).as("every failed answer the edge gave the killed replica's, the others serving, none unresolved")
                .contains("\"DownstreamStatus\"").contains("\"ServiceAddr\"").contains("[ \"$failed_other\" = 0 ] || fail")
                .contains("[ \"$served_other\" -ge 1 ] || fail").contains("[ \"$(workload_unresolved e01)\" = 0 ] || fail").contains("assert_flows_ran e01");
        assertThat(function(harness, "census")).as("the census counts E01's payout").contains("+ ${EXPERIMENTS_PAYOUTS:-0} ))");
        assertThat(function("e01_payout")).as("the merchant's key on stdin, never a command line").contains("IFS= read -r bearer")
                .contains("printf '%s\\n' \"$E01_MERCHANT\" | kube -n finapp-edge exec -i np-edge");
    }

    @Test
    @DisplayName("each probe is wired: the boundary answering without reading, and the harness dying with every kind of fault on, by an error and by a signal")
    void everyProbeIsWired() {
        assertThat(function("experiments_refuses")).contains("if [ \"$PROBE\" = guard-off ]; then\n        return 0");
        assertThat(function("experiments_scope")).contains("case $PROBE in crash-with-faults | term-with-faults) experiments_crash ;; esac")
                .contains("[ \"$PROBE\" != guard-off ] || fail");
        String crash = function("experiments_crash");
        for (String kind : List.of("toxic", "proxy-down", "hold", "partition", "scaled", "node-paused")) {
            assertThat(crash).as("the crash probes put on a %s", kind).contains("experiments_fault_on " + kind + " ");
        }
        assertThat(crash).contains("kill -TERM $$").contains("\n    false\n");
        assertThat(harness).contains("guard-off, crash-with-faults and").contains("term-with-faults");
    }
}
