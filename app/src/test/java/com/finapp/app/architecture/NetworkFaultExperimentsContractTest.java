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
 * What the network, DNS, connection and disk faults hold about themselves ({@code P16-TSK-021}; {@code RESILIENCE_EXPERIMENTS.md} E07,
 * E11-E16 and E24; {@code OPERATIONS_RUNBOOK.md} section 11.47), built on the failure-injection toolkit that
 * {@code ExperimentsRehearsalContractTest} holds.
 *
 * <p>No experiment here has run on a cluster: their runs are owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims a
 * result. What a hermetic test can hold is the text a run would execute: that each new kind of fault passes the boundary, is registered
 * and comes off on every way out; that each experiment measures what its register row names; that a fault whose own positive control does
 * not show reads as a run that could not measure, never as a pass; and that the entry's probe - a cut on a commit - can only end whole,
 * absent, or not counted.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the network, DNS, connection and disk faults hold what they say they hold (P16-TSK-021)")
class NetworkFaultExperimentsContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String VOLUME = "infra/scripts/kind-rehearsal/experiments/limited-volume.yaml";
    private static final String REGISTER = "docs/architecture/RESILIENCE_EXPERIMENTS.md";
    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final List<String> MINE = List.of("E07", "E11", "E12", "E13", "E14", "E15", "E16", "E24");

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
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(experiments);
        assertThat(start.find()).as("experiments.sh defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(experiments);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return experiments.substring(start.end(), end.start());
    }

    @Test
    @DisplayName("each new kind of fault passes the boundary and the registry first, comes off at the stop and on every way out, and is read after")
    void everyNewKindIsGuardedRegisteredAndCleared() {
        String on = function("experiments_fault_on");
        assertThat(on).as("the boundary, then the registry, then the fault").startsWith("\n    experiments_guard\n    printf '%s\\n' \"$*\" >> \"$FAULTS\"\n");
        for (String kind : List.of("blackhole)", "held)", "locked)", "filled)")) {
            assertThat(on).as("put on: %s", kind).contains("        " + kind);
            assertThat(function("experiments_undo")).as("taken off: %s", kind).contains("        " + kind);
        }
        assertThat(on).as("a black hole and a ballast only on a node of this cluster")
                .contains("experiments_netns \"$1\" || fail \"the safety boundary refused to cut $1: $EXP_REASON\"")
                .contains("|| fail \"the safety boundary refused to fill a volume on $1: it is not a node of $CLUSTER\"");
        assertThat(function("experiments_netns")).as("the node's cluster read from its container's own label, as E01's kill does")
                .contains("{{index .Config.Labels \"io.x-k8s.kind.cluster\"}}").contains("= \"$CLUSTER\" ]");
        assertThat(function("experiments_faults_left")).as("what is left of the new kinds is read with the rest").contains("experiments_faults_left_021");
        String left = function("experiments_faults_left_021");
        assertThat(left).as("a marked rule in any replica, a holder's or a lock's session, a ballast on any node, each read from the cluster")
                .contains("iptables -S").contains("--comment $EXPERIMENTS_FAULT_COMMENT").contains("application_name = '$EXPERIMENTS_HOLDER'")
                .contains("application_name = '$EXPERIMENTS_LOCKER'").contains("test -e \"$EXPERIMENTS_LIMITED/$EXPERIMENTS_BALLAST\"")
                .contains("the database could not be read for the holder's or the lock's sessions");
        String clear = function("experiments_clear_faults");
        assertThat(clear.indexOf("experiments_blackhole_clear")).as("the blanket reset covers the new kinds before what is left is read")
                .isPositive().isLessThan(clear.indexOf("left=$(experiments_faults_left)"));
        assertThat(clear).contains("experiments_holder_stop").contains("experiments_sessions_ended \"$EXPERIMENTS_LOCKER\"")
                .contains("rm -f \"$EXPERIMENTS_LIMITED/$EXPERIMENTS_BALLAST\"");
        String crash = function("experiments_crash");
        for (String kind : List.of("blackhole", "held", "locked", "filled")) {
            assertThat(crash).as("the crash probes put on a %s too", kind).contains("experiments_fault_on " + kind + " ");
        }
        assertThat(crash.indexOf("experiments_fault_on held ")).as("before the node is paused and the harness dies")
                .isLessThan(crash.indexOf("experiments_fault_on node-paused"));
    }

    @Test
    @DisplayName("a black hole is a DROP both ways in one replica's own namespace, never a NetworkPolicy, which cannot cut a held connection")
    void aBlackHoleIsADropInOneReplicasNamespace() {
        assertThat(function("experiments_in_netns")).contains("dock exec \"$NS_NODE\" nsenter -t \"$NS_PID\" -n \"$@\"");
        String rules = function("experiments_blackhole_rules");
        assertThat(rules).as("what it sends and what comes back, both dropped, marked")
                .contains("OUTPUT -p tcp -d \"$2\" --dport \"$3\" -m comment --comment \"$EXPERIMENTS_FAULT_COMMENT\" -j DROP")
                .contains("INPUT -p tcp -s \"$2\" --sport \"$3\" -m comment --comment \"$EXPERIMENTS_FAULT_COMMENT\" -j DROP")
                .doesNotContain("REJECT");
        assertThat(function("experiments_blackhole_clear")).as("every marked rule removed, and none left read after")
                .contains("sed 's/^-A /-D /'").contains("= 0 ]");
        assertThat(function("E14_fault")).contains("experiments_fault_on blackhole \"$E14_VICTIM\" \"$E14_PROXY\" 5432").doesNotContain("partition");
        assertThat(experiments).as("the reason is written where the kind is").contains("A NetworkPolicy cannot do it: policies only add what a pod may reach");
    }

    @Test
    @DisplayName("a toxic takes its stream and its toxicity as the CLI's own flags, and a proxy is armed with the boundary and fired only once armed")
    void theToxicsFlagsAndTheArmedProxy() {
        String on = function("experiments_fault_on");
        assertThat(on).contains("stream=upstream) printf -- '-u ' ;;").contains("toxicity=*) printf -- '--tox %s ' \"${attribute#toxicity=}\" ;;")
                .contains("*) printf -- '-a %s ' \"$attribute\" ;;");
        String armed = function("experiments_fault_armed");
        assertThat(armed).as("a disabled proxy alone, from an enabled one, behind the whole boundary, registered before its moment")
                .contains("[ \"$1\" = proxy-down ] || cannot").contains("$4 == \"enabled\"").contains("experiments_guard")
                .contains("printf '%s\\n' \"$*\" >> \"$FAULTS\"");
        assertThat(armed.indexOf("experiments_guard")).isLessThan(armed.indexOf(">> \"$FAULTS\""));
        String fire = function("experiments_fault_fire");
        assertThat(fire).as("never a fault not armed, never an arm older than its bound")
                .contains("grep -qxF \"$*\" \"$FAULTS\" || fail \"the fault $* was fired without being armed")
                .contains("-le 300 ] || cannot").contains("experiments_toxiproxy toggle \"$2\"");
        assertThat(fire.indexOf("grep -qxF")).isLessThan(fire.indexOf("experiments_toxiproxy toggle"));
    }

    @Test
    @DisplayName("E07 holds every ordinary slot but the superuser's reserve, takes the pools' share with the proxy down, and admits the operator over TLS")
    void e07HoldsTheSlotsAndAdmitsTheOperator() {
        String holder = function("experiments_holder_start");
        assertThat(holder).as("a role with no superuser attribute, on the server's own socket, to the maintenance database, every free slot taken")
                .contains("CREATE ROLE finapp_experiment_holder LOGIN NOSUPERUSER").contains("-U finapp_experiment_holder -d postgres")
                .contains("current_setting('\\''superuser_reserved_connections'\\'')").contains("current_setting('\\''reserved_connections'\\'')")
                .contains("[ ! -f /tmp/finapp-experiment-holder.stop ]").contains("\n        wait'");
        String fault = function("E07_fault");
        assertThat(fault).as("the holder, then the proxy down until the fleet holds none and no slot is free, then the proxy back")
                .contains("experiments_fault_on held 720").contains("experiments_fault_on proxy-down postgres")
                .contains("0'|'*'|'0 | 0'|'*'|'-*) ;;").contains("experiments_fault_off proxy-down postgres");
        assertThat(fault.indexOf("experiments_fault_on held")).isLessThan(fault.indexOf("experiments_fault_on proxy-down"));
        String observe = function("E07_observe");
        assertThat(observe).as("the control: an ordinary role refused; the criterion: the superuser over TLS, its password never leaving the pod")
                .contains("case $refused in *reserved*) ;; *) cannot").contains("PGPASSWORD=${POSTGRES_PASSWORD} exec psql \"host=127.0.0.1")
                .contains("sslmode=require").contains("|| fail \"E07: an operator's TLS session as the superuser was not admitted");
        assertThat(observe).as("the stop: the starved pools unseen within the register's ten minutes")
                .contains("$((E07_TAKEN_AT + 600))").contains("experiments_alert_firing ConnectionPoolStarved")
                .contains("EXP_STOPPED=\"ConnectionPoolStarved did not fire within 600 s");
        assertThat(function("E07_recover")).as("no replica restarted, the workload resolved")
                .contains("liveness must not read the database").contains("experiments_workload_end e07");
        assertThat(function("E07_steady")).contains("experiments_start_workload e07 900");
    }

    @Test
    @DisplayName("E11 reads the application's settings by key and needs a store to stop: none named is a run that could not measure, never a pass")
    void e11ReadsTheSettingsAndNeedsAStore() {
        String steady = function("E11_steady");
        assertThat(steady).as("keys only, never a value, and an object store among them refused")
                .contains("{{range $k, $v := .data}}{{$k}}{{\"\\n\"}}{{end}}").contains("grep -iE 'S3|BUCKET|MINIO|OBJECT_?STORE'")
                .contains("|| fail \"E11: the application's environment names an object store")
                .contains("[ -n \"${REHEARSAL_E11_STORE:-}\" ] || cannot").contains("SHOW archive_mode");
        String observe = function("E11_observe");
        assertThat(observe).as("the archiver's failures the control, no failure at the edge the criterion")
                .contains("cannot \"E11: the archiver never failed while the store was away").contains("[ \"$failed\" = 0 ] || fail")
                .contains("pg_switch_wal()");
        assertThat(function("E11_recover")).contains("the archive did not catch up");
    }

    @Test
    @DisplayName("E12 steps the latency 50, 200 and 1000 ms with a baseline, and stops at a step's p99 past 30 s")
    void e12StepsTheLatency() {
        String observe = function("E12_observe");
        assertThat(observe).contains("for ms in 50 200 1000; do").contains("[ \"$3\" -gt 30000 ]").contains("EXP_STOPPED=\"the p99 of the edge's answers")
                .contains("hikaricp_connections_pending").contains("hikaricp_connections_active");
        assertThat(function("e12_latency")).as("one latency at a time, the step before taken off")
                .contains("experiments_fault_off toxic postgres \"e12-latency-$E12_CURRENT\" latency \"latency=$E12_CURRENT\"")
                .contains("experiments_fault_on toxic postgres \"e12-latency-$1\" latency \"latency=$1\"");
        assertThat(function("E12_steady")).contains("${E12_BASELINE_SECONDS:-60}").contains("base\\t%s\\t%s\\n");
        assertThat(function("experiments_percentiles")).as("a step's p99 over the answers that started in it").contains("$1 >= from && $1 < to")
                .contains("NR * 0.99");
    }

    @Test
    @DisplayName("E13 resets 1% then 10% of the links, drawn afresh, and the resets counted, or it could not measure")
    void e13ResetsAndCountsThem() {
        assertThat(function("e13_resets")).as("both ways at the toxicity given")
                .contains("reset_peer timeout=0 \"toxicity=$1\"\n").contains("reset_peer timeout=0 \"toxicity=$1\" stream=upstream");
        String observe = function("E13_observe");
        assertThat(observe).contains("for p in 0.01 0.10; do").contains("${E13_STEP_SECONDS:-600}").contains("e13_resets_off\n            e13_resets \"$p\"")
                .contains("hikaricp_connections_creation_seconds_count").contains("[ \"$E13_MADE\" -ge 1 ] || cannot");
        assertThat(observe.indexOf("experiments_workload_end e13")).as("the cuts run once the workload has stopped")
                .isPositive().isLessThan(observe.indexOf("e13_cut_before")).isLessThan(observe.indexOf("e13_cut_on_commit"));
    }

    @Test
    @DisplayName("the probe: a transfer cut inside its transaction leaves nothing, one cut on its commit is whole, each retried by key to one effect")
    void theProbeIsWholeOrAbsentNeverHalf() {
        String before = function("e13_cut_before");
        assertThat(before).as("inside the transaction: waiting on the run's lock, having written, then the cut, then the lock released")
                .contains("experiments_fault_on locked transfers.transfer 300").contains("experiments_fault_armed proxy-down postgres")
                .contains("wait_event_type = 'Lock' AND backend_xid IS NOT NULL AND query ILIKE '%transfers.transfer%'")
                .contains("[ \"$effect\" = \"0|0|none\" ] || fail \"E13: a transfer cut inside its transaction left part of itself")
                .contains("[ \"$effect\" = \"1|1|COMPLETED\" ] || fail");
        assertThat(before.indexOf("experiments_fault_fire proxy-down postgres")).isLessThan(before.indexOf("experiments_fault_off locked"));
        assertThat(before).as("an answered transfer was not cut: could not run, never a pass").contains("200 | 201) cannot");
        String commit = function("e13_cut_on_commit");
        assertThat(commit).as("its answer held, its commit read on the server's socket, the proxy cut at that moment")
                .contains("experiments_fault_on toxic postgres e13-hold latency latency=1500").contains("e13_watch \"$ref\"")
                .contains("grep -q '^committed$'").contains("experiments_fault_fire proxy-down postgres");
        assertThat(commit).as("whole, and its retry answering the committed transfer; a missed cut retried, never counted as the probe")
                .contains("[ \"$effect\" = \"1|1|COMPLETED\" ] || fail \"E13: a transfer read committed and cut on its answer is not whole")
                .contains("[ \"$E13_RETRIED_ID\" = \"$committed\" ]").contains("while [ \"$attempt\" -lt 3 ]; do")
                .contains("cannot \"E13: in three attempts the cut never landed between a commit and its answer\"");
        assertThat(function("e13_watch")).as("read on the database's own socket, never through the proxy")
                .contains("kube -n finapp exec finapp-postgres-0 -c postgres -- sh -c").contains("psql -q -X -A -t -U finapp -d finapp");
        assertThat(function("e13_effect")).as("the transfer, its journal entry and its own claim")
                .contains("FROM transfers.transfer WHERE reference = '$1'").contains("JOIN transfers.transfer t ON t.journal_entry_id = j.id")
                .contains("WHERE scope = 'transfer.execute' AND idempotency_key = '$2'");
        // The hold stays under the pool's validation, read from the configuration itself, or the pools would drop every connection.
        Matcher validation = Pattern.compile("(?m)^\\s*validation-timeout: (\\d+)$").matcher(read("app/src/main/resources/application.yaml"));
        assertThat(validation.find()).as("the data source states its validation timeout").isTrue();
        assertThat(1500).as("the commit's held answer is shorter than the pool's validation").isLessThan(Integer.parseInt(validation.group(1)));
        // The cut transfers are the rehearsal's own references, which the census reads, and the census counts them.
        assertThat(commit + before).contains("ref=\"wl-e13-");
        assertThat(read("infra/scripts/kind-rehearsal/census.sql")).contains("WHERE reference LIKE 'wl-%' OR reference LIKE 'smoke-%'");
        assertThat(harness).contains("+ ${LOAD_TRANSFERS:-0} + ${EXPERIMENTS_TRANSFERS:-0} ))");
        assertThat(before + commit).contains("EXPERIMENTS_TRANSFERS=$((EXPERIMENTS_TRANSFERS + 1))");
    }

    @Test
    @DisplayName("E14 measures each connection the black hole holds - first held, freed by the kernel or outliving the cut - with its controls")
    void e14MeasuresTheBlackHoledConnection() {
        assertThat(function("experiments_socket_sampler")).as("the namespace's established sockets to the hop, a running retransmission timer read as held")
                .contains("ss -tnoH state established dst \"$4:$5\"").contains("/timer:\\(on,/").contains("sockets-read.tsv");
        String steady = function("E14_steady");
        assertThat(steady).contains("sysctl -n net.ipv4.tcp_retries2").contains("the data source sets no socket timeout");
        String observe = function("E14_observe");
        assertThat(observe).as("the control: something held within 60 s; the stop: a second replica unready; the end: every held one freed or the bound")
                .contains("cannot \"E14: no connection of the cut replica was held by the black hole within 60 s")
                .contains("experiments_unready_others \"$E14_VICTIM\" \"$E14_CUT_AT\"").contains("${E14_SECONDS:-900}")
                .contains("> \"$EXP_DIR/held.tsv\"").contains("the measurement the socket timeout is decided from");
        assertThat(observe).as("the cut replica out of the Service, not restarted, and the others serving")
                .contains("its readiness did not tell").contains("liveness must not read the database").contains("[ \"$served\" -ge 10 ] || fail");
        assertThat(function("E14_recover")).contains("not ready again within 600 s of the cut's removal");
    }

    @Test
    @DisplayName("E15 black-holes the broker's hop, then the provider's, with the proxy's timeout toxic both ways, each with its control")
    void e15BlackHolesTheBrokerAndTheProvider() {
        assertThat(function("E15_fault")).contains("experiments_fault_on toxic kafka e15-broker-down timeout timeout=0")
                .contains("experiments_fault_on toxic kafka e15-broker-up timeout timeout=0 stream=upstream");
        String observe = function("E15_observe");
        assertThat(observe).as("the outbox holding back the control, the stall paging, money moving, the outbox drained")
                .contains("cannot \"E15: the outbox held nothing back").contains("experiments_alert_firing OutboxPublicationStalled")
                .contains("money must still move").contains("drained 900 || fail");
        assertThat(observe).as("then the provider's hop, a 503 or an unknown outcome the control")
                .contains("experiments_fault_on toxic simulator e15-provider-down timeout timeout=0")
                .contains("experiments_fault_on toxic simulator e15-provider-up timeout timeout=0 stream=upstream")
                .contains("max(finapp_payments_unknown_age)").contains("max(finapp_merchant_payout_unknown_age)").contains("cannot \"E15: no provider call ended 503 or unknown");
        assertThat(function("E15_recover")).as("every intent and payout concluded").contains("status IN ('REQUIRES_CONFIRMATION', 'PROCESSING')")
                .contains("status NOT IN ('COMPLETED', 'FAILED')").contains("[ \"$open\" = \"0|0\" ] || fail");
    }

    @Test
    @DisplayName("E16 takes the run's own CoreDNS away, forces new connections, and must see one fail at the proxy's lookup before recovery is timed")
    void e16TakesTheResolverAway() {
        String fault = function("E16_fault");
        assertThat(fault).contains("experiments_fault_on scaled kube-system coredns 0 \"$E16_DNS\"").contains("cannot \"E16: the cluster's resolver still had a ready endpoint");
        String observe = function("E16_observe");
        assertThat(observe).as("the provider hop's links closed once, so its next calls must resolve")
                .contains("experiments_fault_on proxy-down simulator").contains("experiments_fault_off proxy-down simulator");
        assertThat(observe).as("the proxy's own line for a failed lookup, as Toxiproxy 2.13.0 writes it")
                .contains("'\"message\":\"Unable to open connection to upstream\"'").contains("'\"error\":\"dial tcp: lookup'")
                .contains("cannot \"E16: no new connection met the resolver's outage");
        assertThat(observe).as("recovery timed to a provider call answered, bounded by the register's ten minutes")
                .contains("experiments_fault_off scaled kube-system coredns 0 \"$E16_DNS\"").contains("$((E16_OUT_AT + 600))")
                .contains("EXPERIMENTS_PAYOUTS=$((EXPERIMENTS_PAYOUTS + 1))");
    }

    @Test
    @DisplayName("E24 fills a filesystem of the claim's own size from its node, outside the data directory, made only when E24 is named")
    void e24FillsAVolumeOfItsOwnSize() {
        assertThat(function("experiments_render")).contains("case \" $EXPERIMENTS \" in *\" E24 \"*) experiments_limited_volume ;; esac");
        String volume = function("experiments_limited_volume");
        assertThat(volume).as("a 2 GiB ext4 in a file, no reserved blocks, made afresh, on nodes of this cluster alone")
                .contains("truncate -s 2G /var/lib/finapp-experiments/pgdata.img").contains("mkfs.ext4 -q -F -m 0")
                .contains("mount -o loop /var/lib/finapp-experiments/pgdata.img \"$1\"").contains("umount \"$1\"")
                .contains("|| fail \"the safety boundary refused to make a volume on $node: it is not a node of $CLUSTER\"")
                .contains("storageClassName: finapp-experiments-limited").contains("\"$A/StatefulSet.finapp-postgres.yaml\"");
        String ballast = function("experiments_ballast");
        assertThat(ballast).as("at the filesystem's root, never in the data directory, and only on a mounted limited filesystem")
                .contains("grep -qs \" $1 \" /proc/mounts ||").contains("fallocate -l \"$size\" \"$1/$2\"").contains("\"$EXPERIMENTS_LIMITED\" \"$EXPERIMENTS_BALLAST\"");
        assertThat(experiments).contains("EXPERIMENTS_LIMITED=/mnt/finapp-experiments-pgdata");
        assertThat(function("E24_steady")).contains("[ \"$class\" = finapp-experiments-limited ] || cannot");
        assertThat(function("E24_fault")).contains("[ \"${free:-65537}\" -le 65536 ] || cannot");
        String observe = function("E24_observe");
        assertThat(observe).as("the first refused write looked for within the stop's five minutes, or the fault was not felt")
                .contains("$((E24_FULL_AT + 300))").contains("grep -c 'No space left on device'").contains("cannot \"E24: nothing the database wrote met the full volume");
        assertThat(function("E24_recover")).contains("pg_isready").contains("experiments_workload_end e24");
        String manifest = read(VOLUME);
        assertThat(manifest.replace("\n# ", " ")).contains("Test-only and never rendered by any overlay").doesNotContain("kind: ClusterRole")
                .contains("provisioner: kubernetes.io/no-provisioner").contains("path: /mnt/finapp-experiments-pgdata").contains("- NODE_NAME");
        assertThat(manifest.split("finapp/test-only: \"true\"\n", -1).length - 1).as("every object labelled finapp/test-only")
                .isEqualTo(manifest.split("\nkind: ", -1).length - 1);
    }

    @Test
    @DisplayName("every one of the task's experiments keeps the step shape, is registered for this task and has its runbook section")
    void everyExperimentIsShapedRegisteredAndWritten() {
        String register = read(REGISTER);
        String runbook = read(RUNBOOK);
        assertThat(runbook).contains("### 11.47 ");
        String section = runbook.substring(runbook.indexOf("### 11.47 "));
        for (String id : MINE) {
            for (String part : List.of("_steady", "_fault", "_observe", "_recover")) {
                function(id + part);
            }
            Matcher row = Pattern.compile("(?m)^\\| " + id + " \\|.*$").matcher(register);
            assertThat(row.find()).as("%s is a row of the register", id).isTrue();
            assertThat(row.group()).as("%s names this task among its runners", id).contains("`P16-TSK-021`");
            assertThat(section).as("§11.47 writes %s's signature", id).contains("**" + id);
        }
        assertThat(function("experiments_start_workload")).as("a widened retry window is the workload's own, and taken back after")
                .contains("WORKLOAD_RETRY_SECONDS=$2").contains("unset WORKLOAD_RETRY_SECONDS");
    }
}
