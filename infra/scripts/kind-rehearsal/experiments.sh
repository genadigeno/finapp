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
# Kinds: toxic (a Toxiproxy toxic on one proxy), proxy-down (a proxy disabled), hold (one of the simulator's hold flags), store-hold (the archive store's read hold, P16-TSK-010), partition (a
# NetworkPolicy labelled finapp/experiment-fault), node-paused (`docker pause` of a node of this cluster) and scaled (a Deployment scaled,
# its count to restore recorded). A kill, a deletion or an eviction is instant and is logged, not registered. finish() calls
# experiments_clear_faults on every way out, the INT and TERM traps and an unguarded failure included: every registered fault undone,
# newest first, then a blanket reset that needs no record (every proxy enabled with no toxic, the three hold flags removed, every labelled
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
#
# E40 (P16-TSK-008, at three replicas): the sweeps' failure storm - every call to the provider simulator hangs for the payment sweep's
# dispatched age and fifteen minutes more, so every candidate of the sweeps fails and is recorded and backed off; the eight listings' plans
# and statistics read with the record tables empty and with the storm's rows, the tables' writes, dead rows and bytes every 30 s; every
# candidate resolved once the provider answers; the census green. Its plans come from auto_explain's window on the run's own server
# (load.sh's pg_plans_open and pg_plans_close).
#
# THE NETWORK, DNS, CONNECTION AND DISK FAULTS (P16-TSK-021; OPERATIONS_RUNBOOK.md section 11.47): E07, E11, E12, E13, E14, E15, E16 and
# E24, at the end of this file. Four kinds of fault came with them, each registered and cleared like the others:
#   blackhole <pod> <address> <port>   a DROP rule both ways in that one replica's own network namespace, set from its node with the
#                                      node's nsenter and iptables, marked finapp-experiment-fault: a true black hole, no reset and no
#                                      answer. A NetworkPolicy cannot do it: policies only add what a pod may reach, the application's own
#                                      already admit every hop, and the policy engine admits an established connection before any policy;
#   held <seconds>                     a holder on the test-only server's own socket, as a role with no superuser attribute, taking every
#                                      slot an ordinary role may still take (E07);
#   locked <table> <seconds>           a session of the run's superuser holding an EXCLUSIVE lock on one table (E13's cut before a commit);
#   filled <node>                      a ballast filling the size-limited filesystem the primary runs on, from its node, outside the data
#                                      directory (E24, which brings that filesystem: kind's volume claims have no size of their own).
# A toxic also takes stream=upstream and toxicity=<p>, the CLI's flags. A proxy disabled may be armed (the boundary and the registration
# now) and fired at a measured moment (E13's cut on a commit). A stop condition that is a harm (a second replica unready, a page, a p99
# past its bound) is FAILED; one that is a duration ends the observation and is FAILED only when the experiment's own signal never came.
# A fault whose own positive control does not show (nothing held, nothing failed) is a run that could not measure: exit 2, never a pass.
# THE AUDIT ARCHIVE (P16-TSK-010; ADR-0135; OPERATIONS_RUNBOOK.md section 11.50). The scope deploys the archive's test-only store
# (experiments/archive-store.yaml, ArchiveStore.java: write once, its objects on a claim of their own) and names it to the application in
# its own rendered ConfigMap alone, with the settings its experiments run at (experiments_archive_settings); the endpoint check admits that
# one URL under FINAPP_AUDIT_ARCHIVE_ENDPOINT. E44: the store scaled to zero for three minutes mid-run - nothing marked, the queue grows,
# the failures counted, every replica ready, then the queue drained and the archive census green. E45: every read held at the store, the
# holder killed between its copy and its mark, its segment taken over at the lease, its file written once and found again, marked once.
# E46: the session table bounded over a soak, a live session planted kept and forty dead ones swept. archive_census reads every audit row
# queued or in one segment, every segment's digests and chain recomputed, and every archived object digested in the store. Built and not
# run: their runs are owed under POSTPONED_ISSUES.md PI-025.

EXPERIMENTS=${REHEARSAL_EXPERIMENTS:-E01}
TOXIPROXY_IMAGE="$TOXIPROXY_REPOSITORY@$TOXIPROXY_DIGEST"
EXPERIMENTS_MARKER=finapp-experiments-marker
EXPERIMENTS_CONFIG="$DIR/experiments/kind-cluster.yaml"
EXPERIMENTS_COLLECTOR=http://finapp-collector.finapp-observability.svc.cluster.local:4318/
# The audit archive's test-only store (P16-TSK-010), named to the application in this scope's own copy alone.
ARCHIVE_HOST=finapp-archive-store.finapp-rehearsal.svc.cluster.local
ARCHIVE_URL="https://$ARCHIVE_HOST:8443"
ARCHIVE_OBJECTS=/archive/finapp-audit-archive
# The census's expected payouts take this: E01's own payout, zero in every other scope.
EXPERIMENTS_PAYOUTS=0
# And its expected transfers this: E13's two cut transfers (P16-TSK-021), zero in every other scope.
EXPERIMENTS_TRANSFERS=0
# P16-TSK-021's marks: the comment on a black hole's rules, the application names of the holder's and the lock's sessions, the limited
# filesystem's mount point on each worker, and the ballast's name on it.
EXPERIMENTS_FAULT_COMMENT=finapp-experiment-fault
EXPERIMENTS_HOLDER=finapp-experiment-holder
EXPERIMENTS_LOCKER=finapp-experiment-lock
EXPERIMENTS_LIMITED=/mnt/finapp-experiments-pgdata
EXPERIMENTS_BALLAST=finapp-experiment-ballast
EXPERIMENTS_HOLDER_PID=""
EXPERIMENTS_ARMED_AT=0
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
    # E24 (P16-TSK-021): the primary on a filesystem of its claim's own size, only when E24 is named; every other run as before.
    case " $EXPERIMENTS " in *" E24 "*) experiments_limited_volume ;; esac
    experiments_archive_settings
}

# E24's volume. kind's provisioner (local-path) backs a claim with a directory on the node, so a claim has no size of its own and filling it
# would fill the machine's disk. Each worker gets a 2 GiB ext4 filesystem in a file inside its node container, loop-mounted at
# $EXPERIMENTS_LIMITED (made afresh on every run, so a reused cluster's old data never comes back), offered as a local PersistentVolume of
# the test-only StorageClass finapp-experiments-limited (experiments/limited-volume.yaml); the scope's rendered copy of the primary's claim
# names that class, so the scheduler places the primary on a worker with one. The ballast goes there and nowhere else. A node is the
# run's own container, removed with the cluster with its file and its loop device.
experiments_limited_volume() {
    kube delete persistentvolume -l finapp/experiment-volume=true --ignore-not-found >> "$ART/apply.log" 2>&1 || cannot "E24's old volumes could not be removed"
    for node in $(kube get nodes -l '!node-role.kubernetes.io/control-plane' -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
        [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node" 2>/dev/null)" = "$CLUSTER" ] \
            || fail "the safety boundary refused to make a volume on $node: it is not a node of $CLUSTER"
        dock exec "$node" sh -c '
            set -e
            if grep -qs " $1 " /proc/mounts; then umount "$1"; fi
            mkdir -p /var/lib/finapp-experiments "$1"
            rm -f /var/lib/finapp-experiments/pgdata.img
            truncate -s 2G /var/lib/finapp-experiments/pgdata.img
            mkfs.ext4 -q -F -m 0 /var/lib/finapp-experiments/pgdata.img
            mount -o loop /var/lib/finapp-experiments/pgdata.img "$1"
            chown 999:999 "$1"
            chmod 0700 "$1"
            df -PB1 "$1" | awk "NR == 2 {print \"free \" \$4}"' limited "$EXPERIMENTS_LIMITED" >> "$ART/experiments-volume.log" 2>&1 \
            || cannot "E24's size-limited filesystem could not be made on $node: see experiments-volume.log"
        sed "s#NODE_NAME#$node#g" "$DIR/experiments/limited-volume.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 \
            || cannot "E24's volume on $node could not be applied"
    done
    awk '/^ *volumeClaimTemplates:$/ {claims = 1} claims && /^ *accessModes:$/ && !done {indent = $0; sub(/accessModes:$/, "", indent); print indent "storageClassName: finapp-experiments-limited"; done = 1} {print}' \
        "$A/StatefulSet.finapp-postgres.yaml" > "$WORK/statefulset.tmp" && mv "$WORK/statefulset.tmp" "$A/StatefulSet.finapp-postgres.yaml"
    grep -q '^ *storageClassName: finapp-experiments-limited$' "$A/StatefulSet.finapp-postgres.yaml" \
        || cannot "the primary's claim could not be given E24's class"
    log "E24: a 2 GiB filesystem on every worker, the primary's claim of class finapp-experiments-limited"
}

# The audit archive named to the application in this scope's copy alone (P16-TSK-010): its store, and the settings E44-E46 run at - a
# lease and a tick short enough for a takeover inside an experiment, a session grace of five minutes and the retention sweeps' tick at
# thirty seconds so E46 sees dead sessions go. Literals of the run's own rendered ConfigMap, never of a committed overlay.
experiments_archive_settings() {
    config=$(ls "$A"/ConfigMap.finapp-app-config-*.yaml 2>/dev/null | head -1)
    [ -n "$config" ] || cannot "the application's rendered ConfigMap could not be found for the archive's settings"
    cat > "$WORK/archive-settings.yaml" <<YAML
  FINAPP_AUDIT_ARCHIVE_ENDPOINT: "$ARCHIVE_URL"
  FINAPP_AUDIT_ARCHIVE_INTERVAL: "PT10S"
  FINAPP_AUDIT_ARCHIVE_LEASE: "PT30S"
  FINAPP_AUDIT_ARCHIVE_TIMEOUT: "PT3S"
  FINAPP_IDENTITY_SESSION_SWEEP_GRACE: "PT5M"
  FINAPP_RETENTION_SWEEP_INTERVAL: "PT30S"
YAML
    awk -v settings="$WORK/archive-settings.yaml" '/^data:$/ && !done {print; while ((getline line < settings) > 0) print line; done = 1; next} {print}' \
        "$config" > "$WORK/config.tmp" && mv "$WORK/config.tmp" "$config"
    grep -q "^  FINAPP_AUDIT_ARCHIVE_ENDPOINT: \"$ARCHIVE_URL\"$" "$config" || cannot "the archive's settings could not be added to the application's ConfigMap"
    log "the experiments' copy: the audit archive named ($ARCHIVE_URL), its lease 30 s and tick 10 s; the session grace 5 min, the sweeps' tick 30 s"
}

# The archive's test-only store (experiments/archive-store.yaml, ArchiveStore.java), over TLS from the local CA, its objects on a claim of
# their own so that a scale to zero keeps them. Its keystore's phrase is generated here and written to the ledger by secret_of.
experiments_archive_store() {
    issue archive-store "DNS:$ARCHIVE_HOST,DNS:finapp-archive-store,DNS:finapp-archive-store.finapp-rehearsal"
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n' > "$CA_DIR/archive-store.phrase"
    (cd "$CA_DIR" && openssl pkcs12 -export -inkey archive-store.key -in archive-store.crt -certfile ca.crt \
        -passout "file:archive-store.phrase" -out archive-store.p12) >/dev/null 2>&1 || cannot "the archive store's keystore could not be made"
    secret_of finapp-rehearsal finapp-archive-store-tls "keystore.p12=$CA_DIR/archive-store.p12" "store-phrase=$CA_DIR/archive-store.phrase"
    {
        printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: finapp-archive-store-source\n  namespace: finapp-rehearsal\ndata:\n  ArchiveStore.java: |\n'
        sed 's/^/    /' "$DIR/ArchiveStore.java"
    } | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the archive store's source"
    sed "s#JDK_IMAGE#$JDK_IMAGE#" "$DIR/experiments/archive-store.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the archive store"
    kube -n finapp-rehearsal rollout status deployment/finapp-archive-store --timeout=180s >> "$ART/apply.log" 2>&1 \
        || fail "the archive store did not become ready"
}

experiments_store() {
    kube -n finapp-rehearsal exec deploy/finapp-archive-store -c store -- "$@"
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
    experiments_archive_store
    log "the fault proxy on the application's three hops at $PROXY_IP; the standby synchronous; the audit archive's store ready"
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
            FINAPP_AUDIT_ARCHIVE_ENDPOINT)
                [ "$value" = "$ARCHIVE_URL" ] || { EXP_REASON="endpoint: the audit archive's store is not the run's own"; return 1; }
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
#   toxic <proxy> <name> <type> <attribute=value>...   proxy-down <proxy>   hold <flag file>   store-hold <flag file>   partition <namespace> <name> <file>
#   node-paused <node container>   scaled <namespace> <deployment> <replicas> <replicas to restore>
#   blackhole <pod> <address> <port>   held <seconds>   locked <table> <seconds>   filled <node container>   (P16-TSK-021)
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
            # stream=upstream and toxicity=<p> are the CLI's own flags, not attributes of the toxic (P16-TSK-021).
            set -- toxic add -t "$type" -n "$name" $(for attribute in "$@"; do
                case $attribute in
                    stream=upstream) printf -- '-u ' ;;
                    stream=downstream) printf -- '-d ' ;;
                    toxicity=*) printf -- '--tox %s ' "${attribute#toxicity=}" ;;
                    *) printf -- '-a %s ' "$attribute" ;;
                esac
            done) "$proxy"
            experiments_toxiproxy "$@" >> "$EXP_ART/faults.log" 2>&1 ;;
        blackhole)
            experiments_netns "$1" || fail "the safety boundary refused to cut $1: $EXP_REASON"
            experiments_blackhole_rules -I "$2" "$3" >> "$EXP_ART/faults.log" 2>&1 ;;
        held) experiments_holder_start "$1" ;;
        locked) experiments_lock_start "$1" "$2" ;;
        filled)
            [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$1" 2>/dev/null)" = "$CLUSTER" ] \
                || fail "the safety boundary refused to fill a volume on $1: it is not a node of $CLUSTER"
            experiments_ballast "$1" >> "$EXP_ART/faults.log" 2>&1 ;;
        proxy-down)
            experiments_toxiproxy list | awk -F'\t' -v p="$1" '$1 == p && $4 == "enabled" {found = 1} END {exit !found}' \
                && experiments_toxiproxy toggle "$1" >> "$EXP_ART/faults.log" 2>&1 ;;
        hold) experiments_simulator touch "/tmp/$1" >> "$EXP_ART/faults.log" 2>&1 ;;
        store-hold) experiments_store touch "/tmp/$1" >> "$EXP_ART/faults.log" 2>&1 ;;
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
        store-hold) experiments_store rm -f "/tmp/$1" ;;
        partition) kube -n "$1" delete networkpolicy "$2" --ignore-not-found ;;
        node-paused) dock unpause "$1" ;;
        scaled) kube -n "$1" scale "deployment/$2" --replicas="$4" ;;
        # A replica that has gone took its namespace and its rules with it.
        blackhole) if experiments_netns "$1"; then experiments_blackhole_clear; fi ;;
        held) experiments_holder_stop ;;
        locked) experiments_sessions_ended "$EXPERIMENTS_LOCKER" ;;
        filled) dock exec "$1" rm -f "$EXPERIMENTS_LIMITED/$EXPERIMENTS_BALLAST" ;;
        *) return 1 ;;
    esac
}

# A fault whose moment is a measurement's (E13's cut on a commit, P16-TSK-021): the whole boundary and the registration now, the injection
# at the moment, with nothing slower than the injection between them. Only a proxy disabled is armed so, from an enabled one; it stays
# registered, and is removed like any other, whether it was fired or not.
experiments_fault_armed() {
    [ "$1" = proxy-down ] || cannot "only a proxy disabled is armed, not $1"
    experiments_toxiproxy list | awk -F'\t' -v p="$2" '$1 == p && $4 == "enabled" {found = 1} END {exit !found}' \
        || cannot "the proxy $2 is not enabled, so it cannot be armed"
    experiments_guard
    printf '%s\n' "$*" >> "$FAULTS"
    EXPERIMENTS_ARMED_AT=$(date +%s)
    printf '%s\tarmed\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$EXP_ART/faults.log"
}

experiments_fault_fire() {
    grep -qxF "$*" "$FAULTS" || fail "the fault $* was fired without being armed: the boundary had not passed for it"
    [ $(( $(date +%s) - EXPERIMENTS_ARMED_AT )) -le 300 ] || cannot "the fault $* was armed more than 300 s before its moment"
    experiments_toxiproxy toggle "$2" >> "$EXP_ART/faults.log" 2>&1 || cannot "the fault $* could not be put on"
    printf '%s\ton\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$EXP_ART/faults.log"
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
    stored=$(experiments_store ls -a /tmp 2>/dev/null) || echo "the archive store could not be read"
    printf '%s\n' "$stored" | grep -x 'hold-reads' | sed "s/^/the archive store's flag /"
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" get networkpolicy -l finapp/experiment-fault=true -o name 2>/dev/null | sed "s#^#$namespace #"
    done
    while read -r kind namespace deployment replicas restore; do
        [ "$kind" = scaled ] || continue
        now=$(kube -n "$namespace" get "deployment/$deployment" -o jsonpath='{.spec.replicas}' 2>/dev/null)
        [ "$now" = "$restore" ] || echo "$namespace/$deployment at $now replicas, not $restore"
    done < "$FAULTS.cleared"
    experiments_faults_left_021
    return 0
}

# What is left of P16-TSK-021's kinds, read the same way: a marked rule in any replica's namespace, a holder's or a lock's session, a
# ballast on any node. The database unreadable is a fault left on only when a holder or a lock was put on: a database the experiment
# itself took away (E24) is read again at the steady state that follows.
experiments_faults_left_021() {
    for pod in $(experiments_fleet | awk -F'|' 'NF >= 5 && $6 == "" {print $1}'); do
        experiments_netns "$pod" || continue
        rules=$(experiments_in_netns iptables -S 2>/dev/null | grep -c -- "--comment $EXPERIMENTS_FAULT_COMMENT" || true)
        [ "${rules:-0}" = 0 ] || echo "$rules black-hole rules in $pod's namespace"
    done
    sessions=$(psql_query -c "SELECT count(*) FILTER (WHERE application_name = '$EXPERIMENTS_HOLDER') || '|' || count(*) FILTER (WHERE application_name = '$EXPERIMENTS_LOCKER') FROM pg_stat_activity" 2>/dev/null | tr -d '[:space:]')
    case $sessions in
        *'|'*)
            [ "${sessions%|*}" = 0 ] || echo "${sessions%|*} holder sessions on the database"
            [ "${sessions#*|}" = 0 ] || echo "${sessions#*|} locking sessions on the database" ;;
        *) if grep -q '^\(held\|locked\) ' "$FAULTS.cleared" 2>/dev/null; then echo "the database could not be read for the holder's or the lock's sessions"; fi ;;
    esac
    for node in $(experiments_nodes); do
        if dock exec "$node" test -e "$EXPERIMENTS_LIMITED/$EXPERIMENTS_BALLAST" 2>/dev/null; then echo "a ballast on $node"; fi
    done
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
    kube -n finapp-rehearsal rollout status deployment/finapp-archive-store --timeout=180s >> "$EXP_ART/faults.log" 2>&1
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
    experiments_store rm -f /tmp/hold-reads >> "$EXP_ART/faults.log" 2>&1
    for namespace in finapp finapp-experiments finapp-rehearsal; do
        kube -n "$namespace" delete networkpolicy -l finapp/experiment-fault=true --ignore-not-found >> "$EXP_ART/faults.log" 2>&1
    done
    # P16-TSK-021's kinds: every marked rule in every replica's namespace, the holder told to stop and every held or locking session ended,
    # every ballast removed.
    for pod in $(experiments_fleet | awk -F'|' 'NF >= 5 && $6 == "" {print $1}'); do
        if experiments_netns "$pod"; then experiments_blackhole_clear >> "$EXP_ART/faults.log" 2>&1; fi
    done
    experiments_holder_stop >> "$EXP_ART/faults.log" 2>&1
    experiments_sessions_ended "$EXPERIMENTS_LOCKER" >> "$EXP_ART/faults.log" 2>&1
    for node in $(experiments_nodes); do
        dock exec "$node" rm -f "$EXPERIMENTS_LIMITED/$EXPERIMENTS_BALLAST" >> "$EXP_ART/faults.log" 2>&1
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
        log "every fault removed and none left on: the proxies enabled with no toxic, no hold flag, no fault policy, no paused node, every scaled Deployment back, no black-hole rule, no held or locking session, no ballast"
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
# P16-TSK-021's faults: a replica's own network namespace, the database's slots, a table's lock, a full volume.
# ---------------------------------------------------------------------------------------------------

# experiments_netns <pod>: the node a replica runs on and its application container's process, whose network namespace is the pod's.
# Sets NS_NODE and NS_PID; 1 with EXP_REASON when the pod has none, or its node is not one of this cluster's (the check E01's kill makes).
experiments_netns() {
    NS_NODE=$(kube -n finapp get pod "$1" -o jsonpath='{.spec.nodeName}' 2>/dev/null || true)
    ns_container=$(kube -n finapp get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="app")].containerID}' 2>/dev/null | sed 's#^containerd://##')
    [ -n "$NS_NODE" ] && [ -n "$ns_container" ] || { EXP_REASON="the pod $1 has no node or no running container"; return 1; }
    [ "$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$NS_NODE" 2>/dev/null)" = "$CLUSTER" ] \
        || { EXP_REASON="$NS_NODE is not a node of $CLUSTER"; return 1; }
    NS_PID=$(dock exec "$NS_NODE" crictl inspect --output go-template --template '{{.info.pid}}' "$ns_container" 2>/dev/null)
    case $NS_PID in '' | 0 | *[!0-9]*) EXP_REASON="the application process of $1 could not be found on $NS_NODE"; return 1 ;; esac
    return 0
}

# experiments_in_netns <command>...: one of the node's own commands, in the namespace experiments_netns found.
experiments_in_netns() {
    dock exec "$NS_NODE" nsenter -t "$NS_PID" -n "$@"
}

# experiments_blackhole_rules <-I|-C> <address> <port>: the black hole's two DROP rules, what the replica sends to the hop and what the hop
# sends back, each marked so that what is left can be read and removed without a record. Nothing is refused or reset: it is dropped.
experiments_blackhole_rules() {
    experiments_in_netns iptables "$1" OUTPUT -p tcp -d "$2" --dport "$3" -m comment --comment "$EXPERIMENTS_FAULT_COMMENT" -j DROP \
        && experiments_in_netns iptables "$1" INPUT -p tcp -s "$2" --sport "$3" -m comment --comment "$EXPERIMENTS_FAULT_COMMENT" -j DROP
}

# Every marked rule of the namespace experiments_netns found, removed: the registry's entry and the blanket reset both come here.
experiments_blackhole_clear() {
    experiments_in_netns iptables -S 2>/dev/null | grep -- "--comment $EXPERIMENTS_FAULT_COMMENT" | sed 's/^-A /-D /' | while read -r rule; do
        # shellcheck disable=SC2086
        experiments_in_netns iptables $rule
    done
    [ "$(experiments_in_netns iptables -S 2>/dev/null | grep -c -- "--comment $EXPERIMENTS_FAULT_COMMENT" || true)" = 0 ]
}

# The holder (E07). Over the test-only server's own socket (trust there), as a role with no superuser attribute, to the maintenance
# database, every half second it opens a session for each slot an ordinary role may still take, each sleeping to the holder's bound, until
# its stop file or that bound. PostgreSQL's superuser-reserved slots stay free: they are what an operator's break-glass connection takes.
# Every session is a child of the exec's own shell, which waits for them: the postmaster is the container's first process, and a process
# the run left behind would be reaped by it.
experiments_holder_start() {
    psql_query -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finapp_experiment_holder') THEN CREATE ROLE finapp_experiment_holder LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION; END IF; END \$\$" \
        >> "$EXP_ART/holder.log" 2>&1 || return 1
    kube -n finapp exec finapp-postgres-0 -c postgres -- rm -f /tmp/finapp-experiment-holder.stop >> "$EXP_ART/holder.log" 2>&1 || return 1
    kube -n finapp exec finapp-postgres-0 -c postgres -- sh -c '
        end=$(( $(date +%s) + $1 ))
        while [ ! -f /tmp/finapp-experiment-holder.stop ] && [ "$(date +%s)" -lt "$end" ]; do
            free=$(psql -q -X -A -t -U finapp -d postgres -c "SELECT current_setting('\''max_connections'\'')::int - current_setting('\''superuser_reserved_connections'\'')::int - current_setting('\''reserved_connections'\'')::int - count(*) FROM pg_stat_activity WHERE backend_type = '\''client backend'\'' AND pid <> pg_backend_pid()" 2>/dev/null)
            case $free in "" | *[!0-9]*) free=0 ;; esac
            while [ "$free" -gt 0 ]; do
                PGAPPNAME=finapp-experiment-holder psql -q -X -A -t -U finapp_experiment_holder -d postgres -c "SELECT pg_sleep($(( end - $(date +%s) )))" >/dev/null 2>&1 &
                free=$((free - 1))
            done
            sleep 0.5
        done
        wait' finapp-experiment-holder "$1" >> "$EXP_ART/holder.log" 2>&1 &
    EXPERIMENTS_HOLDER_PID=$!
    BACKGROUND="$BACKGROUND $EXPERIMENTS_HOLDER_PID"
}

# The holder stopped: its stop file first, so it opens nothing more, then every session it holds ended, read until none is left.
experiments_holder_stop() {
    kube -n finapp exec finapp-postgres-0 -c postgres -- touch /tmp/finapp-experiment-holder.stop
    [ -z "$EXPERIMENTS_HOLDER_PID" ] || kill "$EXPERIMENTS_HOLDER_PID" 2>/dev/null
    experiments_sessions_ended "$EXPERIMENTS_HOLDER"
}

# experiments_sessions_ended <application name>: every session of that name ended, read again until none is left (15 s at most).
experiments_sessions_ended() {
    tries=0
    while [ "$tries" -lt 15 ]; do
        ended=$(psql_query -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = '$1' AND pid <> pg_backend_pid()" 2>/dev/null | tr -d '[:space:]')
        [ "$ended" = 0 ] && return 0
        tries=$((tries + 1))
        sleep 1
    done
    return 1
}

# experiments_lock_start <table> <seconds>: the run's superuser holds an EXCLUSIVE lock on the table for at most that long, in a session of
# its own named for the lock, a child of the exec's shell; 0 once the lock is read granted.
experiments_lock_start() {
    kube -n finapp exec finapp-postgres-0 -c postgres -- sh -c \
        'PGAPPNAME=finapp-experiment-lock exec psql -q -X -A -t -U finapp -d finapp -v ON_ERROR_STOP=1 -c "BEGIN" -c "LOCK TABLE $1 IN EXCLUSIVE MODE" -c "SELECT pg_sleep($2)" -c "ROLLBACK"' \
        finapp-experiment-lock "$1" "$2" >> "$EXP_ART/lock.log" 2>&1 &
    BACKGROUND="$BACKGROUND $!"
    tries=0
    while [ "$tries" -lt 30 ]; do
        granted=$(psql_query -c "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE a.application_name = '$EXPERIMENTS_LOCKER' AND l.locktype = 'relation' AND l.relation = '$1'::regclass AND l.mode = 'ExclusiveLock' AND l.granted" 2>/dev/null | tr -d '[:space:]')
        [ "$granted" = 1 ] && return 0
        tries=$((tries + 1))
        sleep 1
    done
    return 1
}

# experiments_ballast <node>: the limited filesystem on that node filled - all but a MiB at once, then the rest by writing until the
# filesystem refuses - by a file at its root, beside the data directory and never in it. Prints what is left free.
experiments_ballast() {
    dock exec "$1" sh -c '
        grep -qs " $1 " /proc/mounts || { echo "$1 is not a mounted filesystem on this node"; exit 1; }
        avail=$(df -PB1 "$1" | awk "NR == 2 {print \$4}")
        size=$(( avail - 1048576 ))
        [ "$size" -gt 0 ] || { echo "only $avail bytes free on $1"; exit 1; }
        fallocate -l "$size" "$1/$2" || exit 1
        dd if=/dev/zero bs=4096 count=1048576 >> "$1/$2" 2>/dev/null
        sync
        df -PB1 "$1" | awk "NR == 2 {print \"free \" \$4}"' ballast "$EXPERIMENTS_LIMITED" "$EXPERIMENTS_BALLAST"
}

experiments_rand() {
    od -An -N8 -tx1 /dev/urandom | tr -d ' \n'
}

# The correctness pages every 15 s while a fault is on: a page sets the experiment's stop (ADR-0127 decision 6). 1 once one fired.
experiments_pages_tick() {
    now=$(date +%s)
    [ $((now - ${EXP_PAGES_AT:-0})) -ge 15 ] || return 0
    EXP_PAGES_AT=$now
    firing=$(experiments_pages_firing)
    if [ -n "$firing" ]; then
        EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }a correctness page during the experiment: $firing"
        log "$EXP_ID STOP: $EXP_STOPPED"
        return 1
    fi
    printf '%s\tnone\t%s\n' "$(date -u +%H:%M:%S)" "$(experiments_alerts_firing)" >> "$EXP_DIR/pages.tsv"
    return 0
}

# Every alert the cluster's Prometheus reads firing, its names, or UNREADABLE: what told, recorded beside the pages.
experiments_alerts_firing() {
    answer=$(experiments_prometheus "/api/v1/query?query=$(urlencode 'ALERTS{alertstate="firing"}')" 2>/dev/null || true)
    case $answer in *'"status":"success"'*) ;; *) echo UNREADABLE; return 0 ;; esac
    printf '%s' "$answer" | grep -o '"alertname":"[^"]*"' | sed 's/.*:"\(.*\)"/\1/' | sort -u | tr '\n' ' '
}

# experiments_value <query>: the value of an instant query's first sample, or nothing.
experiments_value() {
    experiments_prometheus "/api/v1/query?query=$(urlencode "$1")" 2>/dev/null | sed -n 's/.*"value":\[[0-9.e+]*,"\([^"]*\)"\].*/\1/p' | head -1
}

# experiments_edge_answers <since epoch> <file>: every answer of the public edge since then, one line each: when it started (the edge's
# own UTC, which sorts as text), its status, the application's (0: none), and how long it took in nanoseconds.
experiments_edge_answers() {
    since=$(date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ)
    : > "$2"
    for pod in $(kube -n finapp-edge get pods -l app.kubernetes.io/name=finapp-edge-public -o jsonpath='{range .items[*]}{.metadata.name}|{.spec.containers[0].name}{"\n"}{end}' | awk -F'|' '$2 == "edge" {print $1}'); do
        kube -n finapp-edge logs "$pod" -c edge --since-time="$since" 2>/dev/null \
            | sed -n 's/.*"DownstreamStatus":\([0-9]*\).*"Duration":\([0-9]*\).*"OriginStatus":\([0-9]*\).*"StartUTC":"\([^"]*\)".*/\4\t\1\t\3\t\2/p' >> "$2"
    done
}

# experiments_percentiles <answers file> <from epoch> <to epoch>: "answers p50 p99 failed" over the answers that started in [from, to),
# the times in milliseconds, a failure an answer of 500 or more.
experiments_percentiles() {
    from=$(date -u -d "@$2" +%Y-%m-%dT%H:%M:%S)
    to=$(date -u -d "@$3" +%Y-%m-%dT%H:%M:%S)
    awk -F'\t' -v from="$from" -v to="$to" '$1 >= from && $1 < to {print int($4 / 1000000), ($2 >= 500 ? 1 : 0)}' "$1" | sort -n | awk '
        {d[NR] = $1; f += $2}
        END {
            if (NR == 0) { print "0 - - 0"; exit }
            i50 = int(NR * 0.50 + 0.5); if (i50 < 1) i50 = 1
            i99 = int(NR * 0.99); if (i99 < NR * 0.99) i99++; if (i99 > NR) i99 = NR; if (i99 < 1) i99 = 1
            print NR, d[i50], d[i99], f + 0
        }'
}

# experiments_socket_sampler <dir> <node> <pid> <address> <port>: every 5 s, the established TCP sockets of that namespace to the hop,
# into <dir>/sockets.tsv - each with its local address, its unacknowledged bytes, and whether its retransmission timer runs (a send
# nobody answered: a thread waiting on the black hole) - and each reading's time into <dir>/sockets-read.tsv, until <dir>/sockets-stop.
experiments_socket_sampler() {
    set +e
    printf 'epoch\tlocal\tsend_q\ttimer\n' > "$1/sockets.tsv"
    printf 'epoch\tread\n' > "$1/sockets-read.tsv"
    while [ ! -f "$1/sockets-stop" ]; do
        epoch=$(date +%s)
        if listing=$(dock exec "$2" nsenter -t "$3" -n ss -tnoH state established dst "$4:$5" 2>/dev/null); then
            printf '%s\n' "$listing" | awk -v e="$epoch" 'NF >= 4 {t = ($0 ~ /timer:\(on,/) ? "on" : "off"; print e "\t" $3 "\t" $2 "\t" t}' >> "$1/sockets.tsv"
            printf '%s\tok\n' "$epoch" >> "$1/sockets-read.tsv"
        else
            printf '%s\tunreadable\n' "$epoch" >> "$1/sockets-read.tsv"
        fi
        sleep 5
    done
}

# experiments_transfer <address> <key> <reference> <label>: 0.01 EUR from the rehearsal's first customer to its second, sent straight to
# one replica from the edge's probe pod, as E01's payout is; the bearer on stdin, never a command line; the status and body kept.
experiments_transfer() {
    printf '%s\n' "$EXP_CUSTOMER" | kube -n finapp-edge exec -i np-edge -c probe -- /bin/sh -c '
        IFS= read -r bearer
        curl -s -w "\nstatus=%{http_code}\n" --max-time 240 -X POST -H "Authorization: Bearer $bearer" -H "Content-Type: application/json" \
            -H "Idempotency-Key: $1" -d "{\"sourceAccountId\":\"$3\",\"destinationAccountId\":\"$4\",\"amount\":\"0.01\",\"currency\":\"EUR\",\"reference\":\"$2\"}" \
            "http://$5:8080/v1/transfers"
        echo "curl=$?"' probe "$2" "$3" "$EXP_FROM" "$EXP_TO" "$1" > "$EXP_DIR/transfer-$4.txt" 2>&1
}

# experiments_payout <address> <key> <label>: the merchant's payout of 0.10 EUR under that key, sent straight to one replica (E01's, with
# its key and label its caller's).
experiments_payout() {
    printf '%s\n' "$EXP_MERCHANT" | kube -n finapp-edge exec -i np-edge -c probe -- /bin/sh -c '
        IFS= read -r bearer
        curl -s -w "\nstatus=%{http_code}\n" --max-time 90 -X POST -H "Authorization: Bearer $bearer" -H "Content-Type: application/json" \
            -H "Idempotency-Key: $1" -d "{\"amount\":\"0.10\",\"currency\":\"EUR\"}" "http://$2:8080/v1/merchant/payouts"
        echo "curl=$?"' probe "$2" "$1" > "$EXP_DIR/payout-$3.txt" 2>&1
}

# experiments_status <file>: the HTTP status a request's file holds, or none.
experiments_status() {
    sed -n 's/^status=//p' "$1" 2>/dev/null | tail -1
}

# experiments_state <name>: one entry of the client's own state, by its name, its value unescaped as java.util.Properties writes it (a
# backslash before =, :, # or !, and a doubled backslash).
experiments_state() {
    awk -F= -v name="$1" '$1 == name {sub(/^[^=]*=/, ""); gsub(/\\\\/, "\001"); gsub(/\\/, ""); gsub(/\001/, "\\"); print; exit}' "$CLIENT/state.properties"
}

# experiments_participants: the rehearsal's first two customers' accounts, the first's session and the merchant's key, from the client's
# own state; they stay in the harness's variables and travel on stdin, never on a command line.
experiments_participants() {
    EXP_FROM=$(experiments_state customer.0.account)
    EXP_TO=$(experiments_state customer.1.account)
    EXP_CUSTOMER=$(experiments_state customer.0.token)
    EXP_MERCHANT=$(experiments_state merchant.key)
    [ -n "$EXP_FROM" ] && [ -n "$EXP_TO" ] && [ -n "$EXP_CUSTOMER" ] && [ -n "$EXP_MERCHANT" ] \
        || cannot "$EXP_ID: the client's state names no two customers' accounts, no customer's session or no merchant key"
}

# experiments_a_ready_replica [<pod to leave out>]: "<pod> <address>" of a ready, running replica, the first by name.
experiments_a_ready_replica() {
    experiments_fleet | awk -F'|' -v out="${1:-}" 'NF >= 5 && $2 == "True" && $6 == "" && $1 != out {print $1, $5}' | sort | head -1
}

# experiments_unready_others <victim> <since epoch>: the first reading of the fleet sampler since then in which a replica other than the
# victim was not ready, "<pod>@<time>", or nothing.
experiments_unready_others() {
    awk -F'\t' -v v="$1" -v at="$2" 'NR > 1 && $1 >= at && $3 != v && $4 != "True" {print $3 "@" $2; exit}' "$EXP_DIR/fleet.tsv" 2>/dev/null
}

# The workload's end for an experiment: stopped, nothing unresolved, every flow seen completing work, its summary recorded.
experiments_workload_end() {
    stop_workload "$1"
    [ "$(workload_unresolved "$1")" = 0 ] || fail "$EXP_ID: $(workload_unresolved "$1") requests stayed unresolved: see workload-$1.tsv"
    assert_flows_ran "$1"
    experiments_record workload "$(cat "$CLIENT/workload-$1.summary")"
}

# experiments_start_workload <label> <retry window seconds>: the rehearsal's workload, retrying by key, its window widened for a fault that
# keeps every replica from the database for minutes (a payment caught mid-call waits the sweep's dispatched age).
experiments_start_workload() {
    WORKLOAD_RETRY_SECONDS=$2
    start_workload "$1" retry
    unset WORKLOAD_RETRY_SECONDS
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
    experiments_fault_on store-hold hold-reads
    experiments_fault_on partition finapp experiments-crash-partition "$WORK/fault-policy.yaml"
    experiments_fault_on scaled finapp-rehearsal finapp-simulator 0 1
    # P16-TSK-021's kinds too: a replica black-holed from the database, the holder, a lock, and a ballast where E24's volume is mounted.
    crash_replica=$(experiments_a_ready_replica)
    [ -n "$crash_replica" ] || cannot "PROBE $PROBE: no ready replica to black-hole"
    experiments_fault_on blackhole "${crash_replica%% *}" "$(kube -n finapp-experiments get service finapp-toxiproxy -o jsonpath='{.spec.clusterIP}')" 5432
    experiments_fault_on held 300
    experiments_fault_on locked transfers.transfer 300
    crash_kinds="a black hole, the holder, a lock"
    for node in $(experiments_nodes); do
        if dock exec "$node" grep -qs " $EXPERIMENTS_LIMITED " /proc/mounts; then
            experiments_fault_on filled "$node"
            crash_kinds="$crash_kinds, a ballast on $node"
            break
        fi
    done
    experiments_faults_left > "$EXP_ART/faults-before-crash.txt" 2>&1 || true
    experiments_fault_on node-paused "$victim_node"
    echo "paused node $victim_node" >> "$EXP_ART/faults-before-crash.txt"
    log "PROBE $PROBE: every kind of fault on - a toxic, a proxy down, the simulator's hold and the archive store's, a partition, a scale to zero, $crash_kinds, a paused node ($victim_node) - and now the harness dies"
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

# ===================================================================================================
# P16-TSK-021: the network, DNS, connection and disk faults (OPERATIONS_RUNBOOK.md section 11.47). Each at three replicas under the
# rehearsal's workload, retrying by key; each ends in the census like every experiment.
# ===================================================================================================

experiments_alert_firing() {
    case " $(experiments_alerts_firing) " in *" $1 "*) return 0 ;; esac
    return 1
}

# ---------------------------------------------------------------------------------------------------
# E07: the database's connections exhausted - other clients hold the reserve and the pool's share.
# ---------------------------------------------------------------------------------------------------

E07_steady() {
    experiments_start_workload e07 900
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E07_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E07_SAMPLER"
    E07_RESTARTS=$(experiments_fleet | awk -F'|' 'NF >= 5 {print $1 "=" $3}' | sort | tr '\n' ' ')
    experiments_record slots "max_connections|superuser-reserved|reserved|the fleet's sessions: $(psql_query -c "SELECT current_setting('max_connections') || '|' || current_setting('superuser_reserved_connections') || '|' || current_setting('reserved_connections') || '|' || count(*) FILTER (WHERE application_name = 'finapp') FROM pg_stat_activity" | tr -d '[:space:]')"
}

# "<the fleet's sessions>|<the holder's>|<slots an ordinary role may still take>", read by the server's superuser on its own socket.
e07_slots() {
    psql_query -c "SELECT count(*) FILTER (WHERE application_name = 'finapp') || '|' || count(*) FILTER (WHERE application_name = '$EXPERIMENTS_HOLDER') || '|' || (current_setting('max_connections')::int - current_setting('superuser_reserved_connections')::int - current_setting('reserved_connections')::int - count(*) FILTER (WHERE backend_type = 'client backend' AND pid <> pg_backend_pid())) FROM pg_stat_activity" | tr -d '[:space:]'
}

E07_fault() {
    # The holder takes every slot an ordinary role may take. The database's proxy down, the fleet's sessions close and its pools cannot
    # race the holder for what they free; once nothing is left for them, the proxy comes back and the pools ask a full server.
    experiments_fault_on held 720
    experiments_fault_on proxy-down postgres
    deadline=$(( $(date +%s) + 90 ))
    taken=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        taken=$(e07_slots)
        case $taken in 0'|'*'|'0 | 0'|'*'|'-*) break ;; esac
        sleep 2
    done
    case $taken in
        0'|'*'|'0 | 0'|'*'|'-*) ;;
        *) cannot "E07: the holder did not take every ordinary slot with the fleet's sessions closed (the fleet's|the holder's|still free: $taken)" ;;
    esac
    experiments_fault_off proxy-down postgres
    E07_TAKEN_AT=$(date +%s)
    experiments_record fault "every ordinary slot held by $(printf '%s' "$taken" | cut -d'|' -f2) sessions of a role with no superuser attribute; the fleet's sessions closed by the database's proxy taken down, and the proxy back at $(date -u -d "@$E07_TAKEN_AT" +%H:%M:%SZ)"
}

E07_observe() {
    # The control: an ordinary role is refused now, so no slot it may take is free.
    refused=$(kube -n finapp exec finapp-postgres-0 -c postgres -- psql -q -X -A -t -U finapp_experiment_holder -d postgres -c 'SELECT 1' 2>&1 || true)
    case $refused in *reserved*) ;; *) cannot "E07: an ordinary role was not refused, so a slot was still free: $(printf '%s' "$refused" | tr '\n' ' ' | cut -c1-200)" ;; esac
    experiments_record "ordinary role" "refused: $(printf '%s' "$refused" | tr '\n' ' ' | cut -c1-200)"
    # The criterion: an operator's TLS session as the server's superuser, on a reserved slot; its password read inside the pod from the
    # container's own environment, never crossing to the harness.
    operator=$(kube -n finapp exec finapp-postgres-0 -c postgres -- sh -c 'PGPASSWORD=${POSTGRES_PASSWORD} exec psql "host=127.0.0.1 dbname=finapp user=finapp sslmode=require" -q -X -A -t -c "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"' 2>&1 || true)
    [ "$(printf '%s' "$operator" | tr -d '[:space:]')" = t ] \
        || fail "E07: an operator's TLS session as the superuser was not admitted while the slots were held: $(printf '%s' "$operator" | tr '\n' ' ' | cut -c1-200)"
    experiments_record operator "a TLS session as the server's superuser admitted on a reserved slot while every ordinary slot was held"
    # Until ConnectionPoolStarved fires (bound: the register's ten minutes), the pages watched.
    fired=""
    while [ "$(date +%s)" -lt $((E07_TAKEN_AT + 600)) ]; do
        experiments_pages_tick || break
        if experiments_alert_firing ConnectionPoolStarved; then
            fired=$(( $(date +%s) - E07_TAKEN_AT ))
            break
        fi
        sleep 5
    done
    [ -z "$EXP_STOPPED" ] || return 0
    if [ -z "$fired" ]; then
        EXP_STOPPED="ConnectionPoolStarved did not fire within 600 s of the take: the starved pools went unseen"
        log "E07 STOP: $EXP_STOPPED"
        return 0
    fi
    sleep 30
    experiments_record starved "ConnectionPoolStarved firing $fired s after the take"
    experiments_record "refused at the pool" "$(experiments_value 'sum(increase(hikaricp_connections_timeout_total{pool!="finapp-reporting"}[10m]))') borrows timed out at the pool's 5 s across the fleet in the last ten minutes"
    experiments_record readiness "replicas read unready after the take: $(awk -F'\t' -v at="$E07_TAKEN_AT" 'NR > 1 && $1 >= at && $4 != "True" {print $3}' "$EXP_DIR/fleet.tsv" | sort -u | tr '\n' ' ')"
    pass "E07: every ordinary slot held, an operator's superuser session admitted, ConnectionPoolStarved firing $fired s after the take"
}

E07_recover() {
    ready_three 600 || fail "E07: the fleet did not return to three ready replicas once the slots were free"
    restarts=$(experiments_fleet | awk -F'|' 'NF >= 5 {print $1 "=" $3}' | sort | tr '\n' ' ')
    [ "$restarts" = "$E07_RESTARTS" ] || fail "E07: a replica restarted or was replaced while the database refused it ($E07_RESTARTS before, $restarts now): liveness must not read the database"
    psql_query -c "DROP ROLE IF EXISTS finapp_experiment_holder" >> "$EXP_ART/holder.log" 2>&1 || true
    sleep 10
    touch "$EXP_DIR/sampler-stop"
    wait "$E07_SAMPLER" 2>/dev/null || true
    experiments_workload_end e07
    pass "E07: the fleet ready again with no replica restarted, nothing unresolved"
}

# ---------------------------------------------------------------------------------------------------
# E11: object storage unavailable. The application uses none; the store is the WAL archive's (P16-TSK-024's), named by the run.
# ---------------------------------------------------------------------------------------------------

# "<archived>|<failed>|<the last archive after the last failure: t or f>", from the primary's archiver.
e11_archiver() {
    psql_query -c "SELECT archived_count || '|' || failed_count || '|' || (coalesce(last_archived_time, 'epoch') > coalesce(last_failed_time, 'epoch')) FROM pg_stat_archiver" | tr -d '[:space:]'
}

E11_steady() {
    # The application's half, read from the cluster: no key of its environment names an object store (keys only, never a value).
    rendered=$(sed -n 's/^ *name: \(finapp-app-config-[a-z0-9]*\)$/\1/p' "$A/Deployment.finapp-app.yaml" | head -1)
    keys=$( { kube -n finapp get configmap "$rendered" -o go-template='{{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}'
              kube -n finapp get secret finapp-app-secrets -o go-template='{{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}'; } 2>/dev/null)
    [ "$(printf '%s\n' "$keys" | grep -c .)" -ge 20 ] || cannot "E11: the application's settings could not be read"
    stores=$(printf '%s\n' "$keys" | grep -iE 'S3|BUCKET|MINIO|OBJECT_?STORE' | tr '\n' ' ')
    [ -z "$stores" ] || fail "E11: the application's environment names an object store: $stores"
    experiments_record application "none of its $(printf '%s\n' "$keys" | grep -c .) settings names an object store"
    # The infrastructure's half needs the store itself.
    [ -n "${REHEARSAL_E11_STORE:-}" ] || cannot "E11: no local object store is named (REHEARSAL_E11_STORE=<namespace>/<deployment>): the run's cluster holds none until the WAL archive's store exists"
    E11_NS=${REHEARSAL_E11_STORE%%/*}
    E11_DEPLOYMENT=${REHEARSAL_E11_STORE#*/}
    case $E11_NS in finapp | finapp-rehearsal | finapp-experiments) ;; *) fail "the safety boundary refused E11's store in $E11_NS: only the run's own namespaces" ;; esac
    E11_REPLICAS=$(kube -n "$E11_NS" get deployment "$E11_DEPLOYMENT" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
    [ "${E11_REPLICAS:-0}" -ge 1 ] || cannot "E11: the store $REHEARSAL_E11_STORE has no replica to stop"
    [ "$(psql_query -c 'SHOW archive_mode' | tr -d '[:space:]')" = on ] || cannot "E11: the primary archives nothing (archive_mode off), so the store's loss could not be felt"
    experiments_start_workload e11 240
    sleep 8
    E11_BEFORE=$(e11_archiver)
    experiments_record "archiver before" "archived|failed|last archive after last failure: $E11_BEFORE"
}

E11_fault() {
    experiments_fault_on scaled "$E11_NS" "$E11_DEPLOYMENT" 0 "$E11_REPLICAS"
    E11_AT=$(date +%s)
    experiments_record fault "the object store $REHEARSAL_E11_STORE scaled to zero at $(date -u -d "@$E11_AT" +%H:%M:%SZ)"
}

E11_observe() {
    switched=0
    printf 'time\tarchived|failed|caught up\n' > "$EXP_DIR/archiver.tsv"
    while [ "$(date +%s)" -lt $((E11_AT + ${E11_SECONDS:-300})) ]; do
        experiments_pages_tick || return 0
        # A segment closed each minute, so the archiver has something to send while the store is away.
        if [ $(( $(date +%s) - switched )) -ge 60 ]; then
            psql_query -c "SELECT pg_switch_wal()" >/dev/null 2>&1 || true
            switched=$(date +%s)
        fi
        printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$(e11_archiver)" >> "$EXP_DIR/archiver.tsv"
        sleep 5
    done
    E11_DURING=$(e11_archiver)
    failed_before=$(printf '%s' "$E11_BEFORE" | cut -d'|' -f2)
    failed_during=$(printf '%s' "$E11_DURING" | cut -d'|' -f2)
    [ "${failed_during:-0}" -gt "${failed_before:-0}" ] || cannot "E11: the archiver never failed while the store was away, so its loss was not felt"
    experiments_record "archiver while away" "failures $failed_before before, $failed_during at the end: the backup step failed and said so"
    experiments_edge_answers "$E11_AT" "$EXP_DIR/edge-answers.tsv"
    failed=$(awk -F'\t' '$2 >= 500' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    served=$(awk -F'\t' '$2 < 500 && $3 != 0' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    [ "$served" -ge 10 ] || fail "E11: the edge served $served answers while the store was away, too few to show its lines were read"
    [ "$failed" = 0 ] || fail "E11: the application answered $failed failures while only the object store was away"
    experiments_record application "$served answers served and none failed at the edge while the store was away"
}

E11_recover() {
    # The store back at the stop: the archiver retries by itself and catches up.
    deadline=$(( $(date +%s) + 600 ))
    caught=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        now_read=$(e11_archiver)
        if [ "$(printf '%s' "$now_read" | cut -d'|' -f1)" -gt "$(printf '%s' "$E11_DURING" | cut -d'|' -f1)" ] && [ "${now_read##*|}" = t ]; then
            caught=$now_read
            break
        fi
        psql_query -c "SELECT pg_switch_wal()" >/dev/null 2>&1 || true
        sleep 10
    done
    [ -n "$caught" ] || fail "E11: the archive did not catch up within 600 s of the store's return: $(e11_archiver)"
    experiments_record "archiver after" "archived|failed|caught up: $caught"
    experiments_workload_end e11
    pass "E11: the application untouched while the store was away, the archiver failing and saying so, and catching up on its return"
}

# ---------------------------------------------------------------------------------------------------
# E12: latency on the database hop - 50, 200 and 1000 ms on every answer.
# ---------------------------------------------------------------------------------------------------

E12_steady() {
    experiments_start_workload e12 240
    sleep 8
    E12_FROM=$(date +%s)
    sleep "${E12_BASELINE_SECONDS:-60}"
    printf 'step\tfrom\tto\n' > "$EXP_DIR/steps.tsv"
    printf 'base\t%s\t%s\n' "$E12_FROM" "$(date +%s)" >> "$EXP_DIR/steps.tsv"
    printf 'time\tstep\tpending\tactive\n' > "$EXP_DIR/pool.tsv"
    E12_CURRENT=""
}

# e12_latency <ms>: the database hop's answers delayed by that much, in place of the step before.
e12_latency() {
    [ -z "$E12_CURRENT" ] || experiments_fault_off toxic postgres "e12-latency-$E12_CURRENT" latency "latency=$E12_CURRENT"
    experiments_fault_on toxic postgres "e12-latency-$1" latency "latency=$1"
    E12_CURRENT=$1
    E12_STEP_AT=$(date +%s)
    experiments_record fault "+$1 ms on every answer of the database hop from $(date -u -d "@$E12_STEP_AT" +%H:%M:%SZ)"
}

E12_fault() {
    e12_latency 50
}

E12_observe() {
    for ms in 50 200 1000; do
        [ "$ms" = "$E12_CURRENT" ] || e12_latency "$ms"
        while [ "$(date +%s)" -lt $((E12_STEP_AT + ${E12_STEP_SECONDS:-180})) ]; do
            experiments_pages_tick || return 0
            printf '%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$ms" "$(experiments_value 'max(hikaricp_connections_pending{pool!="finapp-reporting"})')" \
                "$(experiments_value 'max(hikaricp_connections_active{pool!="finapp-reporting"})')" >> "$EXP_DIR/pool.tsv"
            # The stop: a p99 past 30 s over what the edge has answered of this step so far.
            experiments_edge_answers "$E12_STEP_AT" "$EXP_DIR/step-answers.tsv"
            set -- $(experiments_percentiles "$EXP_DIR/step-answers.tsv" "$E12_STEP_AT" "$(date +%s)")
            if [ "${3:--}" != - ] && [ "$3" -gt 30000 ]; then
                EXP_STOPPED="the p99 of the edge's answers at +$ms ms passed 30 s ($3 ms over $1 answers)"
                log "E12 STOP: $EXP_STOPPED"
                printf '%s\t%s\t%s\n' "$ms" "$E12_STEP_AT" "$(date +%s)" >> "$EXP_DIR/steps.tsv"
                return 0
            fi
            sleep 30
        done
        printf '%s\t%s\t%s\n' "$ms" "$E12_STEP_AT" "$(date +%s)" >> "$EXP_DIR/steps.tsv"
    done
}

E12_recover() {
    # The latency removed at the stop; the tail of the last step answered before the edge is read.
    sleep 40
    experiments_edge_answers "$E12_FROM" "$EXP_DIR/edge-answers.tsv"
    base_p50=""
    while IFS="$(printf '\t')" read -r name from to; do
        [ "$name" != step ] || continue
        set -- $(experiments_percentiles "$EXP_DIR/edge-answers.tsv" "$from" "$to")
        if [ "$name" = base ]; then
            base_p50=$2
            experiments_record "latency base" "$1 answers, p50 $2 ms, p99 $3 ms, $4 failed"
            continue
        fi
        pool=$(awk -F'\t' -v s="$name" '$2 == s {if ($3 + 0 > p) p = $3 + 0; if ($4 + 0 > a) a = $4 + 0} END {print p + 0 " pending, " a + 0 " active"}' "$EXP_DIR/pool.tsv")
        explained="-"
        case $base_p50$2 in *-*) ;; *) explained="the p50 $(( $2 - base_p50 )) ms above the base, about $(( ($2 - base_p50) / name )) round trips of +$name ms" ;; esac
        experiments_record "latency +$name ms" "$1 answers, p50 $2 ms, p99 $3 ms, $4 failed; the pools at their most $pool; $explained"
    done < "$EXP_DIR/steps.tsv"
    experiments_workload_end e12
    pass "E12: the latency steps recorded with the pools beside them, no step's p99 past 30 s"
}

# ---------------------------------------------------------------------------------------------------
# E13: resets on the database hop at 1% and 10% of its links, then the cut on a commit (the entry's probe): whole or absent, never half.
# ---------------------------------------------------------------------------------------------------

E13_steady() {
    experiments_start_workload e13 900
    sleep 8
    experiments_participants
    set -- $(experiments_a_ready_replica)
    E13_TARGET=${1:-}
    E13_TARGET_IP=${2:-}
    [ -n "$E13_TARGET_IP" ] || cannot "E13: no ready replica to send the cut transfers to"
    E13_P=""
    E13_MADE=0
}

# e13_resets <toxicity>: a reset on the database hop's links, both ways, each link drawn with that chance when the toxic meets it.
e13_resets() {
    experiments_fault_on toxic postgres e13-reset-down reset_peer timeout=0 "toxicity=$1"
    experiments_fault_on toxic postgres e13-reset-up reset_peer timeout=0 "toxicity=$1" stream=upstream
    E13_P=$1
}

e13_resets_off() {
    experiments_fault_off toxic postgres e13-reset-up reset_peer timeout=0 "toxicity=$E13_P" stream=upstream
    experiments_fault_off toxic postgres e13-reset-down reset_peer timeout=0 "toxicity=$E13_P"
}

E13_fault() {
    e13_resets 0.01
    experiments_record fault "reset_peer on the database hop both ways at toxicity 0.01 from $(date -u +%H:%M:%SZ)"
}

E13_observe() {
    for p in 0.01 0.10; do
        if [ "$p" != "$E13_P" ]; then
            e13_resets_off
            e13_resets "$p"
            experiments_record fault "reset_peer on the database hop both ways at toxicity $p from $(date -u +%H:%M:%SZ)"
        fi
        step_at=$(date +%s)
        made_before=$(experiments_value 'sum(hikaricp_connections_creation_seconds_count{pool!="finapp-reporting"})')
        while [ "$(date +%s)" -lt $((step_at + ${E13_STEP_SECONDS:-600})) ]; do
            experiments_pages_tick || return 0
            sleep 30
            # A link is drawn once, when the toxic meets it: the toxics put on again every 30 s, so every held link is drawn afresh.
            e13_resets_off
            e13_resets "$p"
        done
        made_after=$(experiments_value 'sum(hikaricp_connections_creation_seconds_count{pool!="finapp-reporting"})')
        made=$(awk -v a="${made_after:-0}" -v b="${made_before:-0}" 'BEGIN {print int(a - b)}')
        E13_MADE=$((E13_MADE + made))
        experiments_record "resets at $p" "$made connections made anew across the fleet's pools in $(( $(date +%s) - step_at )) s"
    done
    e13_resets_off
    [ "$E13_MADE" -ge 1 ] || cannot "E13: no connection was reset and made anew at either step, so the resets were never felt"
    experiments_workload_end e13
    ready_three 300 || fail "E13: the fleet did not return to three ready replicas after the resets"
    e13_cut_before
    e13_cut_on_commit
}

E13_recover() {
    ready_three 300 || fail "E13: the fleet did not return to three ready replicas after the cuts"
    pass "E13: the resets counted, a transfer cut inside its transaction left nothing, a transfer cut on its commit was whole, each retried by its key to one effect"
}

# e13_effect <reference> <key>: "<transfers>|<their journal entries>|<the key's claim>" as the database holds them, read on its own socket.
e13_effect() {
    psql_query -c "SELECT (SELECT count(*) FROM transfers.transfer WHERE reference = '$1') || '|' || (SELECT count(*) FROM ledger.journal_entry j JOIN transfers.transfer t ON t.journal_entry_id = j.id WHERE t.reference = '$1') || '|' || coalesce((SELECT string_agg(state, ',') FROM platform.idempotency_record WHERE scope = 'transfer.execute' AND idempotency_key = '$2'), 'none')" | tr -d '[:space:]'
}

# e13_retry <key> <reference> <label>: the same request sent again under its key until it is answered 200 or 201 (300 s at most); the
# transfer's id it answered in E13_RETRIED_ID.
e13_retry() {
    deadline=$(( $(date +%s) + 300 ))
    tries=0
    E13_RETRIED_ID=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        tries=$((tries + 1))
        experiments_transfer "$E13_TARGET_IP" "$1" "$2" "$3-retry-$tries" || true
        status=$(experiments_status "$EXP_DIR/transfer-$3-retry-$tries.txt")
        printf '%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$3" "$tries" "${status:-none}" >> "$EXP_DIR/retries.tsv"
        case $status in
            200 | 201)
                E13_RETRIED_ID=$(grep -o '"id":"[^"]*"' "$EXP_DIR/transfer-$3-retry-$tries.txt" | head -1 | sed 's/.*:"\(.*\)"/\1/')
                return 0 ;;
        esac
        sleep 5
    done
    return 1
}

# The cut inside a transaction: the transfer, once it has written (an xid of its own, its claim among what it wrote), waits on a lock the
# run holds on its table; the database's proxy is cut then, and the lock released into a closed connection. Nothing of it may be left.
e13_cut_before() {
    key="e13-before-$(experiments_rand)"
    ref="wl-e13-before-$(experiments_rand)"
    experiments_fault_on locked transfers.transfer 300
    experiments_fault_armed proxy-down postgres
    experiments_transfer "$E13_TARGET_IP" "$key" "$ref" before &
    sender=$!
    deadline=$(( $(date +%s) + 120 ))
    waiting=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        waiting=$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'finapp' AND wait_event_type = 'Lock' AND backend_xid IS NOT NULL AND query ILIKE '%transfers.transfer%'" | tr -d '[:space:]')
        [ "$waiting" = 1 ] && break
        sleep 1
    done
    [ "$waiting" = 1 ] || cannot "E13: the transfer sent to $E13_TARGET never waited inside its transaction on the run's lock (read ${waiting:-nothing}), so a cut would find nothing in flight"
    experiments_fault_fire proxy-down postgres
    cut_at=$(date -u +%H:%M:%SZ)
    experiments_fault_off locked transfers.transfer 300
    wait "$sender" 2>/dev/null || true
    status=$(experiments_status "$EXP_DIR/transfer-before.txt")
    case $status in 200 | 201) cannot "E13: the transfer cut inside its transaction was answered $status, so the cut did not land inside it" ;; esac
    deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'finapp' AND wait_event_type = 'Lock'" | tr -d '[:space:]')" = 0 ] && break
        sleep 1
    done
    effect=$(e13_effect "$ref" "$key")
    [ "$effect" = "0|0|none" ] || fail "E13: a transfer cut inside its transaction left part of itself: $effect (transfers|entries|claim) - half"
    experiments_record "cut inside the transaction" "the transfer waited on the run's lock with its transaction written, the database's proxy cut at $cut_at and the lock released: the client read ${status:-no answer} ($(grep -h '^curl=' "$EXP_DIR/transfer-before.txt")); absent: $effect"
    experiments_fault_off proxy-down postgres
    ready_three 300 || fail "E13: the fleet did not return to three ready replicas after the cut inside a transaction"
    e13_retry "$key" "$ref" before || fail "E13: the transfer cut inside its transaction was not answered by its key within 300 s"
    effect=$(e13_effect "$ref" "$key")
    [ "$effect" = "1|1|COMPLETED" ] || fail "E13: the transfer cut inside its transaction, retried by its key, did not take exactly one effect: $effect"
    EXPERIMENTS_TRANSFERS=$((EXPERIMENTS_TRANSFERS + 1))
    pass "E13: a transfer cut inside its transaction left nothing, and its retry by key took one effect"
}

# e13_watch <reference> <file>: on the database's own socket, never through the proxy, "committed" written the moment a transfer of that
# reference can be read - which, the transfer being one transaction, is the moment it committed - or "never" after about five minutes.
e13_watch() {
    kube -n finapp exec finapp-postgres-0 -c postgres -- sh -c '
        i=0
        while [ "$i" -lt 2400 ]; do
            if [ "$(psql -q -X -A -t -U finapp -d finapp -c "SELECT count(*) FROM transfers.transfer WHERE reference = '\''$1'\''")" = 1 ]; then
                echo committed
                exit 0
            fi
            i=$((i + 1))
            sleep 0.1
        done
        echo never' e13-watch "$1" > "$2" 2>&1
}

# The cut on a commit (the entry's probe). Every answer of the database hop held 1500 ms (under the pool's 2 s validation, so the pools
# keep their connections); the transfer's row read committed on the server's socket while its answer is still held; the database's proxy
# disabled at that moment, which closes every link and delivers nothing held (a reset toxic added then lets the held answer through
# first). The client must have failed and the transfer be whole: its row, its journal entry and its claim COMPLETED; its retry by key
# answers the stored result. An attempt whose answer escaped the cut is resolved by its key and counted, and another made: three at most.
e13_cut_on_commit() {
    attempt=0
    while [ "$attempt" -lt 3 ]; do
        attempt=$((attempt + 1))
        key="e13-commit-$(experiments_rand)"
        ref="wl-e13-commit-$(experiments_rand)"
        experiments_fault_on toxic postgres e13-hold latency latency=1500
        experiments_fault_armed proxy-down postgres
        : > "$EXP_DIR/commit-$attempt.watch"
        e13_watch "$ref" "$EXP_DIR/commit-$attempt.watch" &
        watcher=$!
        BACKGROUND="$BACKGROUND $watcher"
        experiments_transfer "$E13_TARGET_IP" "$key" "$ref" "commit-$attempt" &
        sender=$!
        deadline=$(( $(date +%s) + 240 ))
        seen=""
        while [ "$(date +%s)" -lt "$deadline" ]; do
            if grep -q '^committed$' "$EXP_DIR/commit-$attempt.watch"; then
                seen=1
                break
            fi
            grep -q '^never$' "$EXP_DIR/commit-$attempt.watch" && break
            kill -0 "$sender" 2>/dev/null || break
            sleep 0.05
        done
        cut_at=""
        if [ -n "$seen" ]; then
            experiments_fault_fire proxy-down postgres
            cut_at=$(date -u +%H:%M:%SZ)
        fi
        wait "$sender" 2>/dev/null || true
        kill "$watcher" 2>/dev/null
        effect=$(e13_effect "$ref" "$key")
        status=$(experiments_status "$EXP_DIR/transfer-commit-$attempt.txt")
        experiments_fault_off proxy-down postgres
        experiments_fault_off toxic postgres e13-hold latency latency=1500
        ready_three 300 || fail "E13: the fleet did not return to three ready replicas after the cut on a commit"
        e13_retry "$key" "$ref" "commit-$attempt" || fail "E13: attempt $attempt's transfer was not answered by its key within 300 s"
        after=$(e13_effect "$ref" "$key")
        [ "$after" = "1|1|COMPLETED" ] || fail "E13: attempt $attempt's transfer, retried by its key, did not take exactly one effect: $after"
        EXPERIMENTS_TRANSFERS=$((EXPERIMENTS_TRANSFERS + 1))
        case $status in
            200 | 201) ;;
            *)
                if [ -n "$seen" ]; then
                    [ "$effect" = "1|1|COMPLETED" ] || fail "E13: a transfer read committed and cut on its answer is not whole: $effect (transfers|entries|claim) - half"
                    committed=$(psql_query -c "SELECT id FROM transfers.transfer WHERE reference = '$ref'" | tr -d '[:space:]')
                    [ -n "$committed" ] && [ "$E13_RETRIED_ID" = "$committed" ] \
                        || fail "E13: the retry of a committed transfer answered '$E13_RETRIED_ID', not the committed '$committed': its stored result was not replayed"
                    experiments_record "cut on the commit" "attempt $attempt: its row read committed while its answer was held, the database's proxy cut at $cut_at; the client read ${status:-no answer} ($(grep -h '^curl=' "$EXP_DIR/transfer-commit-$attempt.txt")); whole: $effect; its retry by key answered the committed transfer"
                    pass "E13: a transfer cut on its commit was whole - its row, its entry, its claim COMPLETED - and its retry by key answered the stored result"
                    return 0
                fi ;;
        esac
        experiments_record "cut on the commit, attempt $attempt" "missed: ${seen:+its commit was read, but }the client read ${status:-no answer}; resolved by its key to one effect"
    done
    cannot "E13: in three attempts the cut never landed between a commit and its answer"
}

# ---------------------------------------------------------------------------------------------------
# E14: one replica black-holed from the database while the others serve; its held connections measured (THREAT_MODEL.md section 6 item
# 42 (h), the input to P16-TSK-022's socket timeout).
# ---------------------------------------------------------------------------------------------------

E14_steady() {
    experiments_start_workload e14 240
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E14_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E14_SAMPLER"
    set -- $(experiments_a_ready_replica)
    E14_VICTIM=${1:-}
    E14_VICTIM_IP=${2:-}
    [ -n "$E14_VICTIM_IP" ] || cannot "E14: no ready replica to cut"
    E14_PROXY=$(kube -n finapp-experiments get service finapp-toxiproxy -o jsonpath='{.spec.clusterIP}')
    case $E14_PROXY in *.*.*.*) ;; *) cannot "E14: the fault proxy's Service has no address" ;; esac
    E14_RESTARTS=$(kube -n finapp get pod "$E14_VICTIM" -o jsonpath='{.status.containerStatuses[0].restartCount}')
    experiments_netns "$E14_VICTIM" || cannot "E14: $EXP_REASON"
    E14_NODE=$NS_NODE
    E14_PID=$NS_PID
    E14_RETRIES2=$(experiments_in_netns sysctl -n net.ipv4.tcp_retries2 2>/dev/null | tr -d '[:space:]')
    experiments_record victim "$E14_VICTIM on $E14_NODE in $(experiments_zone_of_pod finapp "$E14_VICTIM"), $E14_RESTARTS restarts before; net.ipv4.tcp_retries2 ${E14_RETRIES2:-unread} in its namespace; the data source sets no socket timeout"
}

E14_fault() {
    experiments_socket_sampler "$EXP_DIR" "$E14_NODE" "$E14_PID" "$E14_PROXY" 5432 &
    E14_SOCKETS=$!
    BACKGROUND="$BACKGROUND $E14_SOCKETS"
    experiments_endpoint_sampler "$EXP_DIR" "$E14_VICTIM_IP" &
    E14_ENDPOINTS=$!
    BACKGROUND="$BACKGROUND $E14_ENDPOINTS"
    sleep 6
    experiments_fault_on blackhole "$E14_VICTIM" "$E14_PROXY" 5432
    E14_CUT_AT=$(date +%s)
    experiments_record fault "$E14_VICTIM black-holed from the database hop at $(date -u -d "@$E14_CUT_AT" +%H:%M:%SZ): every packet to and from it on that hop dropped in its own namespace"
}

E14_observe() {
    seen=""
    while [ "$(date +%s)" -lt $((E14_CUT_AT + ${E14_SECONDS:-900})) ]; do
        other=$(experiments_unready_others "$E14_VICTIM" "$E14_CUT_AT")
        if [ -n "$other" ]; then
            EXP_STOPPED="a second replica unready while one was cut: $other"
            log "E14 STOP: $EXP_STOPPED"
            break
        fi
        experiments_pages_tick || break
        last=$(awk -F'\t' 'NR > 1 && $2 == "ok" {e = $1} END {print e + 0}' "$EXP_DIR/sockets-read.tsv")
        held=$(awk -F'\t' -v e="$last" -v at="$E14_CUT_AT" 'NR > 1 && $1 == e && e > at && $4 == "on"' "$EXP_DIR/sockets.tsv" | grep -c . || true)
        [ "$held" = 0 ] || seen=1
        if [ -z "$seen" ] && [ $(( $(date +%s) - E14_CUT_AT )) -ge 60 ]; then
            cannot "E14: no connection of the cut replica was held by the black hole within 60 s of the cut, so nothing was measured"
        fi
        # Every held connection freed by the kernel: the measurement is complete before the partition's bound.
        if [ -n "$seen" ] && [ "$held" = 0 ] && [ "$last" -gt "$E14_CUT_AT" ]; then
            break
        fi
        sleep 5
    done
    E14_ENDED_AT=$(date +%s)
    [ -z "$EXP_STOPPED" ] || return 0
    # Per held connection (its local address): seconds from the cut until it was first read held, and until the first reading without
    # it - the kernel giving it up and its thread released - or "-" when it outlived the cut.
    awk -F'\t' -v at="$E14_CUT_AT" '
        NR == FNR { if (FNR > 1 && $2 == "ok") reads[++n] = $1; next }
        FNR > 1 && $1 > at { last[$2] = $1; if ($4 == "on" && !($2 in first)) first[$2] = $1 }
        END { for (s in first) { freed = "-"; for (i = 1; i <= n; i++) if (reads[i] > last[s]) { freed = reads[i] - at; break }
              print s "\t" first[s] - at "\t" freed } }' "$EXP_DIR/sockets-read.tsv" "$EXP_DIR/sockets.tsv" | sort > "$EXP_DIR/held.tsv"
    held_count=$(grep -c . "$EXP_DIR/held.tsv" || true)
    freed=$(awk -F'\t' '$3 != "-"' "$EXP_DIR/held.tsv" | grep -c . || true)
    longest=$(awk -F'\t' '$3 != "-" && $3 + 0 > m {m = $3 + 0} END {print m + 0}' "$EXP_DIR/held.tsv")
    experiments_record held "$held_count connections of the cut replica held by the black hole (sent, retransmitting, unanswered); $freed freed by the kernel, the last $longest s after the cut; $((held_count - freed)) still held when the cut ended $((E14_ENDED_AT - E14_CUT_AT)) s after it (net.ipv4.tcp_retries2 ${E14_RETRIES2:-unread}, no socket timeout): the measurement the socket timeout is decided from"
    out=$(awk -F'\t' -v k="$E14_CUT_AT" 'NR > 1 && $1 >= k && $2 != "true" {print $1 - k; exit}' "$EXP_DIR/endpoint.tsv")
    [ -n "$out" ] || fail "E14: the cut replica's endpoint never left the Service: its readiness did not tell"
    experiments_record "routed around" "the cut replica's endpoint read not ready $out s after the cut"
    experiments_edge_answers "$((E14_CUT_AT + out))" "$EXP_DIR/edge-answers.tsv"
    served=$(awk -F'\t' '$2 < 500 && $3 != 0' "$EXP_DIR/edge-answers.tsv" | grep -c . || true)
    [ "$served" -ge 10 ] || fail "E14: the others served $served answers once the cut replica was out: they did not carry the work"
    restarts=$(kube -n finapp get pod "$E14_VICTIM" -o jsonpath='{.status.containerStatuses[0].restartCount}')
    [ "$restarts" = "$E14_RESTARTS" ] || fail "E14: the cut replica restarted ($E14_RESTARTS before, $restarts now): liveness must not read the database"
    pass "E14: the cut replica out of the Service $out s after the cut and not restarted, the others serving ($served answers); $held_count connections held, $freed freed by the kernel"
}

E14_recover() {
    # The black hole removed at the stop: the replica ready again, and what became of the connections still held.
    deadline=$(( $(date +%s) + 600 ))
    back=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ "$(experiments_fleet | awk -F'|' -v v="$E14_VICTIM" '$1 == v {print $2}')" = True ]; then
            back=$(( $(date +%s) - E14_ENDED_AT ))
            break
        fi
        sleep 2
    done
    [ -n "$back" ] || fail "E14: the cut replica was not ready again within 600 s of the cut's removal"
    sleep 30
    touch "$EXP_DIR/sockets-stop" "$EXP_DIR/endpoint-stop"
    wait "$E14_SOCKETS" 2>/dev/null || true
    wait "$E14_ENDPOINTS" 2>/dev/null || true
    still=$(awk -F'\t' 'NR == FNR {held[$1] = 1; next} FNR > 1 {last = $1} FNR > 1 && ($2 in held) {seen[$2] = $1} END {n = 0; for (s in seen) if (seen[s] == last) n++; print n + 0}' \
        "$EXP_DIR/held.tsv" "$EXP_DIR/sockets.tsv")
    experiments_record returned "ready again $back s after the cut's removal; $still of the held connections still established at the last reading, 30 s after it"
    ready_three 300 || fail "E14: the fleet did not return to three ready replicas"
    touch "$EXP_DIR/sampler-stop"
    wait "$E14_SAMPLER" 2>/dev/null || true
    experiments_workload_end e14
}

# ---------------------------------------------------------------------------------------------------
# E15: the broker's hop, then the provider's, black-holed for every replica (Toxiproxy's timeout toxic at 0, both ways: every byte
# dropped while it is on, held links silent, new ones accepted and silent; its removal closes every link it held).
# ---------------------------------------------------------------------------------------------------

E15_steady() {
    experiments_start_workload e15 1200
    sleep 8
    E15_PENDING_BEFORE=$(pending_events)
    experiments_record "outbox before" "${E15_PENDING_BEFORE:-unread} rows unpublished"
}

E15_fault() {
    experiments_fault_on toxic kafka e15-broker-down timeout timeout=0
    experiments_fault_on toxic kafka e15-broker-up timeout timeout=0 stream=upstream
    E15_BROKER_AT=$(date +%s)
    printf 'time\tunpublished\toldest_seconds\n' > "$EXP_DIR/outbox.tsv"
    experiments_record fault "the broker's hop black-holed both ways for every replica at $(date -u -d "@$E15_BROKER_AT" +%H:%M:%SZ)"
}

E15_observe() {
    stalled=""
    most=0
    while [ "$(date +%s)" -lt $((E15_BROKER_AT + ${E15_BROKER_SECONDS:-1800})) ]; do
        experiments_pages_tick || return 0
        pending=$(pending_events)
        case $pending in '' | *[!0-9]*) pending=0 ;; esac
        printf '%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$pending" "$(experiments_value 'max(finapp_outbox_oldest_publishable_seconds)')" >> "$EXP_DIR/outbox.tsv"
        [ "$pending" -le "$most" ] || most=$pending
        if experiments_alert_firing OutboxPublicationStalled; then
            stalled=$(( $(date +%s) - E15_BROKER_AT ))
            break
        fi
        sleep 15
    done
    [ "$most" -gt "${E15_PENDING_BEFORE:-0}" ] || cannot "E15: the outbox held nothing back while the broker's hop was black-holed, so the cut was not felt"
    if [ -z "$stalled" ]; then
        EXP_STOPPED="OutboxPublicationStalled did not fire within the broker hop's bound"
        log "E15 STOP: $EXP_STOPPED"
        return 0
    fi
    experiments_edge_answers "$E15_BROKER_AT" "$EXP_DIR/edge-broker.tsv"
    served=$(awk -F'\t' '$2 >= 200 && $2 < 300 && $3 != 0' "$EXP_DIR/edge-broker.tsv" | grep -c . || true)
    [ "$served" -ge 10 ] || fail "E15: the platform served $served answers while the broker was cut off: money must still move"
    experiments_fault_off toxic kafka e15-broker-up timeout timeout=0 stream=upstream
    experiments_fault_off toxic kafka e15-broker-down timeout timeout=0
    back_at=$(date +%s)
    drained 900 || fail "E15: the outbox did not drain within 900 s of the broker's return: $(pending_events) rows unpublished"
    experiments_record broker "the outbox held up to $most rows; OutboxPublicationStalled firing $stalled s after the cut; $served answers served meanwhile; drained $(( $(date +%s) - back_at )) s after the hop returned"
    # The provider's hop.
    experiments_fault_on toxic simulator e15-provider-down timeout timeout=0
    experiments_fault_on toxic simulator e15-provider-up timeout timeout=0 stream=upstream
    E15_PROVIDER_AT=$(date +%s)
    experiments_record fault "the provider's hop black-holed both ways for every replica at $(date -u -d "@$E15_PROVIDER_AT" +%H:%M:%SZ)"
    unknown=0
    printf 'time\tpayments unknown age\tpayouts unknown age\n' > "$EXP_DIR/unknown.tsv"
    while [ "$(date +%s)" -lt $((E15_PROVIDER_AT + ${E15_PROVIDER_SECONDS:-300})) ]; do
        experiments_pages_tick || return 0
        payments=$(experiments_value 'max(finapp_payments_unknown_age)')
        payouts=$(experiments_value 'max(finapp_merchant_payout_unknown_age)')
        printf '%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$payments" "$payouts" >> "$EXP_DIR/unknown.tsv"
        case ${payments:-0}${payouts:-0} in *[1-9]*) unknown=1 ;; esac
        sleep 15
    done
    experiments_edge_answers "$E15_PROVIDER_AT" "$EXP_DIR/edge-provider.tsv"
    refused=$(awk -F'\t' '$3 == 503' "$EXP_DIR/edge-provider.tsv" | grep -c . || true)
    [ "$refused" -ge 1 ] || [ "$unknown" = 1 ] || cannot "E15: no provider call ended 503 or unknown while the provider's hop was black-holed, so the cut was not felt"
    experiments_record provider "while the provider's hop was black-holed: $refused answers 503 from the application; an unknown outcome on the gauges: $unknown"
}

E15_recover() {
    # Every unknown concluded once: the workload's own requests resolve by their keys, then the sweeps ask the provider again (a call
    # caught mid-flight waits the sweep's dispatched age, ten minutes).
    experiments_workload_end e15
    deadline=$(( $(date +%s) + 1200 ))
    open=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        open=$(psql_query -c "SELECT (SELECT count(*) FROM payments.payment_intent WHERE status IN ('REQUIRES_CONFIRMATION', 'PROCESSING')) || '|' || (SELECT count(*) FROM merchant.merchant_payout WHERE status NOT IN ('COMPLETED', 'FAILED'))" | tr -d '[:space:]')
        [ "$open" = "0|0" ] && break
        sleep 15
    done
    [ "$open" = "0|0" ] || fail "E15: payments and payouts still open 1200 s after the provider's hop returned (intents|payouts: $open)"
    experiments_record resolved "every payment intent and payout concluded after the provider's return"
    pass "E15: the outbox held and drained, money moved while the broker was away, the provider's calls refused or unknown and every one concluded once"
}

# ---------------------------------------------------------------------------------------------------
# E16: the cluster's resolver lost - CoreDNS scaled to zero for two minutes.
# ---------------------------------------------------------------------------------------------------

# The cluster resolver's ready endpoints, counted, or "unread" when the API did not answer: an unread list is never an empty one.
e16_resolver_ready() {
    slices=$(kube -n kube-system get endpointslices -l kubernetes.io/service-name=kube-dns -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' 2>/dev/null) \
        || { echo unread; return 0; }
    printf '%s\n' "$slices" | grep -c '^true$' || true
}

E16_steady() {
    experiments_start_workload e16 240
    sleep 8
    experiments_participants
    E16_DNS=$(kube -n kube-system get deployment coredns -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
    [ "${E16_DNS:-0}" -ge 1 ] || cannot "E16: the run's CoreDNS has no replica to stop"
    case $(e16_resolver_ready) in '' | 0 | unread | *[!0-9]*) cannot "E16: the cluster's resolver has no ready endpoint to take away" ;; esac
    experiments_record resolver "CoreDNS at $E16_DNS replicas; the application's three hops resolve by hostAliases to the proxy, which resolves its upstreams per connection through CoreDNS"
}

E16_fault() {
    experiments_fault_on scaled kube-system coredns 0 "$E16_DNS"
    E16_OUT_AT=$(date +%s)
    while [ "$(e16_resolver_ready)" != 0 ] && [ "$(date +%s)" -lt $((E16_OUT_AT + 60)) ]; do
        sleep 2
    done
    [ "$(e16_resolver_ready)" = 0 ] || cannot "E16: the cluster's resolver still had a ready endpoint 60 s after it was scaled to zero"
    experiments_record fault "CoreDNS scaled to zero at $(date -u -d "@$E16_OUT_AT" +%H:%M:%SZ), no ready endpoint $(( $(date +%s) - E16_OUT_AT )) s after"
}

E16_observe() {
    sleep 20
    # Held links carry on, and a new one is needed to meet the outage: the provider hop's links closed once, so its next calls open new
    # ones, each of which the proxy must resolve.
    experiments_fault_on proxy-down simulator
    sleep 2
    experiments_fault_off proxy-down simulator
    E16_STIMULUS_AT=$(date +%s)
    while [ "$(date +%s)" -lt $((E16_OUT_AT + ${E16_SECONDS:-120})) ]; do
        experiments_pages_tick || return 0
        sleep 5
    done
    kube -n finapp-experiments logs deploy/finapp-toxiproxy -c toxiproxy --since-time="$(date -u -d "@$E16_STIMULUS_AT" +%Y-%m-%dT%H:%M:%SZ)" > "$EXP_DIR/toxiproxy.log" 2>&1 || true
    lookups=$(grep '"message":"Unable to open connection to upstream"' "$EXP_DIR/toxiproxy.log" | grep -c '"error":"dial tcp: lookup' || true)
    [ "$lookups" -ge 1 ] || cannot "E16: no new connection met the resolver's outage (the proxy logged no failed lookup), so new connections were not shown failing"
    experiments_edge_answers "$E16_OUT_AT" "$EXP_DIR/edge-outage.tsv"
    served=$(awk -F'\t' '$2 < 500 && $3 != 0' "$EXP_DIR/edge-outage.tsv" | grep -c . || true)
    failed=$(awk -F'\t' '$2 >= 500' "$EXP_DIR/edge-outage.tsv" | grep -c . || true)
    experiments_record outage "$lookups new connections refused at the proxy for want of a lookup; $served answers served and $failed failed at the edge while the resolver was away; replicas unready at its end: $(experiments_fleet | awk -F'|' 'NF >= 5 && $2 != "True" {print $1}' | tr '\n' ' ')"
    experiments_fault_off scaled kube-system coredns 0 "$E16_DNS"
    E16_BACK_AT=$(date +%s)
    # Recovery: a provider call answered again - the merchant's payout under one key, sent until it is answered.
    key="e16-$(experiments_rand)"
    tries=0
    answered=""
    while [ "$(date +%s)" -lt $((E16_OUT_AT + 600)) ]; do
        tries=$((tries + 1))
        set -- $(experiments_a_ready_replica)
        [ -z "${2:-}" ] || experiments_payout "$2" "$key" "recovery-$tries" || true
        case $(experiments_status "$EXP_DIR/payout-recovery-$tries.txt") in
            200 | 201) answered=$(( $(date +%s) - E16_BACK_AT )); break ;;
        esac
        sleep 5
    done
    if [ -z "$answered" ]; then
        EXP_STOPPED="not recovered: no provider call answered within ten minutes of the outage's start"
        log "E16 STOP: $EXP_STOPPED"
        return 0
    fi
    EXPERIMENTS_PAYOUTS=$((EXPERIMENTS_PAYOUTS + 1))
    experiments_record recovered "CoreDNS back at $(date -u -d "@$E16_BACK_AT" +%H:%M:%SZ); a provider call answered $answered s after, on try $tries"
    pass "E16: held connections carried on, new ones failed at the proxy's lookup, a provider call answered $answered s after the resolver's return"
}

E16_recover() {
    ready_three 300 || fail "E16: the fleet did not return to three ready replicas"
    experiments_workload_end e16
}

# ---------------------------------------------------------------------------------------------------
# E24: the database's volume full - the primary's own 2 GiB filesystem filled from its node.
# ---------------------------------------------------------------------------------------------------

# e24_server_lines <since epoch>: the server's lines since then, its container's and, after a restart, its previous container's.
e24_server_lines() {
    since=$(date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ)
    { kube -n finapp logs finapp-postgres-0 -c postgres --since-time="$since"; kube -n finapp logs finapp-postgres-0 -c postgres --previous --since-time="$since"; } 2>/dev/null
}

E24_steady() {
    class=$(kube -n finapp get pvc data-finapp-postgres-0 -o jsonpath='{.spec.storageClassName}' 2>/dev/null || true)
    [ "$class" = finapp-experiments-limited ] || cannot "E24: the primary's claim is of class '$class', not E24's size-limited one: E24 must be named when the run makes its data tier"
    E24_NODE=$(kube -n finapp get pod finapp-postgres-0 -o jsonpath='{.spec.nodeName}')
    dock exec "$E24_NODE" grep -qs " $EXPERIMENTS_LIMITED " /proc/mounts || cannot "E24: no size-limited filesystem is mounted on the primary's node $E24_NODE"
    E24_SERVER_RESTARTS=$(kube -n finapp get pod finapp-postgres-0 -o jsonpath='{.status.containerStatuses[0].restartCount}')
    experiments_start_workload e24 900
    sleep 8
    experiments_sampler "$EXP_DIR" &
    E24_SAMPLER=$!
    BACKGROUND="$BACKGROUND $E24_SAMPLER"
    experiments_record volume "the primary on $E24_NODE, its filesystem $(dock exec "$E24_NODE" df -PB1 "$EXPERIMENTS_LIMITED" | awk 'NR == 2 {print $2 " bytes, " $4 " free"}')"
}

E24_fault() {
    experiments_fault_on filled "$E24_NODE"
    E24_FULL_AT=$(date +%s)
    free=$(dock exec "$E24_NODE" df -PB1 "$EXPERIMENTS_LIMITED" | awk 'NR == 2 {print $4}')
    [ "${free:-65537}" -le 65536 ] || cannot "E24: $free bytes still free after the ballast, so the volume was not filled"
    experiments_record fault "the primary's filesystem filled at $(date -u -d "@$E24_FULL_AT" +%H:%M:%SZ), $free bytes left"
}

E24_observe() {
    first=""
    # The stop's bound, the volume full five minutes: the first write it refuses is looked for until then.
    while [ "$(date +%s)" -lt $((E24_FULL_AT + 300)) ]; do
        experiments_pages_tick || return 0
        if [ "$(e24_server_lines "$E24_FULL_AT" | grep -c 'No space left on device' || true)" -ge 1 ]; then
            first=$(( $(date +%s) - E24_FULL_AT ))
            break
        fi
        sleep 5
    done
    [ -n "$first" ] || cannot "E24: nothing the database wrote met the full volume within 300 s, so the fault was not felt"
    hold_end=$(( $(date +%s) + ${E24_HOLD_SECONDS:-60} ))
    while [ "$(date +%s)" -lt "$hold_end" ]; do
        experiments_pages_tick || return 0
        sleep 5
    done
    e24_server_lines "$E24_FULL_AT" | grep -E 'No space left on device|PANIC|terminated by signal|database system is' > "$EXP_DIR/server-lines.txt" || true
    experiments_record refused "the first write refused for want of space $first s after the volume was full, held ${E24_HOLD_SECONDS:-60} s more; $(grep -c 'No space left on device' "$EXP_DIR/server-lines.txt" || true) such lines, $(grep -c PANIC "$EXP_DIR/server-lines.txt" || true) PANIC lines"
    experiments_record told "replicas read unready while it was full: $(awk -F'\t' -v at="$E24_FULL_AT" 'NR > 1 && $1 >= at && $4 != "True" {print $3}' "$EXP_DIR/fleet.tsv" | sort -u | tr '\n' ' '); alerts firing: $(cut -f3 "$EXP_DIR/pages.tsv" 2>/dev/null | tr ' ' '\n' | grep . | sort -u | tr '\n' ' ')"
}

E24_recover() {
    # The ballast removed at the stop: the server back on its own (a crash recovery if it stopped), then the fleet.
    deadline=$(( $(date +%s) + 600 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        kube -n finapp exec finapp-postgres-0 -c postgres -- pg_isready -q -h 127.0.0.1 -U finapp -d finapp 2>/dev/null && break
        sleep 5
    done
    kube -n finapp exec finapp-postgres-0 -c postgres -- pg_isready -q -h 127.0.0.1 -U finapp -d finapp 2>/dev/null \
        || fail "E24: the database did not accept connections within 600 s of the space's return"
    ready_three 900 || fail "E24: the fleet did not return to three ready replicas once the space was back"
    experiments_record returned "the server's container restarted $(( $(kube -n finapp get pod finapp-postgres-0 -o jsonpath='{.status.containerStatuses[0].restartCount}') - E24_SERVER_RESTARTS )) times; $(dock exec "$E24_NODE" df -PB1 "$EXPERIMENTS_LIMITED" | awk 'NR == 2 {print $4}') bytes free after"
    touch "$EXP_DIR/sampler-stop"
    wait "$E24_SAMPLER" 2>/dev/null || true
    experiments_workload_end e24
    pass "E24: writes refused for want of space, the server and the fleet back once it returned, nothing unresolved"
}

# ---------------------------------------------------------------------------------------------------
# The audit archive and the session sweep (P16-TSK-010; ADR-0135; OPERATIONS_RUNBOOK.md section 11.50): E44, E45, E46. Built and not run:
# by the owner's word of 2026-11-13 their runs are owed under POSTPONED_ISSUES.md PI-025.
# ---------------------------------------------------------------------------------------------------

# One line: archived segments, queued audit rows, claimed segments.
archive_state() {
    psql_query -c "SELECT (SELECT count(*) FROM platform.audit_archive_segment WHERE state = 'ARCHIVED') || E'\t'
        || (SELECT count(*) FROM platform.audit_archive_pending) || E'\t'
        || (SELECT count(*) FROM platform.audit_archive_segment WHERE state = 'CLAIMED')" | tr -d '\r'
}

archive_archived() { archive_state | cut -f1 | tr -d '[:space:]'; }

# archive_wait_archived <segments> <seconds>: 0 once at least that many segments are archived.
archive_wait_archived() {
    deadline=$(( $(date +%s) + $2 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(archive_archived)" -ge "$1" ] 2>/dev/null && return 0
        sleep 5
    done
    return 1
}

# archive_drained <seconds>: 0 once nothing is queued and nothing claimed.
archive_drained() {
    deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(archive_state | cut -f2,3 | tr -d '[:space:]')" = "00" ] && return 0
        sleep 5
    done
    return 1
}

# experiments_series <PromQL>: the first sample's value of an instant query, empty when Prometheus did not answer one.
experiments_series() {
    experiments_prometheus "/api/v1/query?query=$(urlencode "$1")" 2>/dev/null | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p' | head -1
}

# archive_census <id>: the archive against its table and its store, once the queue has drained. Each clause one line of the experiment's
# archive-census.tsv; any FAIL stops the experiment as failed, and a census that read nothing fails the run.
archive_census() {
    out="$EXP_DIR/archive-census.tsv"
    : > "$out"
    archive_drained 600 || printf 'FAIL|the archive drained within 600 s|%s archived, queued and claimed\n' "$(archive_state | tr '\t' ' ')" >> "$out"
    psql_query -f - >> "$out" 2>&1 <<'SQL'
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || '|every audit row queued or in one segment, never both, never neither|' || count(*) || ' rows not so'
  FROM platform.audit_record a
 WHERE EXISTS (SELECT 1 FROM platform.audit_archive_pending p WHERE p.audit_id = a.audit_id)
     = EXISTS (SELECT 1 FROM platform.audit_archive_member m WHERE m.audit_id = a.audit_id);
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || '|every segment holds the rows it counted|' || count(*) || ' segments not so'
  FROM platform.audit_archive_segment s
 WHERE s.row_count <> (SELECT count(*) FROM platform.audit_archive_member m WHERE m.segment_seq = s.segment_seq);
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || '|every archived segment''s digests are its rows''|' || count(*) || ' differ'
  FROM platform.audit_archive_segment s
 WHERE s.state = 'ARCHIVED'
   AND (s.content_sha256 <> encode(sha256(convert_to(platform.audit_archive_content(s.segment_seq), 'UTF8')), 'hex')
        OR s.manifest_sha256 <> encode(sha256(convert_to(platform.audit_archive_manifest(s.segment_seq), 'UTF8')), 'hex'));
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || '|every archived segment names its predecessor''s manifest|' || count(*) || ' breaks'
  FROM platform.audit_archive_segment s
  LEFT JOIN platform.audit_archive_segment p ON p.segment_seq = s.segment_seq - 1
 WHERE s.state = 'ARCHIVED' AND s.segment_seq > 1 AND s.previous_manifest_sha256 IS DISTINCT FROM p.manifest_sha256;
SELECT CASE WHEN count(*) > 0 THEN 'PASS' ELSE 'FAIL' END || '|the census had segments to read|' || count(*) || ' archived'
  FROM platform.audit_archive_segment s WHERE s.state = 'ARCHIVED';
SQL
    # The store's copies, digested where they lie, against the digests the database recorded at each mark.
    psql_query -c "SELECT 'audit/v1/segments/' || lpad(segment_seq::text, 12, '0') || '.ndjson ' || content_sha256 FROM platform.audit_archive_segment
        WHERE state = 'ARCHIVED' UNION ALL SELECT 'audit/v1/manifests/' || lpad(segment_seq::text, 12, '0') || '.json ' || manifest_sha256
        FROM platform.audit_archive_segment WHERE state = 'ARCHIVED'" | tr -d '\r' | grep . | sort > "$EXP_DIR/recorded-digests.txt"
    if experiments_store sh -c "cd $ARCHIVE_OBJECTS && sha256sum audit/v1/segments/* audit/v1/manifests/*" > "$EXP_DIR/store-digests.raw" 2>&1; then
        awk '{print $2 " " $1}' "$EXP_DIR/store-digests.raw" | sort > "$EXP_DIR/store-digests.txt"
        missing=$(comm -23 "$EXP_DIR/recorded-digests.txt" "$EXP_DIR/store-digests.txt" | grep -c . || true)
        recorded=$(grep -c . "$EXP_DIR/recorded-digests.txt" || true)
        if [ "$missing" = 0 ] && [ "$recorded" -gt 0 ]; then verdict=PASS; else verdict=FAIL; fi
        printf '%s|every archived object in the store with the digest recorded at its mark|%s of %s missing or different\n' "$verdict" "$missing" "$recorded" >> "$out"
    else
        printf 'FAIL|the store objects could be read|see store-digests.raw\n' >> "$out"
    fi
    grep -q '^PASS|' "$out" || fail "$1: the archive census printed no verdict: see experiments/$1/archive-census.tsv"
    if grep -q '^FAIL' "$out"; then
        EXP_STOPPED="${EXP_STOPPED:+$EXP_STOPPED; }the archive census: $(grep '^FAIL' "$out" | tr '\n' ';' | cut -c1-400)"
    fi
    experiments_record "archive census" "$(grep -c '^PASS' "$out") clauses PASS, $(grep -c '^FAIL' "$out" || true) FAIL: see archive-census.tsv"
}

# ---------------------------------------------------------------------------------------------------
# E44: the archive store stopped mid-run, at three replicas.
# ---------------------------------------------------------------------------------------------------

E44_steady() {
    start_workload e44 retry
    archive_wait_archived 1 300 || cannot "E44: no audit segment was archived in 300 s, so a stopped store would interrupt nothing"
    experiments_record "archive before" "$(archive_state | awk -F'\t' '{print $1 " segments archived, " $2 " rows queued, " $3 " claimed"}')"
}

E44_fault() {
    experiments_fault_on scaled finapp-rehearsal finapp-archive-store 0 1
    E44_AT=$(date +%s)
    E44_AT_ARCHIVED=$(archive_archived)
    experiments_record fault "the archive store scaled to zero at $(iso "$E44_AT"), its volume kept, $E44_AT_ARCHIVED segments archived then"
}

E44_observe() {
    printf 'epoch\tarchived\tqueued\tclaimed\n' > "$EXP_DIR/archive.tsv"
    deadline=$((E44_AT + 180))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        printf '%s\t%s\n' "$(date +%s)" "$(archive_state)" >> "$EXP_DIR/archive.tsv"
        # The archive is not in readiness: a replica unready while only the store is down is the stop condition.
        unready=$(experiments_fleet | awk -F'|' 'NF >= 5 && $2 != "True" && $6 == "" {print $1}' | head -1)
        [ -z "$unready" ] || { EXP_STOPPED="a replica unready while the archive store was down: $unready"; return 0; }
        firing=$(experiments_pages_firing)
        [ -z "$firing" ] || { EXP_STOPPED="a correctness page during the experiment: $firing"; return 0; }
        sleep 10
    done
    archived=$(archive_archived)
    # A segment whose copy was read back just before the store went may be marked just after: one, never more.
    [ "$archived" -le $((E44_AT_ARCHIVED + 1)) ] \
        || fail "E44: $((archived - E44_AT_ARCHIVED)) segments were marked archived with the store down: a mark without a copy read back"
    first=$(awk -F'\t' 'NR == 2 {print $3}' "$EXP_DIR/archive.tsv")
    last=$(awk -F'\t' 'END {print $3}' "$EXP_DIR/archive.tsv")
    [ "${last:-0}" -gt "${first:-0}" ] || fail "E44: no audit row queued while the store was down ($first then $last), so the fault met no work"
    unavailable=$(experiments_series 'sum(finapp_audit_archive_segments_total{outcome="store_unavailable"})')
    [ "${unavailable%%.*}" -ge 1 ] 2>/dev/null || fail "E44: no attempt was counted store_unavailable (read '$unavailable'): the failure was not seen"
    experiments_record observed "180 s with the store gone: $((archived - E44_AT_ARCHIVED)) segments marked, the queue $first then $last rows, $unavailable attempts counted store_unavailable, every replica ready, no page"
    pass "E44: with the archive store down nothing was marked and the queue grew; every replica stayed ready and the failures were counted"
}

E44_recover() {
    kube -n finapp-rehearsal rollout status deployment/finapp-archive-store --timeout=180s >> "$EXP_ART/faults.log" 2>&1 \
        || fail "E44: the archive store did not return"
    stop_workload e44
    [ "$(workload_unresolved e44)" = 0 ] || fail "E44: $(workload_unresolved e44) requests stayed unresolved: see workload-e44.tsv"
    assert_flows_ran e44
    returned=$(date +%s)
    archive_drained 600 || fail "E44: the archive did not catch up within 600 s of the store's return: $(archive_state | tr '\t' ' ')"
    experiments_record "caught up" "the queue drained $(( $(date +%s) - returned )) s after the workload stopped, the store back"
    archive_census E44
}

# ---------------------------------------------------------------------------------------------------
# E45: a holder killed between the copy and the mark, its segment taken over and marked once; each row in one segment. At three replicas
# here, where fleet_patch holds the fleet; at ten once this scope runs at ten, which kind's overlay admits (owed under PI-025).
# ---------------------------------------------------------------------------------------------------

E45_steady() {
    start_workload e45 retry
    archive_wait_archived 1 300 || cannot "E45: no audit segment was archived in 300 s, so no holder would be found"
    experiments_store sh -c ': > /tmp/held.txt' || cannot "E45: the archive store's held list could not be emptied"
}

E45_fault() {
    # Every read held at the store: the next holder writes both objects, then waits on its read-back, its copy made and its mark not.
    experiments_fault_on store-hold hold-reads
    deadline=$(( $(date +%s) + 120 ))
    caller=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        caller=$(experiments_store cat /tmp/held.txt 2>/dev/null | head -1 | cut -f1)
        [ -n "$caller" ] && break
        sleep 1
    done
    [ -n "$caller" ] || cannot "E45: no archive read reached the store in 120 s, so no holder stood between copy and mark"
    E45_SEGMENT=$(psql_query -c "SELECT segment_seq FROM platform.audit_archive_segment WHERE state = 'CLAIMED'" | tr -d '[:space:]')
    [ -n "$E45_SEGMENT" ] || cannot "E45: no segment was claimed while its read was held"
    E45_KEY=$(printf 'audit/v1/segments/%012d.ndjson' "$E45_SEGMENT")
    experiments_store test -f "$ARCHIVE_OBJECTS/$E45_KEY" \
        || cannot "E45: segment $E45_SEGMENT's copy is not in the store, so its holder was not between copy and mark"
    E45_VICTIM=$(experiments_fleet | awk -F'|' -v ip="$caller" '$5 == ip {print $1}' | head -1)
    [ -n "$E45_VICTIM" ] || cannot "E45: the held read came from $caller, which no application pod holds"
    if [ "$PROBE" != guard-off ]; then
        experiments_context_check || fail "the safety boundary refused before the kill: $EXP_REASON"
    fi
    experiments_kill_jvm "$E45_VICTIM"
    E45_KILLED_AT=$KILLED_EPOCH
    experiments_fault_off store-hold hold-reads
    experiments_record fault "kill -9 of $E45_VICTIM's JVM ($KILLED) at $(iso "$E45_KILLED_AT"), holding segment $E45_SEGMENT: its copy in the store, its mark not made"
}

E45_observe() {
    deadline=$((E45_KILLED_AT + 300))
    state=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        state=$(psql_query -c "SELECT state || '|' || reclaims FROM platform.audit_archive_segment WHERE segment_seq = $E45_SEGMENT" | tr -d '[:space:]')
        [ "${state%%|*}" = ARCHIVED ] && break
        # The killed replica is unready while it restarts; a second one is the stop condition.
        unready=$(experiments_fleet | awk -F'|' -v v="$E45_VICTIM" 'NF >= 5 && $1 != v && $2 != "True" && $6 == "" {print $1}' | head -1)
        [ -z "$unready" ] || { EXP_STOPPED="a second replica unready while the killed holder was replaced: $unready"; return 0; }
        firing=$(experiments_pages_firing)
        [ -z "$firing" ] || { EXP_STOPPED="a correctness page during the experiment: $firing"; return 0; }
        sleep 3
    done
    [ "${state%%|*}" = ARCHIVED ] || { EXP_STOPPED="segment $E45_SEGMENT not archived within 300 s of its holder's death ($state)"; return 0; }
    [ "${state##*|}" -ge 1 ] || fail "E45: segment $E45_SEGMENT was marked without a takeover (reclaims ${state##*|}): the kill missed its holder"
    experiments_store cat /tmp/requests.tsv > "$EXP_DIR/store-requests.tsv" 2>&1 || fail "E45: the store's request log could not be read"
    written=$(awk -F'\t' -v k="/finapp-audit-archive/$E45_KEY" '$2 == "PUT" && $3 == k && $4 == 200' "$EXP_DIR/store-requests.tsv" | grep -c . || true)
    found=$(awk -F'\t' -v k="/finapp-audit-archive/$E45_KEY" '$2 == "PUT" && $3 == k && $4 == 412' "$EXP_DIR/store-requests.tsv" | grep -c . || true)
    [ "$written" = 1 ] || fail "E45: segment $E45_SEGMENT's file was written $written times, where write once means one"
    [ "$found" -ge 1 ] || fail "E45: no takeover found segment $E45_SEGMENT's file already there: the copy was not reused"
    experiments_record "takeover" "segment $E45_SEGMENT archived by another replica after ${state##*|} takeover(s), its file written once and found $found time(s)"
    pass "E45: the holder killed between copy and mark; its segment taken over, its copy found and read back, marked once"
}

E45_recover() {
    ready_three 400 || fail "E45: the fleet did not return to three ready replicas"
    stop_workload e45
    [ "$(workload_unresolved e45)" = 0 ] || fail "E45: $(workload_unresolved e45) requests stayed unresolved: see workload-e45.tsv"
    assert_flows_ran e45
    archive_census E45
}

# ---------------------------------------------------------------------------------------------------
# E46: the session table bounded over a soak, the sweep at a grace of five minutes and a tick of thirty seconds (this scope's settings).
# ---------------------------------------------------------------------------------------------------

E46_steady() {
    start_workload e46 retry
    identity=$(psql_query -c "SELECT id FROM identity.identity ORDER BY created_at LIMIT 1" | tr -d '[:space:]')
    [ -n "$identity" ] || cannot "E46: no identity exists to plant sessions under"
    # The plants, by the run's own throwaway superuser, removed at the recovery: a session live for the whole soak, never touched, and
    # forty dead an hour past their idle bound, the sweep's positive control.
    E46_LIVE=$(psql_query -c "INSERT INTO identity.session (id, identity_id, token_hash, assurance, status, issued_at, idle_expires_at,
        absolute_expires_at, device, revoked_at, live_from) VALUES (gen_random_uuid(), '$identity', encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'base64'), 'PASSWORD', 'ACTIVE', now(),
        now() + interval '2 hours', now() + interval '3 hours', 'e46 plant, live', NULL, now()) RETURNING id" | head -1 | tr -d '[:space:]')
    [ -n "$E46_LIVE" ] || cannot "E46: the live session could not be planted"
    psql_query -c "INSERT INTO identity.session (id, identity_id, token_hash, assurance, status, issued_at, idle_expires_at, absolute_expires_at,
        device, revoked_at, live_from) SELECT gen_random_uuid(), '$identity', encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'base64'), 'PASSWORD', 'ACTIVE', now() - interval '2 hours',
        now() - interval '1 hour', now() - interval '1 hour', 'e46 plant, dead', NULL, now() - interval '2 hours' FROM generate_series(1, 40) g" >/dev/null \
        || cannot "E46: the dead sessions could not be planted"
    experiments_record plants "a session live for three hours ($E46_LIVE) and forty an hour past their idle bound"
}

E46_fault() {
    E46_MINUTES=${REHEARSAL_E46_MINUTES:-45}
    E46_AT=$(date +%s)
    experiments_record fault "none injected: the workload signs in for $E46_MINUTES minutes while the session sweep runs every 30 s at a grace of 5 minutes"
}

E46_observe() {
    printf 'epoch\tsessions\tlive\tpast_bound\tplanted_live\tplanted_dead\n' > "$EXP_DIR/sessions.tsv"
    deadline=$((E46_AT + E46_MINUTES * 60))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        # Past the bound: an idle bound more than the grace (5 min) and two ticks (2 x 30 s) ago - a row the sweep should have taken.
        row=$(psql_query -c "SELECT count(*) || E'\t' || count(*) FILTER (WHERE status = 'ACTIVE' AND idle_expires_at > now() AND absolute_expires_at > now())
            || E'\t' || count(*) FILTER (WHERE idle_expires_at < now() - interval '6 minutes') || E'\t' || count(*) FILTER (WHERE id = '$E46_LIVE')
            || E'\t' || count(*) FILTER (WHERE device = 'e46 plant, dead') FROM identity.session" | tr -d '\r')
        now=$(date +%s)
        printf '%s\t%s\n' "$now" "$row" >> "$EXP_DIR/sessions.tsv"
        [ "$(printf '%s' "$row" | cut -f4 | tr -d '[:space:]')" = 1 ] || { EXP_STOPPED="the live session planted was deleted"; return 0; }
        if [ $((now - E46_AT)) -ge 120 ] && [ "$(printf '%s' "$row" | cut -f3 | tr -d '[:space:]')" != 0 ]; then
            EXP_STOPPED="rows past the grace and two ticks remain $((now - E46_AT)) s in: the table is not bounded ($row)"
            return 0
        fi
        firing=$(experiments_pages_firing)
        [ -z "$firing" ] || { EXP_STOPPED="a correctness page during the soak: $firing"; return 0; }
        sleep 30
    done
    dead=$(awk -F'\t' 'END {print $6}' "$EXP_DIR/sessions.tsv")
    [ "$dead" = 0 ] || fail "E46: $dead of the planted dead sessions remain: the sweep took nothing"
    swept=$(experiments_series 'sum(finapp_retention_swept_total{type="session"})')
    [ "${swept%%.*}" -ge 40 ] 2>/dev/null || fail "E46: the sweep counted $swept sessions deleted, fewer than the forty planted"
    most=$(awk -F'\t' 'NR > 1 && $2 > m {m = $2} END {print m + 0}' "$EXP_DIR/sessions.tsv")
    experiments_record observed "$E46_MINUTES minutes: at most $most session rows, none past the grace and two ticks after the first two minutes, the live plant kept, $swept deleted"
    pass "E46: the session table bounded over the soak - nothing left past the grace, the live session kept, the planted dead swept"
}

E46_recover() {
    stop_workload e46
    [ "$(workload_unresolved e46)" = 0 ] || fail "E46: $(workload_unresolved e46) requests stayed unresolved: see workload-e46.tsv"
    assert_flows_ran e46
    # The plants' teardown, by the superuser that planted them.
    psql_query -c "DELETE FROM identity.session WHERE device IN ('e46 plant, live', 'e46 plant, dead')" >/dev/null \
        || fail "E46: the planted sessions could not be removed"
}
