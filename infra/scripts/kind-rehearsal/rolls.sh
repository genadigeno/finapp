# The component rolls (P16-TSK-005; THREAT_MODEL.md section 6 item 43 (f); OPERATIONS_RUNBOOK.md sections 11.26 and 11.28). Sourced by
# infra/scripts/kind-rehearsal.sh under REHEARSAL_SCOPE=rolls, once release N is deployed, smoked and its census green: every function and
# variable of the harness is in scope here.
#
# WHAT A GREEN RUN SAYS. Each component the platform runs beside its own release changed version on a real API server while the scripted
# workload ran through the real edge, and came back, with the census read after each leg (trial balance zero, one effect per key at the
# platform and at the provider, the outbox drained, every partition owned with no lag, nothing lost to a consumer, GL = ledger):
#
#   roll-edge      both controllers' Deployments, public and operator, set to TRAEFIK_ROLL_* and then back to TRAEFIK_* (the deployed pin,
#                  deploy/edge/base/controllers.yaml), each replaced one pod at a time by its own maxUnavailable 0: zero failed requests
#                  through either host across each roll, the operator host's included, counted until no controller is terminating (its
#                  10 s accept grace and 30 s drain inside the window); every controller ready throughout; each one running the version
#                  asked, read from the binary.
#   roll-postgres  the test-only server's StatefulSet set to POSTGRES_ROLL_* and back to the overlay's pin, on the same volume claim: the
#                  server restarted (its start time moved) onto the version asked (server_version); every request the workload made while
#                  it was away retried with the same key until it resolved, none unresolved - a card payment caught mid-call is concluded
#                  by the platform's own sweep past its dispatched age, never sent again, so the retries are bounded past it; no replica
#                  restarted, liveness being the process and never the database; what each replica's readiness did, recorded.
#   roll-kafka     the test-only broker's StatefulSet set to KAFKA_ROLL_* and back: zero failed requests (the broker is on no request's
#                  path), every replica ready throughout (a broker away is not a refusal), the outbox seen holding rows while it was away,
#                  the version asked, and the topics, their offsets and the application's ACLs kept (the census's lag and lost-event
#                  checks after it read them).
#
# THE PAIRS. infra/scanner-pins.sh's *_ROLL_* triples, each pulled by digest. A target is the same repository and minor as the deployed pin
# and another patch (KindRehearsalContractTest holds it). PostgreSQL's deployed patch is the newest published, so its target is the one
# before and its step rolls back first: the same data directory under two releases of one major, in both directions.
#
# REHEARSAL_PROBE=edge-max-unavailable: before the edge's first roll, both controllers' maxUnavailable set to their replica count, and their
# readiness held 30 s after start, past traefik.yaml's 10 s accept grace (the slow start maxUnavailable 0 is there for). The roll must then
# fail requests, and the zero-failed assertion must say so; the controllers' ready floor is logged beside it.

EDGE_DEPLOYMENTS="finapp-edge-public finapp-edge-operator"
# As deploy/edge/base/controllers.yaml names the image, which EdgeManifestsTest holds equal to TRAEFIK_DIGEST.
EDGE_PINNED="docker.io/$TRAEFIK_REPOSITORY@$TRAEFIK_DIGEST"
EDGE_TARGET="docker.io/$TRAEFIK_ROLL_REPOSITORY@$TRAEFIK_ROLL_DIGEST"
POSTGRES_TARGET="${POSTGRES_ROLL_REPOSITORY#library/}:$POSTGRES_ROLL_VERSION@$POSTGRES_ROLL_DIGEST"
KAFKA_TARGET="$KAFKA_ROLL_REPOSITORY:$KAFKA_ROLL_VERSION@$KAFKA_ROLL_DIGEST"

# ---------------------------------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------------------------------

image_of() {
    # image_of <namespace> <kind/name>: the image the controller's template names.
    kube -n "$1" get "$2" -o jsonpath='{.spec.template.spec.containers[0].image}'
}

app_restarts() {
    kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.name}={.status.containerStatuses[0].restartCount}{" "}{end}'
}

# The application fleet's ready floor over the samples that read a fleet at all: a sample whose listing failed holds no pod and says nothing.
ready_floor_of() { awk -F'\t' 'NR>1 && $4+0 > 0 && (m == "" || $2+0 < m) {m = $2+0} END {print (m == "" ? -1 : m)}' "$ART/samples-$1.tsv"; }

edge_fleet() {
    kube -n finapp-edge get pods -l app.kubernetes.io/component=edge-controller \
        -o jsonpath='{range .items[*]}{.metadata.labels.app\.kubernetes\.io/name}|{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null
}

# Every two seconds: the ready controllers of each surface, the terminating ones, all of them. A listing that read no pod is not written:
# the namespace always holds four, so it is a failed read, not a fleet at nought.
start_edge_sampler() {
    file="$ART/samples-$1.tsv"
    printf 'time\tpublic_ready\toperator_ready\tterminating\tpods\n' > "$file"
    (
        while [ ! -f "$WORK/sampler-stop-$1" ]; do
            edge_fleet | awk -F'|' -v t="$(date -u +%H:%M:%S)" '
                NF>=3 {n++}
                NF>=3 && $2=="" && $3=="True" && $1=="finapp-edge-public" {p++}
                NF>=3 && $2=="" && $3=="True" && $1=="finapp-edge-operator" {o++}
                NF>=3 && $2!="" {x++}
                END {if (n > 0) printf "%s\t%d\t%d\t%d\t%d\n", t, p, o, x, n}' >> "$file"
            sleep 2
        done
    ) &
    SAMPLER=$!
}

edge_floor_of() { awk -F'\t' -v c="$2" 'NR>1 && (m == "" || $c+0 < m) {m = $c+0} END {print (m == "" ? -1 : m)}' "$ART/samples-$1.tsv"; }

edge_none_terminating() {
    deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        listed=$(edge_fleet)
        if [ -n "$listed" ] && [ "$(printf '%s\n' "$listed" | awk -F'|' 'NF>=3 && $2!="" {t++} END {print t+0}')" = 0 ]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# The version every live controller runs, read from the binary: "<count> <version>" per version, so one line means one version.
edge_versions() {
    for pod in $(kube -n finapp-edge get pods -l app.kubernetes.io/component=edge-controller \
        -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.deletionTimestamp}{"\n"}{end}' | awk -F'|' '$2=="" {print $1}'); do
        version=$(kube -n finapp-edge exec "$pod" -c edge -- traefik version 2>/dev/null | sed -n 's/^Version:[[:space:]]*//p' | tr -d '\r')
        printf '%s\n' "${version:-unread}"
    done | sort | uniq -c | awk '{print $1, $2}'
}

edge_replicas() {
    total=0
    for deployment in $EDGE_DEPLOYMENTS; do
        total=$((total + $(kube -n finapp-edge get "deployment/$deployment" -o jsonpath='{.spec.replicas}')))
    done
    printf '%s' "$total"
}

server_started() { psql_query -c "SELECT extract(epoch FROM pg_postmaster_start_time())::bigint" 2>/dev/null | tr -d '[:space:]'; }
server_version() { psql_query -c "SHOW server_version" 2>/dev/null | awk 'NF {print $1; exit}'; }
broker_version() {
    kube -n finapp exec finapp-kafka-0 -c kafka -- /opt/kafka/bin/kafka-topics.sh --version 2>/dev/null | sed -n 's/^\([0-9][0-9.]*\).*/\1/p' | head -1
}

# ---------------------------------------------------------------------------------------------------
# The edge.
# ---------------------------------------------------------------------------------------------------

# edge_leg <label> <image> <version without v>: both controllers set to the image under the workload, the window closed when none terminates.
edge_leg() {
    label=$1
    start_workload "$label" "" operator-edge
    sleep 8
    start_edge_sampler "$label"
    started=$(date +%s)
    for deployment in $EDGE_DEPLOYMENTS; do
        kube -n finapp-edge set image "deployment/$deployment" "edge=$2" >> "$ART/apply.log" 2>&1 || fail "edge roll $label: $deployment's image could not be set"
    done
    for deployment in $EDGE_DEPLOYMENTS; do
        if ! kube -n finapp-edge rollout status "deployment/$deployment" --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1; then
            kube -n finapp-edge get pods -o wide > "$ART/pods-at-failure-$label.txt" 2>&1 || true
            stop_sampler "$label"
            stop_workload "$label"
            fail "edge roll $label: $deployment did not roll within ${ROLLOUT_TIMEOUT}s: see pods-at-failure-$label.txt"
        fi
    done
    # A controller past its rollout still accepts for its grace and then drains its connections: the window closes when none is terminating.
    edge_none_terminating 300 || fail "edge roll $label: a controller was still terminating 300 s after the rollout"
    ended=$(date +%s)
    sleep 8
    stop_sampler "$label"
    stop_workload "$label"
    printf 'edge roll %s took %s s\n' "$label" "$((ended - started))" | tee -a "$ART/timings.txt" >> "$ART/rehearsal.log"
    failed=$(workload_failed "$label")
    succeeded=$(sed -n 's/.* ok=\([0-9]*\) .*/\1/p' "$CLIENT/workload-$label.summary")
    public_floor=$(edge_floor_of "$label" 2)
    operator_floor=$(edge_floor_of "$label" 3)
    log "edge roll $label: $succeeded requests succeeded and $failed failed; ready controllers never below $public_floor public and $operator_floor operator"
    [ "$failed" = 0 ] || fail "edge roll $label: $failed failed requests through the edge, see workload-$label.tsv"
    [ "$PROBE" != edge-max-unavailable ] || fail "PROBE edge-max-unavailable: zero failed requests with every controller replaceable at once and slow to start, so the assertion cannot fail"
    pass "edge roll $label: zero failed requests through the edge, $succeeded succeeded"
    assert_flows_ran "$label"
    operator_ok=$(sed -n 's/.*[{ ]operator-edge\.ok=\([0-9]*\).*/\1/p' "$CLIENT/workload-$label.summary" | head -1)
    [ "${operator_ok:-0}" -gt 0 ] || fail "edge roll $label: no request reached the application through the operator host, so its controllers' roll was not measured"
    pass "edge roll $label: $operator_ok requests through the operator host, every one answered by the application"
    public_replicas=$(kube -n finapp-edge get deployment/finapp-edge-public -o jsonpath='{.spec.replicas}')
    operator_replicas=$(kube -n finapp-edge get deployment/finapp-edge-operator -o jsonpath='{.spec.replicas}')
    [ "$public_floor" -ge "$public_replicas" ] && [ "$operator_floor" -ge "$operator_replicas" ] \
        || fail "edge roll $label: ready controllers fell to $public_floor public and $operator_floor operator, and maxUnavailable 0 keeps every one: see samples-$label.tsv"
    pass "edge roll $label: every controller ready throughout, each replaced one at a time"
    versions=$(edge_versions)
    printf '%s\n' "$versions" > "$ART/edge-versions-$label.txt"
    [ "$versions" = "$(edge_replicas) $3" ] || fail "edge roll $label: the live controllers run '$(printf '%s' "$versions" | tr '\n' ';')', where $(edge_replicas) on $3 were asked"
    pass "edge roll $label: all $(edge_replicas) controllers run Traefik $3, read from the binary"
    census "$label"
}

roll_edge() {
    step roll-edge
    for deployment in $EDGE_DEPLOYMENTS; do
        [ "$(image_of finapp-edge "deployment/$deployment")" = "$EDGE_PINNED" ] || cannot "$deployment does not run the deployed pin $EDGE_PINNED"
    done
    [ "$EDGE_TARGET" != "$EDGE_PINNED" ] || cannot "the edge's roll target is its deployed pin"
    if [ "$PROBE" = edge-max-unavailable ]; then
        for deployment in $EDGE_DEPLOYMENTS; do
            replicas=$(kube -n finapp-edge get "deployment/$deployment" -o jsonpath='{.spec.replicas}')
            kube -n finapp-edge patch "deployment/$deployment" --type=json \
                -p "[{\"op\":\"replace\",\"path\":\"/spec/strategy/rollingUpdate/maxUnavailable\",\"value\":$replicas},{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/readinessProbe/initialDelaySeconds\",\"value\":30}]" \
                >> "$ART/apply.log" 2>&1 || cannot "the probe could not patch $deployment"
        done
        for deployment in $EDGE_DEPLOYMENTS; do
            kube -n finapp-edge rollout status "deployment/$deployment" --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1 || cannot "the probe's patch did not settle on $deployment"
        done
        edge_none_terminating 300 || cannot "the probe's patch left a controller terminating"
        log "PROBE edge-max-unavailable: maxUnavailable at the replica count and readiness 30 s after start, past the 10 s accept grace"
    fi
    edge_leg roll-edge-to "$EDGE_TARGET" "${TRAEFIK_ROLL_VERSION#v}"
    edge_leg roll-edge-back "$EDGE_PINNED" "${TRAEFIK_VERSION#v}"
}

# ---------------------------------------------------------------------------------------------------
# PostgreSQL.
# ---------------------------------------------------------------------------------------------------

# A request in flight when the server stops can leave a card payment PROCESSING with its provider call made and its outcome unwritten. Nothing
# sends that call again: the platform's own sweep asks the provider (the simulator's GET /operations/{reference}) once the dispatch is past
# its dispatched age, and only then does a retry by key see the payment concluded; an authorization concluded that way is captured by the
# client's next confirmation or, failing one, by the sweep's stranded-authorization leg one dispatched age later. So the leg's retries are
# bounded past twice the longest such age the code declares (payments' and merchant payouts', read from their placeholders, never copied),
# with five minutes for the sweep's poll, the claim's lease and the retry's pause.
dispatched_minutes() {
    for source in app/src/main/java/com/finapp/app/payments/PaymentSweeperSchedule.java app/src/main/java/com/finapp/app/merchant/MerchantPayoutBeans.java; do
        sed -n 's/.*finapp\.\(payments\|merchant\.payout\)\.sweeper\.dispatched-age:PT\([0-9]*\)M}.*/\2/p' "$ROOT/$source"
    done | sort -n | tail -1
}
ROLL_RETRY_MINUTES=$(dispatched_minutes)
case $ROLL_RETRY_MINUTES in '' | *[!0-9]*) cannot "the payment sweep's dispatched age could not be read from the code" ;; esac
if grep -Eq 'SWEEPER_DISPATCHED_?AGE' "$A/all.yaml"; then
    cannot "the kind overlay sets a sweep's dispatched age, which this leg reads from the code's default"
fi
ROLL_RETRY_SECONDS=$((2 * ROLL_RETRY_MINUTES * 60 + 300))

# postgres_leg <label> <image> <version>: the server's pod replaced onto the image, its claim kept, the workload retrying by key.
postgres_leg() {
    label=$1
    started_before=$(server_started)
    [ -n "$started_before" ] || cannot "postgres roll $label: the server's start time could not be read before the roll"
    restarts_before=$(app_restarts)
    WORKLOAD_RETRY_SECONDS=$ROLL_RETRY_SECONDS start_workload "$label" retry
    sleep 8
    start_sampler "$label"
    started=$(date +%s)
    kube -n finapp set image statefulset/finapp-postgres "postgres=$2" >> "$ART/apply.log" 2>&1 || fail "postgres roll $label: the image could not be set"
    if ! kube -n finapp rollout status statefulset/finapp-postgres --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1; then
        kube -n finapp get pods -o wide > "$ART/pods-at-failure-$label.txt" 2>&1 || true
        stop_sampler "$label"
        stop_workload "$label"
        fail "postgres roll $label: the server did not come back within ${ROLLOUT_TIMEOUT}s: see pods-at-failure-$label.txt"
    fi
    ready_three 600 || fail "postgres roll $label: three replicas were not ready again after the server came back"
    ended=$(date +%s)
    # Requests after the return count too: a pool that never recovered would fail them, not the ones during the outage.
    sleep 20
    stop_sampler "$label"
    stop_workload "$label"
    printf 'postgres roll %s took %s s\n' "$label" "$((ended - started))" | tee -a "$ART/timings.txt" >> "$ART/rehearsal.log"
    kube -n finapp logs finapp-postgres-0 -c postgres > "$ART/postgres-$label.log" 2>&1 || true
    if grep -q 'database system was shut down at' "$ART/postgres-$label.log"; then
        log "postgres roll $label: the old server stopped cleanly (its successor read a clean shutdown)"
    elif grep -q 'automatic recovery in progress\|was interrupted' "$ART/postgres-$label.log"; then
        log "postgres roll $label: the old server did not stop cleanly; its successor recovered from the WAL"
    fi
    started_after=$(server_started)
    [ -n "$started_after" ] && [ "$started_after" -gt "$started_before" ] \
        || fail "postgres roll $label: the server's start time did not move ($started_before, then $started_after), so it did not restart"
    version=$(server_version)
    [ "$version" = "$3" ] || fail "postgres roll $label: the server runs $version, where $3 was asked"
    pass "postgres roll $label: the server restarted onto PostgreSQL $3 on the same volume claim"
    [ "$(workload_unresolved "$label")" = 0 ] || fail "postgres roll $label: $(workload_unresolved "$label") requests stayed unresolved, see workload-$label.tsv"
    pass "postgres roll $label: $(workload_failed "$label") attempts failed while the server was away and were retried with the same key; none unresolved"
    # A top-up waiting reads "ended PROCESSING" under its own key; a checkout whose payment waits reads "ended PAYMENT_PENDING" and names
    # the payment, since every checkout's key column reads "fresh".
    swept=$( {
        awk -F'\t' '$NF ~ /ended PROCESSING/ {print "top-up|" $2}' "$CLIENT/workload-$label.tsv"
        sed -n 's/.*ended PAYMENT_PENDING.*"paymentIntentId":"\([^"]*\)".*/checkout|\1/p' "$CLIENT/workload-$label.tsv"
    } | sort -u | wc -l | tr -d ' ')
    log "postgres roll $label: $swept payments caught mid-call stayed PROCESSING until the platform's sweep asked the provider, and were then answered"
    assert_flows_ran "$label"
    restarts_after=$(app_restarts)
    [ "$restarts_after" = "$restarts_before" ] || fail "postgres roll $label: a replica restarted for the database's restart: $restarts_before -> $restarts_after"
    floor=$(ready_floor_of "$label")
    pass "postgres roll $label: no replica restarted (liveness is the process); the fewest ready replicas sampled was $floor, three again after"
    census "$label"
}

roll_postgres() {
    step roll-postgres
    POSTGRES_PINNED=$(image_of finapp statefulset/finapp-postgres)
    case $POSTGRES_PINNED in postgres:*@sha256:*) ;; *) cannot "the test-only PostgreSQL does not run a pinned postgres image: $POSTGRES_PINNED" ;; esac
    POSTGRES_PINNED_VERSION=${POSTGRES_PINNED#postgres:}
    POSTGRES_PINNED_VERSION=${POSTGRES_PINNED_VERSION%@*}
    [ "$(server_version)" = "$POSTGRES_PINNED_VERSION" ] || cannot "the server runs $(server_version), not its pin's $POSTGRES_PINNED_VERSION"
    [ "$POSTGRES_TARGET" != "$POSTGRES_PINNED" ] || cannot "the database's roll target is its deployed pin"
    claim=$(kube -n finapp get pvc data-finapp-postgres-0 -o jsonpath='{.metadata.uid}' 2>/dev/null)
    [ -n "$claim" ] || cannot "the test-only PostgreSQL has no volume claim, so a roll would start it empty"
    postgres_leg roll-postgres-to "$POSTGRES_TARGET" "$POSTGRES_ROLL_VERSION"
    postgres_leg roll-postgres-back "$POSTGRES_PINNED" "$POSTGRES_PINNED_VERSION"
    [ "$(kube -n finapp get pvc data-finapp-postgres-0 -o jsonpath='{.metadata.uid}')" = "$claim" ] || fail "postgres roll: the server's volume claim was replaced"
    pass "postgres roll: one volume claim throughout, $claim"
}

# ---------------------------------------------------------------------------------------------------
# The broker.
# ---------------------------------------------------------------------------------------------------

# kafka_leg <label> <image> <version>: the broker's pod replaced onto the image, its claim kept; nothing on a request's path notices.
kafka_leg() {
    label=$1
    start_workload "$label"
    sleep 8
    start_sampler "$label"
    started=$(date +%s)
    kube -n finapp set image statefulset/finapp-kafka "kafka=$2" >> "$ART/apply.log" 2>&1 || fail "kafka roll $label: the image could not be set"
    # While the broker is away, the outbox holds what the fleet commits: the most it held is read until the broker is back.
    held=0
    deadline=$(( $(date +%s) + ROLLOUT_TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        pending=$(pending_events 2>/dev/null || true)
        case $pending in '' | *[!0-9]*) ;; *) [ "$pending" -le "$held" ] || held=$pending ;; esac
        rolled=$(kube -n finapp get pod finapp-kafka-0 -o jsonpath='{.spec.containers[0].image}|{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
        [ "$rolled" = "$2|True" ] && break
        sleep 2
    done
    if ! kube -n finapp rollout status statefulset/finapp-kafka --timeout=60s >> "$ART/apply.log" 2>&1; then
        kube -n finapp get pods -o wide > "$ART/pods-at-failure-$label.txt" 2>&1 || true
        stop_sampler "$label"
        stop_workload "$label"
        fail "kafka roll $label: the broker did not come back within ${ROLLOUT_TIMEOUT}s: see pods-at-failure-$label.txt"
    fi
    ended=$(date +%s)
    # Events committed after the return are published by a relay that reconnected; the census's drain reads them.
    sleep 20
    stop_sampler "$label"
    stop_workload "$label"
    printf 'kafka roll %s took %s s\n' "$label" "$((ended - started))" | tee -a "$ART/timings.txt" >> "$ART/rehearsal.log"
    failed=$(workload_failed "$label")
    [ "$failed" = 0 ] || fail "kafka roll $label: $failed failed requests while the broker rolled, and the broker is on no request's path: see workload-$label.tsv"
    pass "kafka roll $label: zero failed requests, $(sed -n 's/.* ok=\([0-9]*\) .*/\1/p' "$CLIENT/workload-$label.summary") succeeded"
    assert_flows_ran "$label"
    floor=$(ready_floor_of "$label")
    [ "$floor" -ge 3 ] || fail "kafka roll $label: ready replicas fell to $floor while the broker was away, and a broker away is not a refusal: see samples-$label.tsv"
    pass "kafka roll $label: three replicas ready throughout"
    [ "$held" -gt 0 ] || fail "kafka roll $label: the outbox never held a row, so the broker was never seen away"
    pass "kafka roll $label: the outbox held up to $held events while the broker was away"
    version=$(broker_version)
    [ "$version" = "$3" ] || fail "kafka roll $label: the broker runs $version, where $3 was asked"
    pass "kafka roll $label: the broker runs Kafka $3"
    census "$label"
}

roll_kafka() {
    step roll-kafka
    KAFKA_PINNED=$(image_of finapp statefulset/finapp-kafka)
    case $KAFKA_PINNED in apache/kafka:*@sha256:*) ;; *) cannot "the test-only broker does not run a pinned apache/kafka image: $KAFKA_PINNED" ;; esac
    KAFKA_PINNED_VERSION=${KAFKA_PINNED#apache/kafka:}
    KAFKA_PINNED_VERSION=${KAFKA_PINNED_VERSION%@*}
    [ "$(broker_version)" = "$KAFKA_PINNED_VERSION" ] || cannot "the broker runs $(broker_version), not its pin's $KAFKA_PINNED_VERSION"
    [ "$KAFKA_TARGET" != "$KAFKA_PINNED" ] || cannot "the broker's roll target is its deployed pin"
    claim=$(kube -n finapp get pvc data-finapp-kafka-0 -o jsonpath='{.metadata.uid}' 2>/dev/null)
    [ -n "$claim" ] || cannot "the test-only broker has no volume claim, so a roll would start it empty"
    kafka_leg roll-kafka-to "$KAFKA_TARGET" "$KAFKA_ROLL_VERSION"
    kafka_leg roll-kafka-back "$KAFKA_PINNED" "$KAFKA_PINNED_VERSION"
    [ "$(kube -n finapp get pvc data-finapp-kafka-0 -o jsonpath='{.metadata.uid}')" = "$claim" ] || fail "kafka roll: the broker's volume claim was replaced"
    pass "kafka roll: one volume claim throughout, $claim"
}

# ---------------------------------------------------------------------------------------------------
# The sequence.
# ---------------------------------------------------------------------------------------------------
if wanted roll-edge; then roll_edge; fi
if wanted roll-postgres; then roll_postgres; fi
if wanted roll-kafka; then roll_kafka; fi
