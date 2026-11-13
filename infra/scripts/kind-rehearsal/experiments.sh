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
# THE SAFETY BOUNDARY (ADR-0127 decision 5), checked by experiments_refuses before every fault - experiments_fault_on calls it first - and
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
# THE PROBES (REHEARSAL_PROBE), each a deliberate break that must turn the run red:
#   guard-off          the refusals answer "pass" without reading anything: every plant of experiments_boundary goes unrefused;
#   crash-with-faults  every kind of fault put on, then an unguarded failure: the exit trap must leave none on (read on a kept cluster);
#   term-with-faults   the same, then SIGTERM to the harness itself, the signal path of the same trap.
# REHEARSAL_EXPERIMENTS names the experiments to run (E01 by default; the catalogue is P16-TSK-020...-023's and P16-TSK-027's).

EXPERIMENTS=${REHEARSAL_EXPERIMENTS:-E01}
TOXIPROXY_IMAGE="$TOXIPROXY_REPOSITORY@$TOXIPROXY_DIGEST"
EXPERIMENTS_MARKER=finapp-experiments-marker
EXPERIMENTS_CONFIG="$DIR/experiments/kind-cluster.yaml"
EXPERIMENTS_COLLECTOR=http://finapp-collector.finapp-observability.svc.cluster.local:4318/
# The census's expected payouts take this: E01's own payout, zero in every other scope.
EXPERIMENTS_PAYOUTS=0
# Set once the scope's faults can exist; finish() then empties the registry and resets the proxy.
EXPERIMENTS_ARMED=""
EXPERIMENTS_GUARDS=0

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
    printf '%s' "$phrase" > "$WORK/replication.password"
    printf '%s' "$phrase" | sha256sum | cut -c1-64 >> "$LEDGER"
    secret_of finapp finapp-kind-postgres-replication "password=$WORK/replication.password"
    # The password travels on the SQL's stdin, never on a command line; the server logs no statement.
    psql_query -f - >> "$ART/experiments/standby.log" 2>&1 <<SQL || cannot "the replication role or its slot could not be made"
\set ON_ERROR_STOP on
CREATE ROLE finapp_replicator WITH REPLICATION LOGIN PASSWORD '$phrase';
SELECT pg_create_physical_replication_slot('finapp_standby');
SQL
    rm -f "$WORK/replication.password"
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
    # A committed write is on the standby: synchronous_commit on waits for the standby's flush, and its replay follows.
    tag=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
    lsn=$(psql_query -c "SELECT pg_logical_emit_message(false, 'finapp-experiments', '$tag')::text" | tr -d '[:space:]')
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
    sources=$(printf '%s\n' "$deployment" | awk '$1 == "SOURCE" {print $2 "/" $3 "/" $4}' | sort | tr '\n' ' ')
    [ "$sources" = "configmap/finapp-app-config/- secret/finapp-app-secrets/- " ] \
        || { EXP_REASON="endpoint: the application reads its environment from '$sources', not the run's ConfigMap and Secret alone"; return 1; }
    config=$(kube -n finapp get configmap finapp-app-config -o go-template='{{range $k, $v := .data}}{{$k}} {{$v}}{{"\n"}}{{end}}' 2>/dev/null) \
        || { EXP_REASON="endpoint: the application's ConfigMap could not be read"; return 1; }
    # The Secret's endpoints, decoded inside the node: a key of an endpoint's name, or any value holding a scheme. No other value comes back.
    secret=$(dock exec -i "$NODE" sh -s <<'NODE'
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
    [ "$secret" != UNREADABLE ] || { EXP_REASON="endpoint: the application's Secret could not be read"; return 1; }
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
$secret
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
    log "clearing every fault the experiments put on ($(grep -c . "$FAULTS" 2>/dev/null || echo 0) registered)"
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
# is delivered to a process that is its own namespace's init. Prints "<node> <container> <pid>".
experiments_kill_jvm() {
    node=$(kube -n finapp get pod "$1" -o jsonpath='{.spec.nodeName}')
    container=$(kube -n finapp get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="app")].containerID}' | sed 's#^containerd://##')
    [ -n "$node" ] && [ -n "$container" ] || cannot "the pod $1 has no node or no running container to kill"
    [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node" 2>/dev/null)" = "$CLUSTER" ] \
        || fail "the safety boundary refused to kill on $node: it is not a node of $CLUSTER"
    pid=$(dock exec "$node" crictl inspect --output go-template --template '{{.info.pid}}' "$container" 2>/dev/null)
    case $pid in '' | *[!0-9]*) cannot "the application process of $1 could not be found on $node" ;; esac
    dock exec "$node" kill -9 "$pid" || cannot "SIGKILL could not be sent to $1's process on $node"
    printf '%s\tkill -9\t%s on %s, pid %s\n' "$(date -u +%H:%M:%S)" "$1" "$node" "$pid" >> "$EXP_ART/faults.log"
    printf '%s %s %s\n' "$node" "$container" "$pid"
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

# The correctness pages of SLOS.md section 3, every one a rule the cluster's Prometheus loaded, read once: prints the firing ones, or
# UNREADABLE when Prometheus did not answer. Nothing printed is nothing firing.
experiments_pages_firing() {
    answer=$(telemetry_get finapp-prometheus:9090 "/api/v1/query?query=$(urlencode "ALERTS{alertstate=\"firing\",alertname=~\"$EXP_PAGE_REGEX\"}")" 2>/dev/null || true)
    case $answer in *'"status":"success"'*) ;; *) echo UNREADABLE; return 0 ;; esac
    printf '%s' "$answer" | grep -o '"alertname":"[^"]*"' | sed 's/.*:"\(.*\)"/\1/' | sort -u | tr '\n' ' '
}

experiments_pages_setup() {
    EXP_PAGES=$(sed -n '/^## 3\. /,/^## 4\. /s/^| `\([A-Za-z]*\)` |.*/\1/p' "$ROOT/docs/architecture/SLOS.md")
    pages=$(printf '%s\n' "$EXP_PAGES" | grep -c . || true)
    [ "$pages" -ge 20 ] || cannot "only $pages correctness pages were read from SLOS.md section 3"
    telemetry_get finapp-prometheus:9090 /api/v1/rules > "$EXP_ART/prometheus-rules.json" 2>&1 || cannot "the cluster's Prometheus did not list its rules"
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
    members=$(awk '$1 ~ /^finapp\./ && $3 ~ /^\// {print $3}' "$EXP_ART/members.txt" | sort | uniq -c | tr -s ' ' | tr '\n' ';')
    other=$(awk -v p="/$proxy_pod" '$1 ~ /^finapp\./ && $3 ~ /^\// && $3 != p' "$EXP_ART/members.txt" | grep -c . || true)
    counted=$(awk -v p="/$proxy_pod" '$1 ~ /^finapp\./ && $3 == p' "$EXP_ART/members.txt" | grep -c . || true)
    [ "$counted" -ge 1 ] && [ "$other" = 0 ] || fail "topology: the consumer groups' members are not all at the proxy's address: $members"
    kube -n finapp-experiments logs deploy/finapp-toxiproxy -c toxiproxy --tail=-1 > "$EXP_ART/toxiproxy.log" 2>&1 || true
    for ip in $(printf '%s\n' "$fleet" | awk -F'|' 'NF >= 5 && $6 == "" {print $5}'); do
        for proxy in postgres kafka simulator; do
            grep '"message":"Accepted client"' "$EXP_ART/toxiproxy.log" | grep "\"name\":\"$proxy\"" | grep -q "\"client\":\"$ip:" \
                || fail "topology: the replica at $ip never reached the $proxy hop through the proxy: see experiments/toxiproxy.log"
        done
    done
    pass "topology: every one of $all application sessions arrives from the proxy over TLS; $counted consumer-group members at its address and none elsewhere; every replica accepted by the database, broker and simulator proxies (the clients' URLs and verify-full unchanged)"
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
    pass "the experiments ran behind $EXPERIMENTS_GUARDS passes of the safety boundary, one before every fault"
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
    while IFS= read -r fault; do
        [ -n "$fault" ] || continue
        # shellcheck disable=SC2086
        experiments_fault_off $fault
    done <<EOF
$(awk '{line[NR] = $0} END {for (i = NR; i >= 1; i--) print line[i]}' "$FAULTS")
EOF
    cp "$FAULTS" "$FAULTS.cleared"
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
    E01_KILLED_AT=$(date +%s)
    killed=$(experiments_kill_jvm "$E01_VICTIM")
    experiments_record fault "kill -9 of $E01_VICTIM's JVM ($killed) at $(date -u -d "@$E01_KILLED_AT" +%H:%M:%SZ), its payout's claim IN_PROGRESS and held at the provider"
    log "E01: $E01_VICTIM's JVM killed with SIGKILL ($killed), its payout under a claim IN_PROGRESS"
}

E01_observe() {
    sleep 10
    experiments_fault_off hold hold-payouts
    wait "$E01_FIRST" 2>/dev/null || true
    grep -q '^curl=0$' "$EXP_DIR/payout-first.txt" && grep -q '^status=20[01]$' "$EXP_DIR/payout-first.txt" \
        && fail "E01: the payout in flight on the killed replica was answered, so it was not in flight when the process died"
    experiments_record "in flight" "the payout sent to the killed replica: $(grep -h '^curl=\|^status=' "$EXP_DIR/payout-first.txt" | tr '\n' ' ')"
    # Until the killed replica is ready again: its endpoint's removal, its restart, and the stop condition (a second replica unready).
    removed=""
    returned=""
    pages_at=0
    deadline=$((E01_KILLED_AT + 600))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        now=$(date +%s)
        if [ -z "$removed" ]; then
            ready_endpoint=$(kube -n finapp get endpointslices -l kubernetes.io/service-name=finapp-app \
                -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}|{.conditions.ready}{"\n"}{end}' 2>/dev/null | grep -c "^$E01_VICTIM_IP|true$" || true)
            [ "$ready_endpoint" = 0 ] && removed=$((now - E01_KILLED_AT))
        fi
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
    experiments_record "routed around" "the killed replica's endpoint not ready ${removed:-never seen ready after the kill, so at once} s after the kill"
    experiments_record "replaced" "the container restarted in its pod (exit 137) and the replica ready again $returned s after the kill"
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
    pass "E01: the killed replica's claim was taken over and completed once by another, the replica restarted (exit 137) and ready again $returned s after the kill"
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
    # Every answer the edge gave that was not the application's - a 5xx or no answer from the backend - and the backend it was meant for.
    since=$(date -u -d "@$((E01_KILLED_AT - 60))" +%Y-%m-%dT%H:%M:%SZ)
    : > "$EXP_DIR/edge-answers.tsv"
    for pod in $(kube -n finapp-edge get pods -l app.kubernetes.io/name=finapp-edge-public -o jsonpath='{range .items[*]}{.metadata.name}|{.spec.containers[0].name}{"\n"}{end}' | awk -F'|' '$2 == "edge" {print $1}'); do
        kube -n finapp-edge logs "$pod" -c edge --since-time="$since" 2>/dev/null \
            | sed -n 's/.*"DownstreamStatus":\([0-9]*\).*"OriginStatus":\([0-9]*\).*/\1 \2 &/p' \
            | sed -n 's/^\([0-9]*\) \([0-9]*\) .*"ServiceAddr":"\([0-9.]*\):[0-9]*".*"StartUTC":"\([^"]*\)".*/\4\t\3\t\1\t\2/p' >> "$EXP_DIR/edge-answers.tsv"
    done
    answers=$(grep -c . "$EXP_DIR/edge-answers.tsv" || true)
    [ "$answers" -ge 10 ] || fail "E01: the edge's lines held $answers answers since the minute before the kill, too few to judge: see experiments/E01/edge-answers.tsv"
    failed_victim=$(awk -F'\t' -v v="$E01_VICTIM_IP" '$2 == v && ($3 >= 500 || $4 == 0)' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    failed_other=$(awk -F'\t' -v v="$E01_VICTIM_IP" '$2 != v && ($3 >= 500 || $4 == 0)' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    served_other=$(awk -F'\t' -v v="$E01_VICTIM_IP" '$2 != v && $3 < 500 && $4 != 0' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    experiments_record "failed at the edge" "$failed_victim answers the edge could not get from the killed replica, $failed_other from the others, which served $served_other, of $answers since the minute before the kill"
    [ "$failed_other" = 0 ] || fail "E01: the edge answered $failed_other requests with a failure from a replica that was not killed"
    [ "$served_other" -ge 1 ] || fail "E01: the other replicas served nothing through the edge, so the others serving was not shown"
    pass "E01: every failed answer at the edge ($failed_victim) was the killed replica's; the others served $served_other; $(workload_failed e01) workload attempts failed and were retried by key, none unresolved"
}
