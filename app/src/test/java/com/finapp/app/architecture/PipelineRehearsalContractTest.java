package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the event pipeline's rehearsal holds about itself ({@code P16-TSK-013}; {@code PHASE_16_PLAN.md} section 7;
 * {@code OPERATIONS_RUNBOOK.md} section 11.51): the test-only cluster of three brokers, a proxy per broker, the experiments E08, E09,
 * E22, E23 and E49, the pipeline's load runs E47 and E48, and the entry's probe, min-isr-one.
 *
 * <p>None of them has run on a cluster: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025), and
 * every result here is a run's, recorded in the resilience register and the runbook when it happens. What a hermetic test holds is
 * that the text keeps what makes each run a measurement and each fault safe: the brokers' posture is the catalogue's, every broker is
 * behind the proxy, every experiment counts what its register row names from PostgreSQL rather than from the broker, and a check that
 * could pass on nothing has its control.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the event pipeline's rehearsal holds what it says it holds (P16-TSK-013)")
class PipelineRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String BROKERS = "infra/scripts/kind-rehearsal/brokers.sh";
    private static final String PIPELINE = "infra/scripts/kind-rehearsal/pipeline.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String OVERLAY = "deploy/overlays/kind/kafka.yaml";
    private static final String POLICIES = "deploy/overlays/kind/networkpolicy-test-only.yaml";
    private static final String PROXY = "infra/scripts/kind-rehearsal/experiments/toxiproxy.yaml";
    private static final String PROFILE = "infra/scripts/kind-rehearsal/load/pipeline.json";

    private static Path root;
    private static String harness;
    private static String brokers;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        brokers = read(BROKERS);
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

    @Test
    @DisplayName("three brokers where the steps are built for three, refused elsewhere, resolved before anything is built")
    void threeBrokersOnlyWhereTheStepsAreBuiltForThree() {
        String resolve = function(brokers, "brokers_resolve");
        assertThat(resolve).as("three in the experiments scope, one or three in the load scope, one elsewhere")
                .contains("[ \"$SCOPE\" != experiments ] || KAFKA_BROKERS=3").contains("experiments:3 | load:1 | load:3) ;;")
                .contains("experiments:*) echo").contains("*:1) ;;").contains("exit 2");
        assertThat(harness.indexOf("\nbrokers_resolve\n")).as("resolved once the scope is read, before the build").isPositive()
                .isGreaterThan(harness.indexOf("[ \"$SCOPE\" != experiments ] || N_ONLY=1")).isLessThan(harness.indexOf("step build-images"));
        assertThat(harness.indexOf("brokers_render \"$A\"")).as("the run's own copy rendered after the fleet's patch and before anything is applied")
                .isGreaterThan(harness.indexOf("[ \"$SCOPE\" != load ] || load_replicas_patch \"$A\"")).isLessThan(harness.indexOf("apply_files \"$file\""));
        assertThat(read(OVERLAY)).as("the committed overlay keeps one broker, three-capable").contains("  replicas: 1\n")
                .doesNotContain("podManagementPolicy");
        String render = function(brokers, "brokers_render");
        assertThat(render).as("three started together, published before ready, the quota grown by each broker past the first, each change read back")
                .contains("[ \"$KAFKA_BROKERS\" != 1 ] || return 0").contains("podManagementPolicy: Parallel").contains("publishNotReadyAddresses: true")
                .contains("extra=$((KAFKA_BROKERS - 1))").contains("|| cannot \"the rendered broker StatefulSet could not be set");
    }

    @Test
    @DisplayName("at three, the catalogue's posture whole, SCRAM over TLS on every broker, the quorum and replication over mutual TLS")
    void theBrokersKeepTheCataloguesPostureAndAuthenticateEachOther() {
        String config = function(brokers, "brokers_config");
        String three = config.substring(config.indexOf("# The posture, whole"));
        assertThat(three).as("broker.properties unchanged at three: replication 3, two in sync, no unclean election")
                .contains("sed -e '/^#/d' -e '/^$/d' \"$ROOT/infra/kafka/broker.properties\"").doesNotContain("replication.factor=1")
                .doesNotContain("min.insync.replicas=1");
        assertThat(three).as("four listeners: SCRAM clients, the quorum and replication on mutual TLS, loopback")
                .contains("listeners=SASL_SSL://:9092,CONTROLLER://:9093,LOCAL://localhost:9094,INTERNAL://:9095")
                .contains("listener.security.protocol.map=CONTROLLER:SSL,INTERNAL:SSL,LOCAL:PLAINTEXT,SASL_SSL:SASL_SSL")
                .contains("listener.name.controller.ssl.client.auth=required").contains("listener.name.internal.ssl.client.auth=required")
                .contains("inter.broker.listener.name=INTERNAL").contains("ssl.truststore.location=/etc/finapp-kind-kafka-tls/truststore.pem")
                .contains("super.users=User:ANONYMOUS;User:CN=kafka").doesNotContain("node.id=").doesNotContain("advertised.listeners=");
        assertThat(three).as("every broker a voter by its own name").contains("voters=\"${voters:+$voters,}$((i + 1))@$(brokers_name \"$i\"):9093\"");
        assertThat(harness).as("the super user is the certificate's subject, the brokers' certificate a client's too and naming every broker")
                .contains("(cd \"$CA_DIR\" && openssl req -new -newkey rsa:2048 -nodes -subj \"/CN=$1\"")
                .contains("issue kafka \"$(brokers_sans)\" serverAuth,clientAuth").contains("extendedKeyUsage=%s\\n' \"$2\" \"${3:-serverAuth}\"")
                .contains("secret_of finapp finapp-kind-kafka-tls \"keystore.pem=$CA_DIR/kafka-keystore.pem\" \"truststore.pem=$CA_DIR/ca.crt\"");
        assertThat(function(brokers, "brokers_sans")).contains("sans=\"$sans,DNS:$(brokers_name \"$i\")\"");
        String start = read(OVERLAY);
        assertThat(start).as("a pod of several takes its node id and its own advertised names from its own name, and only then")
                .contains("if ! grep -q '^node.id=' /tmp/server.properties; then").contains("finapp-kafka-*:[0-9] | finapp-kafka-*:[0-9][0-9]) ;;")
                .contains("\"$((ordinal + 1))\" \"$name\" \"$name\" >> /tmp/server.properties")
                .contains("SASL_SSL://%s:9092,INTERNAL://%s:9095,LOCAL://localhost:9094").contains("fieldPath: metadata.name");
        assertThat(function(brokers, "brokers_replication")).as("the topics at the catalogue's replication and min.insync.replicas at three")
                .contains("replication-factor:").contains("min.insync.replicas:").contains("[ \"$in_sync\" -lt \"$factor\" ]");
        assertThat(harness).as("created at that posture").contains("--replication-factor \"$topic_replication\"")
                .contains("\"min.insync.replicas=${replication#* }\"").contains("\nbrokers_check\n");
        String check = function(brokers, "brokers_check");
        assertThat(check).as("every broker ready, a quorum of three with a leader, every topic at the posture, nothing out of sync")
                .contains("[ \"$voters\" = \"$KAFKA_BROKERS\" ]").contains("[ \"$posture\" = \"$topics\" ]").contains("--under-replicated-partitions")
                .contains("[ \"$under\" = 0 ]");
        String admin = function(harness, "kafka_admin");
        assertThat(admin).as("at three the tools on the mutual-TLS listener, which names every broker; at one, loopback as before")
                .contains("--bootstrap-server localhost:9094").contains(":9095\" \\\n            --command-config /tmp/admin.properties");
        String policies = read(POLICIES);
        String peers = policies.substring(policies.indexOf("name: finapp-kafka-peers"));
        assertThat(peers).as("the brokers reach one another on 9093 and 9095, and nothing else is admitted there")
                .contains("port: 9093").contains("port: 9095").doesNotContain("namespaceSelector").doesNotContain("ipBlock").doesNotContain("9092");
    }

    @Test
    @DisplayName("every broker behind a proxy of its own, its name sent to its own address, the boundary reading all six proxies")
    void everyBrokerIsBehindItsOwnProxy() {
        String proxy = read(PROXY);
        for (int i = 0; i < 3; i++) {
            assertThat(proxy).as("broker %s's Service sends 9092 to its proxy", i)
                    .contains("name: finapp-toxiproxy-kafka-" + i + "\n").contains("targetPort: kafka-" + i + "\n")
                    .contains("- name: kafka-" + i + "\n              containerPort: " + (19092 + i));
        }
        assertThat(proxy.split("port: 19092\n", -1)).as("the proxy admits, and the application may reach, each broker's port").hasSize(3);
        assertThat(function(experiments, "experiments_endpoints_check")).as("the boundary holds the four alias entries and the six upstreams")
                .contains("[ \"$aliases\" = \"$expected\" ]").contains("[ \"$upstreams\" = \"$wanted_upstreams\" ]");
    }

    @Test
    @DisplayName("E08 kills the quorum leader's broker and counts, through a survivor, every write's copies, its control shown")
    void e08CountsTheCopiesOfEveryWrite() {
        String steady = function(experiments, "E08_steady");
        assertThat(steady).as("the victim the quorum's leader, the tools through a survivor, every partition sampled from before the kill")
                .contains("E08_VICTIM=\"finapp-kafka-$((leader - 1))\"").contains("KAFKA_ADMIN_POD=finapp-kafka-$(( leader % KAFKA_BROKERS ))")
                .contains("experiments_isr_sampler \"$EXP_DIR\" &").contains("start_workload e08 retry");
        assertThat(function(experiments, "experiments_kill_broker")).as("SIGKILL from the node, behind the boundary")
                .contains("{.status.containerStatuses[?(@.name==\"kafka\")].containerID}").contains("dock exec \"$node\" kill -9 \"$pid\"")
                .contains("experiments_context_check || fail");
        String observe = function(experiments, "E08_observe");
        assertThat(observe).as("the stop condition: two brokers down")
                .contains("[ \"$down\" -ge 2 ] && [ -z \"$EXP_STOPPED\" ]").contains("EXP_STOPPED=\"two brokers down at once\"");
        assertThat(observe).as("no write acknowledged by fewer than min.insync.replicas copies, with the control that the kill was seen to shrink a set")
                .contains("experiments_single_replica_writes \"$EXP_DIR/isr.tsv\" \"$(brokers_replication | cut -d' ' -f2)\"")
                .contains("[ \"$samples\" -ge 3 ] && [ \"$under_sampled\" -ge 1 ] || fail").contains("[ \"$1\" = 0 ] || fail \"E08:")
                .contains("[ \"$exit_code\" = 137 ] || fail");
        assertThat(function(experiments, "experiments_single_replica_writes")).as("a write between two readings that both show too few copies")
                .contains("last_isr[k] < need && $4 < need && $5 > last_end[k]");
        assertThat(function(experiments, "E08_recover")).as("no replica unready, nothing abandoned")
                .contains("[ \"$unready\" = 0 ] || fail").contains("[ \"$(experiments_dead_lettered)\" = 0 ] || fail");
    }

    @Test
    @DisplayName("E09 stops every broker for ten minutes at least and until the stall pages, money moving, the fleet ready, nothing abandoned")
    void e09HoldsTheOutageUntilItPages() {
        String observe = function(experiments, "E09_observe");
        assertThat(function(experiments, "E09_fault")).contains("experiments_fault_on brokers-stopped").contains("until [ -z \"$(brokers_ready)\" ]");
        assertThat(observe).as("held 10 minutes and until OutboxPublicationStalled fires, 15 at most, the stop at 30")
                .contains("[ \"$held\" -ge 600 ] && { [ -n \"$stalled\" ] || [ \"$held\" -ge 900 ]; }").contains("[ \"$held\" -ge 1800 ]")
                .contains("'ALERTS{alertname=\"OutboxPublicationStalled\",alertstate=\"firing\"}'")
                .contains("EXP_STOPPED=\"OutboxPublicationStalled did not fire within 15 minutes of the outage\"");
        assertThat(observe).as("readiness holds, money moves, rows wait, the freshness burns, nothing abandoned")
                .contains("[ \"$unready\" = 0 ] || fail").contains("[ \"$moved\" -ge 1 ] || fail").contains("[ \"$pending\" -ge 1 ] || fail")
                .contains("[ -n \"$burning\" ] || fail").contains("[ \"$(experiments_dead_lettered)\" = 0 ] || fail");
        assertThat(function(experiments, "E09_recover")).as("then drained and settled")
                .contains("drained 900 || fail").contains("lag_settled e09 || fail");
        assertThat(function(experiments, "experiments_undo")).contains("brokers-stopped) kube -n finapp scale statefulset/finapp-kafka --replicas=\"$KAFKA_BROKERS\"");
    }

    @Test
    @DisplayName("E22 sends published rows again and resets every group to its start with the fleet stopped, and counts no second effect")
    void e22AbsorbsEveryDuplicate() {
        String fault = function(experiments, "E22_fault");
        assertThat(fault).as("rows a handler names, unmarked as a crash between the acknowledgement and the mark leaves them")
                .contains("UPDATE platform.outbox_event e SET published_at = NULL FROM chosen c").contains("(VALUES :handlers)")
                .contains("e.replay_ordered_at IS NULL").contains("[ \"$E22_SENT\" -ge 10 ] || cannot");
        String observe = function(experiments, "E22_observe");
        assertThat(observe).as("every group reset to its earliest offset only once it has no member, the fleet at zero through the registry")
                .contains("experiments_fault_on scaled finapp finapp-app 0 3").contains("$NF != 0 {busy = 1}")
                .contains("--reset-offsets --all-groups --all-topics --to-earliest --execute").contains("experiments_fault_off scaled finapp finapp-app 0 3");
        assertThat(observe.indexOf("$NF != 0 {busy = 1}")).isLessThan(observe.indexOf("--reset-offsets"));
        assertThat(observe).as("counted from PostgreSQL and the replicas: no inbox row added, nothing failed or parked, the duplicates seen")
                .contains("[ \"$applied\" = \"$E22_APPLIED_BEFORE\" ]").contains("[ \"$failed\" = 0 ] && [ \"$parked\" = 0 ]")
                .contains("[ \"$duplicates_a\" -ge 1 ] && [ \"$duplicates_b\" -ge 1 ] || fail").contains("count(*) - count(DISTINCT source_event_id)");
        assertThat(function(experiments, "experiments_inbox_total")).as("counters read whole, not increase(), which loses what a new replica counted before its first scrape")
                .contains("sum(finapp_inbox_consumption_total{outcome=\\\"$1\\\"})").doesNotContain("increase(");
    }

    @Test
    @DisplayName("E23 holds the relay's own lock - the namespace and key OutboxRelay takes - on a few aggregates, and counts order per consumer")
    void e23DelaysWithTheRelaysOwnLock() {
        String relay = read("platform/src/main/java/com/finapp/platform/outbox/OutboxRelay.java");
        assertThat(relay).as("the relay's lock: namespace 1, the aggregate's UUID.hashCode()")
                .contains("private static final int AGGREGATE_LOCK_NAMESPACE = 1;").contains("lock.setInt(2, aggregateId.hashCode());")
                .contains("private static final int CANDIDATE_OVERSCAN = 4;").contains("this(connections, publisher, RetryPolicy.DEFAULT, 8, 100, abandonments);");
        String hold = function(experiments, "experiments_relay_hold");
        assertThat(hold).as("Java's UUID.hashCode(): the high and low halves XORed, then the high 32 bits of that XORed into its low 32")
                .contains("hilo := ('x' || substr(replace(candidate::text, '-', ''), 1, 16))::bit(64)::bigint")
                .contains("# ('x' || substr(replace(candidate::text, '-', ''), 17, 16))::bit(64)::bigint;")
                .contains("pg_try_advisory_lock(1, ((hilo >> 32) # hilo)::bit(32)::int)").contains("SET application_name = '$EXPERIMENTS_HOLD_APP';")
                .contains("SELECT pg_advisory_unlock_all();").doesNotContain("UPDATE").doesNotContain("INSERT INTO platform");
        assertThat(function(experiments, "E23_fault")).as("eight held: fewer than the 32 candidates a relay reads, so the others keep publishing")
                .contains("experiments_fault_on relay-held 600 8");
        String observe = function(experiments, "E23_observe");
        assertThat(observe).as("late rows were late, then order per consumer and aggregate against the outbox's order, nothing parked")
                .contains("[ \"${late##*|}\" -ge 300 ] || fail").contains("PARTITION BY i.consumer, e.aggregate_id ORDER BY e.event_id")
                .contains("WHERE o.before > o.expires_at").contains("[ \"$violations\" = 0 ] ||").contains("[ \"$parked\" = 0 ] ||");
        assertThat(function(experiments, "experiments_relay_release")).contains("pg_terminate_backend(pid)").contains("'$EXPERIMENTS_HOLD_APP'");
    }

    @Test
    @DisplayName("E49 replays the run's day through the audited door in windows under its bound, an audit record per order, no second effect")
    void e49ReplaysThroughTheAuditedDoor() {
        String operations = read("platform/src/main/java/com/finapp/platform/deadletter/DeadLetterOperations.java");
        assertThat(operations).as("the door's bound").contains("public static final int MAX_REPLAY_EVENTS = 1000;");
        String steady = function(experiments, "E49_steady");
        assertThat(steady).as("windows of first publication, [from, to), at most 900 each, every handled type")
                .contains("coalesce(e.first_published_at, e.published_at) AS t").contains("/ 900 AS chunk")
                .contains("lead(first_t) OVER (PARTITION BY event_type ORDER BY chunk), last_t + interval '1 millisecond'");
        String fault = function(experiments, "E49_fault");
        assertThat(fault).as("through the operator's door, each window's requeued count its own").contains("client replay \"$type\" \"$from\" \"$to\"")
                .contains("[ \"${got:-x}\" = \"$events\" ] || fail");
        String observe = function(experiments, "E49_observe");
        assertThat(observe).as("an audit record per order, every record absorbed, no inbox row added")
                .contains("operation = 'outbox.EventReplayOrdered'").contains("[ \"$audits\" = \"$windows\" ] || fail")
                .contains("[ \"$applied\" = \"$E49_APPLIED_BEFORE\" ]").contains("[ \"$duplicates\" -ge 1 ] || fail");
    }

    @Test
    @DisplayName("the probe lowers the posture with a one-copy topic beside it, holds its victim down, the copy check sees the writes, and every way out puts it back")
    void theProbeIsWiredAndUndone() {
        String fault = function(experiments, "E08_fault");
        assertThat(fault).as("the probe's fault through the registry, before the kill; the victim held down after it")
                .contains("if [ \"$PROBE\" = min-isr-one ]; then").contains("experiments_fault_on min-isr 1 \"$(brokers_replication | cut -d' ' -f2)\" \"$E08_VICTIM\"")
                .contains("[ \"$PROBE\" != min-isr-one ] || experiments_fault_on brokers-stopped \"$((KAFKA_BROKERS - 1))\"");
        assertThat(fault.indexOf("experiments_fault_on min-isr")).isLessThan(fault.indexOf("experiments_kill_broker \"$E08_VICTIM\""));
        assertThat(function(experiments, "E08_steady")).as("the probe's victim the highest ordinal, the only one a StatefulSet can hold down")
                .contains("E08_VICTIM=\"finapp-kafka-$((KAFKA_BROKERS - 1))\"");
        assertThat(function(experiments, "E08_observe")).as("held 90 s, then the StatefulSet whole again")
                .contains("[ $((now - E08_KILLED_AT)) -ge 90 ] && grep -qx \"brokers-stopped $((KAFKA_BROKERS - 1))\" \"$FAULTS\"")
                .contains("experiments_fault_off brokers-stopped \"$((KAFKA_BROKERS - 1))\"");
        String on = function(experiments, "experiments_min_isr_on");
        assertThat(on).as("two copies, on the victim and the admin broker, at n, written with acks=all")
                .contains("--replica-assignment \"$(( ${2##*-} + 1 )):$(( ${KAFKA_ADMIN_POD##*-} + 1 ))\"")
                .contains("--config \"min.insync.replicas=$1\"").contains("--producer-property acks=all");
        String off = function(experiments, "experiments_min_isr_off");
        assertThat(off).as("the topic deleted, the catalogue's value back").contains("--delete --if-exists --topic probe.min-isr")
                .contains("experiments_min_isr \"$1\"");
        assertThat(function(experiments, "experiments_faults_left")).as("read, never trusted")
                .contains("grep -qx 'probe.min-isr'").contains("[ \"$brokers\" = \"$KAFKA_BROKERS\" ]").contains("$EXPERIMENTS_HOLD_APP");
        assertThat(function(experiments, "experiments_clear_faults")).as("every way out puts the brokers, the hold and the posture back")
                .contains("experiments_undo brokers-stopped").contains("experiments_relay_release").contains("experiments_undo min-isr");
        assertThat(function(experiments, "experiments_isr_sampler")).as("the probe topic sampled beside the catalogue's").contains("/^(finapp|probe)\\./");
    }

    @Test
    @DisplayName("the pipeline at load: on three brokers, read every 30 s, judged on correctness and on being read, a scale's counts and members judged")
    void thePipelineAtLoadIsReadThroughout() {
        String profile = read(PROFILE);
        assertThat(profile).as("checkouts at the target's peak, a minute's burst at three times it, the peak again, then the mix")
                .contains("\"stages\": [[600, 1.0, \"checkout\"], [60, 3.0, \"checkout\"], [900, 1.0, \"checkout\"], [120, 0.4167]],")
                .contains("\"stress\": true,").contains("\"exec\": \"checkout\", \"rate\": 5556, \"per\": \"1000s\", \"perSecond\": 5.556, \"preAllocatedVUs\": 990, \"maxVUs\": 990");
        String pipeline = read(PIPELINE);
        assertThat(function(pipeline, "pipeline_preflight")).as("three brokers, and a scale's budget its largest count")
                .contains("[ \"$KAFKA_BROKERS\" = 3 ] || cannot").contains("[ \"$most\" = \"$LOAD_REPLICAS\" ] || cannot");
        assertThat(harness.indexOf("    pipeline_preflight\n")).isGreaterThan(harness.indexOf("    load_preflight\n"));
        String load = read(LOAD);
        String run = function(load, "load_run");
        assertThat(run.indexOf("    pipeline_start\n")).as("a scale's first count and the offsets before the load is timed").isPositive()
                .isLessThan(run.indexOf("LOAD_STARTED=$(date +%s)"));
        assertThat(run).contains("pipeline_tick \"$elapsed\" \"$secs\"");
        String census = function(load, "load_census");
        assertThat(census.indexOf("    pipeline_report\n")).as("reported after the census has counted one effect per record").isGreaterThan(census.indexOf("    census load\n"));
        assertThat(function(pipeline, "pipeline_report")).as("a pipeline nobody read throughout fails; the numbers are recorded, never a verdict")
                .contains("[ \"$gap\" -le 90 ]").contains("[ \"$partitions\" -ge 12 ] || fail").contains("pipeline-summary.txt");
        assertThat(function(pipeline, "pipeline_scale_judge")).as("each count reached, every group following it")
                .contains("$7 == c").contains("[ \"$followed\" = \"$groups\" ] && [ \"$groups\" -ge 1 ] || fail");
        assertThat(function(pipeline, "pipeline_scale_to")).as("the autoscaler held at the count, so it does not undo the scale")
                .contains("{\\\"spec\\\":{\\\"minReplicas\\\":$1,\\\"maxReplicas\\\":$1}}").contains("scale deployment/finapp-app --replicas=\"$1\"");
        assertThat(function(load, "load_judge")).as("a scaled fleet was served by every replica it had")
                .contains("{ [ -n \"$PIPELINE_SCALE\" ] && [ \"$served\" -ge \"$ready\" ]; }");
    }

    @Test
    @DisplayName("the sourced files define functions only and end in a zero status")
    void theSourcedFilesEndInAZeroStatus() {
        for (String file : List.of(BROKERS, PIPELINE)) {
            String text = read(file).stripTrailing();
            assertThat(text).as("%s ends on a function's close", file).endsWith("\n}");
            assertThat(harness).as("%s sourced at the harness's start", file)
                    .contains(". \"$DIR/" + file.substring(file.lastIndexOf('/') + 1) + "\"");
        }
    }
}
