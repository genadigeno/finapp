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
# its count to restore recorded). A kill, a deletion or an eviction is instant and is logged, not registered. finish() calls
# experiments_clear_faults on every way out, the INT and TERM traps and an unguarded failure included: every registered fault undone,
# newest first, then a blanket reset that needs no record (every proxy enabled with no toxic, both hold flags and every degradation flag
# removed, every labelled policy deleted, every paused node of this cluster unpaused), then a read that nothing is left
# (experiments/faults-cleared.txt).
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
# THE PROBES (REHEARSAL_PROBE), each a deliberate break that must turn the run red:
#   guard-off          the refusals answer "pass" without reading anything: every plant of experiments_boundary goes unrefused;
#   crash-with-faults  every kind of fault put on, then an unguarded failure: the exit trap must leave none on (read on a kept cluster);
#   term-with-faults   the same, then SIGTERM to the harness itself, the signal path of the same trap.
# REHEARSAL_EXPERIMENTS names the experiments to run (E01 by default; the catalogue is P16-TSK-020...-023's and P16-TSK-027's).
# The provider experiments E17, E18 and E21 (P16-TSK-018) degrade the simulator one rail at a time (its flags degrade-<rail>-unavailable and
# -silent, put on and taken off by the hold kind) or slow its hop (a latency toxic); E19 and E20 refuse to run, each naming why.
#
# E40 (P16-TSK-008, at three replicas): the sweeps' failure storm - every call to the provider simulator hangs for the payment sweep's
# dispatched age and fifteen minutes more, so every candidate of the sweeps fails and is recorded and backed off; the eight listings' plans
# and statistics read with the record tables empty and with the storm's rows, the tables' writes, dead rows and bytes every 30 s; every
# candidate resolved once the provider answers; the census green. Its plans come from auto_explain's window on the run's own server
# (load.sh's pg_plans_open and pg_plans_close).

EXPERIMENTS=${REHEARSAL_EXPERIMENTS:-E01}
# Every degradation flag the simulator reads (Simulator.java): the clearing removes each, whatever was registered.
EXPERIMENTS_DEGRADATIONS="degrade-card-unavailable degrade-card-silent degrade-tokenisation-unavailable degrade-tokenisation-silent degrade-payout-unavailable degrade-payout-silent degrade-fx-unavailable degrade-fx-silent degrade-reference-unavailable degrade-reference-silent"
TOXIPROXY_IMAGE="$TOXIPROXY_REPOSITORY@$TOXIPROXY_DIGEST"
EXPERIMENTS_MARKER=finapp-experiments-marker
EXPERIMENTS_CONFIG="$DIR/experiments/kind-cluster.yaml"
EXPERIMENTS_COLLECTOR=http://finapp-collector.finapp-observability.svc.cluster.local:4318/
# The census's expected payouts take this: E01's own payout, zero in every other scope.
EXPERIMENTS_PAYOUTS=0
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
    awk -v aliases="$WORK/host-aliases.yaml" '/^      serviceAccountName: / && !done {while ((getline line < aliases) > 0) print line; done = 1} {print}' \
        "$A/Deployment.finapp-app.yaml" > "$WORK/deployment.tmp" && mv "$WORK/deployment.tmp" "$A/Deployment.finapp-app.yaml"
    grep -q "^        - ip: \"$PROXY_IP\"$" "$A/Deployment.finapp-app.yaml" || cannot "the application's names could not be sent to the proxy"
    log "the fault proxy on the application's three hops at $PROXY_IP; the standby synchronous"
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
    [ -n "$proxy" ] && [ "$aliases" = "$proxy $PG_HOST $KAFKA_HOST $SIM_HOST" ] \
        || { EXP_REASON="endpoint: the application's names are sent to '$aliases', not the run's three to its proxy at '$proxy'"; return 1; }
    upstreams=$(experiments_toxiproxy list 2>/dev/null | awk -F'\t' 'NF >= 4 {print $1 "=" $3}' | sort | tr '\n' ' ')
    [ "$upstreams" = "kafka=$KAFKA_HOST:9092 postgres=$PG_HOST:5432 simulator=$SIM_HOST:8443 " ] \
        || { EXP_REASON="endpoint: the proxy's upstreams are '$upstreams', not the run's three services"; return 1; }
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
    [ "$(printf '%s\n' "$proxies" | awk -F'\t' 'NF >= 5' | grep -c . || true)" = 3 ] || echo "the proxy lists $(printf '%s\n' "$proxies" | grep -c . || true) proxies, not its three"
    files=$(experiments_simulator ls -a /tmp 2>/dev/null) || echo "the simulator could not be read"
    printf '%s\n' "$files" | grep -x 'hold-payouts\|hold-inquiries' | sed "s/^/the simulator's flag /"
    printf '%s\n' "$files" | grep -x 'degrade-[a-z]*-unavailable\|degrade-[a-z]*-silent' | sed "s/^/the simulator's flag /"
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" get networkpolicy -l finapp/experiment-fault=true -o name 2>/dev/null | sed "s#^#$namespace #"
    done
    while read -r kind namespace deployment replicas restore; do
        [ "$kind" = scaled ] || continue
        now=$(kube -n "$namespace" get "deployment/$deployment" -o jsonpath='{.spec.replicas}' 2>/dev/null)
        [ "$now" = "$restore" ] || echo "$namespace/$deployment at $now replicas, not $restore"
    done < "$FAULTS.cleared"
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
    # The provider experiments' degradation flags (P16-TSK-018), each a rail and a mode.
    for flag in $EXPERIMENTS_DEGRADATIONS; do
        experiments_simulator rm -f "/tmp/$flag" >> "$EXP_ART/faults.log" 2>&1
    done
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" delete networkpolicy -l finapp/experiment-fault=true --ignore-not-found >> "$EXP_ART/faults.log" 2>&1
    done
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
    for ip in $(printf '%s\n' "$fleet" | awk -F'|' 'NF >= 5 && $6 == "" {print $5}'); do
        for proxy in postgres kafka; do
            grep '"message":"Accepted client"' "$EXP_ART/toxiproxy.log" | grep "\"name\":\"$proxy\"" | grep -q "\"client\":\"$ip:" \
                || fail "topology: the replica at $ip never reached the $proxy hop through the proxy: see experiments/toxiproxy.log"
        done
    done
    callers=$(grep '"message":"Accepted client"' "$EXP_ART/toxiproxy.log" | grep '"name":"simulator"' | sed -n 's/.*"client":"\([0-9.]*\):.*/\1/p' | sort -u | grep -c . || true)
    [ "$callers" -ge 1 ] || fail "topology: no replica's provider call went through the proxy: see experiments/toxiproxy.log"
    pass "topology: every one of $all application sessions arrives from the proxy over TLS; $counted consumer-group members at its address and none elsewhere; every replica accepted by the database and broker proxies, $callers by the simulator's (the clients' URLs and verify-full unchanged)"
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
    experiments_faults_left > "$EXP_ART/faults-before-crash.txt" 2>&1 || true
    experiments_fault_on node-paused "$victim_node"
    echo "paused node $victim_node" >> "$EXP_ART/faults-before-crash.txt"
    log "PROBE $PROBE: six faults on - a toxic, a proxy down, a hold, a partition, a scale to zero, a paused node ($victim_node) - and now the harness dies"
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
# The provider experiments (P16-TSK-018): E17 the card PSP out, E18 the FX provider and the reference out, E21 every provider slow;
# E19 and E20 refused with their reasons. docs/architecture/TIMEOUTS_AND_RETRIES.md section 5; OPERATIONS_RUNBOOK.md section 11.53.
# Each degrades the simulator through the fault registry (the hold kind puts a flag file on and takes it off, Simulator.java reads
# it) or the proxy (a latency toxic on the simulator's hop), so the boundary passes before every fault and the exit trap clears it.
# Each reads the platform's side (the database, Prometheus) and the provider's side (the simulator's requests.tsv) and judges both.
# ---------------------------------------------------------------------------------------------------

# How long each phase of a provider fault holds: long enough for several sweep ticks, short of the unknown-age page's 15 minutes.
PROVIDER_PHASE_SECONDS=${REHEARSAL_PROVIDER_PHASE_SECONDS:-240}

# The bound a card movement is resolved within once its provider answers again, read from the code's default as the load scope reads
# it (never copied): twice the payment sweep's dispatched age, and five minutes for the sweep's poll, the claim's lease and the client.
provider_resolve_seconds() {
    age=$(sed -n 's/.*finapp\.payments\.sweeper\.dispatched-age:PT\([0-9]*\)M}.*/\1/p' \
        "$ROOT/app/src/main/java/com/finapp/app/payments/PaymentSweeperSchedule.java" | head -1)
    case $age in '' | *[!0-9]*) cannot "the payment sweep's dispatched age could not be read from PaymentSweeperSchedule.java" ;; esac
    ! grep -Eqr 'SWEEPER_DISPATCHED_?AGE|sweeper\.dispatched-age' "$ROOT/deploy" \
        || cannot "a deployed overlay sets the payment sweep's dispatched age, which this bound reads from the code's default"
    PROVIDER_RESOLVE_SECONDS=$((2 * age * 60 + 300))
}

# provider_requests: the simulator's own record of every request, degraded or not (Simulator.java's requests.tsv), into the
# experiment's directory: millis, method, path, our key or the inquired reference, what became of it.
provider_requests() {
    sleep 3
    experiments_simulator cat /tmp/requests.tsv > "$EXP_DIR/requests.tsv" 2>/dev/null || cannot "$EXP_ID: the simulator's requests could not be read"
    [ -s "$EXP_DIR/requests.tsv" ] || cannot "$EXP_ID: the simulator recorded no request at all, so nothing could be judged"
}

# provider_blind_resends <since millis>: every dispatch key the provider received again with no answered inquiry of it in between - a blind resend
# of an ambiguous call, which the platform must never make (PHASE_16_PLAN.md section 8). A resend after an inquiry is class (c), the
# provider having said it never saw the first. Prints one line per blind resend; nothing printed is none.
provider_blind_resends() {
    awk -F'\t' -v since="$1" '$1 >= since && $4 != "-" {
        if ($2 == "POST") { key = $3 "|" $4; if ((key in sent) && !asked[$4]) print; sent[key] = 1; asked[$4] = 0 }
        else if ($5 == "inquiry") asked[$4] = 1
    }' "$EXP_DIR/requests.tsv"
}

# provider_rail_outcomes <label>: the acting judgements so far, by rail and outcome (finapp.payments.rail.outcome, counted after commit).
provider_rail_outcomes() {
    answer=$(experiments_prometheus "/api/v1/query?query=$(urlencode 'sum by (rail, outcome) (finapp_payments_rail_outcome_total)')" 2>/dev/null || true)
    case $answer in *'"status":"success"'*) ;; *) cannot "$EXP_ID: Prometheus did not answer the rail outcomes ($1)" ;; esac
    printf '%s' "$answer" | grep -o '"metric":{[^}]*},"value":\[[^]]*\]' \
        | sed -n 's/.*"outcome":"\([^"]*\)".*"rail":"\([^"]*\)".*"\([0-9.e+]*\)"\]$/\2\t\1\t\3/p' | sort > "$EXP_DIR/rail-outcomes-$1.tsv"
}

# provider_failure_rates <from label> <to label>: per rail, the share of the acting judgements between two readings that failed or were left
# unknown - the measurement owner decision C12 waits on (TIMEOUTS_AND_RETRIES.md section 5). Recorded, never judged.
provider_failure_rates() {
    rates=$(awk -F'\t' -v from="$EXP_DIR/rail-outcomes-$1.tsv" '
        BEGIN { while ((getline line < from) > 0) { split(line, f, "\t"); before[f[1] "\t" f[2]] = f[3] } }
        { delta = $3 - before[$1 "\t" $2]; all[$1] += delta; if ($2 == "FAILED" || $2 == "UNKNOWN") bad[$1] += delta }
        END { for (rail in all) printf "%s %d of %d (%s); ", rail, bad[rail], all[rail], (all[rail] > 0 ? sprintf("%.4f", bad[rail] / all[rail]) : "none judged") }' \
        "$EXP_DIR/rail-outcomes-$2.tsv")
    experiments_record "failure rate per rail, $1 to $2" "${rates:-no judgement}"
}

# provider_page <name>: whether a page fires now, by name, for the record ("firing", "silent" or "unreadable").
provider_page() {
    answer=$(experiments_prometheus "/api/v1/query?query=$(urlencode "ALERTS{alertstate=\"firing\",alertname=\"$1\"}")" 2>/dev/null || true)
    case $answer in *'"status":"success"'*) ;; *) echo unreadable; return 0 ;; esac
    case $answer in *"\"alertname\":\"$1\""*) echo firing ;; *) echo silent ;; esac
}

# provider_watch <seconds> <what>: the experiment held for a phase, its correctness pages watched every 15 s (ADR-0127 decision 6), and the
# experiment's own stop condition (provider_stop_condition, set by each experiment) read on each pass. Returns early on a stop.
provider_watch() {
    until_epoch=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$until_epoch" ]; do
        firing=$(experiments_pages_firing)
        if [ -n "$firing" ]; then
            EXP_STOPPED="a correctness page during $2: $firing"
            log "$EXP_ID STOP: $EXP_STOPPED"
            return 0
        fi
        # Called in this shell, never inside $( ): the stop condition keeps its count across passes, and a refusal ends the run.
        provider_stop_condition
        if [ -n "$PROVIDER_STOP" ]; then
            EXP_STOPPED="$PROVIDER_STOP, during $2"
            log "$EXP_ID STOP: $EXP_STOPPED"
            return 0
        fi
        printf '%s\t%s\tnone\n' "$(date -u +%H:%M:%S)" "$2" >> "$EXP_DIR/pages.tsv"
        sleep 15
    done
}

# Card attempts born since the experiment's start: captured ones whose authorization and capture the provider never took, and ones still
# waiting on an answer.
provider_card_unanswered() {
    # Called inside $( ): it never ends the run itself, and an unreadable side is printed as a problem.
    psql_query -c "SELECT a.status || ' ' || a.auth_reference || ' ' || coalesce(a.capture_reference, '-') FROM payments.payment_attempt a
        WHERE a.rail = 'card' AND a.created_at >= to_timestamp($PROVIDER_START) AND a.status IN ('AUTHORIZED', 'CAPTURED')" \
        > "$EXP_DIR/card-concluded.raw" 2>/dev/null || { echo "the concluded card attempts could not be read"; return 0; }
    tr -d '\r' < "$EXP_DIR/card-concluded.raw" > "$EXP_DIR/card-concluded.txt"
    [ -s "$EXP_DIR/requests.tsv" ] || { echo "the provider's requests could not be read"; return 0; }
    awk -F'\t' '$5 == "effect" && ($3 == "/authorizations" || $3 == "/captures") {print $3 " " $4}' "$EXP_DIR/requests.tsv" | sort -u > "$EXP_DIR/card-effects.txt"
    awk 'NR == FNR {taken[$0] = 1; next}
        NF >= 2 && !(("/authorizations " $2) in taken) {print "authorized with no authorization the provider took: " $0}
        NF >= 3 && $1 == "CAPTURED" && !(("/captures " $3) in taken) {print "captured with no capture the provider took: " $0}' \
        "$EXP_DIR/card-effects.txt" "$EXP_DIR/card-concluded.txt"
}

provider_card_waiting() {
    psql_query -c "SELECT count(*) FROM payments.payment_attempt WHERE rail = 'card' AND created_at >= to_timestamp($PROVIDER_START)
        AND status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'VOID_DISPATCHED', 'VOID_UNKNOWN')" | tr -d '[:space:]'
}

# Card attempts that left one unknown state more than once: an unknown is resolved once.
provider_card_resolved_twice() {
    psql_query -c "SELECT count(*) FROM (SELECT e.attempt_id, e.from_status FROM payments.payment_attempt_event e
        JOIN payments.payment_attempt a ON a.id = e.attempt_id
        WHERE a.rail = 'card' AND a.created_at >= to_timestamp($PROVIDER_START) AND e.from_status LIKE '%\_UNKNOWN'
        GROUP BY e.attempt_id, e.from_status HAVING count(*) > 1) twice" | tr -d '[:space:]'
}

# ---------------------------------------------------------------------------------------------------
# E17: the card PSP unavailable (503), then silent past the timeout, under the workload, at three replicas.
# ---------------------------------------------------------------------------------------------------

E17_steady() {
    provider_resolve_seconds
    PROVIDER_START=$(db_epoch)
    PROVIDER_START_MILLIS=$((PROVIDER_START * 1000))
    # The client keeps asking under each key until its movement ends, past the outage and the sweep's resolution: what it was told is
    # then what the platform concluded, so the census's client counts and the provider's keys can agree.
    WORKLOAD_RETRY_SECONDS=$((2 * PROVIDER_PHASE_SECONDS + PROVIDER_RESOLVE_SECONDS))
    start_workload e17 retry
    unset WORKLOAD_RETRY_SECONDS
    sleep 60
    provider_rail_outcomes steady-start
    sleep 60
    provider_rail_outcomes steady
    experiments_record "steady state" "the workload running with retries under its keys for $((2 * PROVIDER_PHASE_SECONDS + PROVIDER_RESOLVE_SECONDS)) s; the card rail answering"
}

# Each experiment's own stop condition (its register row's), read on every pass of provider_watch; sets PROVIDER_STOP. A reading that
# cannot be made is a stop too: a count nobody could read is not a zero.
provider_stop_condition() {
    PROVIDER_STOP=""
    case $EXP_ID in
        E17)
            experiments_simulator cat /tmp/requests.tsv > "$EXP_DIR/requests.tsv" 2>/dev/null || true
            unanswered=$(provider_card_unanswered)
            [ -z "$unanswered" ] || PROVIDER_STOP="a payment posted without a provider answer: $(printf '%s' "$unanswered" | head -1)" ;;
        E18)
            stale=$(provider_fx_stale)
            [ "$stale" = 0 ] || PROVIDER_STOP="a conversion on a stale rate, or the count unreadable ('$stale')" ;;
        E21)
            provider_unaffected_breach ;;
    esac
}

E17_fault() {
    experiments_fault_on hold degrade-card-unavailable
    experiments_record fault "the card rail answered 503 for every request (degrade-card-unavailable) from $(date -u +%H:%M:%SZ)"
    provider_watch "$PROVIDER_PHASE_SECONDS" "the card rail unavailable"
    provider_rail_outcomes unavailable
    [ -n "$EXP_STOPPED" ] && return 0
    experiments_fault_off hold degrade-card-unavailable
    experiments_fault_on hold degrade-card-silent
    experiments_record fault "the card rail silent past the timeout for every request (degrade-card-silent) from $(date -u +%H:%M:%SZ)"
}

E17_observe() {
    [ -n "$EXP_STOPPED" ] && return 0
    provider_watch "$PROVIDER_PHASE_SECONDS" "the card rail silent"
    provider_rail_outcomes silent
    experiments_record "unknown-age page during the fault" "PaymentUnknownAged $(provider_page PaymentUnknownAged)"
}

E17_recover() {
    # The fault is off (the stop removed it). Every card movement born since the start resolves within the bound, each unknown once.
    deadline=$(( $(date +%s) + PROVIDER_RESOLVE_SECONDS ))
    waiting=1
    while [ "$(date +%s)" -lt "$deadline" ]; do
        waiting=$(provider_card_waiting)
        [ "$waiting" = 0 ] && break
        sleep 15
    done
    [ "$waiting" = 0 ] || fail "E17: $waiting card attempts born since the start still wait on an answer $PROVIDER_RESOLVE_SECONDS s after the provider returned"
    stop_workload e17
    assert_flows_ran e17
    experiments_record workload "$(cat "$CLIENT/workload-e17.summary")"
    provider_rail_outcomes returned
    provider_failure_rates steady-start steady
    provider_failure_rates steady unavailable
    provider_failure_rates unavailable silent
    provider_failure_rates silent returned
    provider_requests
    unanswered=$(provider_card_unanswered)
    [ -z "$unanswered" ] || fail "E17: a payment concluded without its provider's answer: $(printf '%s' "$unanswered" | head -3 | tr '\n' ';')"
    twice=$(provider_card_resolved_twice)
    [ "$twice" = 0 ] || fail "E17: $twice card attempts left one unknown state more than once"
    blind=$(provider_blind_resends "$PROVIDER_START_MILLIS")
    [ -z "$blind" ] || fail "E17: a dispatch sent to the provider again with no inquiry of it in between: $(printf '%s' "$blind" | head -3 | tr '\n' ';')"
    degraded=$(awk -F'\t' -v since="$PROVIDER_START_MILLIS" '$1 >= since && ($5 == "unavailable" || $5 == "silent")' "$EXP_DIR/requests.tsv" | grep -c . || true)
    [ "$degraded" -ge 1 ] || fail "E17: the provider degraded no request, so nothing was shown: the control"
    concluded=$(grep -c . "$EXP_DIR/card-concluded.txt" || true)
    experiments_record "provider's side" "$degraded requests degraded; no blind resend; $concluded card attempts concluded since the start, each on an answer the provider took"
    experiments_record "unknowns" "none left waiting; none resolved twice"
    experiments_record "unknown-age page after" "PaymentUnknownAged $(provider_page PaymentUnknownAged)"
    pass "E17: 0 card payments concluded without the provider's answer, every unknown resolved once within $PROVIDER_RESOLVE_SECONDS s of the provider's return, no blind resend ($degraded degraded requests)"
}

# ---------------------------------------------------------------------------------------------------
# E18: the FX provider unavailable, then the reference rate source unavailable past its staleness, at three replicas.
# ---------------------------------------------------------------------------------------------------

# Conversions booked since the start on a stale rate: a quote priced from a reference older than its pair's maximum age when issued, or a
# trade booked after its quote expired.
provider_fx_stale() {
    psql_query -c "SELECT count(*) FROM fx.trade t JOIN fx.quote q ON q.id = t.quote_id JOIN fx.rate_snapshot s ON s.id = q.reference_snapshot_id
        JOIN fx.pricing_pair p ON p.policy_id = q.pricing_policy_version_id AND p.source_currency = q.source_currency
         AND p.destination_currency = q.destination_currency AND p.purpose = q.purpose
        WHERE t.booked_at >= to_timestamp($PROVIDER_START)
          AND (q.issued_at - s.received_at > p.reference_max_age_seconds * interval '1 second' OR t.booked_at > q.expires_at)" 2>/dev/null \
        | tr -d '[:space:]'
}

# e18_conversion <label>: one customer conversion through the doors (a quote, then the conversion); its line in platform-client.log.
e18_conversion() {
    client fx-conversion "$1" >> "$ART/platform-client.log" 2>&1 || true
    grep "^platform fx-conversion $1 " "$ART/platform-client.log" | tail -1
}

E18_steady() {
    case " $EXTRA_PROVIDER_NAMES " in *" FINAPP_FX_PROVIDER_URL "*) ;; *) cannot "E18: the run names no FX provider to the application" ;; esac
    PROVIDER_START=$(db_epoch)
    # Section 2's pricing policy, by two persons, as the platform scopes do (OPERATIONS_RUNBOOK.md section 2): no quote without it.
    client platform-prepare >> "$ART/platform-client.log" 2>&1 || fail "E18: the platform's persons could not be registered: see platform-client.log"
    psql_query -f - < "$CLIENT/seed-platform.sql" >> "$ART/seed.log" 2>&1 || fail "E18: the platform seed failed: see seed.log"
    psql_query -f - < "$CLIENT/ids.sql" > "$CLIENT/ids.properties" 2>> "$ART/seed.log" || fail "E18: the identities could not be read"
    client platform-staff >> "$ART/platform-client.log" 2>&1 || fail "E18: the staff's roles could not be granted: see platform-client.log"
    client activate-pricing >> "$ART/platform-client.log" 2>&1 || fail "E18: the pricing policy could not be activated: see platform-client.log"
    grep -q '^platform activate-pricing .*approved status=20[01]' "$ART/platform-client.log" || fail "E18: the pricing policy was not activated by a second person"
    PROVIDER_MAX_AGE=$(psql_query -c "SELECT max(p.reference_max_age_seconds) FROM fx.pricing_pair p JOIN fx.pricing_policy_version v ON v.id = p.policy_id
        WHERE v.status = 'ACTIVE'" | tr -d '[:space:]')
    case $PROVIDER_MAX_AGE in '' | *[!0-9]*) cannot "E18: the active pricing policy's reference maximum age could not be read" ;; esac
    control=$(e18_conversion e18-steady)
    case $control in *" status=20"[01]" trade="[!-]*) ;; *) fail "E18: the control conversion before the fault was not booked: $control" ;; esac
    start_workload e18 retry
    experiments_record "steady state" "a conversion booked before the fault ($control); the reference's maximum age $PROVIDER_MAX_AGE s"
}

E18_fault() {
    experiments_fault_on hold degrade-fx-unavailable
    experiments_record fault "the FX provider answered 503 for every quote and execution (degrade-fx-unavailable) from $(date -u +%H:%M:%SZ)"
    refused=0
    for try in 1 2 3; do
        answer=$(e18_conversion "e18-provider-out-$try")
        printf '%s\n' "$answer" >> "$EXP_DIR/conversions.txt"
        case $answer in *" status=20"[01]" trade="[!-]*) ;; *) refused=$((refused + 1)) ;; esac
        sleep 20
    done
    experiments_record "provider out" "$refused of 3 conversions refused while no firm quote could be had"
    [ "$refused" = 3 ] || fail "E18: a conversion was booked while the FX provider gave no quote: see experiments/E18/conversions.txt"
    provider_watch 60 "the FX provider unavailable"
    [ -n "$EXP_STOPPED" ] && return 0
    experiments_fault_off hold degrade-fx-unavailable
    experiments_fault_on hold degrade-reference-unavailable
    experiments_record fault "the reference rate source answered 503 (degrade-reference-unavailable) from $(date -u +%H:%M:%SZ), the FX provider answering"
}

E18_observe() {
    [ -n "$EXP_STOPPED" ] && return 0
    # Past the reference's maximum age, and a fetch interval more: every quote must now be refused on its stale reference.
    provider_watch $((PROVIDER_MAX_AGE + 60)) "the reference unavailable, ageing past its maximum"
    [ -n "$EXP_STOPPED" ] && return 0
    before=$(provider_quote_outcome refused_reference_stale)
    stale_refused=0
    for try in 1 2 3; do
        answer=$(e18_conversion "e18-reference-stale-$try")
        printf '%s\n' "$answer" >> "$EXP_DIR/conversions.txt"
        case $answer in *" status=503 "*"code=fx.RateUnavailable"*) stale_refused=$((stale_refused + 1)) ;; esac
        sleep 10
    done
    # Refused for the stale reference itself, not for another rate trouble that answers the same code: the quote's own outcome, counted.
    sleep 30
    after=$(provider_quote_outcome refused_reference_stale)
    case "$before$after" in *[!0-9]* | '') fail "E18: the quotes' outcomes could not be read from Prometheus ('$before', '$after')" ;; esac
    experiments_record "reference stale" "$stale_refused of 3 conversions refused fx.RateUnavailable past the reference's maximum age; refused_reference_stale counted $((after - before)) times"
    [ "$stale_refused" = 3 ] || fail "E18: a quote was not refused on a stale reference: see experiments/E18/conversions.txt"
    [ $((after - before)) -ge 3 ] || fail "E18: the refusals were not counted as the reference's staleness (refused_reference_stale rose by $((after - before)))"
}

# provider_quote_outcome <outcome>: the quotes refused or issued with that outcome so far (finapp.fx.quote), a whole number.
provider_quote_outcome() {
    experiments_prometheus "/api/v1/query?query=$(urlencode "sum(finapp_fx_quote_total{outcome=\"$1\"}) or vector(0)")" 2>/dev/null \
        | sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)\(\.[0-9]*\)\{0,1\}"\].*/\1/p'
}

E18_recover() {
    deadline=$(( $(date +%s) + 300 ))
    answer=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        answer=$(e18_conversion e18-returned)
        case $answer in *" status=20"[01]" trade="[!-]*) break ;; esac
        sleep 20
    done
    case $answer in *" status=20"[01]" trade="[!-]*) ;; *) fail "E18: no conversion was booked within 300 s of the providers' return: $answer" ;; esac
    stop_workload e18
    assert_flows_ran e18
    stale=$(provider_fx_stale)
    [ "$stale" = 0 ] || fail "E18: $stale conversions booked on a stale rate"
    booked=$(psql_query -c "SELECT count(*) FROM fx.trade WHERE booked_at >= to_timestamp($PROVIDER_START)" | tr -d '[:space:]')
    [ "${booked:-0}" -ge 2 ] || fail "E18: only ${booked:-0} conversions were booked, so the stale-rate count read almost nothing: the control"
    provider_requests
    blind=$(provider_blind_resends "$((PROVIDER_START * 1000))")
    [ -z "$blind" ] || fail "E18: an FX dispatch sent again with no inquiry of it in between: $(printf '%s' "$blind" | head -3 | tr '\n' ';')"
    experiments_record conversions "$booked booked since the start, none on a stale rate; the conversion after the return: $answer"
    pass "E18: 0 conversions on a stale rate ($booked booked and judged), quotes refused while the provider gave none and once the reference aged past $PROVIDER_MAX_AGE s"
}

# ---------------------------------------------------------------------------------------------------
# E21: every provider slowed to two, then ten, times its steady latency (a latency toxic on the simulator's hop), at three replicas.
# ---------------------------------------------------------------------------------------------------

# provider_latency_ms <window>: the card provider calls' mean latency over the window, in whole milliseconds (finapp.payments.provider.latency
# publishes _sum and _count, no buckets).
provider_latency_ms() {
    query="1000 * sum(increase(finapp_payments_provider_latency_seconds_sum[$1])) / sum(increase(finapp_payments_provider_latency_seconds_count[$1]))"
    experiments_prometheus "/api/v1/query?query=$(urlencode "$query")" 2>/dev/null | sed -n 's/.*"value":\[[^,]*,"\([0-9.]*\)"\].*/\1/p' | cut -d. -f1
}

# The routes that call no provider, within their objectives (SLOS.md section 2): their 5-minute error ratios, read now.
provider_unaffected_ratios() {
    for objective in transfer_latency:0.01 authentication_latency:0.05 transfer_availability:0.001; do
        name=${objective%%:*}
        budget=${objective##*:}
        value=$(experiments_prometheus "/api/v1/query?query=$(urlencode "max(finapp:slo_${name}:error_ratio_rate5m)")" 2>/dev/null \
            | sed -n 's/.*"value":\[[^,]*,"\([0-9.eE+-]*\)"\].*/\1/p')
        printf '%s %s %s\n' "$name" "${value:-unread}" "$budget"
    done
}

# The stop condition: an unaffected route past its objective for five minutes running (twenty passes of provider_watch's 15 s).
# Run in this shell (provider_stop_condition), so its count of passes over the objective survives from one pass to the next.
provider_unaffected_breach() {
    over=$(provider_unaffected_ratios | awk '$2 != "unread" && $2 + 0 > $3 + 0 {print $1}' | tr '\n' ' ')
    if [ -n "$over" ]; then
        E21_OVER=$((${E21_OVER:-0} + 1))
    else
        E21_OVER=0
    fi
    [ "${E21_OVER:-0}" -lt 20 ] || PROVIDER_STOP="an unaffected route past its objective for 5 minutes: $over"
}

# The hinted inquiries the bulkhead refused, from every replica's lines (no meter counts them: TIMEOUTS_AND_RETRIES.md section 6).
provider_bulkhead_refusals() {
    total=0
    for pod in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name 2>/dev/null); do
        count=$(kube -n finapp logs "$pod" --since-time="$1" 2>/dev/null | grep -c 'Too many hinted inquiries are running on this instance' || true)
        total=$((total + ${count:-0}))
    done
    echo "$total"
}

E21_steady() {
    start_workload e21 retry
    sleep 180
    E21_NORMAL=$(provider_latency_ms 2m)
    case $E21_NORMAL in '' | *[!0-9]*) cannot "E21: the card provider's steady latency could not be read from Prometheus" ;; esac
    [ "$E21_NORMAL" -ge 1 ] || E21_NORMAL=1
    E21_SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    provider_rail_outcomes steady
    experiments_record "steady state" "the card provider's mean latency ${E21_NORMAL} ms over two minutes; unaffected routes: $(provider_unaffected_ratios | tr '\n' ';')"
}

E21_fault() {
    E21_OVER=0
    experiments_fault_on toxic simulator e21-twice latency "latency=$E21_NORMAL"
    experiments_record fault "every provider call delayed by ${E21_NORMAL} ms more (two times its steady latency) from $(date -u +%H:%M:%SZ)"
    provider_watch "$PROVIDER_PHASE_SECONDS" "the providers at two times their latency"
    experiments_record "at two times" "card provider latency $(provider_latency_ms 2m) ms; unaffected routes: $(provider_unaffected_ratios | tr '\n' ';'); bulkhead refusals so far $(provider_bulkhead_refusals "$E21_SINCE")"
    provider_rail_outcomes twice
    [ -n "$EXP_STOPPED" ] && return 0
    experiments_fault_off toxic simulator e21-twice latency "latency=$E21_NORMAL"
    experiments_fault_on toxic simulator e21-tenfold latency "latency=$((9 * E21_NORMAL))"
    experiments_record fault "every provider call delayed by $((9 * E21_NORMAL)) ms more (ten times its steady latency) from $(date -u +%H:%M:%SZ)"
}

E21_observe() {
    [ -n "$EXP_STOPPED" ] && return 0
    provider_watch "$PROVIDER_PHASE_SECONDS" "the providers at ten times their latency"
    experiments_record "at ten times" "card provider latency $(provider_latency_ms 2m) ms; unaffected routes: $(provider_unaffected_ratios | tr '\n' ';'); bulkhead refusals so far $(provider_bulkhead_refusals "$E21_SINCE")"
    provider_rail_outcomes tenfold
}

E21_recover() {
    sleep 120
    stop_workload e21
    assert_flows_ran e21
    [ "$(workload_unresolved e21)" = 0 ] || fail "E21: $(workload_unresolved e21) requests stayed unresolved under slow providers: see workload-e21.tsv"
    provider_rail_outcomes returned
    provider_failure_rates steady twice
    provider_failure_rates twice tenfold
    provider_failure_rates tenfold returned
    unread=$(provider_unaffected_ratios | awk '$2 == "unread" {print $1}' | tr '\n' ' ')
    [ -z "$unread" ] || fail "E21: the unaffected routes' objectives could not be read ($unread), so nothing was shown"
    experiments_record "bulkhead refusals" "$(provider_bulkhead_refusals "$E21_SINCE") hinted inquiries left to the sweep by the bulkhead"
    pass "E21: the routes that call no provider stayed within their objectives at two and ten times the providers' latency, every request resolved"
}

# ---------------------------------------------------------------------------------------------------
# E19 and E20: refused, each with its reason, before any fault.
# ---------------------------------------------------------------------------------------------------

E19_steady() {
    cannot "E19 cannot be driven: no production path runs a KYC case's checks (VerificationRunService.runChecks has callers only in tests) and the kind overlay configures no KYC provider (THREAT_MODEL.md section 6 item 60)"
}
E19_fault() { :; }
E19_observe() { :; }
E19_recover() { :; }

E20_steady() {
    cannot "E20 rests on the recorded fail-safe: no deployment can configure a bureau, and the owner decided on 2026-11-13 that none is built for kind in Phase 16 (unresolved question 13), so there is no bureau here to take away"
}
E20_fault() { :; }
E20_observe() { :; }
E20_recover() { :; }

# ---------------------------------------------------------------------------------------------------
# E40: the sweeps' failure storm (P16-TSK-008; routed by P16-TSK-017; OPERATIONS_RUNBOOK.md section 11.48), at three replicas.
# ---------------------------------------------------------------------------------------------------
#
# Every call to the provider simulator hangs (a Toxiproxy timeout toxic on its hop, through the fault registry), so every card payment the
# workload sends is dispatched into silence and left for the sweep, and every inquiry the sweeps make fails too: every candidate of the
# card legs and of the payout leg fails and is recorded in payments.sweep_row_failure or merchant.sweep_row_failure and backed off. The
# storm lasts the payment sweep's dispatched age (read from the code) and fifteen minutes more, so a quarter of an hour of listings meets a
# head of backed-off candidates. Read: the eight listings' plans with their NOT EXISTS probe (auto_explain, every plan, for three minutes)
# with the record tables empty and again with the storm's rows; their calls, time and rows from pg_stat_statements at each; the record
# tables' rows by leg, writes, HOT updates, dead rows and bytes every 30 s. After the storm every candidate must resolve once the provider
# answers, within the sweep's ages and the backoff's cap (read from the code), and the census holds.

# e40_tables <label>: the two record tables, by leg, and their statistics, appended to tables.tsv.
e40_tables() {
    psql_query -F "$(printf '\t')" -c "SELECT '$1', extract(epoch FROM now())::bigint, t.schemaname || '.' || t.relname, t.n_tup_ins, t.n_tup_upd,
        t.n_tup_hot_upd, t.n_live_tup, t.n_dead_tup, t.autovacuum_count, pg_total_relation_size(t.relid),
        (SELECT string_agg(leg || '=' || n, ',' ORDER BY leg) FROM (SELECT leg, count(*) AS n FROM payments.sweep_row_failure GROUP BY leg
          UNION ALL SELECT leg, count(*) FROM merchant.sweep_row_failure GROUP BY leg) l)
        FROM pg_stat_user_tables t WHERE t.relname = 'sweep_row_failure' ORDER BY 3" >> "$EXP_DIR/tables.tsv" 2>> "$EXP_DIR/errors.log" || true
}

# e40_rows: the storm's records, both tables together.
e40_rows() {
    psql_query -c "SELECT (SELECT count(*) FROM payments.sweep_row_failure) + (SELECT count(*) FROM merchant.sweep_row_failure)" | tr -d '[:space:]'
}

# e40_listings <label>: every statement naming a record table (the eight listings and the record's own upsert), as pg_stat_statements has
# it: calls, time, rows and its parameterised text.
e40_listings() {
    pg_admin -F "$(printf '\t')" -c "SELECT '$1', s.queryid, s.calls, round(s.total_exec_time::numeric, 1), round(s.mean_exec_time::numeric, 3), s.rows,
        s.shared_blks_hit + s.shared_blks_read, left(regexp_replace(s.query, '\s+', ' ', 'g'), 200)
        FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = 'finapp' AND s.query LIKE '%sweep_row_failure%'
        ORDER BY s.total_exec_time DESC" >> "$EXP_DIR/listings.tsv" 2>> "$EXP_DIR/errors.log" || true
}

E40_steady() {
    age=$(sed -n 's/.*finapp\.payments\.sweeper\.dispatched-age:PT\([0-9]*\)M}.*/\1/p' \
        "$ROOT/app/src/main/java/com/finapp/app/payments/PaymentSweeperSchedule.java" | head -1)
    cap=$(sed -n 's/.*Duration CAP = Duration\.ofMinutes(\([0-9]*\));.*/\1/p' "$ROOT/platform/src/main/java/com/finapp/platform/persistence/SweepBackoff.java" | head -1)
    case $age in '' | *[!0-9]*) cannot "E40: the payment sweep's dispatched age could not be read from PaymentSweeperSchedule.java" ;; esac
    case $cap in '' | *[!0-9]*) cannot "E40: the sweeps' backoff cap could not be read from SweepBackoff.java" ;; esac
    E40_STORM=$((age * 60 + 900))
    E40_RESOLVE=$((2 * age * 60 + cap * 60 + 300))
    experiments_record "storm" "${E40_STORM} s: the dispatched age (${age} min) and fifteen minutes of listings meeting backed-off candidates; resolution bound ${E40_RESOLVE} s"
    # The workload's client keeps asking a movement the storm left unknown until the storm and its resolution are over.
    WORKLOAD_RETRY_SECONDS=$((E40_STORM + E40_RESOLVE + 300))
    start_workload e40 retry
    sleep 30
    : > "$EXP_DIR/tables.tsv"
    : > "$EXP_DIR/listings.tsv"
    e40_tables steady
    [ "$(e40_rows)" = 0 ] || fail "E40 steady state: the record tables hold rows before the storm, so the listings' plans with the tables empty cannot be read: see experiments/E40/tables.tsv"
    # The statements' statistics, in the server's maintenance database as the load scope keeps them: the finapp database gains no object.
    pg_admin -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >> "$EXP_DIR/server-settings.log" 2>&1 \
        || cannot "E40: pg_stat_statements could not be created: the kind overlay's server must preload it"
    pg_admin -c "SELECT pg_stat_statements_reset()" >> "$EXP_DIR/server-settings.log" 2>&1 || cannot "E40: pg_stat_statements could not be reset"
    mkdir -p "$EXP_DIR/empty" "$EXP_DIR/storm"
    pg_plans_open "$EXP_DIR/empty" 1
    sleep 180
    pg_plans_close "$EXP_DIR/empty" "s.query LIKE '%sweep_row_failure%'"
    case $PG_PLANS_SUMMARY in *"caught 0 "*) fail "E40: the window with the tables empty caught no listing's plan: see experiments/E40/empty/plans" ;; esac
    e40_listings empty
    experiments_record "empty" "the listings' plans with both record tables empty: $PG_PLANS_SUMMARY(experiments/E40/empty/plans)"
}

E40_fault() {
    experiments_fault_on toxic simulator e40-storm timeout timeout=0
    E40_FROM=$(date +%s)
    experiments_record fault "every call to the provider simulator hangs (a timeout toxic on its hop) from $(date -u -d "@$E40_FROM" +%H:%M:%SZ), for ${E40_STORM} s"
}

E40_observe() {
    opened=""
    while [ "$(date +%s)" -lt $((E40_FROM + E40_STORM)) ]; do
        e40_tables storm
        firing=$(experiments_pages_firing)
        if [ -n "$firing" ]; then
            EXP_STOPPED="a correctness page during the storm: $firing"
            log "E40 STOP: $EXP_STOPPED"
            break
        fi
        # The storm's plans over its last four minutes, when the backed-off head is longest.
        if [ -z "$opened" ] && [ "$(date +%s)" -ge $((E40_FROM + E40_STORM - 240)) ]; then
            pg_plans_open "$EXP_DIR/storm" 1
            opened=1
        fi
        sleep 30
    done
    [ -z "$opened" ] || pg_plans_close "$EXP_DIR/storm" "s.query LIKE '%sweep_row_failure%'"
    [ -n "$EXP_STOPPED" ] && return 0
    case $PG_PLANS_SUMMARY in *"caught 0 "*) fail "E40: the storm's window caught no listing's plan: see experiments/E40/storm/plans" ;; esac
    e40_listings storm
    rows=$(e40_rows)
    [ "${rows:-0}" -ge 1 ] || fail "E40: the storm recorded no sweep row's failure, so the fault never reached the sweeps: see experiments/E40/tables.tsv"
    experiments_record "storm" "$rows rows recorded across both tables ($(tail -1 "$EXP_DIR/tables.tsv" | cut -f11)); the listings' plans with the storm's rows: $PG_PLANS_SUMMARY(experiments/E40/storm/plans); each listing's calls, time and rows in experiments/E40/listings.tsv"
    pass "E40: the storm failed every sweep it reached, $rows rows recorded and backed off; the listings read with the tables empty and full"
}

E40_recover() {
    # The provider answers again (the toxic was removed at stop): every candidate resolves within the ages and the backoff's cap.
    deadline=$(( $(date +%s) + E40_RESOLVE ))
    while :; do
        open=$(psql_query -c "SELECT count(*) FROM payments.payment_attempt WHERE status IN ('AUTH_DISPATCHED', 'CAPTURE_DISPATCHED', 'VOID_DISPATCHED',
            'AUTH_UNKNOWN', 'CAPTURE_UNKNOWN', 'VOID_UNKNOWN')" | tr -d '[:space:]')
        [ "$open" = 0 ] && break
        [ "$(date +%s)" -lt "$deadline" ] || fail "E40: $open payment attempts still unresolved ${E40_RESOLVE} s after the provider answered again"
        sleep 20
    done
    e40_tables recovered
    stop_workload e40
    [ "$(workload_unresolved e40)" = 0 ] || fail "E40: $(workload_unresolved e40) requests stayed unresolved: see workload-e40.tsv"
    assert_flows_ran e40
    experiments_record workload "$(cat "$CLIENT/workload-e40.summary")"
    experiments_record "recovered" "every payment attempt resolved after the storm; the record tables: $(tail -1 "$EXP_DIR/tables.tsv" | cut -f3-10 | tr '\t' ' ')"
}
