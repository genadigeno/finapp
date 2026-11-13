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
# NetworkPolicy labelled finapp/experiment-fault), node-paused (`docker pause` of a node of this cluster), scaled (a Deployment scaled,
# its count to restore recorded) and setting (P16-TSK-022: a variable set on the application's Deployment, removed again). A kill, a
# deletion or an eviction is instant and is logged, not registered; so are E05's fence of the old primary and its promotion, which are a
# person's acts in the drill and are never undone (a fenced primary brought back beside a promoted standby would split the record).
# finish() calls experiments_clear_faults on every way out, the INT and TERM traps and an unguarded failure included: every registered
# fault undone, newest first, then a blanket reset that needs no record (every proxy enabled with no toxic, both hold flags removed, every
# labelled policy deleted, every paused node of this cluster unpaused, the setting E57 uses removed), then a read that nothing is left
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
# THE DATABASE (P16-TSK-022, ADR-0139; OPERATIONS_RUNBOOK.md sections 11.40 and 11.54), each under the workload retrying by key, with the
# journal read with a digest per entry before the fault and compared after it, and the public edge's answers read for the interruption:
#   E06  the primary stopped uncleanly in place (pg_ctl stop -m immediate), no failover: readiness down and back, no replica restarted, the
#        unlogged window counters empty, the standby synchronous again, nothing committed lost;
#   E57  every answer of the database hop black-holed (Toxiproxy's timeout toxic, after a latency toxic so that statements are in flight),
#        twice: with the data source's socket timeout, each held connection given up within it; without it (a rolling update setting it
#        to none), each still held past it. The fleet's blind page (GaugesStaleAcrossTheFleet) is the fault's expected signal here;
#   E05  the primary's server killed with SIGKILL from its node, a payout held at the provider with its claim IN_PROGRESS; the old primary
#        fenced (its StatefulSet at zero) and its pod gone before anything is promoted; the promotion refused unless the primary named the
#        standby synchronous at its last read and the standby holds the last commit the drill saw acknowledged; the standby promoted alone
#        (synchronous_standby_names none, ADR-0139), the Service moved to it, every read after it of the promoted one (PG_POD); nothing
#        acknowledged lost, the payout's key answered once, no money-moving command answered with a success while the database was away,
#        the interruption measured. It leaves one server and no standby, so it runs last.
#
# THE PROBES (REHEARSAL_PROBE), each a deliberate break that must turn the run red:
#   guard-off          the refusals answer "pass" without reading anything: every plant of experiments_boundary goes unrefused;
#   crash-with-faults  every kind of fault put on, then an unguarded failure: the exit trap must leave none on (read on a kept cluster);
#   term-with-faults   the same, then SIGTERM to the harness itself, the signal path of the same trap.
#   async-standby      E05 with its standby made asynchronous and cut from the primary's WAL before the fault (the entry's probe, register
#                      E56): the promotion is refused, the probe promotes it anyway, and the journal's comparison must show the commits lost.
# REHEARSAL_EXPERIMENTS names the experiments to run (E01 by default; the catalogue is P16-TSK-020...-023's and P16-TSK-027's).
#
# E40 (P16-TSK-008, at three replicas): the sweeps' failure storm - every call to the provider simulator hangs for the payment sweep's
# dispatched age and fifteen minutes more, so every candidate of the sweeps fails and is recorded and backed off; the eight listings' plans
# and statistics read with the record tables empty and with the storm's rows, the tables' writes, dead rows and bytes every 30 s; every
# candidate resolved once the provider answers; the census green. Its plans come from auto_explain's window on the run's own server
# (load.sh's pg_plans_open and pg_plans_close).

EXPERIMENTS=${REHEARSAL_EXPERIMENTS:-E01}
TOXIPROXY_IMAGE="$TOXIPROXY_REPOSITORY@$TOXIPROXY_DIGEST"
EXPERIMENTS_MARKER=finapp-experiments-marker
EXPERIMENTS_CONFIG="$DIR/experiments/kind-cluster.yaml"
EXPERIMENTS_COLLECTOR=http://finapp-collector.finapp-observability.svc.cluster.local:4318/
# The census's expected payouts take this: E01's and E05's own payouts, zero in every other scope.
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
#   setting <variable> <value>   (P16-TSK-022: a variable set on the application's Deployment, a rolling update; its value one word)
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
        setting) kube -n finapp set env deployment/finapp-app "$1=$2" >> "$EXP_ART/faults.log" 2>&1 ;;
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
        setting) kube -n finapp set env deployment/finapp-app "$1-" ;;
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
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" get networkpolicy -l finapp/experiment-fault=true -o name 2>/dev/null | sed "s#^#$namespace #"
    done
    while read -r kind namespace deployment replicas restore; do
        [ "$kind" = scaled ] || continue
        now=$(kube -n "$namespace" get "deployment/$deployment" -o jsonpath='{.spec.replicas}' 2>/dev/null)
        [ "$now" = "$restore" ] || echo "$namespace/$deployment at $now replicas, not $restore"
    done < "$FAULTS.cleared"
    # A setting (P16-TSK-022): read from the Deployment itself, which still names it until it is removed.
    named=" $(kube -n finapp get deployment finapp-app -o jsonpath='{range .spec.template.spec.containers[*].env[*]}{.name}{" "}{end}' 2>/dev/null) "
    while read -r kind variable value; do
        [ "$kind" = setting ] || continue
        case $named in *" $variable "*) echo "the application's Deployment still sets $variable" ;; esac
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
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" delete networkpolicy -l finapp/experiment-fault=true --ignore-not-found >> "$EXP_ART/faults.log" 2>&1
    done
    # The one setting an experiment puts on (E57's leg without the socket timeout), removed whether or not it was recorded: a no-op when absent.
    kube -n finapp set env deployment/finapp-app SPRING_APPLICATION_JSON- >> "$EXP_ART/faults.log" 2>&1
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
    # The entry's probe of P16-TSK-022 is E05's: without it the plant would never be put on, and the run could not go red for its reason.
    if [ "$PROBE" = async-standby ]; then
        case " $EXPERIMENTS " in *" E05 "*) ;; *) cannot "PROBE async-standby plants E05's standby: name E05 in REHEARSAL_EXPERIMENTS" ;; esac
    fi
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
# What the database's experiments share (P16-TSK-022): the journal before and after, the edge's answers, the fleet's readiness, the bounds.
# ---------------------------------------------------------------------------------------------------

# The money-moving commands of the rehearsal's workload, as the edge logs their paths: a success on one needs the database.
EXPERIMENTS_MONEY_PATHS='^/v1/(transfers|payments|checkout/sessions|merchant/payouts)(/|$)'

# experiments_socket_timeout: the data source's socket timeout, read from application.yaml into EXP_SOCKET_TIMEOUT (ADR-0139), and refused
# if the run's rendered overlay could set another.
experiments_socket_timeout() {
    EXP_SOCKET_TIMEOUT=$(sed -n 's/^        socketTimeout: \([0-9]*\)$/\1/p' "$ROOT/app/src/main/resources/application.yaml")
    case $EXP_SOCKET_TIMEOUT in '' | *[!0-9]* | 0) cannot "the data source's socket timeout could not be read from application.yaml" ;; esac
    if grep -q 'SPRING_APPLICATION_JSON\|SOCKETTIMEOUT' "$A/all.yaml"; then
        cannot "the rendered overlay sets the data source's socket timeout, which these experiments read from application.yaml"
    fi
}

# experiments_retry_bound: how long the workload retries one request by its key while the database is taken away, into EXP_RETRY_SECONDS:
# past twice the longest dispatched age the code declares (a card payment or a payout caught mid-call concludes only once its sweep asks
# the provider), five minutes for the sweep's poll, the claim's lease and the pause, and the socket timeout a held request may wait.
experiments_retry_bound() {
    experiments_socket_timeout
    minutes=$(for source in app/src/main/java/com/finapp/app/payments/PaymentSweeperSchedule.java app/src/main/java/com/finapp/app/merchant/MerchantPayoutBeans.java; do
        sed -n 's/.*finapp\.\(payments\|merchant\.payout\)\.sweeper\.dispatched-age:PT\([0-9]*\)M}.*/\2/p' "$ROOT/$source"
    done | sort -n | tail -1)
    case $minutes in '' | *[!0-9]*) cannot "the sweeps' dispatched age could not be read from the code" ;; esac
    if grep -Eq 'SWEEPER_DISPATCHED_?AGE' "$A/all.yaml"; then
        cannot "the rendered overlay sets a sweep's dispatched age, which this bound reads from the code's default"
    fi
    EXP_RETRY_SECONDS=$((2 * minutes * 60 + 300 + EXP_SOCKET_TIMEOUT + 60))
}

# experiments_restarts: every application pod and its restart count, one word each, sorted.
experiments_restarts() {
    experiments_fleet | awk -F'|' 'NF >= 5 && $6 == "" {print $1 ":" $3}' | sort | tr '\n' ' '
}

# experiments_db_snapshot <dir> <label>: in one REPEATABLE READ READ ONLY transaction on the primary, every journal entry with a digest of
# its row and its lines' rows, and the row counts of the tables that only grow, into <dir>/journal-<label>.tsv. Read through psql_query,
# so after a promotion it is the promoted server's. An empty journal is refused: a comparison over nothing proves nothing.
experiments_db_snapshot() {
    psql_query -F "$(printf '\t')" -f - > "$1/journal-$2.tsv" 2> "$1/journal-$2.err" <<'SQL' || cannot "the journal could not be read ($2): see $1/journal-$2.err"
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'entry', e.id, md5(e::text || ';' || coalesce((SELECT string_agg(l::text, ';' ORDER BY l.id) FROM ledger.journal_line l
       WHERE l.entry_id = e.id), ''))
  FROM ledger.journal_entry e ORDER BY e.id;
SELECT 'count', 'ledger.journal_entry', count(*) FROM ledger.journal_entry;
SELECT 'count', 'ledger.journal_line', count(*) FROM ledger.journal_line;
SELECT 'count', 'platform.audit_record', count(*) FROM platform.audit_record;
SELECT 'count', 'platform.outbox_event', count(*) FROM platform.outbox_event;
SELECT 'count', 'platform.idempotency_record', count(*) FROM platform.idempotency_record;
SELECT 'count', 'transfers.transfer', count(*) FROM transfers.transfer;
SELECT 'count', 'payments.payment_intent', count(*) FROM payments.payment_intent;
SELECT 'count', 'merchant.merchant_payout', count(*) FROM merchant.merchant_payout;
COMMIT;
SQL
    entries=$(awk -F'\t' '$1 == "entry"' "$1/journal-$2.tsv" | grep -c . || true)
    [ "$entries" -gt 0 ] || cannot "the journal read empty ($2): a comparison over nothing proves nothing"
}

# experiments_db_compare <dir> <before> <after>: every entry read before is there after with the same digest, and no counted table shrank.
# Sets DB_COMPARED, DB_MISSING, DB_CHANGED and DB_FEWER (each table whose count went down, or nothing).
experiments_db_compare() {
    read -r DB_COMPARED DB_MISSING DB_CHANGED <<EOF
$(awk -F'\t' 'FNR == NR { if ($1 == "entry") after[$2] = $3; next }
    $1 == "entry" { n++; if (!($2 in after)) missing++; else if (after[$2] != $3) changed++ }
    END { print n + 0, missing + 0, changed + 0 }' "$1/journal-$3.tsv" "$1/journal-$2.tsv")
EOF
    DB_FEWER=$(awk -F'\t' 'FNR == NR { if ($1 == "count") after[$2] = $3; next }
        $1 == "count" && (!($2 in after) || after[$2] + 0 < $3 + 0) { printf "%s %s -> %s; ", $2, $3, after[$2] }' \
        "$1/journal-$3.tsv" "$1/journal-$2.tsv")
    experiments_record journal "$DB_COMPARED entries read before the fault: $DB_MISSING missing after it, $DB_CHANGED changed; tables that shrank: ${DB_FEWER:-none}"
}

# experiments_edge_read <dir> <epoch>: every answer of the public edge since <epoch> into <dir>/edge-answers.tsv, one line each: the second it
# ended, the second it started, its start as logged, the edge's status, the application's (0: none), its method and its path. The edge
# reaches the replicas through their Service and names none of them; the lines are read beside a control, served answers in them.
experiments_edge_read() {
    since=$(date -u -d "@$2" +%Y-%m-%dT%H:%M:%SZ)
    : > "$1/edge-lines.tsv"
    for pod in $(kube -n finapp-edge get pods -l app.kubernetes.io/name=finapp-edge-public -o jsonpath='{range .items[*]}{.metadata.name}|{.spec.containers[0].name}{"\n"}{end}' | awk -F'|' '$2 == "edge" {print $1}'); do
        kube -n finapp-edge logs "$pod" -c edge --since-time="$since" 2>/dev/null \
            | sed -n 's/.*"DownstreamStatus":\([0-9]*\).*"Duration":\([0-9]*\).*"OriginStatus":\([0-9]*\).*"RequestMethod":"\([A-Z]*\)".*"RequestPath":"\([^"]*\)".*"StartUTC":"\([^"]*\)".*/\6\t\1\t\3\t\2\t\4\t\5/p' \
            >> "$1/edge-lines.tsv"
    done
    : > "$1/edge-answers.tsv"
    while IFS="$(printf '\t')" read -r started status origin took method path; do
        began=$(date -u -d "$started" +%s 2>/dev/null) || continue
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$((began + (took + 999999999) / 1000000000))" "$began" "$started" "$status" "$origin" "$method" "$path" \
            >> "$1/edge-answers.tsv"
    done < "$1/edge-lines.tsv"
    EDGE_SERVED=$(awk -F'\t' '$4 < 500 && $5 != 0' "$1/edge-answers.tsv" | grep -c . || true)
    EDGE_FAILED=$(awk -F'\t' '$4 >= 500' "$1/edge-answers.tsv" | grep -c . || true)
    [ "$EDGE_SERVED" -ge 10 ] || fail "$EXP_ID: the edge's lines held $EDGE_SERVED served answers since $since, too few to show they were read: see experiments/$EXP_ID/edge-answers.tsv"
}

# experiments_money_succeeded <dir> <from> <to>: how many money-moving commands the application answered with a success ending in [from, to].
experiments_money_succeeded() {
    awk -F'\t' -v from="$2" -v to="$3" -v paths="$EXPERIMENTS_MONEY_PATHS" \
        '$6 == "POST" && $7 ~ paths && $5 >= 200 && $5 < 300 && $1 >= from && $1 <= to' "$1/edge-answers.tsv" | grep -c . || true
}

# experiments_money_back <dir> <from>: the second the first money-moving command started at or after <from> ended in a success from the
# application, or "-": money movement's interruption ends there.
experiments_money_back() {
    awk -F'\t' -v from="$2" -v paths="$EXPERIMENTS_MONEY_PATHS" \
        '$6 == "POST" && $7 ~ paths && $5 >= 200 && $5 < 300 && $2 >= from && (first == "" || $1 < first) {first = $1}
        END {print (first == "" ? "-" : first)}' "$1/edge-answers.tsv"
}

# experiments_readiness <dir> <from>: the fleet's samples after <from> (fleet.tsv, every 2 s) per second into <dir>/readiness.tsv - ready
# replicas, replicas - and FLEET_FIRST_UNREADY (a replica first read unready), FLEET_NONE_READY (no replica ready), FLEET_LAST_UNREADY and
# FLEET_ALL_READY (every replica ready again after the last unready reading), each a second or "-". Readiness takes three failed checks ten
# seconds apart, so an outage shorter than that may leave every replica ready throughout: recorded, never assumed.
experiments_readiness() {
    awk -F'\t' -v from="$2" 'NR > 1 && $1 >= from && $8 == "-" { pods[$1]++; if ($4 == "True") ready[$1]++ }
        END { for (s in pods) print s "\t" ready[s] + 0 "\t" pods[s] }' "$1/fleet.tsv" | sort -n > "$1/readiness.tsv"
    read -r FLEET_FIRST_UNREADY FLEET_NONE_READY FLEET_LAST_UNREADY FLEET_ALL_READY <<EOF
$(awk -F'\t' '{ at[NR] = $1; full[NR] = ($2 == $3) } $2 < $3 { if (first == "") first = $1; last = $1 } $2 == 0 && none == "" { none = $1 }
    END { back = "-"; if (last != "") for (i = 1; i <= NR; i++) if (at[i] > last && full[i]) { back = at[i]; break }
          print (first == "" ? "-" : first), (none == "" ? "-" : none), (last == "" ? "-" : last), back }' "$1/readiness.tsv")
EOF
}

# experiments_since <second> <origin>: "<n> s" after the origin, or the word itself when it is not a second.
experiments_since() {
    case $1 in '' | *[!0-9]*) printf '%s' "${1:--}" ;; *) printf '%s s' "$(($1 - $2))" ;; esac
}

# experiments_kill_server <pod>: SIGKILL to the test-only PostgreSQL's postmaster, its container's first process, from the node it runs on:
# every backend dies with it, so the server stops as a crash stops it. Sets KILLED_EPOCH and KILLED; never called inside $( ).
experiments_kill_server() {
    node=$(kube -n finapp get pod "$1" -o jsonpath='{.spec.nodeName}')
    container=$(kube -n finapp get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="postgres")].containerID}' | sed 's#^containerd://##')
    [ -n "$node" ] && [ -n "$container" ] || cannot "the pod $1 has no node or no running server to kill"
    [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node" 2>/dev/null)" = "$CLUSTER" ] \
        || fail "the safety boundary refused to kill on $node: it is not a node of $CLUSTER"
    pid=$(dock exec "$node" crictl inspect --output go-template --template '{{.info.pid}}' "$container" 2>/dev/null)
    case $pid in '' | *[!0-9]*) cannot "the server process of $1 could not be found on $node" ;; esac
    KILLED_EPOCH=$(date +%s)
    dock exec "$node" kill -9 "$pid" || cannot "SIGKILL could not be sent to $1's server on $node"
    printf '%s\tkill -9\t%s on %s, pid %s\n' "$(date -u +%H:%M:%S)" "$1" "$node" "$pid" >> "$EXP_ART/faults.log"
    KILLED="$node $container $pid"
}

# experiments_watch_pages: at most every 15 s, whether a correctness page fires; on the first one (or a Prometheus that does not answer) it
# sets EXP_STOPPED and returns 1. The pages EXP_PAGES_EXPECTED names are the fault's own expected signal and are recorded, not a stop.
experiments_watch_pages() {
    now=$(date +%s)
    [ $((now - ${PAGES_AT:-0})) -ge 15 ] || return 0
    PAGES_AT=$now
    firing=""
    for page in $(experiments_pages_firing); do
        case " ${EXP_PAGES_EXPECTED:-} " in *" $page "*) ;; *) firing="$firing $page" ;; esac
    done
    if [ -n "$firing" ]; then
        EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a correctness page during the experiment:$firing"
        log "$EXP_ID STOP: $EXP_STOPPED"
        return 1
    fi
    printf '%s\tnone%s\n' "$(date -u +%H:%M:%S)" "${EXP_PAGES_EXPECTED:+ beside the expected $EXP_PAGES_EXPECTED}" >> "$EXP_DIR/pages.tsv"
    return 0
}

# experiments_ready_again <deadline>: until three replicas read ready and none terminating, the pages watched meanwhile; 1 when the deadline
# passes first or a page stops the experiment.
experiments_ready_again() {
    PAGES_AT=0
    while [ "$(date +%s)" -lt "$1" ]; do
        experiments_watch_pages || return 1
        ready=$(experiments_fleet | awk -F'|' 'NF >= 5 && $2 == "True" && $6 == ""' | grep -c . || true)
        [ "$ready" -ge 3 ] && return 0
        sleep 2
    done
    return 1
}

# ---------------------------------------------------------------------------------------------------
# E06: the primary stopped uncleanly in place, no failover (POSTPONED_ISSUES.md PI-024's step, run under owner decision C11).
# ---------------------------------------------------------------------------------------------------

E06_steady() {
    experiments_retry_bound
    WORKLOAD_RETRY_SECONDS=$EXP_RETRY_SECONDS start_workload e06 retry
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E06_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E06_SAMPLER"
    E06_RESTARTS=$(experiments_restarts)
    # The positive control: rows in the unlogged window counters, so "empty after the stop" is a reading, not a table always empty.
    client sign-in-flood e06 3 >> "$EXP_DIR/client.log" 2>&1 || true
    E06_COUNTED=$(psql_query -c "SELECT count(*) FROM platform.window_counter" | tr -d '[:space:]')
    [ "${E06_COUNTED:-0}" -gt 0 ] || cannot "E06: no door hit was counted before the stop, so the counters' emptying would read nothing"
    experiments_record counters "$E06_COUNTED rows in platform.window_counter before the stop"
}

E06_fault() {
    experiments_db_snapshot "$EXP_DIR" before
    E06_DB_BEFORE=$(db_epoch)
    datadir=$(psql_query -c "SHOW data_directory" | tr -d '[:space:]')
    [ -n "$datadir" ] && [ -n "$E06_DB_BEFORE" ] || cannot "E06: the server's data directory or clock could not be read"
    if [ "$PROBE" != guard-off ]; then
        experiments_context_check || fail "the safety boundary refused before the stop: $EXP_REASON"
    fi
    E06_FAULT_AT=$(date +%s)
    # An immediate stop: no checkpoint, every backend ended at once, the container ending with its first process and restarted in its pod on
    # the same volume. Not a failover: the Service, the standby and the primary's name are left as they are.
    kube -n finapp exec finapp-postgres-0 -c postgres -- pg_ctl stop -m immediate -D "$datadir" >> "$EXP_ART/faults.log" 2>&1 || true
    printf '%s\tpg_ctl stop -m immediate\tfinapp-postgres-0\n' "$(date -u +%H:%M:%S)" >> "$EXP_ART/faults.log"
    experiments_record fault "the primary's server stopped immediately in place at $(date -u -d "@$E06_FAULT_AT" +%H:%M:%SZ), under the workload"
}

E06_observe() {
    # Its stop condition: the server not accepting connections 10 minutes after the stop.
    E06_BACK_AT=""
    PAGES_AT=0
    while [ "$(date +%s)" -lt $((E06_FAULT_AT + 600)) ]; do
        experiments_watch_pages || return 0
        if [ "$(psql_query -c 'SELECT 1' 2>/dev/null | tr -d '[:space:]')" = 1 ]; then
            E06_BACK_AT=$(date +%s)
            break
        fi
        sleep 2
    done
    [ -n "$E06_BACK_AT" ] || { EXP_STOPPED="the server not accepting connections 10 min after its restart"; return 0; }
    # The control: the server really stopped - it started again after the moment read before the stop.
    started=$(psql_query -c "SELECT extract(epoch FROM pg_postmaster_start_time())::bigint" | tr -d '[:space:]')
    [ "${started:-0}" -gt "$E06_DB_BEFORE" ] || fail "E06: the server's start time ($started) is not after the stop: it was never stopped"
    deadline=$(( $(date +%s) + 120 ))
    until [ "$(experiments_replication)" = "finapp_standby|streaming|sync" ]; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            EXP_STOPPED="the standby not synchronous again 120 s after the server returned: '$(experiments_replication)'"
            return 0
        fi
        sleep 2
    done
    experiments_ready_again $((E06_FAULT_AT + 600)) || { [ -n "$EXP_STOPPED" ] || EXP_STOPPED="the fleet not ready 10 min after the stop"; return 0; }
    after=$(psql_query -c "SELECT count(*) FROM platform.window_counter" | tr -d '[:space:]')
    experiments_record counters "$E06_COUNTED rows before the stop, $after after it (an unlogged table is emptied by a crash)"
    [ "$after" = 0 ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }the unlogged counters kept $after rows across the stop"
    pass "E06: the server stopped immediately and back $(experiments_since "$E06_BACK_AT" "$E06_FAULT_AT") after, the standby synchronous again, the fleet ready"
}

E06_recover() {
    ready_three 400 || fail "E06: the fleet did not return to three ready replicas"
    sleep 20
    touch "$EXP_DIR/sampler-stop"
    wait "$E06_SAMPLER" 2>/dev/null || true
    stop_workload e06
    [ "$(workload_unresolved e06)" = 0 ] || fail "E06: $(workload_unresolved e06) requests stayed unresolved: see workload-e06.tsv"
    assert_flows_ran e06
    experiments_record workload "$(cat "$CLIENT/workload-e06.summary")"
    experiments_db_snapshot "$EXP_DIR" after
    experiments_db_compare "$EXP_DIR" before after
    if [ "$DB_MISSING" != 0 ] || [ "$DB_CHANGED" != 0 ] || [ -n "$DB_FEWER" ]; then
        EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }the journal before the stop is not whole after it: $DB_MISSING missing, $DB_CHANGED changed${DB_FEWER:+; shrank: $DB_FEWER}"
    fi
    experiments_readiness "$EXP_DIR" "$E06_FAULT_AT"
    [ "$(experiments_restarts)" = "$E06_RESTARTS" ] \
        || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a replica restarted for the database's stop: $E06_RESTARTS -> $(experiments_restarts)"
    experiments_edge_read "$EXP_DIR" $((E06_FAULT_AT - 60))
    away=$(experiments_money_succeeded "$EXP_DIR" $((E06_FAULT_AT + 5)) "${E06_BACK_AT:-$(date +%s)}")
    [ "$away" = 0 ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }$away money-moving commands answered a success while the server was down"
    back=$(experiments_money_back "$EXP_DIR" "$E06_FAULT_AT")
    experiments_record timeline "the stop at 0 s; a replica first unready $(experiments_since "$FLEET_FIRST_UNREADY" "$E06_FAULT_AT"), none ready $(experiments_since "$FLEET_NONE_READY" "$E06_FAULT_AT"), every replica ready again $(experiments_since "$FLEET_ALL_READY" "$E06_FAULT_AT"); the server accepting $(experiments_since "$E06_BACK_AT" "$E06_FAULT_AT"); the first money-moving success $(experiments_since "$back" "$E06_FAULT_AT") (the interruption)"
    experiments_record "at the edge" "$EDGE_FAILED failed answers and $EDGE_SERVED served since the minute before the stop; $away money-moving successes while the server was down"
}

# ---------------------------------------------------------------------------------------------------
# E57: every answer of the database hop black-holed, with and without the data source's socket timeout (THREAT_MODEL.md section 6 item
# 42 (h); ADR-0139). The hop is the fault proxy's, so the hole is the whole fleet's, as a failover's black-holed connections are.
# ---------------------------------------------------------------------------------------------------

E57_steady() {
    experiments_retry_bound
    WORKLOAD_RETRY_SECONDS=$EXP_RETRY_SECONDS start_workload e57 retry
    sleep 8
    replicas=$(e57_active | grep -c '^[0-9]' || true)
    [ "$replicas" = 3 ] || cannot "E57: Prometheus read the writer's pool of $replicas replicas, not three, so a held connection could not be seen"
    experiments_record bound "the socket timeout $EXP_SOCKET_TIMEOUT s (application.yaml); with it each held connection given up by $((EXP_SOCKET_TIMEOUT + 45)) s and not before $((EXP_SOCKET_TIMEOUT - 30)) s; without it each still held at $((EXP_SOCKET_TIMEOUT + 65)) s"
}

# e57_active: each replica's writer pool's connections in use, as Prometheus last scraped them, "<instance> <count>" a line, or UNREADABLE.
e57_active() {
    answer=$(experiments_prometheus "/api/v1/query?query=$(urlencode 'sum by (instance) (hikaricp_connections_active{job="finapp",pool!="finapp-reporting"})')" 2>/dev/null || true)
    case $answer in *'"status":"success"'*) ;; *) echo UNREADABLE; return 0 ;; esac
    printf '%s' "$answer" | grep -o '"instance":"[^"]*"},"value":\[[0-9.]*,"[0-9.]*"\]' \
        | sed 's/"instance":"\([^"]*\)"},"value":\[[0-9.]*,"\([0-9.]*\)"\]/\1 \2/'
}

# e57_hole <leg>: a latency toxic first, so that statements are in flight on every replica, then Toxiproxy's timeout toxic with no timeout,
# which keeps each connection open and passes nothing back; both registered, so the stop and every way out remove them. Sets E57_HOLE_AT.
e57_hole() {
    experiments_fault_on toxic postgres "e57-$1-slow" latency latency=3000
    sleep 6
    experiments_fault_on toxic postgres "e57-$1-hole" timeout timeout=0
    E57_HOLE_AT=$(date +%s)
    experiments_record "$1: hole" "every answer of the database hop held from $(date -u -d "@$E57_HOLE_AT" +%H:%M:%SZ)"
}

# e57_watch <seconds> <leg>: every 5 s for <seconds> after the hole, each replica's connections in use, into <leg>.tsv; a correctness page
# other than the fleet's blind one stops it. Sets E57_HELD (the replicas holding a connection 20 to 35 s after the hole), E57_FIRST_FREED
# and E57_LAST_FREED (when the held ones first read none in use, in seconds after the hole; the last "-" while one still holds) and
# E57_STILL (the held ones still holding at the end).
e57_watch() {
    printf 'epoch\tafter\tinstance\tactive\n' > "$EXP_DIR/$2.tsv"
    PAGES_AT=0
    EXP_PAGES_EXPECTED=GaugesStaleAcrossTheFleet
    while [ "$(date +%s)" -lt $((E57_HOLE_AT + $1)) ]; do
        now=$(date +%s)
        e57_active | while read -r instance active; do
            printf '%s\t%s\t%s\t%s\n' "$now" "$((now - E57_HOLE_AT))" "$instance" "${active:--}"
        done >> "$EXP_DIR/$2.tsv"
        experiments_watch_pages || break
        sleep 5
    done
    EXP_PAGES_EXPECTED=""
    E57_HELD=$(awk -F'\t' 'NR > 1 && $2 >= 20 && $2 < 35 && $4 + 0 >= 1 {print $3}' "$EXP_DIR/$2.tsv" | sort -u | tr '\n' ' ')
    read -r E57_FIRST_FREED E57_LAST_FREED <<EOF
$(awk -F'\t' -v held=" $E57_HELD" 'NR > 1 && $2 >= 20 && index(held, " " $3 " ") && $4 == "0" && !($3 in freed) { freed[$3] = $2 }
    END { n = 0; first = ""; last = ""; for (i in freed) { n++; if (first == "" || freed[i] + 0 < first + 0) first = freed[i]; if (freed[i] + 0 > last + 0) last = freed[i] }
          total = split(held, h, " "); print (first == "" ? "-" : first), (n < total || last == "" ? "-" : last) }' "$EXP_DIR/$2.tsv")
EOF
    E57_STILL=$(awk -F'\t' -v held=" $E57_HELD" 'NR > 1 && index(held, " " $3 " ") { last[$3] = $4 }
        END { for (i in last) if (last[i] + 0 >= 1) print i }' "$EXP_DIR/$2.tsv" | sort | tr '\n' ' ')
}

E57_fault() {
    # With the socket timeout, as the fleet is deployed.
    e57_hole with
}

E57_observe() {
    e57_watch $((EXP_SOCKET_TIMEOUT + 45)) with
    [ -n "$EXP_STOPPED" ] && return 0
    [ -n "$E57_HELD" ] || cannot "E57 with: no replica held a connection 20 s after the hole, so the leg shows nothing"
    experiments_record "with: held" "replicas $E57_HELD held a connection 20 s after the hole; the first freed at $E57_FIRST_FREED s, the last at $E57_LAST_FREED s; still holding at the end: ${E57_STILL:-none}"
    if [ "$E57_LAST_FREED" = - ] || [ "$E57_LAST_FREED" -gt $((EXP_SOCKET_TIMEOUT + 45)) ]; then
        EXP_STOPPED="with the socket timeout a held connection was not given up within it and 45 s: last freed $E57_LAST_FREED, still ${E57_STILL:-none}"
        return 0
    fi
    if [ "$E57_FIRST_FREED" -lt $((EXP_SOCKET_TIMEOUT - 30)) ]; then
        EXP_STOPPED="a held connection was freed at $E57_FIRST_FREED s, before the socket timeout could have freed it: something else did, and the leg measures nothing"
        return 0
    fi
    experiments_fault_off toxic postgres e57-with-hole timeout timeout=0
    experiments_fault_off toxic postgres e57-with-slow latency latency=3000
    ready_three 600 || { EXP_STOPPED="the fleet not ready again 10 min after the hole was removed"; return 0; }
    # Without it: the fleet rolled with the driver's socket timeout set to none, through Spring's own JSON source, read before application.yaml.
    experiments_fault_on setting SPRING_APPLICATION_JSON '{"spring":{"datasource":{"hikari":{"data-source-properties":{"socketTimeout":"0"}}}}}'
    kube -n finapp rollout status deployment/finapp-app --timeout=900s >> "$EXP_DIR/rollout.log" 2>&1 || cannot "E57: the fleet did not roll without the socket timeout"
    ready_three 600 || cannot "E57: the fleet without the socket timeout did not reach three ready replicas"
    e57_hole without
    e57_watch $((EXP_SOCKET_TIMEOUT + 65)) without
    [ -n "$EXP_STOPPED" ] && return 0
    [ -n "$E57_HELD" ] || cannot "E57 without: no replica held a connection 20 s after the hole, so the leg shows nothing"
    experiments_record "without: held" "replicas $E57_HELD held a connection 20 s after the hole; still holding $((EXP_SOCKET_TIMEOUT + 65)) s after it: ${E57_STILL:-none}"
    if [ "$E57_STILL" != "$E57_HELD" ]; then
        EXP_STOPPED="without the socket timeout a held connection was given up anyway (held $E57_HELD, still $E57_STILL): the with leg's freeing was not the timeout's"
        return 0
    fi
    pass "E57: with the socket timeout every held connection given up within it; without it every one still held past it"
}

E57_recover() {
    # The stop removed the hole and the setting: the fleet rolled back with the socket timeout, the held threads gone with their pods.
    kube -n finapp rollout status deployment/finapp-app --timeout=900s >> "$EXP_DIR/rollout.log" 2>&1 || fail "E57: the fleet did not roll back"
    ready_three 600 || fail "E57: the fleet did not return to three ready replicas"
    sleep 20
    stop_workload e57
    [ "$(workload_unresolved e57)" = 0 ] || fail "E57: $(workload_unresolved e57) requests stayed unresolved: see workload-e57.tsv"
    assert_flows_ran e57
    experiments_record workload "$(cat "$CLIENT/workload-e57.summary")"
}

# ---------------------------------------------------------------------------------------------------
# E05: the database primary lost and the synchronous standby promoted (owner decision C7; OPERATIONS_RUNBOOK.md sections 11.40 and 11.54;
# ADR-0139). Run last: it leaves one server and no standby.
# ---------------------------------------------------------------------------------------------------

E05_steady() {
    [ "${EXPERIMENTS##* }" = E05 ] || cannot "E05 leaves the cluster one server and no standby, so no experiment's steady state could follow it: name it last in REHEARSAL_EXPERIMENTS ($EXPERIMENTS)"
    experiments_retry_bound
    WORKLOAD_RETRY_SECONDS=$EXP_RETRY_SECONDS start_workload e05 retry
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E05_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E05_SAMPLER"
    E05_RESTARTS=$(experiments_restarts)
    E05_TARGET_IP=$(experiments_fleet | awk -F'|' 'NF >= 5 && $2 == "True" && $6 == "" {print $5}' | sort | head -1)
    E05_MERCHANT=$(sed -n 's/^merchant.key=//p' "$CLIENT/state.properties")
    [ -n "$E05_TARGET_IP" ] && [ -n "$E05_MERCHANT" ] || cannot "E05: no ready replica or no merchant to pay out"
    E05_KEY="e05-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    client sign-in-flood e05 3 >> "$EXP_DIR/client.log" 2>&1 || true
    E05_COUNTED=$(psql_query -c "SELECT count(*) FROM platform.window_counter" | tr -d '[:space:]')
    [ "${E05_COUNTED:-0}" -gt 0 ] || cannot "E05: no door hit was counted before the failover, so the counters' emptying would read nothing"
    printf '%s' "$PG_POD" > "$EXP_DIR/primary"
    experiments_record pair "the primary $PG_POD in $(experiments_zone_of_pod finapp finapp-postgres-0), the standby finapp-postgres-standby-0 in $(experiments_zone_of_pod finapp finapp-postgres-standby-0), synchronous (FIRST 1)"
}

# e05_standby <psql arguments>: the superuser on the standby's own socket.
e05_standby() {
    kube -n finapp exec -i finapp-postgres-standby-0 -c postgres -- psql -q -X -A -t -U finapp -d finapp "$@"
}

# e05_payout <address> <label>: the merchant's payout of 0.10 EUR under E05's key, sent to one replica from the edge's probe pod. The key
# travels on stdin; the answer's status and body are kept.
e05_payout() {
    printf '%s\n' "$E05_MERCHANT" | kube -n finapp-edge exec -i np-edge -c probe -- /bin/sh -c '
        IFS= read -r bearer
        curl -s -w "\nstatus=%{http_code}\n" --max-time 90 -X POST -H "Authorization: Bearer $bearer" -H "Content-Type: application/json" \
            -H "Idempotency-Key: $1" -d "{\"amount\":\"0.10\",\"currency\":\"EUR\"}" "http://$2:8080/v1/merchant/payouts"
        echo "curl=$?"' probe "$E05_KEY" "$1" > "$EXP_DIR/payout-$2.txt" 2>&1
}

e05_claim() {
    psql_query -c "SELECT state FROM platform.idempotency_record WHERE idempotency_key = '$E05_KEY'" 2>/dev/null | tr -d '[:space:]'
}

# e05_processing_sampler <dir>: every 5 s the card payments caught between their provider's call and its outcome (PROCESSING), read from the
# server <dir>/primary names, into <dir>/processing.tsv until <dir>/processing-stop exists; a read that fails is "-".
e05_processing_sampler() {
    set +e
    printf 'epoch\tprocessing\n' > "$1/processing.tsv"
    while [ ! -f "$1/processing-stop" ]; do
        PG_POD=$(cat "$1/primary")
        n=$(psql_query -c "SELECT count(*) FROM payments.payment_intent WHERE status = 'PROCESSING'" 2>/dev/null | tr -d '[:space:]')
        printf '%s\t%s\n' "$(date +%s)" "${n:--}" >> "$1/processing.tsv"
        sleep 5
    done
}

# PROBE async-standby (the entry's probe; RESILIENCE_EXPERIMENTS.md E56): the primary made asynchronous and the standby cut from its WAL, each
# by the server's own setting, so the primary acknowledges commits the standby never receives; held until the standby has missed journal
# entries. E05 then runs as written: its promotion is refused, the probe overrides the refusal, and the comparison must show the loss.
e05_plant_lag() {
    psql_query -c "ALTER SYSTEM SET synchronous_standby_names = ''" >> "$EXP_DIR/plant.log" 2>&1 \
        || cannot "PROBE async-standby: the primary could not be made asynchronous"
    psql_query -c "SELECT pg_reload_conf()" >> "$EXP_DIR/plant.log" 2>&1 || cannot "PROBE async-standby: the primary's configuration was not reloaded"
    e05_standby -c "ALTER SYSTEM SET primary_conninfo = ''" >> "$EXP_DIR/plant.log" 2>&1 \
        || cannot "PROBE async-standby: the standby could not be cut from the primary's WAL"
    e05_standby -c "SELECT pg_reload_conf()" >> "$EXP_DIR/plant.log" 2>&1 || cannot "PROBE async-standby: the standby's configuration was not reloaded"
    deadline=$(( $(date +%s) + 60 ))
    while [ -n "$(experiments_replication)" ]; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "PROBE async-standby: the standby still streams 60 s after its cut"
        sleep 2
    done
    behind=0
    deadline=$(( $(date +%s) + 120 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        on_primary=$(psql_query -c "SELECT count(*) FROM ledger.journal_entry" | tr -d '[:space:]')
        on_standby=$(e05_standby -c "SELECT count(*) FROM ledger.journal_entry" | tr -d '[:space:]')
        behind=$(( ${on_primary:-0} - ${on_standby:-0} ))
        [ "$behind" -ge 5 ] && break
        sleep 5
    done
    [ "$behind" -ge 5 ] || cannot "PROBE async-standby: the standby missed only $behind journal entries in 120 s, too few to show"
    experiments_record plant "PROBE async-standby: the standby asynchronous and cut; the primary holds $behind journal entries the standby never received"
}

E05_fault() {
    # The worst moment for the record: a payout's effect taken at the provider, its answer held, its claim IN_PROGRESS on the primary.
    experiments_fault_on hold hold-payouts
    e05_payout "$E05_TARGET_IP" first &
    E05_FIRST=$!
    deadline=$(( $(date +%s) + 60 ))
    claim=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        claim=$(e05_claim)
        [ "$claim" = IN_PROGRESS ] && break
        sleep 1
    done
    [ "$claim" = IN_PROGRESS ] || cannot "E05: the payout never held a claim IN_PROGRESS, so the failover would find nothing in flight"
    [ "$PROBE" != async-standby ] || e05_plant_lag
    # Before the fault: the journal, then the last commit the drill sees acknowledged, then whether the primary names the standby synchronous.
    experiments_db_snapshot "$EXP_DIR" before
    tag=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
    E05_MARK=$(psql_query -c "SELECT pg_logical_emit_message(true, 'finapp-experiments', 'e05-$tag')::text" | tr -d '[:space:]')
    case $E05_MARK in */*) ;; *) cannot "E05: the acknowledged mark could not be written on the primary" ;; esac
    E05_SYNC_AT_FAULT=$(experiments_replication)
    if [ "$PROBE" != guard-off ]; then
        experiments_context_check || fail "the safety boundary refused before the kill: $EXP_REASON"
    fi
    e05_processing_sampler "$EXP_DIR" &
    E05_PROCESSING=$!
    BACKGROUND="$BACKGROUND $E05_PROCESSING"
    experiments_kill_server finapp-postgres-0
    E05_FAULT_AT=$KILLED_EPOCH
    # The fence: the old primary's StatefulSet to zero, so the kubelet's restart of the killed container is ended and no second primary can
    # serve. The drill's act, logged and never undone (a primary back beside a promoted standby would split the record).
    kube -n finapp scale statefulset/finapp-postgres --replicas=0 >> "$EXP_ART/faults.log" 2>&1 || cannot "E05: the old primary could not be fenced"
    printf '%s\tfenced\tstatefulset/finapp-postgres at 0 replicas, its volume kept\n' "$(date -u +%H:%M:%S)" >> "$EXP_ART/faults.log"
    experiments_record fault "kill -9 of finapp-postgres-0's server ($KILLED) at $(date -u -d "@$E05_FAULT_AT" +%H:%M:%SZ), the payout's claim IN_PROGRESS and held at the provider; the last acknowledged commit at $E05_MARK, the primary naming the standby '$E05_SYNC_AT_FAULT'; the old primary fenced"
}

E05_observe() {
    sleep 5
    experiments_fault_off hold hold-payouts
    # Never two primaries: the fenced one's pod gone before anything is promoted.
    kube -n finapp wait --for=delete pod/finapp-postgres-0 --timeout=180s >> "$EXP_ART/faults.log" 2>&1 \
        || cannot "E05: the fenced primary's pod was still there 180 s after the fence"
    E05_GONE_AT=$(date +%s)
    # The promotion's check (ADR-0139): the primary named the standby synchronous at its last read, and the standby holds the last commit
    # the drill saw acknowledged. Otherwise refused: promoting it would lose what the primary acknowledged.
    held=$(e05_standby -c "SELECT greatest(coalesce(pg_last_wal_receive_lsn(), '0/0'::pg_lsn), coalesce(pg_last_wal_replay_lsn(), '0/0'::pg_lsn)) >= '$E05_MARK'::pg_lsn" | tr -d '[:space:]')
    E05_REFUSED=""
    [ "$E05_SYNC_AT_FAULT" = "finapp_standby|streaming|sync" ] || E05_REFUSED="the primary did not name the standby synchronous at its last read ('$E05_SYNC_AT_FAULT')"
    [ "$held" = t ] || E05_REFUSED="${E05_REFUSED:+$E05_REFUSED; }the standby does not hold the last acknowledged commit ($E05_MARK)"
    if [ -n "$E05_REFUSED" ]; then
        experiments_record promotion "refused: $E05_REFUSED"
        if [ "$PROBE" != async-standby ]; then
            EXP_STOPPED="the standby not caught up when the promotion was due, the promotion refused: $E05_REFUSED"
            log "E05 STOP: $EXP_STOPPED"
            e05_unfence
            return 0
        fi
        log "PROBE async-standby: the promotion refused as it must be ($E05_REFUSED); the probe promotes the standby anyway, as a person overriding the refusal would"
    fi
    E05_PROMOTE_FROM=$(date +%s)
    promoted=$(e05_standby -c "SELECT pg_promote(true, 60)" | tr -d '[:space:]')
    [ "$promoted" = t ] || cannot "E05: the standby did not promote within 60 s ('$promoted')"
    [ "$(e05_standby -c 'SELECT pg_is_in_recovery()' | tr -d '[:space:]')" = f ] || cannot "E05: the promoted standby still reads in recovery"
    E05_PROMOTED_AT=$(date +%s)
    # The new primary alone (ADR-0139): it names no synchronous standby, or every commit would wait for one it does not have.
    e05_standby -c "ALTER SYSTEM RESET synchronous_standby_names" >> "$EXP_DIR/promotion.log" 2>&1 || cannot "E05: the new primary's synchronous_standby_names could not be reset"
    e05_standby -c "SELECT pg_reload_conf()" >> "$EXP_DIR/promotion.log" 2>&1 || cannot "E05: the new primary's configuration was not reloaded"
    names=$(e05_standby -c "SHOW synchronous_standby_names" | tr -d '[:space:]')
    [ -z "$names" ] || cannot "E05: the new primary still names a synchronous standby ('$names')"
    # The Service moved to the promoted pod: the name the clients and the fault proxy use now leads to it, and verify-full holds, the standby
    # presenting the primary's certificate. Every read after this is of the promoted server.
    kube -n finapp patch service finapp-postgres --type=merge -p '{"spec":{"selector":{"app.kubernetes.io/name":"finapp-postgres-standby"}}}' \
        >> "$EXP_ART/faults.log" 2>&1 || cannot "E05: the Service could not be moved to the promoted standby"
    printf '%s\tmoved\tservice/finapp-postgres to finapp-postgres-standby-0\n' "$(date -u +%H:%M:%S)" >> "$EXP_ART/faults.log"
    PG_POD=finapp-postgres-standby-0
    printf '%s' "$PG_POD" > "$EXP_DIR/primary"
    promoted_ip=$(kube -n finapp get pod finapp-postgres-standby-0 -o jsonpath='{.status.podIP}')
    endpoints=""
    deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        endpoints=$(kube -n finapp get endpointslices -l kubernetes.io/service-name=finapp-postgres -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}{" "}{end}' | sed 's/ *$//')
        [ "$endpoints" = "$promoted_ip" ] && break
        sleep 1
    done
    [ -n "$promoted_ip" ] && [ "$endpoints" = "$promoted_ip" ] || cannot "E05: the Service's endpoints read '$endpoints', not the promoted server's"
    E05_MOVED_AT=$(date +%s)
    experiments_record promotion "the old primary's pod gone $(experiments_since "$E05_GONE_AT" "$E05_FAULT_AT"); promoted ${E05_REFUSED:+against the refusal (PROBE async-standby) }in $((E05_PROMOTED_AT - E05_PROMOTE_FROM)) s, $(experiments_since "$E05_PROMOTED_AT" "$E05_FAULT_AT") after the fault, alone (no synchronous standby named); the Service's endpoint the promoted server $(experiments_since "$E05_MOVED_AT" "$E05_FAULT_AT")"
    experiments_ready_again $((E05_FAULT_AT + 600)) || { [ -n "$EXP_STOPPED" ] || EXP_STOPPED="the fleet not ready on the new primary 10 min after the fault"; return 0; }
    after=$(psql_query -c "SELECT count(*) FROM platform.window_counter" | tr -d '[:space:]')
    experiments_record counters "$E05_COUNTED rows before the failover, $after after it (a promoted standby never held an unlogged table's rows)"
    pass "E05: the standby promoted alone and the Service moved, every replica ready on it"
}

# e05_unfence: a refused promotion's way back - the old primary returned on its volume, the standby streaming and synchronous from it again.
e05_unfence() {
    kube -n finapp scale statefulset/finapp-postgres --replicas=1 >> "$EXP_ART/faults.log" 2>&1 || cannot "E05: the old primary could not be brought back"
    kube -n finapp rollout status statefulset/finapp-postgres --timeout=300s >> "$EXP_ART/faults.log" 2>&1 || cannot "E05: the old primary did not return"
    printf '%s\tunfenced\tstatefulset/finapp-postgres at 1 replica: the promotion refused\n' "$(date -u +%H:%M:%S)" >> "$EXP_ART/faults.log"
    deadline=$(( $(date +%s) + 120 ))
    until [ "$(experiments_replication)" = "finapp_standby|streaming|sync" ]; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "E05: the standby did not stream synchronously from the primary brought back"
        sleep 2
    done
    E05_MOVED_AT=$(date +%s)
}

E05_recover() {
    ready_three 400 || fail "E05: the fleet did not return to three ready replicas"
    # The payout held when the primary was lost: its key sent until answered (409 while the claim's lease holds), its claim completed once.
    wait "$E05_FIRST" 2>/dev/null || true
    experiments_record "in flight" "the payout held at the provider when the primary was lost: $(grep -h '^curl=\|^status=' "$EXP_DIR/payout-first.txt" | tr '\n' ' ')"
    deadline=$(( $(date +%s) + 900 ))
    answered=""
    tries=0
    while [ "$(date +%s)" -lt "$deadline" ]; do
        tries=$((tries + 1))
        e05_payout "$E05_TARGET_IP" "retry-$tries" || true
        status=$(sed -n 's/^status=//p' "$EXP_DIR/payout-retry-$tries.txt")
        printf '%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$tries" "${status:-none}" >> "$EXP_DIR/retries.tsv"
        case $status in 200 | 201) answered=$tries; break ;; esac
        sleep 5
    done
    [ -n "$answered" ] || fail "E05: the payout's key was not answered within 900 s: see experiments/E05/retries.tsv"
    [ "$(e05_claim)" = COMPLETED ] || fail "E05: the payout's claim is $(e05_claim), not COMPLETED, after its key was answered"
    EXPERIMENTS_PAYOUTS=$((EXPERIMENTS_PAYOUTS + 1))
    experiments_record claim "the payout's claim completed once, answered on try $answered ($(cut -f3 "$EXP_DIR/retries.tsv" | tr '\n' ' '))"
    sleep 20
    touch "$EXP_DIR/sampler-stop" "$EXP_DIR/processing-stop"
    wait "$E05_SAMPLER" 2>/dev/null || true
    wait "$E05_PROCESSING" 2>/dev/null || true
    stop_workload e05
    [ "$(workload_unresolved e05)" = 0 ] || fail "E05: $(workload_unresolved e05) requests stayed unresolved: see workload-e05.tsv"
    assert_flows_ran e05
    experiments_record workload "$(cat "$CLIENT/workload-e05.summary")"
    # Nothing acknowledged lost: every entry read before the fault on the new primary, unchanged, and no counted table shrank.
    experiments_db_snapshot "$EXP_DIR" after
    experiments_db_compare "$EXP_DIR" before after
    if [ "$DB_MISSING" != 0 ] || [ "$DB_CHANGED" != 0 ] || [ -n "$DB_FEWER" ]; then
        EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }the journal before the failover is not whole after it: $DB_MISSING missing, $DB_CHANGED changed of $DB_COMPARED${DB_FEWER:+; shrank: $DB_FEWER}"
    fi
    if [ "$PROBE" = async-standby ]; then
        [ "$DB_MISSING" -gt 0 ] || fail "PROBE async-standby: the standby was promoted behind the primary, and the comparison showed no lost entry: it reads nothing"
        log "PROBE async-standby: the comparison showed $DB_MISSING journal entries the promoted standby never received"
    fi
    experiments_readiness "$EXP_DIR" "$E05_FAULT_AT"
    [ "$(experiments_restarts)" = "$E05_RESTARTS" ] \
        || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a replica restarted for the failover: $E05_RESTARTS -> $(experiments_restarts)"
    experiments_edge_read "$EXP_DIR" $((E05_FAULT_AT - 60))
    away=$(experiments_money_succeeded "$EXP_DIR" $((E05_FAULT_AT + 5)) "${E05_MOVED_AT:-$(date +%s)}")
    [ "$away" = 0 ] || EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }$away money-moving commands answered a success while no primary served"
    back=$(experiments_money_back "$EXP_DIR" "$E05_FAULT_AT")
    caught=$(awk -F'\t' -v k="$E05_FAULT_AT" 'NR > 1 && $1 >= k && $2 != "-" {print $2; exit}' "$EXP_DIR/processing.tsv")
    cleared=$(awk -F'\t' -v k="${E05_MOVED_AT:-$E05_FAULT_AT}" 'NR > 1 && $1 >= k && $2 == "0" {print $1; exit}' "$EXP_DIR/processing.tsv")
    experiments_record timeline "the kill at 0 s; a replica first unready $(experiments_since "$FLEET_FIRST_UNREADY" "$E05_FAULT_AT"), none ready $(experiments_since "$FLEET_NONE_READY" "$E05_FAULT_AT"), every replica ready again $(experiments_since "$FLEET_ALL_READY" "$E05_FAULT_AT"); the old primary gone $(experiments_since "$E05_GONE_AT" "$E05_FAULT_AT"); promoted $(experiments_since "${E05_PROMOTED_AT:-}" "$E05_FAULT_AT"); the Service on it $(experiments_since "${E05_MOVED_AT:-}" "$E05_FAULT_AT"); the first money-moving success $(experiments_since "$back" "$E05_FAULT_AT") (the interruption)"
    experiments_record "caught mid-call" "${caught:-no} card payments PROCESSING at the first read after the fault; none left $(experiments_since "$cleared" "$E05_FAULT_AT") (the payment sweep's dispatched age concludes them)"
    experiments_record "at the edge" "$EDGE_FAILED failed answers and $EDGE_SERVED served since the minute before the fault; $away money-moving successes while no primary served"
}

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
