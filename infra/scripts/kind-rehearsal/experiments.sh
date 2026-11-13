# The failure-injection toolkit (P16-TSK-019; ADR-0127; PHASE_16_PLAN.md section 9.1; OPERATIONS_RUNBOOK.md section 11.46). Sourced by
# infra/scripts/kind-rehearsal.sh at its start, as load.sh is, so it only defines functions: experiments_cluster_* run while the cluster
# is made or reused, experiments_data_tier once the test-only data tier is up, experiments_edge_addresses once the edge is, and
# experiments_scope under REHEARSAL_SCOPE=experiments once release N is deployed, smoked, its census green, the cluster suites passed and
# the telemetry pipeline up. It uses the harness's helpers (dock, dpath, kube, psql_query, client, census, start_workload, stop_workload,
# assert_flows_ran, ready_three, db_epoch, telemetry_get, urlencode, step, wanted, log, pass, fail, cannot).
#
# THE TOPOLOGY (ADR-0127 decision 4; owner decisions C6 and C7). A cluster of one control plane and three workers, each worker a zone
# (experiments/kind-cluster.yaml), every image loaded into every node. Toxiproxy, by its pinned digest, on the application's three hops
# (experiments/toxiproxy.yaml): the application's Deployment, in this scope's own rendered copy, resolves the three names it already uses to
# the proxy, and nothing else of the client changes. A synchronous standby of the test-only PostgreSQL in another zone
# (experiments/standby.yaml). The application's zone constraint is the base's DoNotSchedule again (the kind overlay relaxes it only because
# its one node has no zone). The namespace quota grows by the standby. Nothing here reaches deploy/: no overlay renders any of it.
#
# THE SAFETY BOUNDARY (ADR-0127 decision 5), checked by experiments_refuses at each steady state and before each fault put on - experiments_fault_on calls it first - and its context again just before a kill, and
# proven in every run by a plant of each kind beside its control (experiments_boundary):
#   context     the node the harness execs into is the control plane of the kind cluster $CLUSTER by its container's labels; its own
#               context is kubernetes-admin@$CLUSTER against https://$NODE:6443; the nodes the API lists are exactly the containers of
#               that cluster; and the cluster carries this harness's marker naming it (written when the scope made the cluster). A cluster
#               of the name without the marker is neither reused nor deleted: the harness refuses it before it changes anything;
#   credential  every value of every Secret of finapp, finapp-rehearsal and finapp-experiments, endpoints aside, is one the run generated
#               or set: its sha256 is in the run's ledger, which kind-secrets.sh and secret_of write as they make each value;
#   endpoint    every value of the application's environment that holds a URL is the simulator, the run's own database or the run's
#               collector, its bootstrap servers the run's broker; its hostAliases send only the run's three names to the run's proxy;
#               and the proxy's three upstreams are the run's three services.
# A refusal fails the run before any fault. The harness holds no kubeconfig: kube runs inside the node.
#
# THE FAULTS. experiments_fault_on <kind> ... registers a fault in $WORK/faults before injecting it, and experiments_fault_off removes it.
# Kinds: toxic (a Toxiproxy toxic on one proxy), proxy-down (a proxy disabled), hold (one of the simulator's hold flags), partition (a
# NetworkPolicy labelled finapp/experiment-fault), node-paused (`docker pause` of a node of this cluster) and scaled (a Deployment scaled,
# its count to restore recorded); and P16-TSK-013's brokers-stopped (the broker StatefulSet at zero, back at the run's count),
# relay-held (a database session holding the relay's own lock on a few aggregates, ended) and min-isr (every topic's min.insync.replicas
# lowered with a probe topic written beside them, the catalogue's value back and the probe topic deleted). A kill, a deletion or an eviction is instant and is logged, not registered. finish() calls
# experiments_clear_faults on every way out, the INT and TERM traps and an unguarded failure included: every registered fault undone,
# newest first, then a blanket reset that needs no record (every proxy enabled with no toxic, both hold flags removed, every labelled
# policy deleted, every paused node of this cluster unpaused), then a read that nothing is left (experiments/faults-cleared.txt).
#
# THE STEP SHAPE (ADR-0127 decision 1): experiment_run <id> runs <id>-steady, -fault, -observe, -stop, -recover and -census in that order.
# The steady state is read before the fault (the fleet ready on three nodes in three zones, the standby streaming and synchronous, every
# application session arriving through the proxy over TLS, no correctness page firing, no fault on). An experiment's stop condition, met
# while it is observed, stops it: the fault is removed and the experiment is FAILED, and its recovery and census still run so that the
# cluster is left whole and the record complete. A correctness page during or after the fault, or a failing census clause, is FAILED too
# (ADR-0127 decision 6). Each experiment's record is under $REHEARSAL_ARTEFACTS/experiments/<id>.
#
# E01 (the toolkit's own proof, at three replicas): one replica's JVM killed with SIGKILL from its node while the workload runs and a
# payout the harness sent to that replica is held at the provider with its claim IN_PROGRESS; the others serve; the dead replica's claim
# is taken over and completed once by another; every failed answer the edge gave was the killed replica's; no second replica unready
# while it was replaced (its stop condition); the census green.
#
# THE EVENT PIPELINE (P16-TSK-013; OPERATIONS_RUNBOOK.md section 11.51), on the scope's three brokers (kind-rehearsal/brokers.sh), each
# under the workload unless it says otherwise, each judged by PostgreSQL's count - every outbox row published, every record a handler
# names in its consumer's inbox, no inbox row added for a record already applied, nothing parked - and the census:
#   E08  the quorum leader's broker killed with SIGKILL: every partition's in-sync replicas and end offset sampled through a survivor, no
#        write acknowledged by fewer than min.insync.replicas copies, no replica unready, the broker back in sync (stop: two down);
#   E09  every broker stopped at least 10 minutes and until OutboxPublicationStalled fires (15 at most): money still moves, every replica
#        ready, rows wait inside the relay's retry window, the event freshness burns; then the outbox drains and the groups settle;
#   E22  published rows sent again, as a relay that crashed between the acknowledgement and its mark sends them, then every group reset
#        to its earliest offset with the fleet at zero: every applied record absorbed, counted duplicate, none failed or parked;
#   E23  the relay's own lock held on a few aggregates for 10 minutes as their rows appear: their records arrive after every other
#        aggregate's, each applied once and, per consumer and aggregate, in order;
#   E49  the run's day replayed through the audited operator door, every handled type in windows under its bound: every record absorbed,
#        an audit record per order, the replay's rate recorded.
#
# THE PROBES (REHEARSAL_PROBE), each a deliberate break that must turn the run red:
#   guard-off          the refusals answer "pass" without reading anything: every plant of experiments_boundary goes unrefused;
#   crash-with-faults  every kind of fault put on, then an unguarded failure: the exit trap must leave none on (read on a kept cluster);
#   term-with-faults   the same, then SIGTERM to the harness itself, the signal path of the same trap;
#   min-isr-one        (P16-TSK-013, with E08) every topic at min.insync.replicas 1 and a probe topic of two copies written beside them: the
#                      highest-ordinal broker killed and held down 90 s leaves the probe topic one copy in sync, its writes still acknowledged,
#                      and E08's copy check must see them.
# REHEARSAL_EXPERIMENTS names the experiments to run (E01 by default; the catalogue is P16-TSK-013's, -020...-023's and P16-TSK-027's).

EXPERIMENTS=${REHEARSAL_EXPERIMENTS:-E01}
TOXIPROXY_IMAGE="$TOXIPROXY_REPOSITORY@$TOXIPROXY_DIGEST"
EXPERIMENTS_MARKER=finapp-experiments-marker
EXPERIMENTS_CONFIG="$DIR/experiments/kind-cluster.yaml"
EXPERIMENTS_COLLECTOR=http://finapp-collector.finapp-observability.svc.cluster.local:4318/
# The census's expected payouts take this: E01's own payout, zero in every other scope.
EXPERIMENTS_PAYOUTS=0
# The application name of E23's relay hold, the session that holds some aggregates' relay locks (P16-TSK-013).
EXPERIMENTS_HOLD_APP=finapp-experiments-relay-hold
# Set once the scope's faults can exist; finish() then empties the registry and resets the proxy.
EXPERIMENTS_ARMED=""
EXPERIMENTS_GUARDS=0
# What the boundary read on its last pass, for the record: none until it has read.
cred_secrets=0
cred_values=0
end_read=0
end_providers=0

experiments_paths() {
    EXP_ART="$ART/experiments"
    LEDGER="$WORK/ledger"
    FAULTS="$WORK/faults"
    mkdir -p "$EXP_ART"
    [ -f "$FAULTS" ] || : > "$FAULTS"
}

# ---------------------------------------------------------------------------------------------------
# The cluster: made with its marker, reused or replaced only when it carries it.
# ---------------------------------------------------------------------------------------------------

# experiments_marked: 0 when the cluster named $CLUSTER carries this harness's marker naming it.
experiments_marked() {
    marker=$(kube -n kube-system get configmap "$EXPERIMENTS_MARKER" -o jsonpath='{.data.cluster}|{.data.scope}|{.data.run}' 2>/dev/null || true)
    EXP_MARKER_RUN=${marker##*|}
    [ "${marker%|*}" = "$CLUSTER|experiments" ]
}

# Before a run deletes a cluster of its name to make its own, or reuses one: refused unless the cluster is one this scope made.
experiments_cluster_owned() {
    if ! experiments_marked; then
        log "!! the safety boundary refused the cluster $CLUSTER: it carries no marker of the experiments scope, so the harness did not make it and changes nothing in it"
        printf 'FAIL\tthe safety boundary refused the cluster %s: no marker\n' "$CLUSTER" >> "$ART/assertions.tsv"
        FAILED=1
        finish
    fi
    log "the cluster $CLUSTER carries the experiments scope's marker (run $EXP_MARKER_RUN)"
}

# Once kind has made the cluster: the marker, and every node the images go to.
experiments_cluster_made() {
    run=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
    kube -n kube-system create configmap "$EXPERIMENTS_MARKER" --from-literal="cluster=$CLUSTER" --from-literal=scope=experiments \
        --from-literal="run=$run" --from-literal="created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$ART/cluster.log" 2>&1 \
        || cannot "the experiments scope's marker could not be written"
    mkdir -p "$ART/experiments"
    printf 'cluster\t%s\nrun\t%s\n' "$CLUSTER" "$run" > "$ART/experiments/marker.txt"
    log "the cluster $CLUSTER marked as the experiments scope's own (run $run)"
}

# The nodes of this cluster, as docker knows them: every image is loaded into each.
experiments_nodes() {
    dock ps -a --filter "label=io.x-k8s.kind.cluster=$CLUSTER" --format '{{.Names}}' | sort
}

# ---------------------------------------------------------------------------------------------------
# The topology: the scope's rendered copy, the proxy, the standby.
# ---------------------------------------------------------------------------------------------------

# Before the data tier is applied: the scope's own changes to its rendered copy of the kind overlay, each checked to have taken.
experiments_render() {
    # The base's zone rule again: DoNotSchedule on the zone, which the kind overlay relaxes for a node with no zone.
    awk '/topologyKey: topology.kubernetes.io\/zone$/ {zone = 1} zone && /whenUnsatisfiable:/ {sub(/ScheduleAnyway/, "DoNotSchedule"); zone = 0} {print}' \
        "$A/Deployment.finapp-app.yaml" > "$WORK/deployment.tmp" && mv "$WORK/deployment.tmp" "$A/Deployment.finapp-app.yaml"
    awk '/topologyKey: topology.kubernetes.io\/zone$/ {getline; print; exit}' "$A/Deployment.finapp-app.yaml" | grep -q 'whenUnsatisfiable: DoNotSchedule' \
        || cannot "the application's zone constraint could not be restored to DoNotSchedule"
    # The standby's replication connection: a line of its own, because the hba keyword all does not match the replication database.
    sed -i 's/^\(    \)hostssl all all all scram-sha-256$/&\n\1hostssl replication finapp_replicator all scram-sha-256/' "$A/ConfigMap.finapp-kind-postgres-hba.yaml"
    grep -q '^    hostssl replication finapp_replicator all scram-sha-256$' "$A/ConfigMap.finapp-kind-postgres-hba.yaml" \
        || cannot "the replication line could not be added to the test-only server's hba"
    # The quota grows by the standby's pod: one pod, 250m and 1 CPU, 1 GiB.
    quota="$A/ResourceQuota.finapp.yaml"
    for field in pods requests.cpu requests.memory limits.cpu limits.memory; do
        value=$(sed -n "s/^    $field: \"\{0,1\}\([0-9a-zA-Z]*\)\"\{0,1\}$/\1/p" "$quota")
        case $field:$value in
            pods:*) grown=$((value + 1)) ;;
            requests.cpu:*m) grown="$((${value%m} + 250))m" ;;
            limits.cpu:*) grown=$((value + 1)) ;;
            *:*Mi) grown="$((${value%Mi} + 1024))Mi" ;;
            *) cannot "the quota's $field ($value) is not of a form the experiments scope can grow" ;;
        esac
        sed -i "s/^    $field: \"\{0,1\}$value\"\{0,1\}$/    $field: \"$grown\"/" "$quota"
    done
    log "the experiments' copy: the zone constraint DoNotSchedule, the hba's replication line, the quota grown by the standby ($(tr '\n' ' ' < "$quota" | sed 's/.*hard: *//'))"
}

# Once the test-only PostgreSQL and the broker are ready: the proxy, then the standby, then the application's way to the proxy.
experiments_data_tier() {
    experiments_paths
    sed "s#TOXIPROXY_IMAGE#$TOXIPROXY_IMAGE#" "$DIR/experiments/toxiproxy.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 \
        || cannot "the fault proxy could not be applied"
    kube -n finapp-experiments rollout status deployment/finapp-toxiproxy --timeout=180s >> "$ART/apply.log" 2>&1 || fail "the fault proxy did not become ready"
    PROXY_IP=$(kube -n finapp-experiments get service finapp-toxiproxy -o jsonpath='{.spec.clusterIP}')
    case $PROXY_IP in *.*.*.*) ;; *) cannot "the fault proxy's Service has no address" ;; esac
    experiments_standby
    # The application's names for the three hops, resolved to the proxy in its own pods alone.
    cat > "$WORK/host-aliases.yaml" <<YAML
      hostAliases:
        - ip: "$PROXY_IP"
          hostnames:
            - $PG_HOST
            - $KAFKA_HOST
            - $SIM_HOST
YAML
    # Each broker's own name to its own proxy's address (P16-TSK-013): the names the three brokers advertise after the bootstrap.
    EXPERIMENTS_BROKER_ALIASES=""
    i=0
    while [ "$i" -lt "$KAFKA_BROKERS" ]; do
        ip=$(kube -n finapp-experiments get service "finapp-toxiproxy-kafka-$i" -o jsonpath='{.spec.clusterIP}')
        case $ip in *.*.*.*) ;; *) cannot "the proxy of broker $i has no address" ;; esac
        printf '        - ip: "%s"\n          hostnames:\n            - %s\n' "$ip" "$(brokers_name "$i")" >> "$WORK/host-aliases.yaml"
        EXPERIMENTS_BROKER_ALIASES="$EXPERIMENTS_BROKER_ALIASES
$ip $(brokers_name "$i")"
        i=$((i + 1))
    done
    awk -v aliases="$WORK/host-aliases.yaml" '/^      serviceAccountName: / && !done {while ((getline line < aliases) > 0) print line; done = 1} {print}' \
        "$A/Deployment.finapp-app.yaml" > "$WORK/deployment.tmp" && mv "$WORK/deployment.tmp" "$A/Deployment.finapp-app.yaml"
    grep -q "^        - ip: \"$PROXY_IP\"$" "$A/Deployment.finapp-app.yaml" || cannot "the application's names could not be sent to the proxy"
    log "the fault proxy on the application's three hops at $PROXY_IP, each of the $KAFKA_BROKERS brokers at its own; the standby synchronous"
}

# The standby (owner decision C7): the role, its password and its slot on the primary, the base backup, streaming, then synchronous.
experiments_standby() {
    phrase=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    printf '%s' "$phrase" > "$WORK/replication.phrase"
    printf '%s' "$phrase" | sha256sum | cut -c1-64 >> "$LEDGER"
    secret_of finapp finapp-kind-postgres-replication "phrase=$WORK/replication.phrase"
    # The password travels on the SQL's stdin, never on a command line; the server logs no statement.
    psql_query -f - >> "$ART/experiments/standby.log" 2>&1 <<SQL || cannot "the replication role or its slot could not be made"
\set ON_ERROR_STOP on
CREATE ROLE finapp_replicator WITH REPLICATION LOGIN PASSWORD '${phrase}';
SELECT pg_create_physical_replication_slot('finapp_standby');
SQL
    rm -f "$WORK/replication.phrase"
    image=$(sed -n 's/^ *image: \(postgres:18\.6@sha256:[0-9a-f]*\)$/\1/p' "$ROOT/deploy/overlays/kind/postgres.yaml")
    [ -n "$image" ] || cannot "the test-only PostgreSQL's pinned image could not be read from the kind overlay"
    sed "s#POSTGRES_IMAGE#$image#" "$DIR/experiments/standby.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the standby could not be applied"
    kube -n finapp rollout status statefulset/finapp-postgres-standby --timeout=420s >> "$ART/apply.log" 2>&1 || {
        kube -n finapp logs finapp-postgres-standby-0 -c base-backup >> "$ART/experiments/standby.log" 2>&1 || true
        fail "the standby did not become ready: see experiments/standby.log"
    }
    deadline=$(( $(date +%s) + 120 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(experiments_replication)" = "finapp_standby|streaming|async" ] && break
        sleep 2
    done
    [ "$(experiments_replication)" = "finapp_standby|streaming|async" ] || fail "the standby is not streaming: $(experiments_replication)"
    psql_query -c "ALTER SYSTEM SET synchronous_standby_names = 'FIRST 1 (finapp_standby)'" >> "$ART/experiments/standby.log" 2>&1 \
        || cannot "the primary could not be made synchronous"
    psql_query -c "SELECT pg_reload_conf()" >> "$ART/experiments/standby.log" 2>&1 || cannot "the primary's configuration could not be reloaded"
    deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(experiments_replication)" = "finapp_standby|streaming|sync" ] && break
        sleep 2
    done
    experiments_standby_check
}

# One line per standby the primary streams to: name|state|sync_state.
experiments_replication() {
    psql_query -c "SELECT application_name || '|' || state || '|' || sync_state FROM pg_stat_replication ORDER BY application_name" 2>/dev/null | tr -d '\r'
}

# The pair as C7 decided it: one standby, streaming and synchronous, in another zone, holding what the primary committed.
experiments_standby_check() {
    replication=$(experiments_replication)
    [ "$replication" = "finapp_standby|streaming|sync" ] || fail "the standby is not synchronous: pg_stat_replication says '$replication'"
    [ "$(psql_query -c 'SHOW synchronous_standby_names' | tr -d '[:space:]')" = 'FIRST1(finapp_standby)' ] || fail "the primary names no synchronous standby"
    primary_zone=$(experiments_zone_of_pod finapp finapp-postgres-0)
    standby_zone=$(experiments_zone_of_pod finapp finapp-postgres-standby-0)
    [ -n "$primary_zone" ] && [ -n "$standby_zone" ] && [ "$primary_zone" != "$standby_zone" ] \
        || fail "the standby is not in another zone than the primary ('$standby_zone' and '$primary_zone')"
    # A committed write is on the standby: a transaction that writes WAL (a transactional message, which no table holds) commits only once
    # the standby has flushed it (synchronous_commit on), and the standby's replay follows. A transaction that only takes an id writes no
    # WAL of its own and would not wait, so it would prove nothing here.
    tag=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
    lsn=$(psql_query -c "SELECT pg_logical_emit_message(true, 'finapp-experiments', '$tag')::text" | tr -d '[:space:]')
    deadline=$(( $(date +%s) + 30 ))
    replayed=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        replayed=$(kube -n finapp exec -i finapp-postgres-standby-0 -c postgres -- psql -q -X -A -t -U finapp -d finapp \
            -c "SELECT pg_last_wal_replay_lsn() >= '$lsn'::pg_lsn AND pg_is_in_recovery()" 2>/dev/null | tr -d '[:space:]')
        [ "$replayed" = t ] && break
        sleep 1
    done
    [ "$replayed" = t ] || fail "the standby did not replay a write the primary committed at $lsn"
    pass "the standby: streaming and synchronous (FIRST 1), in $standby_zone where the primary is in $primary_zone, a committed write replayed"
}

experiments_zone_of_pod() {
    node=$(kube -n "$1" get pod "$2" -o jsonpath='{.spec.nodeName}' 2>/dev/null || true)
    [ -n "$node" ] || return 0
    kube get node "$node" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}' 2>/dev/null || true
}

# After the edge: on several nodes its NodePorts (externalTrafficPolicy Local) answer only on a node running a controller of their own, so
# the client is given, for each host, a worker running one of that edge's controllers.
experiments_edge_addresses() {
    for edge in public operator; do
        node=$(kube -n finapp-edge get pods -l "app.kubernetes.io/name=finapp-edge-$edge" \
            -o jsonpath='{range .items[*]}{.spec.containers[0].name}|{.status.conditions[?(@.type=="Ready")].status}|{.spec.nodeName}{"\n"}{end}' \
            | awk -F'|' '$1 == "edge" && $2 == "True" {print $3}' | sort | head -1)
        [ -n "$node" ] || cannot "no ready controller of the $edge edge to give the client"
        address=$(dock inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$node")
        [ -n "$address" ] || cannot "the node $node has no address on the kind network"
        case $edge in public) NODE_IP=$address ;; operator) OPERATOR_IP=$address ;; esac
        log "the $edge edge is reached on $node ($address), a worker running one of its controllers"
    done
}

# ---------------------------------------------------------------------------------------------------
# The safety boundary.
# ---------------------------------------------------------------------------------------------------

# experiments_refuses: 0 when every check passes; 1 with the reason in EXP_REASON. Never fails the run itself.
experiments_refuses() {
    EXP_REASON=""
    if [ "$PROBE" = guard-off ]; then
        return 0
    fi
    experiments_context_check || return 1
    experiments_credentials_check || return 1
    experiments_endpoints_check || return 1
    return 0
}

# Before every fault: a refusal fails the run.
experiments_guard() {
    if ! experiments_refuses; then
        fail "the safety boundary refused before any fault: $EXP_REASON"
    fi
    EXPERIMENTS_GUARDS=$((EXPERIMENTS_GUARDS + 1))
}

experiments_context_check() {
    labels=$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}|{{index .Config.Labels "io.x-k8s.kind.role"}}' "$NODE" 2>/dev/null || true)
    [ "$labels" = "$CLUSTER|control-plane" ] \
        || { EXP_REASON="context: $NODE is not the control plane of the kind cluster $CLUSTER (its labels say '$labels')"; return 1; }
    own=$(kube config view --minify -o jsonpath='{.current-context}|{.clusters[0].cluster.server}' 2>/dev/null || true)
    [ "$own" = "kubernetes-admin@$CLUSTER|https://$NODE:6443" ] \
        || { EXP_REASON="context: the node's own context and API server are '$own', not kubernetes-admin@$CLUSTER at https://$NODE:6443"; return 1; }
    listed=$(kube get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null | sort | tr '\n' ' ')
    containers=$(experiments_nodes | tr '\n' ' ')
    [ -n "$listed" ] && [ "$listed" = "$containers" ] \
        || { EXP_REASON="context: the API lists the nodes '$listed' where the cluster's containers are '$containers'"; return 1; }
    experiments_marked || { EXP_REASON="context: the cluster carries no marker of the experiments scope naming $CLUSTER"; return 1; }
    return 0
}

# Every value of every Secret of the run's namespaces, endpoints aside, is one the run generated or set. The values are hashed inside the
# node, so none crosses to the harness's side: what comes back is "<namespace> <secret> <key> <sha256>", or "endpoint" for an endpoint's key.
experiments_credentials_check() {
    [ -s "$LEDGER" ] || { EXP_REASON="credential: the run's ledger of the values it made is empty, so nothing could be compared"; return 1; }
    hashed=$(dock exec -i "$NODE" sh -s <<'NODE'
for namespace in finapp finapp-rehearsal finapp-experiments; do
    listing=$(kubectl --kubeconfig=/etc/kubernetes/admin.conf -n "$namespace" get secrets \
        -o go-template='{{range .items}}{{$n := .metadata.name}}{{range $k, $v := .data}}{{$n}} {{$k}} {{$v}}{{"\n"}}{{end}}{{end}}') \
        || { echo "UNREADABLE $namespace"; continue; }
    printf '%s\n' "$listing" | while read -r name key value; do
        [ -n "$name" ] || continue
        case $key in
            *_URL | FINAPP_KAFKA_BOOTSTRAPSERVERS) hash=endpoint ;;
            *) hash=$(printf '%s' "$value" | base64 -d | sha256sum | cut -c1-64) ;;
        esac
        echo "$namespace $name $key $hash"
    done
done
NODE
    ) || { EXP_REASON="credential: the Secrets could not be read inside the node"; return 1; }
    verdict=$(printf '%s\n' "$hashed" | awk -v ledger="$LEDGER" '
        BEGIN { while ((getline line < ledger) > 0) made[line] = 1 }
        $1 == "UNREADABLE" { print "UNREADABLE " $2; exit }
        NF == 4 { secrets[$1 "/" $2] = 1; if ($4 == "endpoint") next; values++
                  if (!($4 in made)) { print "FOREIGN " $1 "/" $2 " " $3; exit } }
        END { n = 0; for (s in secrets) n++; app = ("finapp/finapp-app-secrets" in secrets); db = ("finapp/finapp-kind-postgres" in secrets)
              print "READ " n " " values + 0 " " app " " db }')
    case $verdict in
        UNREADABLE*) EXP_REASON="credential: the Secrets of ${verdict#UNREADABLE } could not be read"; return 1 ;;
        FOREIGN*) set -- ${verdict#FOREIGN }
                  EXP_REASON="credential: $1 holds under $2 a value this run did not generate"; return 1 ;;
    esac
    set -- ${verdict##*READ }
    cred_secrets=${1:-0}
    cred_values=${2:-0}
    [ "${3:-0}" = 1 ] && [ "${4:-0}" = 1 ] \
        || { EXP_REASON="credential: the application's and the database's own Secrets were not both among those read"; return 1; }
    [ "$cred_values" -ge 60 ] || { EXP_REASON="credential: only $cred_values values were read, too few to have read the run's Secrets"; return 1; }
    return 0
}

# Every endpoint the application is given is the run's own: the simulator, its database, its broker, its collector, through its proxy.
experiments_endpoints_check() {
    template='{{range .spec.template.spec.containers}}{{range .envFrom}}SOURCE {{if .configMapRef}}configmap {{.configMapRef.name}}{{else if .secretRef}}secret {{.secretRef.name}}{{else}}other -{{end}} {{if .prefix}}{{.prefix}}{{else}}-{{end}}{{"\n"}}{{end}}{{range .env}}ENV {{.name}} {{if .valueFrom}}{{if .valueFrom.fieldRef}}field{{else}}other{{end}}{{else}}{{.value}}{{end}}{{"\n"}}{{end}}{{end}}{{range .spec.template.spec.hostAliases}}ALIAS {{.ip}}{{range .hostnames}} {{.}}{{end}}{{"\n"}}{{end}}'
    deployment=$(kube -n finapp get deployment finapp-app -o go-template="$template" 2>/dev/null) \
        || { EXP_REASON="endpoint: the application's Deployment could not be read"; return 1; }
    # The ConfigMap's name is the generator's, a hash of its content: the one the run rendered and applied, read from its own copy.
    rendered=$(sed -n 's/^ *name: \(finapp-app-config-[a-z0-9]*\)$/\1/p' "$A/Deployment.finapp-app.yaml" | head -1)
    [ -n "$rendered" ] || { EXP_REASON="endpoint: the run's rendered Deployment names no generated ConfigMap"; return 1; }
    sources=$(printf '%s\n' "$deployment" | awk '$1 == "SOURCE" {print $2 "/" $3 "/" $4}' | sort | tr '\n' ' ')
    [ "$sources" = "configmap/$rendered/- secret/finapp-app-secrets/- " ] \
        || { EXP_REASON="endpoint: the application reads its environment from '$sources', not the run's ConfigMap and Secret alone"; return 1; }
    config=$(kube -n finapp get configmap "$rendered" -o go-template='{{range $k, $v := .data}}{{$k}} {{$v}}{{"\n"}}{{end}}' 2>/dev/null) \
        || { EXP_REASON="endpoint: the application's ConfigMap could not be read"; return 1; }
    # The Secret's endpoints, decoded inside the node: a key of an endpoint's name, or any value holding a scheme. No other value comes back.
    delivered=$(dock exec -i "$NODE" sh -s <<'NODE'
listing=$(kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp get secret finapp-app-secrets \
    -o go-template='{{range $k, $v := .data}}{{$k}} {{$v}}{{"\n"}}{{end}}') || { echo "UNREADABLE"; exit 0; }
printf '%s\n' "$listing" | while read -r key value; do
    [ -n "$key" ] || continue
    plain=$(printf '%s' "$value" | base64 -d)
    case $key:$plain in
        *_URL:* | FINAPP_KAFKA_BOOTSTRAPSERVERS:* | *://*) echo "$key $plain" ;;
        *) echo "$key -" ;;
    esac
done
NODE
    ) || { EXP_REASON="endpoint: the application's Secret could not be read inside the node"; return 1; }
    [ "$delivered" != UNREADABLE ] || { EXP_REASON="endpoint: the application's Secret could not be read"; return 1; }
    end_read=0
    end_providers=0
    while read -r key value; do
        [ -n "$key" ] || continue
        end_read=$((end_read + 1))
        case $key in
            FINAPP_KAFKA_BOOTSTRAPSERVERS)
                [ "$value" = "$KAFKA_HOST:9092" ] || { EXP_REASON="endpoint: the bootstrap servers are not the run's broker"; return 1; }
                continue ;;
            FINAPP_DB_URL)
                [ "$value" = "$DB_URL" ] || { EXP_REASON="endpoint: the database URL is not the run's own"; return 1; }
                continue ;;
        esac
        case $value in
            *://*)
                [ "$value" = "$SIM_URL" ] || { EXP_REASON="endpoint: $key names an endpoint other than the simulator"; return 1; }
                end_providers=$((end_providers + 1)) ;;
            *)
                case $key in *_URL) EXP_REASON="endpoint: $key is not a URL this check can read"; return 1 ;; esac ;;
        esac
    done <<EOF
$config
$delivered
EOF
    [ "$end_providers" -ge 3 ] || { EXP_REASON="endpoint: only $end_providers provider endpoints were read, where the run names three"; return 1; }
    while read -r kind key value; do
        [ "$kind" = ENV ] || continue
        case $value in
            field) ;;
            other) EXP_REASON="endpoint: $key is read from a source this check does not read"; return 1 ;;
            *://*) case $value in "$EXPERIMENTS_COLLECTOR"*) ;; *) EXP_REASON="endpoint: $key names an endpoint other than the run's collector"; return 1 ;; esac ;;
        esac
    done <<EOF
$deployment
EOF
    aliases=$(printf '%s\n' "$deployment" | sed -n 's/^ALIAS //p')
    proxy=$(kube -n finapp-experiments get service finapp-toxiproxy -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)
    # The run's three names to its proxy, then each broker's own name to its own proxy's address, read from the cluster (P16-TSK-013).
    expected="$proxy $PG_HOST $KAFKA_HOST $SIM_HOST"
    i=0
    while [ "$i" -lt "$KAFKA_BROKERS" ]; do
        broker_proxy=$(kube -n finapp-experiments get service "finapp-toxiproxy-kafka-$i" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)
        [ -n "$broker_proxy" ] || { EXP_REASON="endpoint: broker $i's proxy has no address"; return 1; }
        expected="$expected
$broker_proxy $(brokers_name "$i")"
        i=$((i + 1))
    done
    [ -n "$proxy" ] && [ "$aliases" = "$expected" ] \
        || { EXP_REASON="endpoint: the application's names are sent to '$(printf '%s' "$aliases" | tr '\n' ';')', not the run's three to its proxy at '$proxy' and each broker's to its own"; return 1; }
    upstreams=$(experiments_toxiproxy list 2>/dev/null | awk -F'\t' 'NF >= 4 {print $1 "=" $3}' | sort | tr '\n' ' ')
    wanted_upstreams=$({
        printf 'kafka=%s:9092\npostgres=%s:5432\nsimulator=%s:8443\n' "$KAFKA_HOST" "$PG_HOST" "$SIM_HOST"
        i=0
        while [ "$i" -lt "$KAFKA_BROKERS" ]; do
            printf 'kafka-%s=%s:9092\n' "$i" "$(brokers_name "$i")"
            i=$((i + 1))
        done
    } | sort | tr '\n' ' ')
    [ "$upstreams" = "$wanted_upstreams" ] \
        || { EXP_REASON="endpoint: the proxy's upstreams are '$upstreams', not the run's three services and its brokers ('$wanted_upstreams')"; return 1; }
    return 0
}

# The plants: each refusal shown refusing what it exists to refuse, and passing again once the plant is gone, in every run. Each plant is
# put back before its verdict is read, so a plant that was not refused leaves nothing behind on a kept cluster.
experiments_boundary() {
    experiments_refuses || fail "the safety boundary refused the run's own cluster, credentials and endpoints: $EXP_REASON"
    pass "the safety boundary's control: the run's own context, $cred_values values of $cred_secrets Secrets, $end_read settings and $end_providers provider endpoints pass"
    : > "$EXP_ART/boundary.tsv"
    # A context without its marker.
    kube -n kube-system get configmap "$EXPERIMENTS_MARKER" -o go-template='{{range $k, $v := .data}}{{$k}}={{$v}}{{"\n"}}{{end}}' > "$WORK/marker.env" \
        || cannot "the marker could not be read to plant its absence"
    kube -n kube-system delete configmap "$EXPERIMENTS_MARKER" >> "$ART/apply.log" 2>&1 || cannot "the marker could not be removed for its plant"
    experiments_plant unmarked
    set --
    while IFS= read -r line; do
        [ -z "$line" ] || set -- "$@" --from-literal="$line"
    done < "$WORK/marker.env"
    kube -n kube-system create configmap "$EXPERIMENTS_MARKER" "$@" >> "$ART/apply.log" 2>&1 || cannot "the marker could not be put back"
    experiments_plant_judged unmarked "context: the cluster carries no marker"
    # A credential the run did not generate, in the application's own Secret.
    saved=$(kube -n finapp get secret finapp-app-secrets -o jsonpath='{.data.FINAPP_PAYMENTS_WEBHOOK_KEY}')
    [ -n "$saved" ] || cannot "the application's webhook key could not be read for its plant"
    experiments_patch_secret FINAPP_PAYMENTS_WEBHOOK_KEY "$(printf 'planted, not generated by this run' | base64 | tr -d '\n')"
    experiments_plant foreign-credential
    experiments_patch_secret FINAPP_PAYMENTS_WEBHOOK_KEY "$saved"
    experiments_plant_judged foreign-credential "credential: finapp/finapp-app-secrets holds under FINAPP_PAYMENTS_WEBHOOK_KEY"
    # A provider endpoint that is not the simulator.
    saved=$(kube -n finapp get secret finapp-app-secrets -o jsonpath='{.data.FINAPP_PAYMENTS_PROVIDER_URL}')
    [ -n "$saved" ] || cannot "the application's provider URL could not be read for its plant"
    experiments_patch_secret FINAPP_PAYMENTS_PROVIDER_URL "$(printf 'https://psp.example.invalid' | base64 | tr -d '\n')"
    experiments_plant foreign-endpoint
    experiments_patch_secret FINAPP_PAYMENTS_PROVIDER_URL "$saved"
    experiments_plant_judged foreign-endpoint "endpoint: FINAPP_PAYMENTS_PROVIDER_URL names an endpoint other than the simulator"
    [ ! -s "$FAULTS" ] || fail "a fault was registered during the boundary's plants: $(tr '\n' ';' < "$FAULTS")"
    pass "the safety boundary: an unmarked context, a credential the run did not generate and a provider endpoint not the simulator each refused, with no fault on, and each passing again once put back"
}

# experiments_plant <name>: the boundary read with the plant in place; its answer kept for experiments_plant_judged.
experiments_plant() {
    if experiments_refuses; then
        PLANT_REFUSED=""
        answer=passed
    else
        PLANT_REFUSED=1
        answer=refused
    fi
    PLANT_REASON=$EXP_REASON
    printf '%s\t%s\t%s\n' "$1" "$answer" "${PLANT_REASON:--}" >> "$EXP_ART/boundary.tsv"
}

# experiments_plant_judged <name> <the reason its own check gives>: the plant, now removed, was refused by its own check, and the boundary
# passes again without it.
experiments_plant_judged() {
    [ -n "$PLANT_REFUSED" ] || fail "plant $1: the safety boundary did not refuse it${PROBE:+ (PROBE $PROBE)}"
    case $PLANT_REASON in
        "$2"*) ;;
        *) fail "plant $1 was refused for another reason than its own: $PLANT_REASON" ;;
    esac
    experiments_refuses || fail "plant $1 removed, the safety boundary still refuses: $EXP_REASON"
    pass "plant $1 refused before any fault: $PLANT_REASON"
}

# experiments_patch_secret <key> <base64 value>: the value travels on stdin into the node, never on a command line.
experiments_patch_secret() {
    printf '{"data":{"%s":"%s"}}' "$1" "$2" | dock exec -i "$NODE" sh -c 'cat > /tmp/experiments-patch.json && kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp patch secret finapp-app-secrets --type=merge --patch-file=/tmp/experiments-patch.json; status=$?; rm -f /tmp/experiments-patch.json; exit $status' \
        >> "$ART/apply.log" 2>&1 || cannot "the application's Secret could not be patched for a plant"
}

# ---------------------------------------------------------------------------------------------------
# The faults: registered before they are injected, removed at stop and on every way out.
# ---------------------------------------------------------------------------------------------------

experiments_toxiproxy() {
    kube -n finapp-experiments exec deploy/finapp-toxiproxy -c toxiproxy -- /toxiproxy-cli "$@"
}

experiments_simulator() {
    kube -n finapp-rehearsal exec deploy/finapp-simulator -c simulator -- "$@"
}

# experiments_fault_on <kind> <arguments>:
#   toxic <proxy> <name> <type> <attribute=value>...   proxy-down <proxy>   hold <flag file>   partition <namespace> <name> <file>
#   node-paused <node container>   scaled <namespace> <deployment> <replicas> <replicas to restore>
experiments_fault_on() {
    experiments_guard
    printf '%s\n' "$*" >> "$FAULTS"
    printf '%s\ton\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$EXP_ART/faults.log"
    kind=$1
    shift
    case $kind in
        toxic)
            proxy=$1 name=$2 type=$3
            shift 3
            set -- toxic add -t "$type" -n "$name" $(for attribute in "$@"; do printf -- '-a %s ' "$attribute"; done) "$proxy"
            experiments_toxiproxy "$@" >> "$EXP_ART/faults.log" 2>&1 ;;
        proxy-down)
            experiments_toxiproxy list | awk -F'\t' -v p="$1" '$1 == p && $4 == "enabled" {found = 1} END {exit !found}' \
                && experiments_toxiproxy toggle "$1" >> "$EXP_ART/faults.log" 2>&1 ;;
        hold) experiments_simulator touch "/tmp/$1" >> "$EXP_ART/faults.log" 2>&1 ;;
        partition) kube -n "$1" apply -f - < "$3" >> "$EXP_ART/faults.log" 2>&1 ;;
        node-paused)
            [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$1" 2>/dev/null)" = "$CLUSTER" ] \
                || fail "the safety boundary refused to pause $1: it is not a node of $CLUSTER"
            dock pause "$1" >> "$EXP_ART/faults.log" 2>&1 ;;
        scaled) kube -n "$1" scale "deployment/$2" --replicas="$3" >> "$EXP_ART/faults.log" 2>&1 ;;
        # P16-TSK-013's three: every broker stopped (E09); a relay hold on some aggregates (E23); a topic posture lowered (the probe).
        brokers-stopped) kube -n finapp scale statefulset/finapp-kafka --replicas="${1:-0}" >> "$EXP_ART/faults.log" 2>&1 ;;
        relay-held) experiments_relay_hold "$1" "$2" ;;
        min-isr) experiments_min_isr_on "$1" "$3" >> "$EXP_ART/faults.log" 2>&1 ;;
        *) cannot "no fault of the kind $kind" ;;
    esac || cannot "the fault $kind $* could not be put on"
}

# experiments_undo <kind> <arguments>: one fault removed. Never fails the run: finish() calls it too.
experiments_undo() {
    kind=$1
    shift
    case $kind in
        toxic) experiments_toxiproxy toxic remove -n "$2" "$1" ;;
        proxy-down)
            if experiments_toxiproxy list | awk -F'\t' -v p="$1" '$1 == p && $4 == "disabled" {found = 1} END {exit !found}'; then
                experiments_toxiproxy toggle "$1"
            fi ;;
        hold) experiments_simulator rm -f "/tmp/$1" ;;
        partition) kube -n "$1" delete networkpolicy "$2" --ignore-not-found ;;
        node-paused) dock unpause "$1" ;;
        scaled) kube -n "$1" scale "deployment/$2" --replicas="$4" ;;
        brokers-stopped) kube -n finapp scale statefulset/finapp-kafka --replicas="$KAFKA_BROKERS" ;;
        relay-held) experiments_relay_release ;;
        min-isr) experiments_min_isr_off "${2:-$(brokers_replication | cut -d' ' -f2)}" ;;
        *) return 1 ;;
    esac
}

# experiments_fault_off <kind> <arguments>: as put on.
experiments_fault_off() {
    experiments_undo "$@" >> "$EXP_ART/faults.log" 2>&1 || fail "the fault $* could not be removed"
    grep -vxF "$*" "$FAULTS" > "$FAULTS.next" || true
    mv "$FAULTS.next" "$FAULTS"
    printf '%s\toff\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$EXP_ART/faults.log"
}

# What is still on, read from the cluster and the machine, never from the registry: one line per fault, nothing when none is. A paused node
# first, and alone if there is one: an exec into a pod on a paused node would wait on its kubelet.
experiments_faults_left() {
    [ -f "$FAULTS.cleared" ] || : > "$FAULTS.cleared"
    paused=$(dock ps --filter "label=io.x-k8s.kind.cluster=$CLUSTER" --filter status=paused --format '{{.Names}}' 2>/dev/null)
    if [ -n "$paused" ]; then
        printf 'paused node %s\n' $paused
        return 0
    fi
    proxies=$(experiments_toxiproxy list 2>/dev/null) || echo "the proxy could not be read"
    printf '%s\n' "$proxies" | awk -F'\t' 'NF >= 5 && ($4 != "enabled" || $5 != "0") {print "proxy " $1 " " $4 " with " $5 " toxics"}'
    # Its three hops' proxies and one per broker (P16-TSK-013): six.
    [ "$(printf '%s\n' "$proxies" | awk -F'\t' 'NF >= 5' | grep -c . || true)" = "$((3 + KAFKA_BROKERS))" ] \
        || echo "the proxy lists $(printf '%s\n' "$proxies" | grep -c . || true) proxies, not its six"
    files=$(experiments_simulator ls -a /tmp 2>/dev/null) || echo "the simulator could not be read"
    printf '%s\n' "$files" | grep -x 'hold-payouts\|hold-inquiries' | sed "s/^/the simulator's flag /"
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" get networkpolicy -l finapp/experiment-fault=true -o name 2>/dev/null | sed "s#^#$namespace #"
    done
    while read -r kind namespace deployment replicas restore; do
        [ "$kind" = scaled ] || continue
        now=$(kube -n "$namespace" get "deployment/$deployment" -o jsonpath='{.spec.replicas}' 2>/dev/null)
        [ "$now" = "$restore" ] || echo "$namespace/$deployment at $now replicas, not $restore"
    done < "$FAULTS.cleared"
    # P16-TSK-013's: the brokers at their count, no relay hold's session, every topic at the catalogue's min.insync.replicas.
    brokers=$(kube -n finapp get statefulset/finapp-kafka -o jsonpath='{.spec.replicas}' 2>/dev/null)
    [ "$brokers" = "$KAFKA_BROKERS" ] || echo "the brokers at ${brokers:-unreadable} replicas, not $KAFKA_BROKERS"
    held=$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = '$EXPERIMENTS_HOLD_APP'" 2>/dev/null | tr -d '[:space:]')
    [ "$held" = 0 ] || echo "a relay hold's session is still open (${held:-unreadable})"
    if [ "$brokers" = "$KAFKA_BROKERS" ] && [ "$KAFKA_BROKERS" != 1 ]; then
        in_sync=$(brokers_replication | cut -d' ' -f2)
        lowered=$(kafka_admin kafka-topics.sh --describe --topics-with-overrides 2>/dev/null \
            | awk -v isr="$in_sync" '$1 == "Topic:" && $3 != "Partition:" && $2 ~ /^finapp\./ && $0 !~ ("min[.]insync[.]replicas=" isr "(,|[[:space:]]|$)") {print $2}' | tr '\n' ' ')
        [ -z "$lowered" ] || echo "topics not at min.insync.replicas $in_sync: $lowered"
        ! kafka_admin kafka-topics.sh --list 2>/dev/null | grep -qx 'probe.min-isr' || echo "the probe's topic probe.min-isr is still there"
    fi
    return 0
}

# Called by finish() on every way out. Every registered fault undone, newest first; then the blanket reset; then what is left, read.
experiments_clear_faults() {
    [ -n "$EXPERIMENTS_ARMED" ] || return 0
    log "clearing every fault the experiments put on ($(cat "$FAULTS" 2>/dev/null | grep -c . || true) registered)"
    cp "$FAULTS" "$FAULTS.cleared" 2>/dev/null || : > "$FAULTS.cleared"
    # A paused node before anything else: every exec below reaches a pod through its node's kubelet.
    for node in $(dock ps --filter "label=io.x-k8s.kind.cluster=$CLUSTER" --filter status=paused --format '{{.Names}}' 2>/dev/null); do
        dock unpause "$node" >> "$EXP_ART/faults.log" 2>&1 && printf '%s\tcleared\tnode-paused %s\n' "$(date -u +%H:%M:%S)" "$node" >> "$EXP_ART/faults.log"
    done
    awk '{line[NR] = $0} END {for (i = NR; i >= 1; i--) print line[i]}' "$FAULTS" 2>/dev/null | while IFS= read -r fault; do
        # shellcheck disable=SC2086
        experiments_undo $fault >> "$EXP_ART/faults.log" 2>&1 && printf '%s\tcleared\t%s\n' "$(date -u +%H:%M:%S)" "$fault" >> "$EXP_ART/faults.log"
    done
    : > "$FAULTS"
    # What the undoing restarted, ready before it is read.
    kube -n finapp-rehearsal rollout status deployment/finapp-simulator --timeout=180s >> "$EXP_ART/faults.log" 2>&1
    kube -n finapp-experiments rollout status deployment/finapp-toxiproxy --timeout=120s >> "$EXP_ART/faults.log" 2>&1
    # The blanket reset: what no record is needed for.
    for proxy in $(experiments_toxiproxy list 2>/dev/null | awk -F'\t' 'NF >= 4 {print $1}'); do
        for toxic in $(experiments_toxiproxy inspect "$proxy" 2>/dev/null | awk -F'\t' '$2 ~ /^type=/ {print $1}'); do
            experiments_toxiproxy toxic remove -n "$toxic" "$proxy" >> "$EXP_ART/faults.log" 2>&1
        done
        experiments_undo proxy-down "$proxy" >> "$EXP_ART/faults.log" 2>&1
    done
    for flag in hold-payouts hold-inquiries; do
        experiments_simulator rm -f "/tmp/$flag" >> "$EXP_ART/faults.log" 2>&1
    done
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" delete networkpolicy -l finapp/experiment-fault=true --ignore-not-found >> "$EXP_ART/faults.log" 2>&1
    done
    # P16-TSK-013's: the brokers back at their count, any relay hold's session ended, the topics at the catalogue's posture.
    if [ "$(kube -n finapp get statefulset/finapp-kafka -o jsonpath='{.spec.replicas}' 2>/dev/null)" != "$KAFKA_BROKERS" ]; then
        experiments_undo brokers-stopped >> "$EXP_ART/faults.log" 2>&1
        kube -n finapp rollout status statefulset/finapp-kafka --timeout=300s >> "$EXP_ART/faults.log" 2>&1
    fi
    experiments_relay_release >> "$EXP_ART/faults.log" 2>&1
    [ "$KAFKA_BROKERS" = 1 ] || experiments_undo min-isr >> "$EXP_ART/faults.log" 2>&1
    left=$(experiments_faults_left)
    {
        printf 'cleared at %s; registered: %s\n' "$(date -u +%H:%M:%SZ)" "$(tr '\n' ';' < "$FAULTS.cleared")"
        printf 'left on: %s\n' "${left:-none}"
    } > "$EXP_ART/faults-cleared.txt"
    if [ -n "$left" ]; then
        log "!! a fault is still on after the exit trap: $(printf '%s' "$left" | tr '\n' ';')"
        [ "$FAILED" != 0 ] || FAILED=1
    else
        log "every fault removed and none left on: the proxies enabled with no toxic, no hold flag, no fault policy, no paused node, every scaled Deployment back"
    fi
}

# experiments_kill_jvm <pod>: SIGKILL to the pod's application process, from the node it runs on - the PID namespace's parent, so the signal
# is delivered to a process that is its own namespace's init. Sets KILLED to "<node> <container> <pid>"; never called inside $( ), where a
# refusal would end a subshell and not the run.
experiments_kill_jvm() {
    node=$(kube -n finapp get pod "$1" -o jsonpath='{.spec.nodeName}')
    container=$(kube -n finapp get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="app")].containerID}' | sed 's#^containerd://##')
    [ -n "$node" ] && [ -n "$container" ] || cannot "the pod $1 has no node or no running container to kill"
    [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node" 2>/dev/null)" = "$CLUSTER" ] \
        || fail "the safety boundary refused to kill on $node: it is not a node of $CLUSTER"
    pid=$(dock exec "$node" crictl inspect --output go-template --template '{{.info.pid}}' "$container" 2>/dev/null)
    case $pid in '' | *[!0-9]*) cannot "the application process of $1 could not be found on $node" ;; esac
    # The kill's time: taken as the signal is sent, after every lookup, so that what is measured from it starts at the signal.
    KILLED_EPOCH=$(date +%s)
    dock exec "$node" kill -9 "$pid" || cannot "SIGKILL could not be sent to $1's process on $node"
    printf '%s\tkill -9\t%s on %s, pid %s\n' "$(date -u +%H:%M:%S)" "$1" "$node" "$pid" >> "$EXP_ART/faults.log"
    KILLED="$node $container $pid"
}

# ---------------------------------------------------------------------------------------------------
# What every experiment reads: the fleet, the pages, the path through the proxy.
# ---------------------------------------------------------------------------------------------------

# One line per application pod: name|ready|restarts|node|ip|terminating.
experiments_fleet() {
    kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.name}|{.status.conditions[?(@.type=="Ready")].status}|{.status.containerStatuses[0].restartCount}|{.spec.nodeName}|{.status.podIP}|{.metadata.deletionTimestamp}{"\n"}{end}' 2>/dev/null
}

# experiments_sampler <dir>: the fleet every 2 s into <dir>/fleet.tsv, until <dir>/sampler-stop exists.
experiments_sampler() {
    set +e
    printf 'epoch\ttime\tpod\tready\trestarts\tnode\tip\tterminating\n' > "$1/fleet.tsv"
    while [ ! -f "$1/sampler-stop" ]; do
        epoch=$(date +%s)
        experiments_fleet | awk -F'|' -v epoch="$epoch" -v at="$(date -u -d "@$epoch" +%H:%M:%S)" \
            'NF >= 5 {print epoch "\t" at "\t" $1 "\t" $2 "\t" $3 "\t" $4 "\t" $5 "\t" ($6 == "" ? "-" : "yes")}' >> "$1/fleet.tsv"
        sleep 2
    done
}

# experiments_prometheus <path and query>: the cluster's Prometheus asked on its own pod's loopback. On several nodes the API server's proxy
# reaches a pod on another node from that node's address, which the observability namespace's default-deny need not admit; an exec does not
# cross the network at all.
experiments_prometheus() {
    kube -n finapp-observability exec deploy/finapp-prometheus -c prometheus -- wget -q -T 20 -O - "http://127.0.0.1:9090$1"
}

# experiments_endpoint_sampler <dir> <address>: that address's readiness in the application's EndpointSlices every second, into
# <dir>/endpoint.tsv, until <dir>/endpoint-stop exists. Started just before a fault, so its first change is the fault's.
experiments_endpoint_sampler() {
    set +e
    printf 'epoch\tready\n' > "$1/endpoint.tsv"
    while [ ! -f "$1/endpoint-stop" ]; do
        epoch=$(date +%s)
        state=$(kube -n finapp get endpointslices -l kubernetes.io/service-name=finapp-app \
            -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}|{.conditions.ready}{"\n"}{end}' 2>/dev/null | awk -F'|' -v a="$2" '$1 == a {print $2}')
        printf '%s\t%s\n' "$epoch" "${state:-absent}" >> "$1/endpoint.tsv"
        sleep 1
    done
}

# The correctness pages of SLOS.md section 3, every one a rule the cluster's Prometheus loaded, read once: prints the firing ones, or
# UNREADABLE when Prometheus did not answer. Nothing printed is nothing firing.
experiments_pages_firing() {
    answer=$(experiments_prometheus "/api/v1/query?query=$(urlencode "ALERTS{alertstate=\"firing\",alertname=~\"$EXP_PAGE_REGEX\"}")" 2>/dev/null || true)
    case $answer in *'"status":"success"'*) ;; *) echo UNREADABLE; return 0 ;; esac
    printf '%s' "$answer" | grep -o '"alertname":"[^"]*"' | sed 's/.*:"\(.*\)"/\1/' | sort -u | tr '\n' ' '
}

experiments_pages_setup() {
    EXP_PAGES=$(sed -n '/^## 3\. /,/^## 4\. /s/^| `\([A-Za-z]*\)` |.*/\1/p' "$ROOT/docs/architecture/SLOS.md")
    pages=$(printf '%s\n' "$EXP_PAGES" | grep -c . || true)
    [ "$pages" -ge 20 ] || cannot "only $pages correctness pages were read from SLOS.md section 3"
    experiments_prometheus /api/v1/rules > "$EXP_ART/prometheus-rules.json" 2>&1 || cannot "the cluster's Prometheus did not list its rules"
    for page in $EXP_PAGES; do
        grep -q "\"name\":\"$page\"" "$EXP_ART/prometheus-rules.json" || fail "the correctness page $page is not a rule the cluster's Prometheus loaded"
    done
    EXP_PAGE_REGEX=$(printf '%s\n' "$EXP_PAGES" | paste -sd'|' -)
    EXP_PAGE_COUNT=$pages
}

# The topology, read: three replicas on three nodes in three zones, the standby synchronous, every application session through the proxy
# over TLS, every replica accepted by each of the proxy's three proxies, the consumer groups' members at the proxy's address.
experiments_topology_check() {
    fleet=$(experiments_fleet)
    ready=$(printf '%s\n' "$fleet" | awk -F'|' 'NF >= 5 && $2 == "True" && $6 == ""' | grep -c . || true)
    [ "$ready" = 3 ] || fail "topology: $ready application replicas ready, where the experiments run at three"
    nodes=$(printf '%s\n' "$fleet" | awk -F'|' 'NF >= 5 && $6 == "" {print $4}' | sort -u)
    [ "$(printf '%s\n' "$nodes" | grep -c .)" = 3 ] || fail "topology: the three replicas share nodes ($(printf '%s' "$nodes" | tr '\n' ' '))"
    zones=""
    for node in $nodes; do
        zones="$zones $(kube get node "$node" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}')"
    done
    [ "$(printf '%s\n' $zones | sort -u | grep -c .)" = 3 ] || fail "topology: the three replicas are not in three zones ($zones)"
    workers=$(kube get nodes -l '!node-role.kubernetes.io/control-plane' -o jsonpath='{range .items[*]}{.metadata.labels.topology\.kubernetes\.io/zone}{"\n"}{end}' | sort -u | grep -c . || true)
    [ "$workers" = 3 ] || fail "topology: the workers are in $workers zones, not three"
    rule=$(kube -n finapp get deployment finapp-app -o jsonpath='{range .spec.template.spec.topologySpreadConstraints[*]}{.topologyKey}={.whenUnsatisfiable} {end}')
    case $rule in *"topology.kubernetes.io/zone=DoNotSchedule"*) ;; *) fail "topology: the zone constraint is not DoNotSchedule ($rule)" ;; esac
    pass "topology: three replicas ready on three nodes in three zones ($(printf '%s' "$zones" | sed 's/^ //')), the zone constraint DoNotSchedule"
    experiments_standby_check
    brokers_check
    proxy_pod=$(kube -n finapp-experiments get pods -l app.kubernetes.io/name=finapp-toxiproxy -o jsonpath='{.items[0].status.podIP}')
    [ -n "$proxy_pod" ] || fail "topology: the fault proxy has no pod address"
    sessions=$(psql_query -c "SELECT count(*) || '|' || count(*) FILTER (WHERE s.ssl) || '|' || count(*) FILTER (WHERE host(a.client_addr) = '$proxy_pod')
        FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.application_name = 'finapp'" | tr -d '[:space:]')
    IFS='|' read -r all tls proxied <<EOF
$sessions
EOF
    [ "${all:-0}" -ge 3 ] || fail "topology: only ${all:-0} application sessions to read"
    [ "$tls" = "$all" ] && [ "$proxied" = "$all" ] \
        || fail "topology: of $all application sessions $tls are TLS and $proxied arrive from the proxy ($proxy_pod); every one must be both"
    kafka_admin kafka-consumer-groups.sh --describe --all-groups --members > "$EXP_ART/members.txt" 2>/dev/null || true
    # A member's host is the one field of its line written /<address>, whichever column the broker's version puts it in.
    hosts=$(awk '$1 ~ /^finapp\./ {for (i = 2; i <= NF; i++) if ($i ~ /^\/[0-9]/) print $i}' "$EXP_ART/members.txt")
    members=$(printf '%s\n' "$hosts" | grep . | sort | uniq -c | tr -s ' ' | tr '\n' ';')
    other=$(printf '%s\n' "$hosts" | grep . | grep -vxF "/$proxy_pod" | grep -c . || true)
    counted=$(printf '%s\n' "$hosts" | grep -cxF "/$proxy_pod" || true)
    [ "$counted" -ge 1 ] && [ "$other" = 0 ] || fail "topology: the consumer groups' members are not all at the proxy's address: $members"
    kube -n finapp-experiments logs deploy/finapp-toxiproxy -c toxiproxy --tail=-1 > "$EXP_ART/toxiproxy.log" 2>&1 || true
    # Every replica opens its database and broker connections as it starts; a replica opens one to a provider only when it first calls
    # one, so the provider hop is held to have carried some replica's calls, the run's own names sending every one there.
    # The broker hop is four proxies at three brokers (P16-TSK-013): the bootstrap's, and each broker's own, which a replica's producer and
    # consumers reach for the partitions that broker leads - every broker leads some of every topic's twelve.
    for ip in $(printf '%s\n' "$fleet" | awk -F'|' 'NF >= 5 && $6 == "" {print $5}'); do
        for proxy in postgres kafka kafka-0 kafka-1 kafka-2; do
            grep '"message":"Accepted client"' "$EXP_ART/toxiproxy.log" | grep "\"name\":\"$proxy\"" | grep -q "\"client\":\"$ip:" \
                || fail "topology: the replica at $ip never reached the $proxy hop through the proxy: see experiments/toxiproxy.log"
        done
    done
    callers=$(grep '"message":"Accepted client"' "$EXP_ART/toxiproxy.log" | grep '"name":"simulator"' | sed -n 's/.*"client":"\([0-9.]*\):.*/\1/p' | sort -u | grep -c . || true)
    [ "$callers" -ge 1 ] || fail "topology: no replica's provider call went through the proxy: see experiments/toxiproxy.log"
    pass "topology: every one of $all application sessions arrives from the proxy over TLS; $counted consumer-group members at its address and none elsewhere; every replica accepted by the database proxy and the four broker proxies (the bootstrap's and each broker's own), $callers by the simulator's (the clients' URLs and verify-full unchanged)"
}

# ---------------------------------------------------------------------------------------------------
# The step shape.
# ---------------------------------------------------------------------------------------------------

experiments_scope() {
    experiments_paths
    EXPERIMENTS_ARMED=1
    step experiments-pages
    experiments_pages_setup
    if wanted experiments-topology; then
        step experiments-topology
        experiments_topology_check
    fi
    if wanted experiments-boundary; then
        step experiments-boundary
        experiments_boundary
    fi
    case $PROBE in crash-with-faults | term-with-faults) experiments_crash ;; esac
    for id in $EXPERIMENTS; do
        if wanted "$id"; then
            command -v "${id}_fault" >/dev/null 2>&1 || cannot "no experiment $id in this toolkit"
            experiment_run "$id"
        fi
    done
    [ "$PROBE" != guard-off ] || fail "PROBE guard-off: no plant was refused, and the run went on"
    pass "the experiments ran behind $EXPERIMENTS_GUARDS passes of the whole safety boundary - at each steady state and before each fault put on - and its context read again before each kill"
}

experiment_run() {
    EXP_ID=$1
    EXP_DIR="$EXP_ART/$1"
    EXP_STOPPED=""
    mkdir -p "$EXP_DIR"
    : > "$EXP_DIR/record.tsv"
    experiments_record experiment "$1"
    experiments_record commit "$(git -C "$(dpath "$ROOT")" rev-parse HEAD)"
    experiments_record started "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    step "$1-steady"
    experiments_guard
    experiments_steady
    "${1}_steady"
    step "$1-fault"
    "${1}_fault"
    step "$1-observe"
    "${1}_observe"
    step "$1-stop"
    experiments_stop
    step "$1-recover"
    "${1}_recover"
    step "$1-census"
    census "$1"
    firing=$(experiments_pages_firing)
    experiments_record "pages after" "${firing:-none}"
    [ -z "$firing" ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a correctness page after the experiment: $firing"
    experiments_record replicas "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')"
    if [ -n "$EXP_STOPPED" ]; then
        experiments_record verdict FAILED
        fail "$1 FAILED: $EXP_STOPPED; see experiments/$1"
    fi
    experiments_record verdict PASSED
    experiments_record ended "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    pass "$1 PASSED: its success criteria met, no stop condition, no correctness page, the census green; see experiments/$1/record.tsv"
}

experiments_record() {
    printf '%s\t%s\n' "$1" "$2" >> "$EXP_DIR/record.tsv"
}

# The steady state every experiment starts from.
experiments_steady() {
    [ ! -s "$FAULTS" ] || fail "$EXP_ID steady state: a fault is still registered: $(tr '\n' ';' < "$FAULTS")"
    left=$(experiments_faults_left 2>/dev/null)
    [ -z "$left" ] || fail "$EXP_ID steady state: a fault is on: $left"
    experiments_topology_check
    firing=$(experiments_pages_firing)
    [ -z "$firing" ] || fail "$EXP_ID steady state: correctness pages firing before the fault: $firing"
    experiments_record "steady state" "3 replicas on 3 nodes in 3 zones; the standby synchronous; every session through the proxy over TLS; $EXP_PAGE_COUNT pages silent"
}

# Stop: every fault the experiment put on is removed, and the cluster read to have none left.
experiments_stop() {
    cp "$FAULTS" "$FAULTS.cleared"
    while IFS= read -r fault; do
        [ -n "$fault" ] || continue
        # shellcheck disable=SC2086
        experiments_fault_off $fault
    done <<EOF
$(awk '{line[NR] = $0} END {for (i = NR; i >= 1; i--) print line[i]}' "$FAULTS")
EOF
    left=$(experiments_faults_left)
    [ -z "$left" ] || fail "$EXP_ID stop: a fault is still on after its removal: $left"
    experiments_record stopped "${EXP_STOPPED:-on schedule, no stop condition met}"
}

# PROBE crash-with-faults and term-with-faults: every kind of fault on, then the harness dies. Read the cluster after it: none is left on.
experiments_crash() {
    step experiments-crash
    # The node to pause: a worker that runs neither the proxy nor the primary, whose pods the clearing must exec into first.
    busy=" $(kube -n finapp-experiments get pods -l app.kubernetes.io/name=finapp-toxiproxy -o jsonpath='{.items[0].spec.nodeName}') $(kube -n finapp get pod finapp-postgres-0 -o jsonpath='{.spec.nodeName}') "
    victim_node=$(kube get nodes -l '!node-role.kubernetes.io/control-plane' -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
        | while read -r worker; do case $busy in *" $worker "*) ;; *) echo "$worker" ;; esac; done | head -1)
    [ -n "$victim_node" ] || cannot "PROBE $PROBE: no worker runs neither the proxy nor the primary, to pause"
    cat > "$WORK/fault-policy.yaml" <<YAML
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: experiments-crash-partition
  labels:
    finapp/test-only: "true"
    finapp/experiment-fault: "true"
spec:
  podSelector:
    matchLabels:
      finapp/experiment-target: crash-probe
  policyTypes:
    - Egress
YAML
    experiments_fault_on toxic postgres crash-latency latency latency=50
    experiments_fault_on proxy-down simulator
    experiments_fault_on hold hold-payouts
    experiments_fault_on partition finapp experiments-crash-partition "$WORK/fault-policy.yaml"
    experiments_fault_on scaled finapp-rehearsal finapp-simulator 0 1
    # P16-TSK-013's three kinds: the topics' posture lowered with the probe topic, a relay hold, every broker stopped.
    experiments_fault_on min-isr 1 "$(brokers_replication | cut -d' ' -f2)" finapp-kafka-1
    experiments_fault_on relay-held 600 8
    experiments_fault_on brokers-stopped
    experiments_faults_left > "$EXP_ART/faults-before-crash.txt" 2>&1 || true
    experiments_fault_on node-paused "$victim_node"
    echo "paused node $victim_node" >> "$EXP_ART/faults-before-crash.txt"
    log "PROBE $PROBE: nine faults on - a toxic, a proxy down, a hold, a partition, a scale to zero, the topics at one copy in sync with the probe topic written, a relay hold, every broker stopped, a paused node ($victim_node) - and now the harness dies"
    if [ "$PROBE" = term-with-faults ]; then
        kill -TERM $$
        sleep 30
    fi
    false
    fail "PROBE $PROBE: the harness did not die"
}

# ---------------------------------------------------------------------------------------------------
# E01: one replica crashes (kill -9 of the JVM), at three replicas.
# ---------------------------------------------------------------------------------------------------

E01_steady() {
    start_workload e01 retry
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E01_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E01_SAMPLER"
    E01_VICTIM=$(experiments_fleet | awk -F'|' 'NF >= 5 && $2 == "True" && $6 == "" {print $1}' | sort | head -1)
    E01_VICTIM_IP=$(kube -n finapp get pod "$E01_VICTIM" -o jsonpath='{.status.podIP}')
    E01_RESTARTS=$(kube -n finapp get pod "$E01_VICTIM" -o jsonpath='{.status.containerStatuses[0].restartCount}')
    E01_OTHER_IP=$(experiments_fleet | awk -F'|' -v v="$E01_VICTIM" 'NF >= 5 && $2 == "True" && $6 == "" && $1 != v {print $5}' | head -1)
    E01_MERCHANT=$(sed -n 's/^merchant.key=//p' "$CLIENT/state.properties")
    [ -n "$E01_VICTIM_IP" ] && [ -n "$E01_OTHER_IP" ] && [ -n "$E01_MERCHANT" ] || cannot "E01: no victim, no other replica or no merchant to pay out"
    E01_KEY="e01-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    experiments_record victim "$E01_VICTIM on $(kube -n finapp get pod "$E01_VICTIM" -o jsonpath='{.spec.nodeName}') in $(experiments_zone_of_pod finapp "$E01_VICTIM"), $E01_RESTARTS restarts before"
}

# e01_payout <address> <label>: the merchant's payout of 0.10 EUR under E01's key, sent to one replica from the edge's probe pod. The key
# travels on stdin; the answer's status and body (a payout's id, its state) are kept.
e01_payout() {
    printf '%s\n' "$E01_MERCHANT" | kube -n finapp-edge exec -i np-edge -c probe -- /bin/sh -c '
        IFS= read -r bearer
        curl -s -w "\nstatus=%{http_code}\n" --max-time 90 -X POST -H "Authorization: Bearer $bearer" -H "Content-Type: application/json" \
            -H "Idempotency-Key: $1" -d "{\"amount\":\"0.10\",\"currency\":\"EUR\"}" "http://$2:8080/v1/merchant/payouts"
        echo "curl=$?"' probe "$E01_KEY" "$1" > "$EXP_DIR/payout-$2.txt" 2>&1
}

e01_claim() {
    psql_query -c "SELECT state FROM platform.idempotency_record WHERE idempotency_key = '$E01_KEY'" | tr -d '[:space:]'
}

E01_fault() {
    # The worst moment to die: a payout's effect taken at the provider, its answer held, its claim IN_PROGRESS on the victim.
    experiments_fault_on hold hold-payouts
    e01_payout "$E01_VICTIM_IP" first &
    E01_FIRST=$!
    deadline=$(( $(date +%s) + 60 ))
    claim=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        claim=$(e01_claim)
        [ "$claim" = IN_PROGRESS ] && break
        sleep 1
    done
    [ "$claim" = IN_PROGRESS ] || cannot "E01: the payout sent to $E01_VICTIM never held a claim IN_PROGRESS, so a kill would find nothing in flight"
    # The whole boundary passed seconds ago, before the hold; the provider holds an answer for 20 s at most, so the context alone again here.
    if [ "$PROBE" != guard-off ]; then
        experiments_context_check || fail "the safety boundary refused before the kill: $EXP_REASON"
    fi
    experiments_endpoint_sampler "$EXP_DIR" "$E01_VICTIM_IP" &
    E01_ENDPOINTS=$!
    BACKGROUND="$BACKGROUND $E01_ENDPOINTS"
    sleep 1
    experiments_kill_jvm "$E01_VICTIM"
    E01_KILLED_AT=$KILLED_EPOCH
    experiments_record fault "kill -9 of $E01_VICTIM's JVM ($KILLED) at $(date -u -d "@$E01_KILLED_AT" +%H:%M:%SZ), its payout's claim IN_PROGRESS and held at the provider"
    log "E01: $E01_VICTIM's JVM killed with SIGKILL ($KILLED), its payout under a claim IN_PROGRESS"
}

E01_observe() {
    sleep 10
    experiments_fault_off hold hold-payouts
    wait "$E01_FIRST" 2>/dev/null || true
    if grep -q '^curl=0$' "$EXP_DIR/payout-first.txt" && grep -q '^status=20[01]$' "$EXP_DIR/payout-first.txt"; then
        fail "E01: the payout in flight on the killed replica was answered, so it was not in flight when the process died"
    fi
    experiments_record "in flight" "the payout sent to the killed replica: $(grep -h '^curl=\|^status=' "$EXP_DIR/payout-first.txt" | tr '\n' ' ')"
    # Until the killed replica is ready again: the stop condition (a second replica unready) and the pages watched meanwhile. Its endpoint's
    # times are the sampler's, read every second from just before the kill.
    returned=""
    pages_at=0
    deadline=$((E01_KILLED_AT + 600))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        now=$(date +%s)
        unready=$(awk -F'\t' -v v="$E01_VICTIM" -v at="$E01_KILLED_AT" 'NR > 1 && $1 >= at && $3 != v && $4 != "True" {print $3 "@" $2}' "$EXP_DIR/fleet.tsv" | head -1)
        if [ -n "$unready" ] && [ -z "$EXP_STOPPED" ]; then
            EXP_STOPPED="a second replica unready while the killed one was replaced: $unready"
            log "E01 STOP: $EXP_STOPPED"
            break
        fi
        if [ $((now - pages_at)) -ge 15 ]; then
            pages_at=$now
            firing=$(experiments_pages_firing)
            if [ -n "$firing" ]; then
                EXP_STOPPED="a correctness page during the experiment: $firing"
                log "E01 STOP: $EXP_STOPPED"
                break
            fi
            printf '%s\tnone\n' "$(date -u +%H:%M:%S)" >> "$EXP_DIR/pages.tsv"
        fi
        state=$(experiments_fleet | awk -F'|' -v v="$E01_VICTIM" '$1 == v {print $2 "|" $3}')
        if [ "${state%%|*}" = True ] && [ "${state##*|}" -gt "$E01_RESTARTS" ]; then
            returned=$((now - E01_KILLED_AT))
            break
        fi
        sleep 1
    done
    [ -n "$EXP_STOPPED" ] && return 0
    [ -n "$returned" ] || { EXP_STOPPED="the killed replica was not ready again within 600 s"; return 0; }
    exit_code=$(kube -n finapp get pod "$E01_VICTIM" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}')
    [ "$exit_code" = 137 ] || fail "E01: the killed container ended with $exit_code, not 137: it was not a SIGKILL"
    sleep 3
    touch "$EXP_DIR/endpoint-stop"
    wait "$E01_ENDPOINTS" 2>/dev/null || true
    # The sampler's first reading after the kill that is not ready, and the first ready one after it; each to the second it was read.
    read -r E01_ROUTED E01_BACK <<EOF
$(awk -F'\t' -v k="$E01_KILLED_AT" 'NR > 1 && $1 >= k && out == "" && $2 != "true" {out = $1 - k}
    NR > 1 && out != "" && back == "" && $2 == "true" {back = $1 - k} END {print (out == "" ? "-" : out), (back == "" ? "-" : back)}' "$EXP_DIR/endpoint.tsv")
EOF
    before=$(awk -F'\t' -v k="$E01_KILLED_AT" 'NR > 1 && $1 < k && $2 == "true"' "$EXP_DIR/endpoint.tsv" | grep -c . || true)
    [ "$before" -ge 1 ] || fail "E01: the endpoint sampler never read the killed replica ready before the kill, so its times mean nothing: see experiments/E01/endpoint.tsv"
    [ "$E01_ROUTED" != - ] && [ "$E01_BACK" != - ] || fail "E01: the sampler did not see the killed replica leave and return: see experiments/E01/endpoint.tsv"
    experiments_record "routed around" "the killed replica's endpoint read not ready $E01_ROUTED s after the kill (read every second; ready at each reading before it)"
    experiments_record "replaced" "the container restarted in its pod (exit 137), its endpoint ready again $E01_BACK s after the kill"
    # Its claim taken over: the same key sent to another replica until it is answered.
    deadline=$(( $(date +%s) + 600 ))
    answered=""
    tries=0
    while [ "$(date +%s)" -lt "$deadline" ]; do
        tries=$((tries + 1))
        e01_payout "$E01_OTHER_IP" "retry-$tries" || true
        status=$(sed -n 's/^status=//p' "$EXP_DIR/payout-retry-$tries.txt")
        printf '%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$tries" "${status:-none}" >> "$EXP_DIR/retries.tsv"
        case $status in 200 | 201) answered=$tries; break ;; esac
        sleep 5
    done
    [ -n "$answered" ] || fail "E01: the payout's key was not answered by another replica within 600 s: see experiments/E01/retries.tsv"
    [ "$(e01_claim)" = COMPLETED ] || fail "E01: the dead replica's claim is $(e01_claim), not COMPLETED, after its key was answered"
    EXPERIMENTS_PAYOUTS=$((EXPERIMENTS_PAYOUTS + 1))
    experiments_record "claim" "the dead replica's claim taken over and completed by another replica, answered on try $answered ($(cut -f3 "$EXP_DIR/retries.tsv" | tr '\n' ' '))"
    pass "E01: the killed replica's claim was taken over and completed once by another, the replica restarted (exit 137), its endpoint out ${E01_ROUTED} s and back $E01_BACK s after the kill"
}

E01_recover() {
    ready_three 400 || fail "E01: the fleet did not return to three ready replicas"
    sleep 20
    touch "$EXP_DIR/sampler-stop"
    wait "$E01_SAMPLER" 2>/dev/null || true
    stop_workload e01
    [ "$(workload_unresolved e01)" = 0 ] || fail "E01: $(workload_unresolved e01) requests stayed unresolved: see workload-e01.tsv"
    assert_flows_ran e01
    experiments_record workload "$(cat "$CLIENT/workload-e01.summary")"
    # Every answer the edge gave from the minute before the kill: when it started, the status it answered, the status the application gave
    # (0: none) and how long it took. The edge reaches the application through its Service, which picks the replica, so a failed answer is
    # told apart by when it ended: one in flight on the killed replica ends at the kill, one sent to it after ends at once, until its endpoint
    # leaves the Service and kube-proxy follows. Ending from a second before the kill to five seconds after its endpoint was read not ready (a
    # second for the read, kube-proxy's sync after it) it is the killed replica's; ending anywhere else, another's.
    since=$(date -u -d "@$((E01_KILLED_AT - 60))" +%Y-%m-%dT%H:%M:%SZ)
    : > "$EXP_DIR/edge-answers.tsv"
    for pod in $(kube -n finapp-edge get pods -l app.kubernetes.io/name=finapp-edge-public -o jsonpath='{range .items[*]}{.metadata.name}|{.spec.containers[0].name}{"\n"}{end}' | awk -F'|' '$2 == "edge" {print $1}'); do
        kube -n finapp-edge logs "$pod" -c edge --since-time="$since" 2>/dev/null \
            | sed -n 's/.*"DownstreamStatus":\([0-9]*\).*"Duration":\([0-9]*\).*"OriginStatus":\([0-9]*\).*"StartUTC":"\([^"]*\)".*/\4\t\1\t\3\t\2/p' >> "$EXP_DIR/edge-answers.tsv"
    done
    answers=$(grep -c . "$EXP_DIR/edge-answers.tsv" || true)
    window_end=$((E01_KILLED_AT + E01_ROUTED + 5))
    : > "$EXP_DIR/edge-failed.tsv"
    # A failure is the edge's 5xx, the application's own or the edge's for a backend that did not answer; an unrouted path's 404 is neither.
    awk -F'\t' '$2 >= 500' "$EXP_DIR/edge-answers.tsv" | while IFS="$(printf '\t')" read -r started answered origin took; do
        ended=$(( $(date -u -d "$started" +%s) + (took + 999999999) / 1000000000 ))
        printf '%s\t%s\t%s\t%s\n' "$ended" "$started" "$answered" "$origin" >> "$EXP_DIR/edge-failed.tsv"
    done
    failed_inside=$(awk -F'\t' -v k="$((E01_KILLED_AT - 1))" -v e="$window_end" '$1 >= k && $1 <= e' "$EXP_DIR/edge-failed.tsv" | grep -c . || true)
    failed_outside=$(awk -F'\t' -v k="$((E01_KILLED_AT - 1))" -v e="$window_end" '$1 < k || $1 > e' "$EXP_DIR/edge-failed.tsv" | grep -c . || true)
    served=$(awk -F'\t' '$2 < 500 && $3 != 0' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    experiments_record "failed at the edge" "$failed_inside failed answers ending in the window the killed replica could still be picked in (a second before the kill to $((E01_ROUTED + 5)) s after it), $failed_outside outside it; $served served, of $answers answers since the minute before the kill"
    # The workload's requests that failed, each counted once however often its key was tried again (a retry of a request whose claim the
    # dead replica held is answered 409 until its lease lapses): a key the client minted is one request; a register, a sign-in or a checkout
    # session's attempt is one each. Each must be one of the edge's failures in the window.
    failed_requests=$(awk -F'\t' '$NF ~ /^ATTEMPT-FAILED/ { if ($2 ~ /^wl-/) keys[$1 "|" $2] = 1; else n++ } END { for (k in keys) n++; print n + 0 }' "$CLIENT/workload-e01.tsv")
    retried_in_progress=$(awk -F'\t' '$NF ~ /^ATTEMPT-FAILED .* 409: .*IdempotencyInProgress/' "$CLIENT/workload-e01.tsv" | grep -c . || true)
    experiments_record "in flight and failed" "the payout held on the killed replica ($(grep -h '^curl=' "$EXP_DIR/payout-first.txt")); $failed_requests of the workload's requests, $failed_inside failed at the edge in the window; $(workload_failed e01) failed attempts, $retried_in_progress of them a retry answered 409 while the dead replica's claim held; none unresolved"
    # The control: the lines read, answers served in them, so a failed one would have been seen.
    [ "$served" -ge 10 ] || fail "E01: the edge's lines held $served served answers since the minute before the kill, too few to show the lines were read: see experiments/E01/edge-answers.tsv"
    [ "$failed_outside" = 0 ] || fail "E01: the edge answered $failed_outside requests with a failure outside the window the killed replica could be picked in: another replica failed them"
    [ "$failed_requests" -le "$failed_inside" ] \
        || fail "E01: $failed_requests of the workload's requests failed where the edge failed $failed_inside in the killed replica's window: a request failed that was not on it"
    pass "E01: the failed requests were those on the killed replica - its held payout and $failed_requests of the workload's, each a failure at the edge in its window, none outside it; $served answers served; none unresolved"
}

# ---------------------------------------------------------------------------------------------------
# The event pipeline (P16-TSK-013; PHASE_16_PLAN.md section 7; OPERATIONS_RUNBOOK.md section 11.51): E08, E09, E22, E23 and E49, on the
# scope's three brokers. What every one of them counts is PostgreSQL's, never the broker's: every outbox row published, every published
# record a catalogued handler names found in its consumer's inbox (lost_events, in the census), no inbox row added for a record a
# consumer had already applied, no record parked, and the census green.
# ---------------------------------------------------------------------------------------------------

# experiments_pages_check <experiment>: a correctness page firing stops the experiment (ADR-0127 decision 6); 1 when it did.
experiments_pages_check() {
    firing=$(experiments_pages_firing)
    if [ -n "$firing" ]; then
        EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a correctness page during the experiment: $firing"
        log "$1 STOP: $EXP_STOPPED"
        return 1
    fi
    printf '%s\tnone\n' "$(date -u +%H:%M:%S)" >> "$EXP_DIR/pages.tsv"
    return 0
}

# experiments_prom_value <query>: the first value of an instant query, or nothing.
experiments_prom_value() {
    experiments_prometheus "/api/v1/query?query=$(urlencode "$1")" 2>/dev/null | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p' | head -1
}

# experiments_inbox_total <outcome>: the fleet's inbox deliveries of that outcome, summed over the replicas' counters as Prometheus last
# scraped them. A difference across a window where the replicas stayed the same, or the whole of it for replicas started after an
# instant, is what they counted; an increase() would lose what a new replica counted before its first scrape.
experiments_inbox_total() {
    value=$(experiments_prom_value "sum(finapp_inbox_consumption_total{outcome=\"$1\"})")
    case $value in '') printf 'unreadable' ;; *) printf '%s' "${value%%.*}" ;; esac
}

# experiments_applied <since epoch>: inbox rows for events first written before that instant, one per (consumer, event).
experiments_applied() {
    psql_query -c "SELECT count(*) FROM platform.inbox_message i JOIN platform.outbox_event e ON i.dedupe_key = e.event_id::text
        WHERE e.occurred_at < to_timestamp($1)" | tr -d '[:space:]'
}

# experiments_parked <since epoch>: records parked since that instant.
experiments_parked() {
    psql_query -c "SELECT count(*) FROM platform.inbox_parked_record WHERE parked_at >= to_timestamp($1)" 2>/dev/null | tr -d '[:space:]'
}

# experiments_dead_lettered: outbox rows abandoned and not discarded.
experiments_dead_lettered() {
    psql_query -c "SELECT count(*) FROM platform.outbox_event WHERE dead_lettered_at IS NOT NULL AND discarded_at IS NULL" | tr -d '[:space:]'
}

# experiments_moved <workload>: the workload's money movements answered ok so far.
experiments_moved() {
    awk -F'\t' '($1 == "transfer" || $1 == "top-up" || $1 == "checkout" || $1 == "payout") && $NF == "ok"' "$CLIENT/workload-$1.tsv" 2>/dev/null | grep -c . || true
}

# experiments_kill_broker <pod>: SIGKILL to a broker's JVM from its node, as experiments_kill_jvm does to a replica's. Sets KILLED_EPOCH.
experiments_kill_broker() {
    node=$(kube -n finapp get pod "$1" -o jsonpath='{.spec.nodeName}')
    container=$(kube -n finapp get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="kafka")].containerID}' | sed 's#^containerd://##')
    [ -n "$node" ] && [ -n "$container" ] || cannot "the broker $1 has no node or no running container to kill"
    [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node" 2>/dev/null)" = "$CLUSTER" ] \
        || fail "the safety boundary refused to kill on $node: it is not a node of $CLUSTER"
    if [ "$PROBE" != guard-off ]; then
        experiments_context_check || fail "the safety boundary refused before the kill: $EXP_REASON"
    fi
    pid=$(dock exec "$node" crictl inspect --output go-template --template '{{.info.pid}}' "$container" 2>/dev/null)
    case $pid in '' | *[!0-9]*) cannot "the broker process of $1 could not be found on $node" ;; esac
    KILLED_EPOCH=$(date +%s)
    dock exec "$node" kill -9 "$pid" || cannot "SIGKILL could not be sent to $1's process on $node"
    printf '%s\tkill -9\t%s on %s, pid %s\n' "$(date -u +%H:%M:%S)" "$1" "$node" "$pid" >> "$EXP_ART/faults.log"
}

# experiments_min_isr <n>: every catalogued topic's min.insync.replicas set to n, through the brokers' admin listener.
experiments_min_isr() {
    for topic in $(sed -n 's/^  \(finapp\.[a-z]*\): domain-events$/\1/p' "$ROOT/infra/kafka/topics.yaml"); do
        kafka_admin kafka-configs.sh --alter --entity-type topics --entity-name "$topic" --add-config "min.insync.replicas=$1" || return 1
    done
}

# experiments_min_isr_on <n> <broker pod>: the probe's fault (min-isr-one). Every topic at min.insync.replicas n, and a probe topic of
# two copies, on that broker and the admin broker, at n too, written once a second for five minutes with acks=all from the admin broker.
experiments_min_isr_on() {
    experiments_min_isr "$1" || return 1
    kafka_admin kafka-topics.sh --create --if-not-exists --topic probe.min-isr --replica-assignment "$(( ${2##*-} + 1 )):$(( ${KAFKA_ADMIN_POD##*-} + 1 ))" \
        --config "min.insync.replicas=$1" || return 1
    kube -n finapp exec -i "$KAFKA_ADMIN_POD" -c kafka -- /bin/sh -c 'end=$(( $(date +%s) + 300 ))
        while [ "$(date +%s)" -lt "$end" ]; do date +%s; sleep 1; done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$1" \
            --producer.config /tmp/admin.properties --topic probe.min-isr --producer-property acks=all \
            --producer-property request.timeout.ms=4000 --producer-property delivery.timeout.ms=5000' \
        probe "$(brokers_name "${KAFKA_ADMIN_POD##*-}"):9095" >> "$EXP_ART/probe-producer.log" 2>&1 &
    BACKGROUND="$BACKGROUND $!"
}

# experiments_min_isr_off <n>: the probe topic deleted and every topic at min.insync.replicas n again.
experiments_min_isr_off() {
    kafka_admin kafka-topics.sh --delete --if-exists --topic probe.min-isr
    experiments_min_isr "$1"
}

# experiments_isr_sampler <dir>: every partition's in-sync replicas and end offset, about every 10 s, into <dir>/isr.tsv, until
# <dir>/isr-stop exists. Read through a broker the experiment keeps running (KAFKA_ADMIN_POD).
experiments_isr_sampler() {
    set +e
    printf 'epoch\ttopic\tpartition\tisr\tend\n' > "$1/isr.tsv"
    while [ ! -f "$1/isr-stop" ]; do
        epoch=$(date +%s)
        kafka_admin kafka-topics.sh --describe 2>/dev/null \
            | awk '$1 == "Topic:" && $3 == "Partition:" {for (i = 1; i <= NF; i++) if ($i == "Isr:") n = split($(i + 1), r, ","); print $2 ":" $4 "\t" n}' \
            > "$1/isr.now"
        kafka_admin kafka-get-offsets.sh 2>/dev/null | awk -F: '$1 ~ /^(finapp|probe)\./ {print $1 ":" $2 "\t" $3}' > "$1/offsets.now"
        awk -F'\t' -v epoch="$epoch" 'FNR == NR {isr[$1] = $2; next} ($1 in isr) {split($1, p, ":"); print epoch "\t" p[1] "\t" p[2] "\t" isr[$1] "\t" $2}' \
            "$1/isr.now" "$1/offsets.now" >> "$1/isr.tsv"
        sleep 5
    done
}

# experiments_single_replica_writes <isr.tsv> <in sync required>: the intervals in which a partition took a write while fewer replicas than required were
# in sync at the samples on both sides of it - an acknowledged record held by too few copies. Prints "<count> <first: topic:partition@epoch>".
experiments_single_replica_writes() {
    awk -F'\t' -v need="$2" 'NR > 1 {
        k = $2 ":" $3
        if ((k in last_isr) && last_isr[k] < need && $4 < need && $5 > last_end[k]) { n++; if (first == "") first = k "@" $1 }
        last_isr[k] = $4; last_end[k] = $5 }
        END {print n + 0, (first == "" ? "-" : first)}' "$1"
}

# E08: one broker of three killed - the quorum's leader, whose broker leads some of every topic's partitions - while the workload runs.
E08_steady() {
    brokers_check
    start_workload e08 retry
    sleep 8
    kafka_admin kafka-metadata-quorum.sh describe --status > "$EXP_DIR/quorum-before.txt" 2>&1 || fail "E08: the quorum could not be read"
    leader=$(sed -n 's/^LeaderId:[[:space:]]*\([0-9]*\)[[:space:]]*$/\1/p' "$EXP_DIR/quorum-before.txt")
    case $leader in [1-9]) ;; *) fail "E08: the quorum names no leader: see experiments/E08/quorum-before.txt" ;; esac
    E08_VICTIM="finapp-kafka-$((leader - 1))"
    # The admin tools through a broker the experiment keeps running.
    KAFKA_ADMIN_POD=finapp-kafka-$(( leader % KAFKA_BROKERS ))
    if [ "$PROBE" = min-isr-one ]; then
        # The probe holds its victim down for its window by the StatefulSet's count, which stops only the highest ordinal.
        E08_VICTIM="finapp-kafka-$((KAFKA_BROKERS - 1))"
        KAFKA_ADMIN_POD=finapp-kafka-0
    fi
    E08_RESTARTS=$(kube -n finapp get pod "$E08_VICTIM" -o jsonpath='{.status.containerStatuses[0].restartCount}')
    E08_FAILED_BEFORE=$(experiments_prom_value 'sum(finapp_outbox_publication_total{outcome="failed"})')
    experiments_isr_sampler "$EXP_DIR" &
    E08_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E08_SAMPLER"
    experiments_sampler "$EXP_DIR" &
    E08_FLEET=$!
    BACKGROUND="$BACKGROUND $E08_FLEET"
    sleep 20
    experiments_record victim "$E08_VICTIM (the quorum's leader is node $leader${PROBE:+; PROBE $PROBE takes the highest ordinal}), $E08_RESTARTS restarts before; the admin tools through $KAFKA_ADMIN_POD"
}

E08_fault() {
    if [ "$PROBE" = min-isr-one ]; then
        # The entry's probe: every topic's min.insync.replicas set to 1 and, beside them, a topic of two copies on the victim and the
        # admin broker written once a second with acks=all. The catalogue's topics keep two copies in sync when one broker of three goes;
        # the probe topic keeps one, and with min.insync.replicas 1 its writes are still acknowledged - a record held by one replica,
        # the risk 2 exists to refuse. The victim is then held down for the window (the StatefulSet one short), so that the one-copy
        # writes are sampled on both sides whatever a restart would take. The check below must see them and turn the run red; the stop
        # puts the catalogue's value back.
        experiments_fault_on min-isr 1 "$(brokers_replication | cut -d' ' -f2)" "$E08_VICTIM"
        log "PROBE min-isr-one: every topic at min.insync.replicas 1, and probe.min-isr written on $E08_VICTIM and $KAFKA_ADMIN_POD"
        sleep 15
    fi
    experiments_kill_broker "$E08_VICTIM"
    E08_KILLED_AT=$KILLED_EPOCH
    [ "$PROBE" != min-isr-one ] || experiments_fault_on brokers-stopped "$((KAFKA_BROKERS - 1))"
    experiments_record fault "kill -9 of $E08_VICTIM's broker at $(date -u -d "@$E08_KILLED_AT" +%H:%M:%SZ)"
    log "E08: $E08_VICTIM's broker killed with SIGKILL"
}

E08_observe() {
    returned=""
    pages_at=0
    deadline=$((E08_KILLED_AT + 600))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        now=$(date +%s)
        # The stop condition: two brokers down at once. The probe's held victim is one: a pod the StatefulSet stopped is no pod.
        down=$(( KAFKA_BROKERS - $(brokers_ready | awk -F'|' '$2 == "True"' | grep -c . || true) ))
        if [ "$down" -ge 2 ] && [ -z "$EXP_STOPPED" ]; then
            EXP_STOPPED="two brokers down at once"
            log "E08 STOP: $EXP_STOPPED"
            break
        fi
        if [ $((now - pages_at)) -ge 15 ]; then
            pages_at=$now
            experiments_pages_check E08 || break
        fi
        # The probe's window: 90 s held down, then the StatefulSet whole again and its victim a new pod.
        if [ "$PROBE" = min-isr-one ] && [ $((now - E08_KILLED_AT)) -ge 90 ] && grep -qx "brokers-stopped $((KAFKA_BROKERS - 1))" "$FAULTS"; then
            experiments_fault_off brokers-stopped "$((KAFKA_BROKERS - 1))"
            E08_RESTARTS=-1
        fi
        state=$(kube -n finapp get pod "$E08_VICTIM" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}|{.status.containerStatuses[0].restartCount}' 2>/dev/null)
        if [ "${state%%|*}" = True ] && [ "${state##*|}" -gt "$E08_RESTARTS" ]; then
            under=$(kafka_admin kafka-topics.sh --describe --under-replicated-partitions 2>/dev/null | grep -c 'Topic: finapp\.' || true)
            if [ "$under" = 0 ]; then
                returned=$((now - E08_KILLED_AT))
                break
            fi
        fi
        sleep 2
    done
    touch "$EXP_DIR/isr-stop"
    wait "$E08_SAMPLER" 2>/dev/null || true
    set -- $(experiments_single_replica_writes "$EXP_DIR/isr.tsv" "$(brokers_replication | cut -d' ' -f2)")
    samples=$(awk -F'\t' 'NR > 1 {print $1}' "$EXP_DIR/isr.tsv" | sort -u | grep -c . || true)
    under_sampled=$(awk -F'\t' -v k="$E08_KILLED_AT" 'NR > 1 && $1 >= k && $4 < 3' "$EXP_DIR/isr.tsv" | grep -c . || true)
    experiments_record "copies" "$samples samples of every partition's in-sync replicas and end offset; $under_sampled partition readings below three in sync after the kill; $1 intervals in which a partition took a write with fewer than two in sync (first: $2)"
    # The control: the sampler saw the kill shrink the in-sync sets, so a write on too few copies would have been seen.
    [ "$samples" -ge 3 ] && [ "$under_sampled" -ge 1 ] || fail "E08: the sampler read $samples samples and no partition below three in sync after the kill, so it shows nothing: see experiments/E08/isr.tsv"
    [ "$1" = 0 ] || fail "E08: $1 intervals in which a partition took a write acknowledged by fewer than two replicas in sync (first: $2)${PROBE:+ (PROBE $PROBE)}"
    [ -n "$EXP_STOPPED" ] && return 0
    [ -n "$returned" ] || { EXP_STOPPED="the killed broker was not back in sync within 600 s"; return 0; }
    if [ "$PROBE" != min-isr-one ]; then
        exit_code=$(kube -n finapp get pod "$E08_VICTIM" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}')
        [ "$exit_code" = 137 ] || fail "E08: the killed broker ended with $exit_code, not 137: it was not a SIGKILL"
    fi
    failed_after=$(experiments_prom_value 'sum(finapp_outbox_publication_total{outcome="failed"})')
    experiments_record "returned" "the broker restarted in its pod (exit 137), every partition back in sync $returned s after the kill; the relay's failed attempts ${E08_FAILED_BEFORE:-unread} before, ${failed_after:-unread} after (recorded: a leader's move may cost an attempt its retry, which the outbox holds)"
    pass "E08: one broker of three lost and back in $returned s; no write was acknowledged by fewer than two copies; the fleet served on"
}

E08_recover() {
    brokers_check
    sleep 10
    touch "$EXP_DIR/sampler-stop"
    wait "$E08_FLEET" 2>/dev/null || true
    stop_workload e08
    [ "$(workload_unresolved e08)" = 0 ] || fail "E08: $(workload_unresolved e08) requests stayed unresolved: see workload-e08.tsv"
    assert_flows_ran e08
    unready=$(awk -F'\t' -v at="$E08_KILLED_AT" 'NR > 1 && $1 >= at && $4 != "True"' "$EXP_DIR/fleet.tsv" | grep -c . || true)
    experiments_record workload "$(cat "$CLIENT/workload-e08.summary"); $unready replica readings unready after the kill"
    [ "$unready" = 0 ] || fail "E08: a replica went unready while a broker was lost: Kafka is not in readiness"
    [ "$(experiments_dead_lettered)" = 0 ] || fail "E08: an outbox row was abandoned"
    KAFKA_ADMIN_POD=finapp-kafka-0
}

# E09: every broker stopped for at least 10 minutes, until OutboxPublicationStalled fires (15 at most), while money keeps moving.
E09_steady() {
    brokers_check
    start_workload e09 retry
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E09_FLEET=$!
    BACKGROUND="$BACKGROUND $E09_FLEET"
    E09_PENDING_BEFORE=$(pending_events)
    experiments_record "outbox before" "$E09_PENDING_BEFORE rows pending"
}

E09_fault() {
    experiments_fault_on brokers-stopped
    deadline=$(( $(date +%s) + 180 ))
    until [ -z "$(brokers_ready)" ]; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "E09: the brokers did not stop within 180 s"
        sleep 2
    done
    E09_STOPPED_AT=$(date +%s)
    E09_MOVED_BEFORE=$(experiments_moved e09)
    experiments_record fault "every broker stopped (the StatefulSet at zero) at $(date -u -d "@$E09_STOPPED_AT" +%H:%M:%SZ)"
}

E09_observe() {
    stalled=""
    burning=""
    pages_at=0
    while :; do
        now=$(date +%s)
        held=$((now - E09_STOPPED_AT))
        if [ $((now - pages_at)) -ge 15 ]; then
            pages_at=$now
            experiments_pages_check E09 || break
            if [ -z "$stalled" ]; then
                answer=$(experiments_prometheus "/api/v1/query?query=$(urlencode 'ALERTS{alertname="OutboxPublicationStalled",alertstate="firing"}')" 2>/dev/null || true)
                case $answer in *'"alertname":"OutboxPublicationStalled"'*) stalled=$held ;; esac
            fi
            ratio=$(experiments_prom_value 'max(finapp:slo_event_freshness:error_ratio_rate5m)')
            case $ratio in '' | 0 | 0.0) ;; *) [ -n "$burning" ] || burning="$held s in, at $ratio" ;; esac
        fi
        # Stop condition and its limit: the outage held past 30 minutes. Held at least 10, until the stall has paged, 15 at most.
        if [ "$held" -ge 1800 ]; then
            EXP_STOPPED="the outage held 30 minutes"
            break
        fi
        if [ "$held" -ge 600 ] && { [ -n "$stalled" ] || [ "$held" -ge 900 ]; }; then
            break
        fi
        sleep 5
    done
    E09_HELD=$(( $(date +%s) - E09_STOPPED_AT ))
    moved=$(( $(experiments_moved e09) - E09_MOVED_BEFORE ))
    pending=$(pending_events)
    unready=$(awk -F'\t' -v at="$E09_STOPPED_AT" 'NR > 1 && $1 >= at && $4 != "True"' "$EXP_DIR/fleet.tsv" | grep -c . || true)
    experiments_record "outage" "held $E09_HELD s; $pending outbox rows waiting at its end; $moved money movements answered ok during it; OutboxPublicationStalled firing ${stalled:+$stalled s in}${stalled:-never}; the event freshness burn ${burning:-never seen}; $unready replica readings unready"
    [ -n "$EXP_STOPPED" ] && return 0
    [ -n "$stalled" ] || { EXP_STOPPED="OutboxPublicationStalled did not fire within 15 minutes of the outage"; return 0; }
    [ "$unready" = 0 ] || fail "E09: a replica went unready while the brokers were away: Kafka is not in readiness"
    [ "$moved" -ge 1 ] || fail "E09: no money movement was answered while the brokers were away"
    [ "$pending" -ge 1 ] || fail "E09: no outbox row waited during the outage, so it shows nothing"
    [ -n "$burning" ] || fail "E09: the event freshness objective did not burn during the outage"
    [ "$(experiments_dead_lettered)" = 0 ] || fail "E09: an outbox row was abandoned during the outage, inside the relay's retry window"
    pass "E09: the brokers away $E09_HELD s; $pending rows waited in the outbox, $moved movements answered, every replica ready, the stall paged $stalled s in"
}

E09_recover() {
    kube -n finapp rollout status statefulset/finapp-kafka --timeout=300s >> "$ART/apply.log" 2>&1 || fail "E09: the brokers did not come back"
    back=$(date +%s)
    brokers_check
    drained 900 || fail "E09: the outbox did not drain within 15 minutes of the brokers' return: $(pending_events) rows pending"
    experiments_record drained "the outbox drained $(( $(date +%s) - back )) s after the brokers were back"
    lag_settled e09 || fail "E09: a partition is unowned or lagging after the return: see consumer-groups-e09.txt"
    sleep 10
    touch "$EXP_DIR/sampler-stop"
    wait "$E09_FLEET" 2>/dev/null || true
    stop_workload e09
    [ "$(workload_unresolved e09)" = 0 ] || fail "E09: $(workload_unresolved e09) requests stayed unresolved: see workload-e09.tsv"
    assert_flows_ran e09
    [ "$(experiments_dead_lettered)" = 0 ] || fail "E09: an outbox row was abandoned"
    experiments_record workload "$(cat "$CLIENT/workload-e09.summary")"
}

# E22: duplication. (a) Published rows sent again, as a relay that crashed between the broker's acknowledgement and its mark sends
# them; (b) every consumer group reset to the earliest retained offset, the fleet stopped for it. The inbox must absorb every one.
E22_steady() {
    brokers_check
    start_workload e22 retry
    sleep 30
    stop_workload e22
    assert_flows_ran e22
    lag_settled e22-before || fail "E22: the groups were not settled before the experiment"
    E22_FROM=$(db_epoch)
    E22_APPLIED_BEFORE=$(experiments_applied "$E22_FROM")
    E22_RISK_BEFORE=$(psql_query -c "SELECT count(*) FROM risk.risk_signal" | tr -d '[:space:]')
    experiments_record "applied before" "$E22_APPLIED_BEFORE inbox rows for events written before the experiment; $E22_RISK_BEFORE risk signals"
}

E22_fault() {
    # (a) The 200 latest published rows of a type a catalogued handler names, their marks taken off by the run's own superuser, as a
    # crash between the acknowledgement and the mark leaves them: the relay sends them again under the same event id.
    E22_DUPLICATES_BEFORE=$(experiments_inbox_total duplicate)
    E22_FAILED_BEFORE=$(experiments_inbox_total failed)
    E22_SENT=$(psql_query -v "handlers=$HANDLERS" -f - <<'SQL' | tr -d '[:space:]'
\set ON_ERROR_STOP on
WITH chosen AS (
    SELECT e.event_id FROM platform.outbox_event e
     WHERE e.published_at IS NOT NULL AND e.discarded_at IS NULL AND e.replay_ordered_at IS NULL
       AND e.event_type IN (SELECT h.event_type FROM (VALUES :handlers) AS h (consumer, event_type))
     ORDER BY e.event_id DESC LIMIT 200),
sent AS (
    UPDATE platform.outbox_event e SET published_at = NULL FROM chosen c WHERE e.event_id = c.event_id RETURNING 1)
SELECT count(*) FROM sent;
SQL
)
    case $E22_SENT in '' | *[!0-9]*) cannot "E22: the rows could not be sent again: '$E22_SENT'" ;; esac
    [ "$E22_SENT" -ge 10 ] || cannot "E22: only $E22_SENT published rows of a handled type to send again"
    E22_SENT_AT=$(date +%s)
    experiments_record fault "(a) $E22_SENT published rows of handled types unmarked, to be sent again under their event ids"
}

E22_observe() {
    drained 300 || { EXP_STOPPED="the rows sent again were not published within 300 s"; return 0; }
    lag_settled e22-sent || { EXP_STOPPED="the groups did not settle after the rows were sent again"; return 0; }
    # Two scrapes past the last delivery, then the same replicas' counters again.
    sleep 45
    after=$(experiments_inbox_total duplicate)
    failed_after=$(experiments_inbox_total failed)
    case $E22_DUPLICATES_BEFORE:$after:$E22_FAILED_BEFORE:$failed_after in *unreadable*) fail "E22: Prometheus did not answer for the inbox's outcomes" ;; esac
    duplicates_a=$((after - E22_DUPLICATES_BEFORE))
    failed_a=$((failed_after - E22_FAILED_BEFORE))
    # (b) The fleet stopped, every group reset to the earliest retained record, the fleet started: every record redelivered.
    experiments_fault_on scaled finapp finapp-app 0 3
    deadline=$(( $(date +%s) + 300 ))
    until [ "$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name 2>/dev/null | grep -c . || true)" = 0 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "E22: the fleet did not stop within 300 s"
        sleep 3
    done
    # A reset needs groups with no member: a member that left without a word stays until its session times out.
    deadline=$(( $(date +%s) + 120 ))
    until kafka_admin kafka-consumer-groups.sh --describe --all-groups --state 2>/dev/null | awk '$1 ~ /^finapp\./ && $NF != 0 {busy = 1} END {exit busy}'; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "E22: a consumer group kept a member 120 s after the fleet stopped"
        sleep 5
    done
    kafka_admin kafka-consumer-groups.sh --reset-offsets --all-groups --all-topics --to-earliest --execute > "$EXP_DIR/reset.txt" 2>&1 \
        || cannot "E22: the groups could not be reset: see experiments/E22/reset.txt"
    kafka_admin kafka-consumer-groups.sh --describe --all-groups > "$EXP_DIR/groups-reset.txt" 2>/dev/null || true
    E22_REDELIVER=$(awk '$1 ~ /^finapp\./ && $6 ~ /^[0-9]+$/ {n += $6} END {print n + 0}' "$EXP_DIR/groups-reset.txt")
    E22_RESET_AT=$(date +%s)
    experiments_fault_off scaled finapp finapp-app 0 3
    ready_three 600 || { EXP_STOPPED="the fleet did not return to three ready replicas after the reset"; return 0; }
    lag_settled e22-reset || { EXP_STOPPED="the groups did not settle after the reset"; return 0; }
    sleep 45
    # Every replica now started after the reset: its counters are what it did since.
    duplicates_b=$(experiments_inbox_total duplicate)
    failed_b=$(experiments_inbox_total failed)
    case $duplicates_b:$failed_b in *unreadable*) fail "E22: Prometheus did not answer for the inbox's outcomes" ;; esac
    failed=$((failed_a + failed_b))
    parked=$(experiments_parked "$E22_SENT_AT")
    applied=$(experiments_applied "$E22_FROM")
    risk=$(psql_query -c "SELECT count(*) - count(DISTINCT source_event_id) || '|' || count(*) FROM risk.risk_signal" | tr -d '[:space:]')
    experiments_record "absorbed" "(a) $E22_SENT rows sent again, ${duplicates_a} deliveries counted duplicate; (b) $E22_REDELIVER records redelivered after the reset at $(date -u -d "@$E22_RESET_AT" +%H:%M:%SZ), ${duplicates_b} counted duplicate; ${failed} failed, ${parked:-unread} parked; inbox rows for events before the experiment $E22_APPLIED_BEFORE before and $applied after; risk signals ${risk#*|} (${risk%%|*} for a repeated source event)"
    [ "$duplicates_a" -ge 1 ] && [ "$duplicates_b" -ge 1 ] || fail "E22: the inbox counted no duplicate (${duplicates_a} and ${duplicates_b}), so the duplicates were not delivered"
    [ "$E22_REDELIVER" -ge 1 ] || fail "E22: the reset redelivered nothing: see experiments/E22/groups-reset.txt"
    [ "$failed" = 0 ] && [ "$parked" = 0 ] || EXP_STOPPED="a duplicate failed or parked ($failed failed, ${parked:-unread} parked)"
    [ "$applied" = "$E22_APPLIED_BEFORE" ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a second effect: inbox rows for events before the experiment went from $E22_APPLIED_BEFORE to $applied"
    [ "${risk%%|*}" = 0 ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a second risk signal for one source event"
    [ -n "$EXP_STOPPED" ] || pass "E22: $E22_SENT rows sent again and $E22_REDELIVER records redelivered, every applied one absorbed by the inbox, no second effect"
}

E22_recover() {
    ready_three 300 || fail "E22: the fleet is not three ready replicas"
    experiments_topology_check
}

# E23: delay. A session of the run's superuser holds the relay's own lock on a few aggregates as their rows appear, for 10 minutes, and
# lets go: their records reach the consumers 10 minutes late, after later records of every other aggregate, each aggregate in its order.
E23_steady() {
    brokers_check
    start_workload e23 retry
    sleep 8
    E23_FROM=$(db_epoch)
}

E23_fault() {
    experiments_fault_on relay-held 600 8
    E23_HELD_AT=$(date +%s)
    experiments_record fault "the relay's lock held on the first 8 aggregates with rows waiting, for 600 s, from $(date -u -d "@$E23_HELD_AT" +%H:%M:%SZ)"
}

# experiments_relay_hold <seconds> <aggregates>: a session holding the relay's own advisory lock (namespace 1, the key OutboxRelay takes:
# Java's UUID.hashCode() of the aggregate id) on the first <aggregates> aggregates with a row waiting, taken as they appear, for <seconds>.
# The relay try-locks and moves on (a refused lock takes no slot), so their rows wait in order while every other aggregate publishes. It
# writes nothing; its end, or its termination, releases them. It prints each aggregate it held, HELD|<id>|<when>, before it ends.
experiments_relay_hold() {
    kube -n finapp exec -i finapp-postgres-0 -c postgres -- psql -q -X -A -t -U finapp -d finapp > "$EXP_ART/relay-hold.out" 2>&1 <<SQL &
\set ON_ERROR_STOP on
SET application_name = '$EXPERIMENTS_HOLD_APP';
CREATE TEMP TABLE held (aggregate_id uuid PRIMARY KEY, held_at timestamptz NOT NULL);
DO \$\$
DECLARE
    candidate uuid;
    hilo bigint;
    until timestamptz := clock_timestamp() + make_interval(secs => $1);
BEGIN
    WHILE clock_timestamp() < until LOOP
        IF (SELECT count(*) FROM held) < $2 THEN
            FOR candidate IN SELECT DISTINCT e.aggregate_id FROM platform.outbox_event e
                              WHERE e.published_at IS NULL AND e.discarded_at IS NULL
                                AND NOT EXISTS (SELECT 1 FROM held h WHERE h.aggregate_id = e.aggregate_id) LOOP
                EXIT WHEN (SELECT count(*) FROM held) >= $2;
                hilo := ('x' || substr(replace(candidate::text, '-', ''), 1, 16))::bit(64)::bigint
                      # ('x' || substr(replace(candidate::text, '-', ''), 17, 16))::bit(64)::bigint;
                IF pg_try_advisory_lock(1, ((hilo >> 32) # hilo)::bit(32)::int) THEN
                    INSERT INTO held VALUES (candidate, clock_timestamp());
                END IF;
            END LOOP;
        END IF;
        PERFORM pg_sleep(0.2);
    END LOOP;
END
\$\$;
SELECT 'HELD|' || aggregate_id || '|' || extract(epoch FROM held_at)::bigint FROM held ORDER BY held_at;
SELECT pg_advisory_unlock_all();
SQL
    EXPERIMENTS_HOLD_PID=$!
    BACKGROUND="$BACKGROUND $EXPERIMENTS_HOLD_PID"
    deadline=$(( $(date +%s) + 30 ))
    until [ "$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = '$EXPERIMENTS_HOLD_APP'" | tr -d '[:space:]')" = 1 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || return 1
        sleep 1
    done
}

# Its undo: the holding session ended, which releases every lock it took.
experiments_relay_release() {
    psql_query -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = '$EXPERIMENTS_HOLD_APP'"
}

E23_observe() {
    pages_at=0
    while kill -0 "$EXPERIMENTS_HOLD_PID" 2>/dev/null; do
        now=$(date +%s)
        if [ $((now - pages_at)) -ge 15 ]; then
            pages_at=$now
            experiments_pages_check E23 || { experiments_fault_off relay-held 600 8; return 0; }
        fi
        [ $((now - E23_HELD_AT)) -lt 1800 ] || { EXP_STOPPED="the hold outlived 30 minutes"; experiments_fault_off relay-held 600 8; return 0; }
        sleep 5
    done
    E23_RELEASED_AT=$(date +%s)
    grep '^HELD|' "$EXP_ART/relay-hold.out" | cut -d'|' -f2,3 > "$EXP_DIR/held.txt" || true
    held=$(grep -c . "$EXP_DIR/held.txt" || true)
    [ "$held" -ge 1 ] || { EXP_STOPPED="the hold caught no aggregate: see experiments/relay-hold.out"; return 0; }
    ids=$(cut -d'|' -f1 "$EXP_DIR/held.txt" | sed "s/.*/'&'/" | paste -sd, -)
    # Every held aggregate's rows published within two minutes of the release; the late ones were late.
    deadline=$((E23_RELEASED_AT + 120))
    until [ "$(psql_query -c "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id IN ($ids) AND published_at IS NULL AND discarded_at IS NULL" | tr -d '[:space:]')" = 0 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || { EXP_STOPPED="a held aggregate's rows were not published within 120 s of the release"; return 0; }
        sleep 3
    done
    late=$(psql_query -c "SELECT count(*) || '|' || coalesce(max(extract(epoch FROM published_at - occurred_at))::bigint, 0)
        FROM platform.outbox_event WHERE aggregate_id IN ($ids) AND published_at >= to_timestamp($E23_RELEASED_AT - 5)" | tr -d '[:space:]')
    lag_settled e23 || { EXP_STOPPED="the groups did not settle after the release"; return 0; }
    sleep 10
    # Per consumer and aggregate, each record applied after every earlier one: the inbox row's server time (its expiry less the
    # retention every row carries) against the outbox's order of the aggregate's events.
    violations=$(psql_query -c "SELECT count(*) FROM (
        SELECT i.expires_at, max(i.expires_at) OVER (PARTITION BY i.consumer, e.aggregate_id ORDER BY e.event_id
                                                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS before
          FROM platform.inbox_message i JOIN platform.outbox_event e ON i.dedupe_key = e.event_id::text
         WHERE e.occurred_at >= to_timestamp($E23_FROM)) o WHERE o.before > o.expires_at" | tr -d '[:space:]')
    applied=$(psql_query -c "SELECT count(*) || '|' || count(DISTINCT i.dedupe_key) FROM platform.inbox_message i
        JOIN platform.outbox_event e ON i.dedupe_key = e.event_id::text WHERE e.aggregate_id IN ($ids)" | tr -d '[:space:]')
    parked=$(experiments_parked "$E23_HELD_AT")
    experiments_record "delayed" "$held aggregates held $((E23_RELEASED_AT - E23_HELD_AT)) s; ${late%%|*} of their rows published after the release, the latest ${late##*|} s after it was written; their records applied ${applied%%|*} times by consumers over ${applied##*|} events; $violations order violations per consumer and aggregate since the experiment began; ${parked:-unread} parked"
    [ "${late%%|*}" -ge 1 ] && [ "${late##*|}" -ge 300 ] || fail "E23: no held row was published late, so the hold delayed nothing: see experiments/E23/held.txt"
    [ "$violations" = 0 ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }$violations records applied before an earlier record of their aggregate"
    [ "$parked" = 0 ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }$parked records parked"
    [ -n "$EXP_STOPPED" ] || pass "E23: $held aggregates' records held $((E23_RELEASED_AT - E23_HELD_AT)) s and released after every other aggregate's, each applied once and in its aggregate's order"
}

E23_recover() {
    stop_workload e23
    [ "$(workload_unresolved e23)" = 0 ] || fail "E23: $(workload_unresolved e23) requests stayed unresolved: see workload-e23.tsv"
    assert_flows_ran e23
}

# E49: the run's day replayed through the audited door, inside the dedupe window: every event type a catalogued handler names, every
# event of it the run published, in windows of at most 900 under the door's bound of 1000 a command, each an operator's audited order.
E49_steady() {
    brokers_check
    lag_settled e49-before || fail "E49: the groups were not settled before the replay"
    E49_FROM=$(db_epoch)
    E49_APPLIED_BEFORE=$(experiments_applied "$E49_FROM")
    psql_query -v "handlers=$HANDLERS" -f - > "$EXP_DIR/windows.txt" <<'SQL' || cannot "E49: the replay's windows could not be read"
\set ON_ERROR_STOP on
WITH published AS (
    SELECT e.event_type, coalesce(e.first_published_at, e.published_at) AS t
      FROM platform.outbox_event e
     WHERE e.published_at IS NOT NULL AND e.discarded_at IS NULL
       AND e.event_type IN (SELECT h.event_type FROM (VALUES :handlers) AS h (consumer, event_type))),
numbered AS (
    SELECT event_type, t, (row_number() OVER (PARTITION BY event_type ORDER BY t) - 1) / 900 AS chunk FROM published),
chunks AS (
    SELECT event_type, chunk, min(t) AS first_t, max(t) AS last_t, count(*) AS events FROM numbered GROUP BY event_type, chunk)
SELECT event_type || '|' || to_char(first_t AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|'
       || to_char(coalesce(lead(first_t) OVER (PARTITION BY event_type ORDER BY chunk), last_t + interval '1 millisecond') AT TIME ZONE 'UTC',
                  'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' || events
  FROM chunks ORDER BY event_type, chunk;
SQL
    E49_WINDOWS=$(grep -c '|' "$EXP_DIR/windows.txt" || true)
    E49_EVENTS=$(awk -F'|' '{n += $4} END {print n + 0}' "$EXP_DIR/windows.txt")
    [ "$E49_WINDOWS" -ge 1 ] && [ "$E49_EVENTS" -ge 1 ] || cannot "E49: the run published no event of a handled type to replay"
    experiments_record "the day" "$E49_EVENTS published events of $(cut -d'|' -f1 "$EXP_DIR/windows.txt" | sort -u | grep -c .) handled types, in $E49_WINDOWS windows of at most 900; $E49_APPLIED_BEFORE inbox rows for them before"
}

E49_fault() {
    E49_DUPLICATES_BEFORE=$(experiments_inbox_total duplicate)
    E49_FAILED_BEFORE=$(experiments_inbox_total failed)
    E49_STARTED=$(date +%s)
    requeued=0
    n=0
    while IFS='|' read -r type from to events; do
        [ -n "$type" ] || continue
        n=$((n + 1))
        answer=$(client replay "$type" "$from" "$to" "E49: the run's day replayed inside the dedupe window" 2>&1 | grep '^replay ' | tail -1)
        got=$(printf '%s' "$answer" | sed -n 's/.* requeued=\([0-9]*\) .*/\1/p')
        printf '%s\t%s\t%s\t%s\t%s\n' "$type" "$from" "$to" "$events" "${got:-refused}" >> "$EXP_DIR/replays.tsv"
        [ "${got:-x}" = "$events" ] || fail "E49: the window $n of $type ($from to $to) requeued '${got:-refused}' of its $events events: see experiments/E49/replays.tsv"
        requeued=$((requeued + got))
    done < "$EXP_DIR/windows.txt"
    E49_REQUEUED=$requeued
    E49_ORDERED_AT=$(date +%s)
    experiments_record fault "$n audited replay orders through the operator door, $E49_REQUEUED events requeued, in $((E49_ORDERED_AT - E49_STARTED)) s"
}

E49_observe() {
    drained 1800 || { EXP_STOPPED="the replayed rows were not published within 30 minutes"; return 0; }
    E49_PUBLISHED_AT=$(date +%s)
    lag_settled e49 || { EXP_STOPPED="the groups did not settle after the replay"; return 0; }
    E49_SETTLED_AT=$(date +%s)
    sleep 45
    duplicates_after=$(experiments_inbox_total duplicate)
    failed_after=$(experiments_inbox_total failed)
    case $E49_DUPLICATES_BEFORE:$duplicates_after:$E49_FAILED_BEFORE:$failed_after in *unreadable*) fail "E49: Prometheus did not answer for the inbox's outcomes" ;; esac
    duplicates=$((duplicates_after - E49_DUPLICATES_BEFORE))
    failed=$((failed_after - E49_FAILED_BEFORE))
    parked=$(experiments_parked "$E49_STARTED")
    applied=$(experiments_applied "$E49_FROM")
    audits=$(psql_query -c "SELECT count(*) FROM platform.audit_record WHERE operation = 'outbox.EventReplayOrdered' AND occurred_at >= to_timestamp($E49_STARTED - 5)" | tr -d '[:space:]')
    windows=$(grep -c . "$EXP_DIR/replays.tsv" || true)
    rate=$(awk -v e="$E49_REQUEUED" -v s="$((E49_SETTLED_AT - E49_STARTED))" 'BEGIN {printf "%.1f", (s > 0 ? e / s : 0)}')
    experiments_record "replayed" "$E49_REQUEUED events requeued, every one published again $((E49_PUBLISHED_AT - E49_STARTED)) s after the first order, every group settled $((E49_SETTLED_AT - E49_STARTED)) s after it ($rate events a second from order to settled, measured on this run's volume); $duplicates deliveries counted duplicate, $failed failed, ${parked:-unread} parked; inbox rows for the replayed events $E49_APPLIED_BEFORE before and $applied after; $audits audit records for $windows orders"
    [ "$duplicates" -ge 1 ] || fail "E49: the inbox counted no duplicate, so the replayed records were not delivered"
    [ "$audits" = "$windows" ] || fail "E49: $audits audit records for $windows replay orders"
    [ "$failed" = 0 ] && [ "$parked" = 0 ] || EXP_STOPPED="a replayed record failed or parked ($failed failed, ${parked:-unread} parked)"
    [ "$applied" = "$E49_APPLIED_BEFORE" ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a second effect: inbox rows for the replayed events went from $E49_APPLIED_BEFORE to $applied"
    [ -n "$EXP_STOPPED" ] || pass "E49: the run's $E49_REQUEUED published events replayed in $windows audited orders, every one absorbed, no second effect"
}

E49_recover() {
    experiments_topology_check
}
