# The load harness (P16-TSK-006; ADR-0126; PHASE_16_PLAN.md section 5; OPERATIONS_RUNBOOK.md section 11.43). Sourced by
# infra/scripts/kind-rehearsal.sh at its start, as telemetry.sh is, so it only defines functions: load_preflight runs under
# REHEARSAL_SCOPE=load before anything is built, and load_scope once release N is deployed, smoked, its census green, the cluster
# suites passed and the telemetry pipeline up. It uses the harness's helpers (dock, dpath, kube, psql_query, client, census, db_epoch,
# telemetry_get, urlencode, step, log, pass, fail, cannot).
#
# WHAT A GREEN RUN SAYS. A load profile (kind-rehearsal/load/<profile>.json; REHEARSAL_LOAD_PROFILE, `demonstration` by default) was
# driven through the platform's real public edge by k6, pinned by digest, as an open model at the profile's arrival rates, and:
#   - the generator held its model: every scenario an arrival-rate executor (read from what k6 will run, before the cluster exists), no
#     iteration dropped, every operation's arrivals within 2% of its rate, the generator's CPU below 90% of its limit;
#   - every 30 s while the load ran, no correctness page of SLOS.md section 3 was firing in the cluster's Prometheus, every replica was
#     scraped, every correctness gauge was readable (CorrectnessUnverifiable's expression, evaluated at once, empty) and every clause of
#     census.sql marked "[sampled during load]" passed inside one REPEATABLE READ READ ONLY transaction; the samples cover the load with no
#     gap over 45 s, and a sample that fails or cannot read stops the load and fails the run - it never passes blind;
#   - every money movement was resolved by its key, the same-key retries (one iteration in twenty, half before and half after the first
#     answer) answered alike, every operation's errors were within its objective, and every ready replica served each money-moving route;
#   - the provider's callbacks (two per captured card payment) and one settlement file (at mid-run, or at the first capture after it) were
#     delivered and taken;
#   - after the load, the full census held with the generator's counts, and a last sample passed;
#   - the platform's own signals (telemetry.sh's platform_signals_plant and platform_signals, THREAT_MODEL.md section 6 item 47 (c)): a
#     migrator Job planted to fail before the load raised MigratorJobFailed and, its seventeen minutes passing under the load,
#     MigratorJobOverdue, each routed; its lines in Loki scrubbed; the edge's, the migrator's and the stack's streams swept for every
#     needle beside their controls; the release's Job run again and both pages cleared.
# The latency percentiles, errors, PostgreSQL's statistics every 10 s, pg_stat_statements, the containers' CPU and memory and the
# saturation signals Prometheus holds are in $REHEARSAL_ARTEFACTS/load. A run is reported with its workload's name and its machine
# (load/workload.txt): a kind number is never production capacity (ADR-0126 decision 1).
#
# WHAT IT HOLDS AND WHERE IT GOES. The generator is handed a directory with the run's fixture (its customers' sessions, the run's
# password, the merchant's key) and the run's edge CA, nothing else, and the address of the node of the cluster the run made or kept
# under its own name; it verifies TLS. The harness has no kubeconfig: the cluster's API is reached inside that node. Before any load the target is
# checked to be a kind node of this cluster's name. Every credential stays in the run's temporary directory, never in the artefacts.
#
# THE PROBES (REHEARSAL_PROBE), each a deliberate break that must turn the run red:
#   closed-model       the generator's model.js replaced by a closed model (constant-vus): refused by the pre-flight, before any cluster;
#   sampler-disabled   the load runs with no correctness sampler: the coverage assertion fails, so a run whose invariants nobody read
#                      cannot pass;
#   unbalanced-entry   60 s into the load the test-only superuser inserts a journal entry with one debit line and no credit: the next
#                      sample fails and stops the run.
# and one that must move the run's first limit (P16-TSK-007's entry):
#   slow-statement     before the load the test-only superuser drops the outbox relay's index on pending rows
#                      (platform.outbox_event_pending, V006) on the run's own server, never by a migration, so every relay poll on every
#                      replica scans the whole outbox; recreated with V006's own definition after the load and before the census. Run with
#                      the stress profile, the database's CPU is the first limit (OPERATIONS_RUNBOOK.md section 11.45).
# REHEARSAL_LOAD_DURATION shortens a run (for a probe); such a run is not the profile's workload and load/workload.txt says so.
#
# THE WORKLOAD BASELINE (P16-TSK-007; OPERATIONS_RUNBOOK.md section 11.45). A profile with "stages" (normal, peak, sustained, stress,
# isolation: model.js) names every operation at the proposed production target's peak and scales it per stage; REHEARSAL_LOAD_DURATION
# shortens every stage in proportion. The judge holds each operation's arrivals to the sum over the stages of its rate times its factor
# times the stage's seconds; the stress profile, which runs past the platform's break by design, records the iterations it dropped per
# stage instead of failing on them, and is judged on everything else. The run writes, per operation and stage, the arrivals, outcomes,
# errors by class and the latency percentiles (k6/stages.tsv), and per stage the fleet's and each pod's CPU, throttling and memory, the
# JVMs' heap, GC, threads, request threads, the pools' hold and wait, and the database's time (stages-resources.tsv), so every number
# the capacity model takes is read from the run's own artefacts. REHEARSAL_LOAD_REPLICAS (3, today's minimum; up to 7, today's ceiling)
# runs the fleet at that count: the rendered kind overlay is patched in the run's own directory, never in the repository, with the
# connection budget's formula at that count (N + 2 x 1 + 1 instances, each 7 + 1 connections, 12 reserved: FINAPP_DB_INSTANCES, the
# server's figure the guard reads, and the test-only server's max_connections), the HorizontalPodAutoscaler held at N and the quota to
# match. BNPL purchases and instalments, lending repayments and loan credit decisions stay undriven on kind, their reason in each profile:
# every one rests on a credit approval, and on kind none is possible, automatic or by a person (no bureau can be configured there, and
# without the bureau's total balance the exposure is unassessed: INV-CRD-09, INV-CRD-10; OPERATIONS_RUNBOOK.md section 11.43).
#
# CONTENTION, THE SOAK AND THE PLANS (P16-TSK-008; PHASE_16_PLAN.md sections 6.3-6.5 and 6.7; OPERATIONS_RUNBOOK.md section 11.48). Two
# profiles ask the run for more of the database, each by a flag, and every reading below is the run's own server's, set there by ALTER
# SYSTEM and thrown away with the cluster:
#   "contention": true   (concurrent.json, E38: many clients on few accounts - every transfer between one pair of customers, one way
#                        and back by turns, every checkout to the one merchant, at the engineering workload and at the target's burst).
#                        The server logs each lock wait of a second or more (log_lock_waits); load/contention.sql samples every client
#                        backend waiting on a lock every 250 ms, each wait resolved to its row (a balance or account row to its
#                        account's purpose, currency and id, an advisory lock to its namespace and key); load/contention.awk writes each
#                        row's waits per stage (contention-rows.tsv). Before the load the sampler is shown a wait (one wallet's balance
#                        row held four seconds while a second session waits on it) and must place it, and the server's log must hold
#                        it. After the load every deadlock the server counted must have its record in its log (load/locks.awk:
#                        deadlocks.txt, with every serialisation failure; lock-waits.tsv); what explains each is the run's record.
#   "soak": true         (soak.json, E39: the engineering workload held eight hours, the payable read left out because its cost grows
#                        with the run's age, ADR-0133). The server logs every autovacuum run and checkpoint; load/soak.sql reads the
#                        named tables every 60 s; over 80% to 83% of the load auto_explain's window (preloaded by the kind overlay and
#                        inert until here) logs a twentieth of every statement's plan, and load/plans.awk keeps the slowest plan of each
#                        of the 25 statements pg_stat_statements ranks highest and counts every sequential scan; at 86% a transactional
#                        index build on ledger.journal_line (load/index-build.sql, THREAT_MODEL.md section 6 item 42 (b)) measures the
#                        write stop a migration's CREATE INDEX makes under the load, and the throwaway index is dropped.
# The probe of E38 (the lock order removed from the transfer's two account locks) is a throwaway branch's, section 11.48.

LOAD_PROFILE_NAME=${REHEARSAL_LOAD_PROFILE:-demonstration}
LOAD_CUSTOMERS=${REHEARSAL_LOAD_CUSTOMERS:-20}
LOAD_DURATION=${REHEARSAL_LOAD_DURATION:-}
# The fleet's size for the run: today's minimum by default, today's ceiling at most (ten is P16-TSK-014's, on C3's larger server).
LOAD_REPLICAS=${REHEARSAL_LOAD_REPLICAS:-3}
# Run inside the node every 10 s: one line per pod - its uid as systemd spells it, its CPU in nanoseconds, how many of its containers'
# 100 ms periods hit their CPU limit and the task-time throttled there, its memory in bytes, and the periods its limited containers ran,
# each cumulative. The reference machine's kind node runs cgroup v1 under the systemd driver (kubelet.slice); a pod's limits sit on its
# containers' scopes beneath its slice. Throttled periods over periods is the share of the time a pod's CPU limit held it back.
LOAD_CGROUPS='cd /sys/fs/cgroup || exit 0
for p in cpu/kubelet.slice/kubelet-kubepods.slice/*-pod*.slice cpu/kubelet.slice/kubelet-kubepods.slice/*/*-pod*.slice; do
    [ -d "$p" ] || continue
    r=${p#cpu/}; u=${p##*-pod}; u=${u%.slice}; n=0; t=0; q=0
    for c in "$p"/*/cpu.stat; do
        [ -f "$c" ] || continue
        n=$((n + $(sed -n "s/^nr_throttled //p" "$c"))); t=$((t + $(sed -n "s/^throttled_time //p" "$c")))
        q=$((q + $(sed -n "s/^nr_periods //p" "$c")))
    done
    printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$u" "$(cat "cpuacct/$r/cpuacct.usage")" "$n" "$t" "$(cat "memory/$r/memory.usage_in_bytes")" "$q"
done'
# How many addresses the generator arrives from: each instance its own container, running its share of every rate.
LOAD_GENERATORS=${REHEARSAL_LOAD_GENERATORS:-4}
K6_IMAGE="$K6_REPOSITORY@$K6_DIGEST"
LOAD_PROM=finapp-prometheus:9090
# The census's expected counts take these from load_judge: zero in every other scope.
LOAD_TRANSFERS=0
LOAD_TOPUPS=0
LOAD_CHECKOUTS=0
LOAD_WALLET_MINOR=0
# P16-TSK-008: the profile's "contention" and "soak" flags, read by load_preflight; what the soak's windows have done so far.
LOAD_CONTENTION_ON=""
LOAD_SOAK=""
PG_PLANS_SUMMARY=""

load_paths() {
    LOAD_ART="$ART/load"
    LOAD_SRC="$WORK/load"
    LOAD_GEN="$WORK/generator"
    LOAD_PROFILE_FILE="$LOAD_SRC/$LOAD_PROFILE_NAME.json"
    mkdir -p "$LOAD_ART" "$LOAD_SRC" "$LOAD_GEN"
}

# load_profile_value <key>: a top-level value of the profile, unquoted.
load_profile_value() {
    sed -n "s/^  \"$1\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}$/\1/p" "$LOAD_PROFILE_FILE" | head -1
}

# load_operations <generator>: the names of the profile's operations that generator drives.
load_operations() {
    sed -n "s/.*{\"name\": \"\([a-z]*\)\", \"generator\": \"$1\".*/\1/p" "$LOAD_PROFILE_FILE"
}

# load_operation_value <operation> <key>: one field of one operation's line.
load_operation_value() {
    sed -n "s/.*{\"name\": \"$1\", .*\"$2\": \"\{0,1\}\([^\",}]*\)\"\{0,1\}[,}].*/\1/p" "$LOAD_PROFILE_FILE" | head -1
}

# load_errors_chance <errors> <attempts> <objective>: the chance that an operation meeting its error objective would err at least this often
# in this many attempts (a one-sided test, Poisson at attempts x objective). Below 1% the errors are more than the objective explains.
# Recorded beside each operation's 5xx, never a verdict: a run fails on correctness and on its generator (PHASE_16_PLAN.md §5.3), the
# error rates are measured by status (§5.2), and the objectives under load are P16-TSK-029's. The seventh run went red on this test
# when it was a verdict: a 25-second stall of the one machine's disk (commits waiting on WAL writes, the pool's 7 connections a replica
# held, 74 requests waiting, acquisition at its 5 s bound) answered 5xx to sign-ins and reads and 429 from the edge's in-flight bound,
# every money movement resolving by its key and every sample PASS through it.
load_errors_chance() {
    awk -v k="$1" -v n="$2" -v p="$3" 'BEGIN {
        lambda = n * p; term = exp(-lambda); below = 0
        for (i = 0; i < k; i++) { below += term; term = term * lambda / (i + 1) }
        tail = 1 - below; if (tail < 0) tail = 0
        printf "%.4f", tail }'
}

# load_seconds <duration>: 10m, 90s or 1h as seconds.
load_seconds() {
    case $1 in
        *h) echo $(( ${1%h} * 3600 )) ;;
        *m) echo $(( ${1%m} * 60 )) ;;
        *s) echo "${1%s}" ;;
        *) echo 0 ;;
    esac
}

# load_stages: a staged profile's stages, one "seconds factor operation" line each ("-" when every operation runs), shortened in proportion
# to REHEARSAL_LOAD_DURATION exactly as scenarios.js shortens them (at least two seconds each); nothing for a profile without stages.
load_stages() {
    line=$(sed -n 's/^  "stages": \[\[\(.*\)\]\],$/\1/p' "$LOAD_PROFILE_FILE" | head -1)
    [ -n "$line" ] || return 0
    printf '%s\n' "$line" | sed 's/\], \[/\n/g' | tr -d '"' | awk -F', *' -v want="$(load_seconds "${LOAD_DURATION:-0s}")" '
        { s[NR] = $1; f[NR] = $2; o[NR] = (NF > 2) ? $3 : "-"; total += $1 }
        END { scale = (want > 0) ? want / total : 1
              for (i = 1; i <= NR; i++) { t = int(s[i] * scale + 0.5); if (t < 2) t = 2; print t, f[i], o[i] } }'
}

# load_total_seconds: the load's length - its stages' sum for a staged profile, its duration otherwise.
load_total_seconds() {
    if [ -n "$(load_stages)" ]; then
        load_stages | awk '{ t += $1 } END { print t + 0 }'
    else
        load_seconds "$LOAD_DURATION"
    fi
}

# load_expected <operation>: the arrivals the profile asks of it - k6's whole iterations per time unit (model.js rounds rate x factor),
# times the stage's seconds over the time unit, summed over the stages; its rate times the duration for a profile without stages.
load_expected() {
    if [ -n "$(load_stages)" ]; then
        rate=$(load_operation_value "$1" rate)
        unit=$(load_seconds "$(load_operation_value "$1" per)")
        load_stages | awk -v op="$1" -v rate="$rate" -v unit="$unit" '
            { target = ($3 == "-" || $3 == op) ? int(rate * $2 + 0.5) : 0; n += target * $1 / unit } END { printf "%.1f", n }'
    else
        awk -v r="$(load_operation_value "$1" perSecond)" -v s="$(load_seconds "$LOAD_DURATION")" 'BEGIN { printf "%.1f", r * s }'
    fi
}

# load_replicas_patch <rendered overlay directory>: the fleet at LOAD_REPLICAS, in the run's own rendered copy (kind-rehearsal.sh calls it
# for the load scope before anything is applied). The connection budget's formula at that count (DISTRIBUTED_EXECUTION.md section 4a): N +
# 2 x maxSurge + 1 instances, each its pool of 7 and its reporting connection, and the 12 reserved; the quota sized as the kind overlay
# sizes it for three. Three changes nothing: the committed overlay is the three-replica rehearsal.
load_replicas_patch() {
    [ "$LOAD_REPLICAS" != 3 ] || return 0
    instances=$((LOAD_REPLICAS + 3))
    connections=$((instances * 8 + 12))
    # The split files the run applies, and all.yaml beside them, which the harness reads the budget from.
    sed -i "s/^\(  \)minReplicas: 3$/\1minReplicas: $LOAD_REPLICAS/; s/^\(  \)maxReplicas: 3$/\1maxReplicas: $LOAD_REPLICAS/" \
        "$1/HorizontalPodAutoscaler.finapp-app.yaml" "$1/all.yaml"
    for file in "$1"/ConfigMap.finapp-app-config*.yaml "$1/all.yaml"; do
        sed -i "s/^\(  FINAPP_DB_INSTANCES: \)\"6\"$/\1\"$instances\"/; s/^\(  FINAPP_DB_SERVER_MAX_CONNECTIONS: \)\"60\"$/\1\"$connections\"/" "$file"
    done
    sed -i "s/^\( *- \)max_connections=60$/\1max_connections=$connections/" "$1/StatefulSet.finapp-postgres.yaml" "$1/all.yaml"
    sed -i "s/^\(    pods: \)\"9\"$/\1\"$((instances + 3))\"/; s/^\(    requests.cpu: \)3750m$/\1$((instances * 500 + 750))m/;
        s/^\(    requests.memory: \)8704Mi$/\1$((instances * 1024 + 2560))Mi/; s/^\(    limits.cpu: \)\"15\"$/\1\"$((instances * 2 + 3))\"/;
        s/^\(    limits.memory: \)8704Mi$/\1$((instances * 1024 + 2560))Mi/" "$1/ResourceQuota.finapp.yaml" "$1/all.yaml"
    grep -q "minReplicas: $LOAD_REPLICAS\$" "$1/HorizontalPodAutoscaler.finapp-app.yaml" \
        && grep -q "maxReplicas: $LOAD_REPLICAS\$" "$1/HorizontalPodAutoscaler.finapp-app.yaml" \
        && grep -q "FINAPP_DB_INSTANCES: \"$instances\"" "$1"/ConfigMap.finapp-app-config*.yaml \
        && grep -q "FINAPP_DB_SERVER_MAX_CONNECTIONS: \"$connections\"" "$1"/ConfigMap.finapp-app-config*.yaml \
        && grep -q "max_connections=$connections\$" "$1/StatefulSet.finapp-postgres.yaml" \
        && grep -q "pods: \"$((instances + 3))\"" "$1/ResourceQuota.finapp.yaml" \
        || cannot "the rendered kind overlay could not be set to $LOAD_REPLICAS replicas: a value it patches has moved"
    log "the fleet at $LOAD_REPLICAS replicas: $instances instances in the budget, the test-only server at $connections connections (the run's own copy)"
}

pg_admin() {
    # The superuser in the server's maintenance database, where pg_stat_statements lives: the finapp database gains no object.
    kube -n finapp exec -i finapp-postgres-0 -c postgres -- psql -q -X -A -t -U finapp -d postgres "$@"
}

load_prom() {
    telemetry_get "$LOAD_PROM" "/api/v1/query?query=$(urlencode "$1")"
}

# ---------------------------------------------------------------------------------------------------
# The pre-flight: what k6 will run, read before any cluster exists.
# ---------------------------------------------------------------------------------------------------
load_preflight() {
    load_paths
    cp "$DIR/load/"*.js "$DIR/load/"*.json "$DIR/load/"*.sql "$DIR/load/"*.awk "$LOAD_SRC/" || cannot "the load's scenarios could not be copied"
    [ -f "$LOAD_PROFILE_FILE" ] || cannot "no load profile $LOAD_PROFILE_NAME.json under kind-rehearsal/load"
    [ -n "$LOAD_DURATION" ] || LOAD_DURATION=$(load_profile_value duration)
    # P16-TSK-008: the waits sampled and the server's lock log read (E38), the soak's tables, plans and index build (E39).
    LOAD_CONTENTION_ON=$(load_profile_value contention)
    LOAD_SOAK=$(load_profile_value soak)
    [ "$(load_seconds "$LOAD_DURATION")" -gt 0 ] || cannot "the load's duration '$LOAD_DURATION' is not a number of seconds, minutes or hours"
    case $LOAD_REPLICAS in 3 | 4 | 5 | 6 | 7) ;; *) cannot "REHEARSAL_LOAD_REPLICAS is 3 to 7 (today's minimum to its ceiling), not '$LOAD_REPLICAS'" ;; esac
    if [ -n "$(load_stages)" ]; then
        load_stages > "$LOAD_ART/stages.txt"
        log "a staged profile: $(grep -c . "$LOAD_ART/stages.txt") stages, $(load_total_seconds) s"
    fi
    # The bound each movement is resolved within, as a client that lost its answer keeps asking: a provider call whose answer was lost
    # leaves its payment PROCESSING until the platform's sweep asks the provider, once the dispatch is past its dispatched age, and an
    # authorization concluded that way is captured by the stranded-authorization leg one age later. Twice the age, read from the code's
    # default (never copied), and five minutes for the sweep's poll, the claim's lease and the client's pause. The eighth run's client
    # gave up after 30 s of reads with 6 checkouts and a top-up PROCESSING, their provider calls cut by a stall.
    age=$(sed -n 's/.*finapp\.payments\.sweeper\.dispatched-age:PT\([0-9]*\)M}.*/\1/p' \
        "$ROOT/app/src/main/java/com/finapp/app/payments/PaymentSweeperSchedule.java" | head -1)
    case $age in '' | *[!0-9]*) cannot "the payment sweep's dispatched age could not be read from PaymentSweeperSchedule.java" ;; esac
    ! grep -Eqr 'SWEEPER_DISPATCHED_?AGE|sweeper\.dispatched-age' "$ROOT/deploy" \
        || cannot "a deployed overlay sets the payment sweep's dispatched age, which this bound reads from the code's default"
    LOAD_RESOLVE_SECONDS=$((2 * age * 60 + 300))
    if [ "$PROBE" = closed-model ]; then
        cat > "$LOAD_SRC/model.js" <<'JS'
    // PROBE closed-model (P16-TSK-006): the generator with its open model dropped, a fixed population looping as fast as answers come.
    export function openModel(operation, duration) {
        return { executor: 'constant-vus', exec: operation.exec, vus: operation.preAllocatedVUs, duration, gracefulStop: '120s', tags: { op: operation.name } };
    }
JS
        log "PROBE closed-model: the generator's model replaced by a closed one (constant-vus)"
    fi
    dock image inspect "$K6_IMAGE" >/dev/null 2>&1 || dock pull --platform linux/amd64 "$K6_IMAGE" >/dev/null || cannot "could not pull $K6_IMAGE"
    # A fixture of the fixture's shape and no credential: k6's init code reads one.
    mkdir -p "$WORK/preflight"
    printf '{"publicBase":"https://public.finapp.test:30443","password":"","merchantKey":"","customers":[{"login":"","token":"","account":"","card":""}]}\n' \
        > "$WORK/preflight/fixture.json"
    # k6 inspect reads no environment of the container's unless told: the run's variables go as its own -e.
    dock run --rm -v "$(dpath "$LOAD_SRC"):/load:ro" -v "$(dpath "$WORK/preflight"):/fixture:ro" "$K6_IMAGE" inspect \
        -e "LOAD_PROFILE=/load/$LOAD_PROFILE_NAME.json" -e LOAD_FIXTURE=/fixture/fixture.json -e "LOAD_DURATION=$LOAD_DURATION" \
        -e "LOAD_RESOLVE_SECONDS=$LOAD_RESOLVE_SECONDS" /load/scenarios.js \
        > "$LOAD_ART/k6-inspect.json" 2> "$LOAD_ART/k6-inspect.log" || cannot "k6 could not read the scenarios: see load/k6-inspect.log"
    executors=$(sed -n 's/^ *"executor": "\([^"]*\)",*$/\1/p' "$LOAD_ART/k6-inspect.json")
    scenarios=$(printf '%s\n' "$executors" | grep -c . || true)
    wanted=$(load_operations k6 | grep -c . || true)
    closed=$(printf '%s\n' "$executors" | grep -v '^\(constant\|ramping\)-arrival-rate$' | grep -c . || true)
    [ "$wanted" -ge 1 ] || cannot "the profile $LOAD_PROFILE_NAME names no operation for k6"
    [ "$closed" = 0 ] || fail "the generator dropped its open model: its scenarios run $(printf '%s ' $executors)where every one must be an arrival-rate executor (ADR-0126 decision 2): see load/k6-inspect.json"
    [ "$scenarios" = "$wanted" ] || fail "the generator runs $scenarios scenarios where the profile names $wanted operations for k6: see load/k6-inspect.json"
    grep -q '"insecureSkipTLSVerify": true' "$LOAD_ART/k6-inspect.json" && fail "the generator skips TLS verification"
    grep -q '"rps": null' "$LOAD_ART/k6-inspect.json" || fail "the generator caps requests a second (rps), which closes an open model"
    pass "the generator's model: $scenarios scenarios, each an arrival-rate executor, as the profile names them; TLS verified; no request cap"
}

# ---------------------------------------------------------------------------------------------------
# The sample: the correctness pages and the census's committed-fact clauses, every 30 s.
# ---------------------------------------------------------------------------------------------------

# load_sample <n>: one sample, appended to load/samples.tsv. Never calls fail or cannot: it runs in the background too. A sample that
# fails, or cannot read, leaves its line in $WORK/load-sample-failed for the main loop.
load_sample() {
    s_out="$LOAD_ART/samples/sample-$1"
    s_epoch=$(date +%s)
    s_verdict=PASS
    s_detail=""
    s_ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
    load_prom 'count(up{job="finapp"} == 1)' > "$s_out.up.json" 2>&1 || true
    s_up=$(sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)"\].*/\1/p' "$s_out.up.json")
    load_prom "ALERTS{alertstate=\"firing\",alertname=~\"$LOAD_PAGE_REGEX\"}" > "$s_out.pages.json" 2>&1 || true
    load_prom "$LOAD_UNVERIFIABLE" > "$s_out.gauges.json" 2>&1 || true
    s_firing=$(grep -o '"alertname":"[^"]*"' "$s_out.pages.json" 2>/dev/null | sed 's/.*:"\(.*\)"/\1/' | sort -u | tr '\n' ' ')
    if ! grep -q '"status":"success"' "$s_out.up.json" || ! grep -q '"status":"success"' "$s_out.pages.json" \
        || ! grep -q '"status":"success"' "$s_out.gauges.json"; then
        s_verdict=UNREADABLE
        s_detail="Prometheus did not answer"
    elif [ -z "$s_up" ] || [ -z "$s_ready" ] || [ "$s_up" != "$s_ready" ]; then
        s_verdict=UNREADABLE
        s_detail="${s_up:-no} replicas scraped of ${s_ready:-no} ready"
    elif ! grep -q '"result":\[\]' "$s_out.gauges.json"; then
        s_verdict=UNREADABLE
        s_detail="a correctness gauge no instance can read (CorrectnessUnverifiable's expression is not empty)"
    elif [ -n "$s_firing" ]; then
        s_verdict=FAIL
        s_detail="firing: $s_firing"
    fi
    psql_query -f - < "$LOAD_SRC/sample.sql" > "$s_out.census.txt" 2>&1 || true
    s_verdicts=$(grep -c '^\(PASS\|FAIL\)|' "$s_out.census.txt" || true)
    s_failed=$(grep '^FAIL|' "$s_out.census.txt" | cut -d'|' -f2,3 | tr '\n' ';' || true)
    if [ "$s_verdicts" != "$LOAD_CLAUSES" ]; then
        s_verdict=UNREADABLE
        s_detail="$s_detail; the census printed $s_verdicts verdicts of $LOAD_CLAUSES"
    elif [ -n "$s_failed" ]; then
        [ "$s_verdict" = UNREADABLE ] || s_verdict=FAIL
        s_detail="$s_detail; census: $s_failed"
    fi
    printf '%s\t%s\t%s\t%s\t%s/%s\t%s\t%s\n' "$1" "$s_epoch" "$(date -u -d "@$s_epoch" +%H:%M:%S 2>/dev/null || echo -)" "$s_verdict" \
        "${s_up:--}" "${s_ready:--}" "$s_verdicts" "${s_detail:--}" >> "$LOAD_ART/samples.tsv"
    if [ "$s_verdict" != PASS ]; then
        printf 'sample %s %s: %s\n' "$1" "$s_verdict" "$s_detail" >> "$WORK/load-sample-failed"
    fi
}

load_sampler() {
    set +e
    i=1
    while [ ! -f "$WORK/load-stop" ]; do
        started=$(date +%s)
        load_sample "$i"
        i=$((i + 1))
        while [ "$(date +%s)" -lt $((started + 30)) ] && [ ! -f "$WORK/load-stop" ]; do
            sleep 1
        done
    done
}

# PostgreSQL's statistics and the containers' CPU and memory, every 10 s, into the artefacts. Never judged here.
load_statistics() {
    set +e
    while [ ! -f "$WORK/load-stop" ]; do
        started=$(date +%s)
        # The host's time of day, and (P16-TSK-007) its epoch second beside it, so a sample can be cut by stage.
        at=$(date -u -d "@$started" +%H:%M:%S)
        {
            psql_query -f - < "$LOAD_SRC/statistics.sql"
            pg_admin -f - < "$LOAD_SRC/statements.sql"
        } 2>> "$LOAD_ART/statistics-errors.log" | awk -F'|' -v at="$at" -v epoch="$started" -v dir="$LOAD_ART" \
            'NF > 1 {name = $1; line = at "\t" epoch; for (f = 2; f <= NF; f++) line = line "\t" $f; print line >> (dir "/pg-" name ".tsv")}'
        # One call for every container: a call each took a second or two apiece, and stretched the 10 s to 18 under the load.
        dock stats --no-stream --format "$at\t{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" \
            "$NODE" $(dock ps --format '{{.Names}}' --filter "name=$CLUSTER-load-") >> "$LOAD_ART/containers.tsv" 2>/dev/null
        # Every pod's own cgroup, read inside the node in one call (P16-TSK-007): CPU used and throttled against its limit (the saturation
        # a CPU limit shows) and memory, cumulative; the pod's uid names it, and load_census maps the uids to names.
        dock exec "$NODE" sh -c "$LOAD_CGROUPS" 2>/dev/null | sed "s/^/$at	$started	/" >> "$LOAD_ART/pods.tsv"
        while [ "$(date +%s)" -lt $((started + 10)) ] && [ ! -f "$WORK/load-stop" ]; do
            sleep 1
        done
    done
}

# The captured card payments of the load, as the provider would know them, fed to the callback sender every 10 s. Every captured attempt
# is compared with those already fed, never with a cursor over ids or times, so one committed late is fed late, not skipped.
load_feeder() {
    set +e
    : > "$WORK/load-fed"
    while :; do
        stopping=0
        [ -f "$WORK/load-feed-stop" ] && stopping=1
        psql_query -c "SELECT id, auth_reference, auth_provider_reference, capture_reference, capture_provider_reference FROM payments.payment_attempt
            WHERE status = 'CAPTURED' AND rail = 'card' AND created_at >= to_timestamp($LOAD_FROM) ORDER BY created_at" 2>> "$LOAD_ART/feed-errors.log" \
            | tr '|' ' ' | awk -v fed="$WORK/load-fed" 'BEGIN {while ((getline id < fed) > 0) seen[id] = 1; close(fed)} NF == 5 && !($1 in seen) {print; print $1 >> fed}' \
            >> "$CLIENT/callbacks-feed-load.txt"
        [ "$stopping" = 1 ] && break
        waited=0
        while [ "$waited" -lt 10 ] && [ ! -f "$WORK/load-feed-stop" ]; do
            sleep 1
            waited=$((waited + 1))
        done
    done
}

# ---------------------------------------------------------------------------------------------------
# Planted during the run.
# ---------------------------------------------------------------------------------------------------

# PROBE unbalanced-entry: one journal entry with a single debit line of one cent, written by the test-only superuser around the ledger's
# own balance trigger. Nothing removes it: the run is red from here, and the cluster is thrown away.
load_plant_unbalanced() {
    account=$(psql_query -c "SELECT b.ledger_account_id FROM ledger.account_balance b JOIN ledger.ledger_account a ON a.id = b.ledger_account_id
        WHERE a.purpose = 'CUSTOMER_WALLET' ORDER BY b.ledger_account_id LIMIT 1" | tr -d '[:space:]')
    [ -n "$account" ] || cannot "PROBE unbalanced-entry: no customer wallet to plant against"
    psql_query -f - >> "$LOAD_ART/plants.log" 2>&1 <<SQL || cannot "PROBE unbalanced-entry: the entry could not be planted: see load/plants.log"
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL session_replication_role = replica;
WITH entry AS (
    INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, reason, actor_id, correlation_id, causation_id,
                                      idempotency_scope, created_at)
    VALUES (uuidv7(), current_date, current_date, 'POSTING', 'rehearsal-plant-unbalanced', NULL, 'rehearsal-plant', 'rehearsal-plant',
            'rehearsal-plant', 'rehearsal-plant:unbalanced:' || uuidv7(), now())
    RETURNING id)
INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction, amount_minor, currency, scale, seq)
SELECT uuidv7(), entry.id, '$account', 'DEBIT', 1, 'EUR', 2, 0 FROM entry;
COMMIT;
SQL
    log "PROBE unbalanced-entry: a journal entry of one debit line, one cent on a customer wallet, planted by the test-only superuser"
}

# Whether the load has captured a card payment yet.
load_captured() {
    [ "$(psql_query -c "SELECT count(*) > 0 FROM payments.payment_attempt WHERE status = 'CAPTURED' AND rail = 'card'
        AND created_at >= to_timestamp($LOAD_FROM)" 2>/dev/null | tr -d '[:space:]')" = t ]
}

# One PSP settlement file of the load's captures so far, uploaded and attested through the operator edge by the two persons the door
# needs, as telemetry_trace delivers one: gross the captured amount, the PSP's fee of rule set v1 (round_half_up(1.5%) + 0.25), net the rest.
load_settlement() {
    day=$(psql_query -c "SELECT current_date" | tr -d '[:space:]')
    psql_query -c "SELECT capture_provider_reference, captured_amount_minor FROM payments.payment_attempt
        WHERE status = 'CAPTURED' AND rail = 'card' AND created_at >= to_timestamp($LOAD_FROM) ORDER BY created_at" > "$WORK/load-captures.txt" \
        || cannot "the load's captures could not be read for the settlement file"
    # The settlement door's screen refuses a whole file holding a digit run of 13 to 19 across single dashes or spaces that is Luhn-valid
    # (ConservativeScreen: a card number), and the simulator's references are UUIDs, whose digit groups now and then make one (the fourth run
    # was refused at line 313 for psp_cap-20720108-5146-4...). A capture whose reference holds any such run, Luhn or not, is left out of the
    # file and counted; it stays an open expectation, as an unreported capture does.
    awk -F'|' 'NF == 2 { flat = $1; gsub(/[- ]/, "", flat)
        if (flat ~ /[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/) next; print }' "$WORK/load-captures.txt" > "$WORK/load-captures-kept.txt"
    LOAD_SETTLEMENT_LEFT_OUT=$(( $(grep -c '|' "$WORK/load-captures.txt" || true) - $(grep -c '|' "$WORK/load-captures-kept.txt" || true) ))
    mv "$WORK/load-captures-kept.txt" "$WORK/load-captures.txt"
    tag=$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')
    remittance=$(( 10000000 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') ))
    awk -F'|' -v day="$day" -v tag="$tag" -v remittance="$remittance" '
        function decimal(minor) { return sprintf("%d.%02d", int(minor / 100), minor % 100) }
        BEGIN { print "H,SIM_PSP_CSV,1,PSPB-LOAD-" tag ",EUR," day }
        NF == 2 && $2 ~ /^[0-9]+$/ { n++; fee = int(($2 * 15 + 500) / 1000) + 25; net += $2 - fee
            print "D," n ",SALE," decimal($2) "," decimal(fee) ",EUR," day ",,," $1 ",,,,Card capture" }
        END { print "T," n "," decimal(net) ",PSP-REM-82" remittance }' "$WORK/load-captures.txt" > "$CLIENT/load-settlement.csv"
    LOAD_SETTLEMENT_LINES=$(grep -c '^D,' "$CLIENT/load-settlement.csv" || true)
    cp "$CLIENT/load-settlement.csv" "$LOAD_ART/settlement.csv"
    [ "$LOAD_SETTLEMENT_LINES" -ge 1 ] || fail "settlement: no card capture of the load to settle at mid-run"
    psql_query <<SQL >> "$LOAD_ART/settlement.log" 2>&1 || cannot "the reconciliation operators could not be seeded"
INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)
 SELECT uuidv7(), i.id, 'RECONCILIATION_OPERATOR', i.id, now() FROM identity.identity i
  WHERE i.login_identifier IN ('$(sed -n 's/^operator.login=//p' "$CLIENT/state.properties")', '$(sed -n 's/^controller.login=//p' "$CLIENT/state.properties")')
  AND NOT EXISTS (SELECT 1 FROM identity.role_assignment r WHERE r.identity_id = i.id AND r.role_name = 'RECONCILIATION_OPERATOR');
SQL
    client settle-file "$day" load-settlement.csv >> "$LOAD_ART/settlement.log" 2>&1 || fail "settlement: the file could not be delivered: see load/settlement.log"
    LOAD_SETTLEMENT_FILE=$(sed -n 's/^settlement file=\([^ ]*\) .*/\1/p' "$LOAD_ART/settlement.log" | tail -1)
    [ -n "$LOAD_SETTLEMENT_FILE" ] || fail "settlement: no file id came back: see load/settlement.log"
    log "settlement: $LOAD_SETTLEMENT_LINES captures in one PSP file, uploaded and attested during the load ($LOAD_SETTLEMENT_LEFT_OUT left out, their references shaped like a card number)"
}

# ---------------------------------------------------------------------------------------------------
# The scope.
# ---------------------------------------------------------------------------------------------------
load_scope() {
    load_paths
    step load-target
    load_target
    step load-setup
    load_setup
    # The platform's own signals (THREAT_MODEL.md section 6 item 47 (c); telemetry.sh): planted before the load, read after its census.
    step platform-signals-plant
    platform_signals_plant
    step load-run
    load_run
    step load-judge
    load_judge
    step load-census
    load_census
    step platform-signals
    platform_signals
}

# The harness loads only the cluster it made or kept under its own name: the node it execs into must be that cluster's.
load_target() {
    owner=$(dock inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$NODE" 2>/dev/null || true)
    [ "$owner" = "$CLUSTER" ] || fail "the load's target: $NODE is not a node of the kind cluster $CLUSTER (its label says '$owner'); the harness loads only its own cluster"
    address=$(dock inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$NODE" 2>/dev/null || true)
    [ -n "$address" ] && [ "$address" = "$NODE_IP" ] || fail "the load's target: the generator's address $NODE_IP is not the node's ($address)"
    kube config current-context > "$LOAD_ART/context.txt" 2>&1 || true
    pass "the load's target is $NODE, a node of the kind cluster $CLUSTER, at $NODE_IP; the generator is given that address and no kubeconfig"
    # The fleet at the run's count (REHEARSAL_LOAD_REPLICAS): the HorizontalPodAutoscaler holds it there; the release waited for three.
    deadline=$(( $(date +%s) + 900 ))
    until [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "$LOAD_REPLICAS" ]; do
        [ "$(date +%s)" -lt "$deadline" ] || { kube -n finapp get pods -o wide > "$LOAD_ART/pods-at-failure.txt" 2>&1; fail "the fleet did not reach $LOAD_REPLICAS ready replicas: see load/pods-at-failure.txt"; }
        sleep 5
    done
    kube -n finapp get pods -o wide > "$LOAD_ART/pods.txt" 2>&1 || true
    pass "the fleet is $LOAD_REPLICAS ready replicas"
}

load_setup() {
    # The provider's half: the webhook key as the delivered Secret holds it, in the run's directory alone.
    kube -n finapp get secret finapp-app-secrets -o jsonpath='{.data.FINAPP_PAYMENTS_WEBHOOK_KEY}' | base64 -d > "$CLIENT/provider-key" \
        || cannot "the payment webhook's key could not be read from the delivered Secret"
    [ -s "$CLIENT/provider-key" ] || cannot "the delivered Secret holds no payment webhook key"
    client load-customers "$LOAD_CUSTOMERS" >> "$ART/client.log" 2>&1 || fail "the load's customers could not be registered: see client.log"
    psql_query -f - < "$CLIENT/seed-load.sql" >> "$ART/seed.log" 2>&1 || fail "the load's customers could not be made ACTIVE: see seed.log"
    client load-fixture load-fixture.json > "$LOAD_ART/fixture.log" 2>&1 || fail "the load's fixture could not be made: see load/fixture.log"
    fixture_line=$(grep '^load fixture ' "$LOAD_ART/fixture.log" | tail -1)
    LOAD_FUNDED=$(printf '%s' "$fixture_line" | sed -n 's/.* funded=\([0-9]*\) .*/\1/p')
    LOAD_FUNDED_MINOR=$(printf '%s' "$fixture_line" | sed -n 's/.* funded_minor=\([0-9]*\).*/\1/p')
    [ "${LOAD_FUNDED:-0}" = "$LOAD_CUSTOMERS" ] || fail "the load's fixture funded ${LOAD_FUNDED:-0} of $LOAD_CUSTOMERS customers: see load/fixture.log"
    # The funding top-ups are the census's from here on, whatever the load does.
    LOAD_TOPUPS=$LOAD_FUNDED
    LOAD_WALLET_MINOR=$LOAD_FUNDED_MINOR
    # What the generator is handed: the fixture and the edge's CA, and nothing else of the run's directory.
    cp "$CLIENT/load-fixture.json" "$CLIENT/edge-ca.crt" "$LOAD_GEN/" || cannot "the generator's directory could not be made"
    pg_admin -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >> "$LOAD_ART/postgres.log" 2>&1 \
        || fail "pg_stat_statements could not be created: the kind overlay's server must preload it (shared_preload_libraries)"
    # The pages: every correctness signal of SLOS.md section 3, each a rule the cluster's Prometheus loaded, or the sampler would wait for a
    # page that cannot fire.
    LOAD_PAGES=$(sed -n '/^## 3\. /,/^## 4\. /s/^| `\([A-Za-z]*\)` |.*/\1/p' "$ROOT/docs/architecture/SLOS.md")
    pages=$(printf '%s\n' "$LOAD_PAGES" | grep -c . || true)
    [ "$pages" -ge 20 ] || cannot "only $pages correctness pages were read from SLOS.md section 3"
    telemetry_get "$LOAD_PROM" /api/v1/rules > "$LOAD_ART/prometheus-rules.json" 2>&1 || cannot "the cluster's Prometheus did not list its rules"
    for page in $LOAD_PAGES; do
        grep -q "\"name\":\"$page\"" "$LOAD_ART/prometheus-rules.json" || fail "the correctness page $page is not a rule the cluster's Prometheus loaded"
    done
    LOAD_PAGE_REGEX=$(printf '%s\n' "$LOAD_PAGES" | paste -sd'|' -)
    # CorrectnessUnverifiable waits 15 minutes before it tickets; the sampler evaluates its expression at once, so no sample reads blind.
    LOAD_UNVERIFIABLE=$(awk '/- alert: CorrectnessUnverifiable$/ {found = 1; next} found && /^ *expr: / {sub(/^ *expr: /, ""); print; exit}' \
        "$ROOT/infra/prometheus/rules/correctness.yml")
    [ -n "$LOAD_UNVERIFIABLE" ] || cannot "CorrectnessUnverifiable's expression could not be read from correctness.yml"
    # The clauses: census.sql's own, those marked as committed facts, in one read-only repeatable-read transaction.
    {
        sed -n '/^\\set ON_ERROR_STOP/,/^\\pset null/p' "$DIR/census.sql"
        printf 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
        awk '/^-- \[sampled during load\]$/ {take = 1; next} take && /^SELECT CASE/ {grab = 1} grab {print} grab && /;[[:space:]]*$/ {grab = 0; take = 0}' \
            "$DIR/census.sql"
        printf 'COMMIT;\n'
    } > "$LOAD_SRC/sample.sql"
    LOAD_CLAUSES=$(grep -c '^SELECT CASE' "$LOAD_SRC/sample.sql" || true)
    [ "$LOAD_CLAUSES" -ge 8 ] || cannot "only $LOAD_CLAUSES clauses of census.sql are marked as sampled during load"
    cp "$LOAD_SRC/sample.sql" "$LOAD_ART/sample.sql"
    mkdir -p "$LOAD_ART/samples" "$LOAD_ART/k6"
    chmod 0777 "$LOAD_ART/k6"
    printf 'n\tepoch\ttime\tverdict\tscraped/ready\tclauses\tdetail\n' > "$LOAD_ART/samples.tsv"
    : > "$WORK/load-sample-failed"
    # The platform before the load: a sample that must read clean, or the load would be judged on what was there before it.
    load_sample 0
    [ ! -s "$WORK/load-sample-failed" ] || fail "the platform was not clean before the load: $(cat "$WORK/load-sample-failed"): see load/samples"
    pass "the sampler reads: $pages correctness pages, every one a loaded rule, none firing; every gauge readable; $LOAD_CLAUSES census clauses in one snapshot, all PASS"
}

# PROBE slow-statement: the outbox relay's index on pending rows dropped on the run's own server, so every poll scans the outbox; and put
# back, with V006's own definition, after the load.
load_plant_slow_statement() {
    psql_query -c "DROP INDEX platform.outbox_event_pending" >> "$LOAD_ART/plants.log" 2>&1 \
        || cannot "PROBE slow-statement: the relay's index could not be dropped: see load/plants.log"
    log "PROBE slow-statement: platform.outbox_event_pending dropped on the run's own server; every relay poll scans the outbox"
}

load_restore_slow_statement() {
    psql_query -c "CREATE INDEX IF NOT EXISTS outbox_event_pending ON platform.outbox_event (aggregate_id, event_id) WHERE published_at IS NULL" \
        >> "$LOAD_ART/plants.log" 2>&1 || cannot "PROBE slow-statement: the relay's index could not be recreated: see load/plants.log"
    log "PROBE slow-statement: platform.outbox_event_pending recreated as V006 defines it"
}

# ---------------------------------------------------------------------------------------------------
# Contention, the soak and the plans (P16-TSK-008; PHASE_16_PLAN.md sections 6.3-6.5 and 6.7; OPERATIONS_RUNBOOK.md section 11.48).
# ---------------------------------------------------------------------------------------------------

# pg_server_set <artefacts> <name=value>...: the run's own test-only server set by ALTER SYSTEM and a reload, each setting read back by
# a new session. Only what the server logs and auto_explain's window: nothing that changes what a statement does. The server is thrown
# away with the run's cluster. Used by the load scope and by E40 (experiments.sh).
pg_server_set() {
    dir=$1
    shift
    for setting in "$@"; do
        pg_admin -c "ALTER SYSTEM SET ${setting%%=*} = '${setting#*=}'" >> "$dir/server-settings.log" 2>&1 \
            || cannot "the run's server could not be set: ${setting%%=*}"
    done
    pg_admin -c "SELECT pg_reload_conf()" >> "$dir/server-settings.log" 2>&1 || cannot "the run's server could not reload its settings"
    sleep 2
    for setting in "$@"; do
        [ "$(pg_admin -c "SHOW ${setting%%=*}" | tr -d '[:space:]')" = "${setting#*=}" ] \
            || cannot "the run's server does not read ${setting%%=*} as ${setting#*=} after its reload: see server-settings.log"
    done
    printf '%s\tset\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$dir/server-settings.tsv"
}

# pg_server_reset <artefacts> <name>...: back to the server's own values, read back as no longer the run's.
pg_server_reset() {
    dir=$1
    shift
    for name in "$@"; do
        pg_admin -c "ALTER SYSTEM RESET $name" >> "$dir/server-settings.log" 2>&1 || cannot "the run's server could not reset $name"
    done
    pg_admin -c "SELECT pg_reload_conf()" >> "$dir/server-settings.log" 2>&1 || cannot "the run's server could not reload its settings"
    printf '%s\treset\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$dir/server-settings.tsv"
}

# pg_log_follow <file>: the test-only server's log streamed into <file> from now, in the background; PG_LOG_FOLLOWER is its pid. The
# kubelet rotates a container's log at 10 MiB and a read of the logs returns only the current file, so a window that logs every plan, or hours of
# autovacuum and checkpoints, is followed as it is written rather than read once at its end, when its start may have rotated away.
pg_log_follow() {
    kube -n finapp logs -f finapp-postgres-0 -c postgres --since-time="$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$1" 2>&1 &
    PG_LOG_FOLLOWER=$!
    BACKGROUND="$BACKGROUND $PG_LOG_FOLLOWER"
}

# pg_log_stop <pid> <file>: the follower stopped once the server has written what came before; the file must hold what it followed.
pg_log_stop() {
    sleep 3
    kill "$1" 2>/dev/null || true
    wait "$1" 2>/dev/null || true
    [ -s "$2" ]
}

# The settings of a plans window: auto_explain, which the kind overlay preloads and leaves inert, logs a share of every statement's plan
# with ANALYZE and BUFFERS (timing off, its cost), verbose so that each plan ends in its Query Identifier, and never a parameter's value.
PG_PLANS_SETTINGS="auto_explain.log_analyze=on auto_explain.log_buffers=on auto_explain.log_timing=off auto_explain.log_verbose=on auto_explain.log_parameter_max_length=0 auto_explain.log_min_duration=0"

# pg_plans_open <artefacts> <share of statements>: the window opened, logging that share of every statement's plan (the soak a twentieth, E40
# every one for its few minutes); the server's log followed into <artefacts>/plans-server.log from just before.
pg_plans_open() {
    pg_log_follow "$1/plans-server.log"
    PG_PLANS_FOLLOWER=$PG_LOG_FOLLOWER
    # shellcheck disable=SC2086
    pg_server_set "$1" "auto_explain.sample_rate=$2" $PG_PLANS_SETTINGS
    log "plans: auto_explain's window opened on the run's own server (a share of $2 of every statement's plan, no parameter value)"
}

# pg_plans_close <artefacts> <the statements, as a condition on pg_stat_statements s>: the window closed, its threshold reset first; the
# statements that meet the condition ranked by their total time over the run (at most 25) and the slowest plan of each the window caught
# written under <artefacts>/plans (load/plans.awk). PG_PLANS_SUMMARY is the reader's summary.
pg_plans_close() {
    pg_server_reset "$1" auto_explain.log_min_duration auto_explain.sample_rate auto_explain.log_analyze auto_explain.log_buffers \
        auto_explain.log_timing auto_explain.log_verbose auto_explain.log_parameter_max_length
    mkdir -p "$1/plans"
    pg_admin -F "$(printf '\t')" -c "SELECT row_number() OVER (ORDER BY s.total_exec_time DESC), s.queryid, round(s.total_exec_time::numeric, 1),
        s.calls FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = 'finapp' AND ($2)
        ORDER BY s.total_exec_time DESC LIMIT 25" > "$1/plans/top.tsv" 2>> "$1/server-settings.log" \
        || cannot "plans: the statements could not be ranked from pg_stat_statements"
    pg_log_stop "$PG_PLANS_FOLLOWER" "$1/plans-server.log" || cannot "plans: the test-only server's log was not followed: see plans-server.log"
    awk -v top="$1/plans/top.tsv" -v dir="$1/plans" -f "$DIR/load/plans.awk" "$1/plans-server.log" > "$1/plans-summary.txt" \
        || cannot "plans: the server's log could not be read for its plans"
    PG_PLANS_SUMMARY=$(tr '\n' ' ' < "$1/plans-summary.txt")
    log "plans: $PG_PLANS_SUMMARY(plans/, plans/seq-scans.tsv)"
}

load_deadlocks() {
    pg_admin -c "SELECT deadlocks FROM pg_stat_database WHERE datname = 'finapp'" | tr -d '[:space:]'
}

# A profile with "contention": true (concurrent.json, E38). Before the load: the server logs every lock wait of deadlock_timeout (1 s) or
# more, the 250 ms sampler of load/contention.sql runs under its own application name, and it is shown a wait it must see.
load_contention_start() {
    pg_server_set "$LOAD_ART" log_lock_waits=on
    pg_log_follow "$LOAD_ART/postgres-server.log"
    LOAD_CONTENTION_LOG=$PG_LOG_FOLLOWER
    LOAD_DEADLOCKS_FROM=$(load_deadlocks)
    case $LOAD_DEADLOCKS_FROM in '' | *[!0-9]*) cannot "the server's deadlock counter could not be read" ;; esac
    # Every 250 ms for the load, its resolution bound and ten minutes more; stopped by name at the load's end.
    count=$(( ($(load_total_seconds) + LOAD_RESOLVE_SECONDS + 600) * 4 ))
    kube -n finapp exec -i finapp-postgres-0 -c postgres -- env PGAPPNAME=finapp-contention-sampler psql -q -X -A -t -U finapp -d finapp \
        -v "count=$count" -f - < "$LOAD_SRC/contention.sql" > "$LOAD_ART/contention-samples.txt" 2> "$LOAD_ART/contention-errors.log" &
    LOAD_CONTENTION=$!
    BACKGROUND="$BACKGROUND $LOAD_CONTENTION"
    load_contention_control
}

# The control: one wallet's balance row held for four seconds by the test-only superuser while a second session waits on it, before any
# load. The sampler must place that wait on that row in at least four samples, and the server's log must hold it (load_contention_judge),
# so a sampler or a log reader that sees nothing cannot pass for a platform with no waits.
load_contention_control() {
    account=$(psql_query -c "SELECT b.ledger_account_id FROM ledger.account_balance b JOIN ledger.ledger_account a ON a.id = b.ledger_account_id
        WHERE a.purpose = 'CUSTOMER_WALLET' ORDER BY b.ledger_account_id LIMIT 1" | tr -d '[:space:]')
    [ -n "$account" ] || cannot "the contention control: no customer wallet's balance row to wait on"
    sleep 2
    psql_query -c "BEGIN; SELECT 1 FROM ledger.account_balance WHERE ledger_account_id = '$account' FOR UPDATE; SELECT pg_sleep(4); COMMIT;" \
        >> "$LOAD_ART/plants.log" 2>&1 &
    holder=$!
    sleep 1
    psql_query -c "SELECT 1 FROM ledger.account_balance WHERE ledger_account_id = '$account' FOR UPDATE" >> "$LOAD_ART/plants.log" 2>&1 \
        || cannot "the contention control's waiter could not take the row after its holder"
    wait "$holder" 2>/dev/null || true
    sleep 2
    seen=$(grep -c "^wait|[^|]*|[^|]*|[^|]*|[^|]*|balance:CUSTOMER_WALLET:[A-Z]*:$account|" "$LOAD_ART/contention-samples.txt" || true)
    [ "$seen" -ge 4 ] || fail "the contention sampler did not see the wait it was shown: $seen samples placed the control's waiter on its wallet's balance row, where it waited about three seconds: see load/contention-samples.txt"
    pass "the contention sampler saw the wait it was shown: the control's waiter on one wallet's balance row, in $seen samples, resolved to the row"
}

# After the load (or a stopped one): the sampler stopped, the server's log read and the waits turned into each row's distribution per
# stage. Returns non-zero, never fails the run: load_stopped calls it too.
load_contention_read() {
    pg_admin -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'finapp-contention-sampler'" \
        >> "$LOAD_ART/server-settings.log" 2>&1 || true
    wait "$LOAD_CONTENTION" 2>/dev/null || true
    LOAD_DEADLOCKS_END=$(load_deadlocks)
    pg_log_stop "$LOAD_CONTENTION_LOG" "$LOAD_ART/postgres-server.log" || return 1
    LOAD_DEADLOCKS_AFTER=$(load_deadlocks)
    : > "$LOAD_ART/deadlocks.txt"
    awk -v dir="$LOAD_ART" -f "$LOAD_SRC/locks.awk" "$LOAD_ART/postgres-server.log" > "$LOAD_ART/locks-summary.txt" || return 1
    awk -F'|' -v stages="$WORK/stages.txt" -v t0="${LOAD_T0:-0}" -v speed="${LOAD_SPEED:-1}" -f "$LOAD_SRC/contention.awk" \
        "$LOAD_ART/contention-samples.txt" > "$LOAD_ART/contention-rows.tsv" || return 1
}

# The judgement: the sampler read the whole load, every deadlock the server counted has its record in the server's log, and the control's
# wait is in that log too. What explains each deadlock is the run's record (section 11.48); a deadlock no known path explains is a defect.
load_contention_judge() {
    load_contention_read || cannot "the contention sampler's or the server's record could not be read: see load/contention-errors.log"
    read -r _ samples interval gap <<EOF
$(tail -1 "$LOAD_ART/contention-rows.tsv" | tr '\t' ' ' | sed 's/interval_ms //; s/largest_gap_ms //')
EOF
    secs=$(load_total_seconds)
    [ "${samples:-0}" -ge $((secs * 3)) ] || fail "the contention sampler read $samples samples over a load of $secs s, fewer than three a second: see load/contention-samples.txt"
    [ "${gap:-99999}" -le 5000 ] || fail "the contention sampler went $gap ms without a sample: no wait could be seen in that time"
    logged=$(sed -n 's/^deadlocks //p' "$LOAD_ART/locks-summary.txt")
    serial=$(sed -n 's/^serialisation //p' "$LOAD_ART/locks-summary.txt")
    counted=$((LOAD_DEADLOCKS_END - LOAD_DEADLOCKS_FROM))
    most=$((LOAD_DEADLOCKS_AFTER - LOAD_DEADLOCKS_FROM))
    [ "${logged:-0}" -ge "$counted" ] && [ "${logged:-0}" -le "$most" ] \
        || fail "the server counted $counted deadlocks over the load and its log holds ${logged:-no} records: a deadlock without its record cannot be explained: see load/deadlocks.txt"
    awk -F'\t' 'NR > 1 && $4 == "acquired" && $5 == "account_balance" {found = 1} END {exit !found}' \
        "$LOAD_ART/lock-waits.tsv" || fail "the server's log holds no wait of a second or more on a balance row, not even the control's: log_lock_waits was not read"
    pass "contention: $samples samples every $interval ms (the longest gap $gap ms), each row's waits per stage in load/contention-rows.tsv; $logged deadlocks and $serial serialisation failures, each with its record in load/deadlocks.txt, the server's own count $counted; the long waits in load/lock-waits.tsv"
    [ "${logged:-0}" = 0 ] || log "recorded: $logged deadlocks, each to be explained by a known path in the run's record (OPERATIONS_RUNBOOK.md section 11.48): an unexplained one is a defect"
}

# A profile with "soak": true (soak.json, E39). The server logs every autovacuum run and every checkpoint, and the named tables are read
# every 60 s (load/soak.sql); near the load's end a plans window, and then a transactional index build on the journal.
load_soak_start() {
    pg_server_set "$LOAD_ART" log_autovacuum_min_duration=0 log_checkpoints=on
    pg_log_follow "$LOAD_ART/postgres-soak.log"
    LOAD_SOAK_LOG=$PG_LOG_FOLLOWER
    load_soak_sampler &
    BACKGROUND="$BACKGROUND $!"
}

load_soak_sampler() {
    set +e
    while [ ! -f "$WORK/load-stop" ]; do
        started=$(date +%s)
        at=$(date -u -d "@$started" +%H:%M:%S)
        psql_query -f - < "$LOAD_SRC/soak.sql" 2>> "$LOAD_ART/statistics-errors.log" | awk -F'|' -v at="$at" -v epoch="$started" -v dir="$LOAD_ART" \
            'NF > 1 {name = $1; line = at "\t" epoch; for (f = 2; f <= NF; f++) line = line "\t" $f; print line >> (dir "/pg-" name ".tsv")}'
        while [ "$(date +%s)" -lt $((started + 60)) ] && [ ! -f "$WORK/load-stop" ]; do
            sleep 1
        done
    done
}

# load_soak_tick <elapsed> <seconds>: the plans window over 80% to 83% of the load, then the index build at 86%, each once. Counted in the
# host's seconds, while k6 runs its stages on the VM's monotonic clock, which ran 8% fast in E30 (section 11.45): the load can end at 92% of
# its written length in the host's seconds, so nothing here waits for a later fraction than that.
load_soak_tick() {
    if [ -z "${LOAD_PLANS_OPENED:-}" ] && [ "$1" -ge $(($2 * 80 / 100)) ]; then
        pg_plans_open "$LOAD_ART" 0.05
        LOAD_PLANS_OPENED=1
    fi
    if [ -n "${LOAD_PLANS_OPENED:-}" ] && [ -z "${LOAD_PLANS_CLOSED:-}" ] && [ "$1" -ge $(($2 * 83 / 100)) ]; then
        pg_plans_close "$LOAD_ART" "true"
        LOAD_PLANS_CLOSED=1
    fi
    if [ -z "${LOAD_INDEX:-}" ] && [ "$1" -ge $(($2 * 86 / 100)) ]; then
        load_index_build &
        LOAD_INDEX=$!
        BACKGROUND="$BACKGROUND $LOAD_INDEX"
    fi
}

# The transactional index build on the journal (load/index-build.sql; THREAT_MODEL.md section 6 item 42 (b)), while the load still runs: up
# to three tries if the migrator's lock_timeout (3 s) refuses its lock, each recorded; a build past ten minutes is cancelled by its own
# statement_timeout. Runs in the background: its record is load/index-build.tsv, judged after the load.
load_index_build() {
    set +e
    # The migrator's own lock_timeout, read from its code, never copied (MigrationPlan.LOCK_TIMEOUT).
    seconds=$(sed -n 's/.*Duration LOCK_TIMEOUT = Duration\.ofSeconds(\([0-9]*\));.*/\1/p' \
        "$ROOT/migrator/src/main/java/com/finapp/migrator/MigrationPlan.java" | head -1)
    case $seconds in '' | *[!0-9]*) echo "the migrator's lock_timeout could not be read from MigrationPlan.java" >> "$LOAD_ART/index-build.log"; return 1 ;; esac
    try=1
    while [ "$try" -le 3 ]; do
        printf 'try\t%s\t%s\n' "$try" "$(date +%s)" >> "$LOAD_ART/index-build.tsv"
        if psql_query -v "lock_timeout=${seconds}s" -f - < "$LOAD_SRC/index-build.sql" >> "$LOAD_ART/index-build.tsv" 2>> "$LOAD_ART/index-build.log"; then
            return 0
        fi
        grep -q 'lock timeout' "$LOAD_ART/index-build.log" || return 1
        try=$((try + 1))
        sleep 10
    done
    return 1
}

# After the load: the build's write stop, the table's size and rows, and each operation's answers in the build's window and in the ten
# minutes before it, from the generator's series (load/index-build-latency.tsv).
load_index_judge() {
    [ -n "${LOAD_INDEX:-}" ] || fail "the transactional index build never started: the soak ended before 86% of its length in the host's seconds"
    wait "$LOAD_INDEX" 2>/dev/null || true
    # The try that built: its own "before", the last one ahead of the "built" line (a try its lock_timeout refused wrote one too).
    before=$(awk -F'|' '$1 == "before" {b = $2} $1 == "built" {print b; exit}' "$LOAD_ART/index-build.tsv")
    built=$(awk -F'|' '$1 == "built" {print $2; exit}' "$LOAD_ART/index-build.tsv")
    dropped=$(awk -F'|' '$1 == "dropped" {print $3; exit}' "$LOAD_ART/index-build.tsv")
    [ -n "$before" ] && [ -n "$built" ] || fail "the transactional index build did not complete under the load (three tries at the migrator's 3 s lock_timeout, a ten-minute bound on the build): see load/index-build.log"
    [ "$dropped" = 0 ] || fail "the index build's throwaway index was not dropped: see load/index-build.tsv"
    for series in "$LOAD_ART"/k6/*/k6-series.csv.gz; do gzip -dc "$series"; done | awk -F, -v from="$before" -v to="$built" '
        $1 ~ /^latency_/ { t = $2 + 0; op = substr($1, 9)
            w = (t >= from && t <= to + 30) ? "build" : (t >= from - 600 && t < from) ? "before" : ""
            if (w == "") next
            n[op SUBSEP w]++; if ($3 + 0 > mx[op SUBSEP w]) mx[op SUBSEP w] = $3 + 0; s[op SUBSEP w] += $3 }
        END { print "op\twindow\tanswers\tmean_ms\tmax_ms"
              for (k in n) { split(k, p, SUBSEP); printf "%s\t%s\t%d\t%.0f\t%.0f\n", p[1], p[2], n[k], s[k] / n[k], mx[k] } }' \
        > "$LOAD_ART/index-build-latency.tsv"
    stop=$(awk -v a="$before" -v b="$built" 'BEGIN {printf "%.1f", b - a}')
    rows=$(awk -F'|' '$1 == "before" {r = $5} $1 == "built" {print r; exit}' "$LOAD_ART/index-build.tsv")
    bytes=$(awk -F'|' '$1 == "before" {b = $3} $1 == "built" {print b; exit}' "$LOAD_ART/index-build.tsv")
    pass "the transactional index build on ledger.journal_line ($rows rows, $bytes bytes of heap) stopped its writes for $stop s under the load, from its lock request to its commit; each operation's answers in that window beside the ten minutes before it in load/index-build-latency.tsv"
}

# After a soak: the server's log over the load for its autovacuum runs and checkpoints; WAL bytes per money movement.
load_soak_judge() {
    pg_log_stop "$LOAD_SOAK_LOG" "$LOAD_ART/postgres-soak.log" || cannot "the soak: the test-only server's log was not followed: see load/postgres-soak.log"
    grep 'automatic vacuum of table\|automatic analyze of table' "$LOAD_ART/postgres-soak.log" > "$LOAD_ART/autovacuum.txt" || true
    grep -A2 'checkpoint complete' "$LOAD_ART/postgres-soak.log" > "$LOAD_ART/checkpoints.txt" || true
    samples=$(grep -c . "$LOAD_ART/pg-soak-database.tsv" 2>/dev/null || true)
    [ "${samples:-0}" -ge $(( $(load_total_seconds) / 60 * 9 / 10 )) ] || fail "the soak's tables were read $samples times, not every 60 s: see load/statistics-errors.log"
    grep -q 'checkpoint complete' "$LOAD_ART/checkpoints.txt" || fail "the soak's log holds no checkpoint: log_checkpoints was not read"
    [ -n "${LOAD_PLANS_CLOSED:-}" ] || fail "the soak's plans window never closed"
    case $PG_PLANS_SUMMARY in *"caught 0 "*) fail "the soak's plans window caught no plan of any top statement: see load/plans-summary.txt" ;; esac
    wal=$(awk -F'\t' 'NR == 1 {a = $4} {b = $4} END {print b - a}' "$LOAD_ART/pg-soak-database.tsv")
    moved=$(awk -F'\t' '$1 == "transfer" || $1 == "checkout" || $1 == "topup" {n += $4} END {print n + 0}' "$LOAD_ART/k6/report.tsv")
    printf 'wal bytes\t%s\nmovements\t%s\nbytes a movement\t%s\n' "$wal" "$moved" "$(awk -v w="$wal" -v m="$moved" 'BEGIN {if (m > 0) printf "%.0f", w / m}')" \
        > "$LOAD_ART/wal-per-movement.tsv"
    load_index_judge
    pass "the soak: the named tables read $samples times (load/pg-soak-tables.tsv), $(grep -c . "$LOAD_ART/autovacuum.txt" || true) autovacuum runs and $(grep -c 'checkpoint complete' "$LOAD_ART/checkpoints.txt" || true) checkpoints from the server's log, $wal WAL bytes over $moved money movements; the top statements' plans: $PG_PLANS_SUMMARY"
}

load_run() {
    LOAD_RUN_TAG=$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')
    pg_admin -c "SELECT pg_stat_statements_reset()" >> "$LOAD_ART/postgres.log" 2>&1 || true
    : > "$CLIENT/callbacks-feed-load.txt"
    [ "$PROBE" != slow-statement ] || load_plant_slow_statement
    [ "$LOAD_CONTENTION_ON" != true ] || load_contention_start
    [ "$LOAD_SOAK" != true ] || load_soak_start
    # The event pipeline's runs (P16-TSK-013, kind-rehearsal/pipeline.sh: E47, E48): a scale's first count, the offsets, the sampler.
    pipeline_start
    LOAD_FROM=$(db_epoch)
    load_clock start
    LOAD_STARTED=$(date +%s)
    secs=$(load_total_seconds)
    load_statistics &
    BACKGROUND="$BACKGROUND $!"
    if [ "$PROBE" = sampler-disabled ]; then
        log "PROBE sampler-disabled: the load runs with no correctness sampler"
    else
        load_sampler &
        BACKGROUND="$BACKGROUND $!"
    fi
    load_feeder &
    LOAD_FEEDER=$!
    BACKGROUND="$BACKGROUND $LOAD_FEEDER"
    # The provider's callbacks from as many addresses as the generator's, each sender its share of the feed: a provider sends from a few
    # addresses, and one carrying every callback met the edge's per-source hold (the fifth run: 1397 of 3934 answered 429).
    s=0
    while [ "$s" -lt "$LOAD_GENERATORS" ]; do
        dock run -d --name "$CLUSTER-load-callbacks-$s" --network kind --add-host "public.finapp.test:$NODE_IP" \
            -v "$(dpath "$CLIENT"):/work" -v "$(dpath "$DIR"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Rehearsal.java /work load-callbacks load \
            "$s" "$LOAD_GENERATORS" >/dev/null || cannot "the callback sender $s could not start"
        CONTAINERS="$CONTAINERS $CLUSTER-load-callbacks-$s"
        s=$((s + 1))
    done
    # The generator as LOAD_GENERATORS instances, each its own container and so its own address, each running its exact share of every
    # arrival rate (k6's execution segments). Customers arrive from many addresses; one address carrying the whole population would meet
    # the edge's per-source tiers, which are a client's limits, not the platform's, and measure them instead (OPERATIONS_RUNBOOK.md
    # section 11.43's first run).
    sequence=$(awk -v n="$LOAD_GENERATORS" 'BEGIN {s = "0"; for (i = 1; i <= n; i++) s = s "," i "/" n; print s}')
    i=0
    while [ "$i" -lt "$LOAD_GENERATORS" ]; do
        mkdir -p "$LOAD_ART/k6/$i"
        chmod 0777 "$LOAD_ART/k6/$i"
        dock run -d --name "$CLUSTER-load-k6-$i" --network kind --cpus 1 --add-host "public.finapp.test:$NODE_IP" \
            -e SSL_CERT_FILE=/generator/edge-ca.crt -e "LOAD_PROFILE=/load/$LOAD_PROFILE_NAME.json" -e LOAD_FIXTURE=/generator/load-fixture.json \
            -e "LOAD_RUN=$LOAD_RUN_TAG" -e "LOAD_DURATION=$LOAD_DURATION" -e "LOAD_RESOLVE_SECONDS=$LOAD_RESOLVE_SECONDS" \
            -v "$(dpath "$LOAD_GEN"):/generator:ro" -v "$(dpath "$LOAD_SRC"):/load:ro" -v "$(dpath "$LOAD_ART/k6/$i"):/out" \
            "$K6_IMAGE" run --quiet --no-color --execution-segment "$i/$LOAD_GENERATORS:$((i + 1))/$LOAD_GENERATORS" \
            --execution-segment-sequence "$sequence" --out csv=/out/k6-series.csv.gz /load/scenarios.js >/dev/null || cannot "k6 could not start"
        CONTAINERS="$CONTAINERS $CLUSTER-load-k6-$i"
        i=$((i + 1))
    done
    log "load $LOAD_PROFILE_NAME for $LOAD_DURATION ($secs s), run $LOAD_RUN_TAG, $LOAD_CUSTOMERS customers, $LOAD_GENERATORS generator addresses"
    planted=""
    settled=""
    while load_generating; do
        if [ -s "$WORK/load-sample-failed" ]; then
            touch "$WORK/load-stop" "$WORK/load-feed-stop" "$CLIENT/stop-callbacks-load"
            load_generators stop
            load_stopped
            fail "a sample failed during the load, which stopped it: $(cat "$WORK/load-sample-failed" | tr '\n' ' ' | cut -c1-600)see load/samples.tsv"
        fi
        elapsed=$(( $(date +%s) - LOAD_STARTED ))
        [ "$LOAD_SOAK" != true ] || load_soak_tick "$elapsed" "$secs"
        # E48's next count at its share of the load (P16-TSK-013); nothing in any other run.
        pipeline_tick "$elapsed" "$secs"
        if [ "$PROBE" = unbalanced-entry ] && [ -z "$planted" ] && [ "$elapsed" -ge 60 ]; then
            load_plant_unbalanced
            planted=1
        fi
        # At mid-run, or at the load's first card capture after it: the isolation profile captures nothing before its fifth stage.
        if [ -z "$settled" ] && [ "$elapsed" -ge $((secs / 2)) ] && load_captured; then
            load_settlement
            settled=1
        fi
        sleep 5
    done
    LOAD_ENDED=$(date +%s)
    [ -n "$settled" ] || fail "settlement: the load captured no card payment to settle"
    load_clock end
    [ "$PROBE" != slow-statement ] || load_restore_slow_statement
    k6_status=$(load_generators status)
    load_generators logs
    # The last captures fed, then the sender drained: everything fed before its stop is delivered and answered.
    sleep 15
    load_keep_senders
    # One sample after the load's end, so the samples cover all of it; then the samplers stop.
    if [ "$PROBE" != sampler-disabled ]; then
        deadline=$((LOAD_ENDED + 60))
        while [ "$(awk -F'\t' -v end="$LOAD_ENDED" 'NR > 1 && $2 >= end {n++} END {print n + 0}' "$LOAD_ART/samples.tsv")" = 0 ] \
            && [ "$(date +%s)" -lt "$deadline" ]; do
            sleep 2
        done
    fi
    touch "$WORK/load-stop"
    sleep 12
    [ ! -s "$WORK/load-sample-failed" ] || fail "a sample failed during the load: $(cat "$WORK/load-sample-failed" | tr '\n' ' ' | cut -c1-600)see load/samples.tsv"
    [ "$k6_status" = 0 ] || cannot "a generator ended with status $k6_status: see load/k6-*.log"
    load_report
    log "the load ran $((LOAD_ENDED - LOAD_STARTED)) s"
}

# load_generating: whether any generator instance still runs.
load_generating() {
    g=0
    while [ "$g" -lt "$LOAD_GENERATORS" ]; do
        [ "$(dock inspect -f '{{.State.Running}}' "$CLUSTER-load-k6-$g" 2>/dev/null || echo false)" = true ] && return 0
        g=$((g + 1))
    done
    return 1
}

# load_generators <stop|status|logs>: every instance stopped; the first non-zero exit status, or 0; every instance's log kept.
load_generators() {
    g=0
    worst=0
    while [ "$g" -lt "$LOAD_GENERATORS" ]; do
        case $1 in
            stop) dock stop -t 30 "$CLUSTER-load-k6-$g" >/dev/null 2>&1 || true
                  dock logs "$CLUSTER-load-k6-$g" > "$LOAD_ART/k6-$g.log" 2>&1 || true ;;
            status) code=$(dock inspect -f '{{.State.ExitCode}}' "$CLUSTER-load-k6-$g" 2>/dev/null || echo unknown)
                    [ "$worst" != 0 ] || worst=$code ;;
            logs) dock logs "$CLUSTER-load-k6-$g" > "$LOAD_ART/k6-$g.log" 2>&1 || true ;;
        esac
        g=$((g + 1))
    done
    [ "$1" != status ] || echo "$worst"
}

# The instances' reports as one: counts summed per operation; the percentiles taken from every instance's samples together, never
# averaged across instances; the generator's own figures summed, its peak the largest.
load_report() {
    g=0
    while [ "$g" -lt "$LOAD_GENERATORS" ]; do
        [ -s "$LOAD_ART/k6/$g/report.tsv" ] || cannot "the generator $g wrote no report: see load/k6-$g.log"
        g=$((g + 1))
    done
    head -1 "$LOAD_ART/k6/0/report.tsv" > "$LOAD_ART/k6/report.tsv"
    for op in $(load_operations k6); do
        counts=$(awk -F'\t' -v op="$op" '$1 == op {rate = $2; for (f = 3; f <= 11; f++) s[f] += $f}
            END {line = op "\t" rate; for (f = 3; f <= 11; f++) line = line "\t" s[f] + 0; print line}' "$LOAD_ART"/k6/*/report.tsv)
        percentiles=$(for series in "$LOAD_ART"/k6/*/k6-series.csv.gz; do gzip -dc "$series"; done \
            | awk -F, -v m="latency_$op" -v f="flow_$op" '$1 == m {print "l", $3} $1 == f {print "f", $3}' \
            | sort -k1,1 -k2,2g | awk '
                $1 == "l" {l[++nl] = $2} $1 == "f" {fl[++nf] = $2}
                function at(a, n, p) { if (n == 0) return 0; i = int(n * p + 0.999999); if (i < 1) i = 1; return a[i] }
                END {printf "%.1f\t%.1f\t%.1f\t%.1f\t%.1f", at(l, nl, 0.50), at(l, nl, 0.95), at(l, nl, 0.99), at(l, nl, 1), at(fl, nf, 0.99)}')
        printf '%s\t%s\n' "$counts" "$percentiles" >> "$LOAD_ART/k6/report.tsv"
    done
    # Every answer that was not a success, per operation and status (0: none, a timeout or a connection refused), from every instance's
    # samples (PHASE_16_PLAN.md §5.2: error and timeout rates by status). Columns found by name in each instance's header.
    printf 'op\tstatus\tanswers\n' > "$LOAD_ART/k6/statuses.tsv"
    for series in "$LOAD_ART"/k6/*/k6-series.csv.gz; do gzip -dc "$series"; done \
        | awk -F, '$1 == "metric_name" {for (f = 1; f <= NF; f++) c[$f] = f; next}
            $1 == "http_reqs" && $c["status"] !~ /^2/ {n[$c["scenario"] "\t" ($c["status"] == "" ? 0 : $c["status"])]++}
            END {for (k in n) print k "\t" n[k]}' | sort >> "$LOAD_ART/k6/statuses.tsv"
    head -1 "$LOAD_ART/k6/0/generator.tsv" > "$LOAD_ART/k6/generator.tsv"
    awk -F'\t' 'FNR == 2 {d += $1; if ($2 + 0 > p) p = $2 + 0; m += $3; r += $4; if ($5 + 0 > t) t = $5 + 0} END {print d + 0 "\t" p + 0 "\t" m + 0 "\t" r + 0 "\t" t + 0}' \
        "$LOAD_ART"/k6/*/generator.tsv >> "$LOAD_ART/k6/generator.tsv"
    load_stage_tables || cannot "the per-stage report could not be written from the series"
}

# load_stage_tables: per operation and stage (P16-TSK-007, load/stages.awk), from every instance's series; a profile without stages is one
# stage, its duration. The load's start is the earliest sample any instance wrote. Returns non-zero, never stops the run: a run stopped by
# a sample writes its tables too, before it fails.
load_stage_tables() {
    if [ -s "$LOAD_ART/stages.txt" ]; then
        cp "$LOAD_ART/stages.txt" "$WORK/stages.txt"
    else
        printf '%s 1 -\n' "$(load_seconds "$LOAD_DURATION")" > "$WORK/stages.txt"
    fi
    LOAD_T0=$(for series in "$LOAD_ART"/k6/*/k6-series.csv.gz; do gzip -dc "$series"; done | awk -F, 'NR > 1 && $2 ~ /^[0-9]+$/ && (m == "" || $2 < m) {m = $2} END {print m}')
    [ -n "$LOAD_T0" ] || return 1
    # How many of k6's seconds passed in one of the host's over the load (load_clock's samples): 1 where the clocks agree.
    LOAD_SPEED=$(awk -F'\t' '$1 == "start" {h0 = $2; m0 = $3} $1 == "end" {h1 = $2; m1 = $3}
        END {if (h1 > h0 && m1 > m0) printf "%.4f", (m1 - m0) / (h1 - h0); else print 1}' "$LOAD_ART/clock.tsv" 2>/dev/null || echo 1)
    printf 't0\t%s\nk6 seconds a host second\t%s\n' "$LOAD_T0" "$LOAD_SPEED" > "$LOAD_ART/k6/t0.txt"
    for series in "$LOAD_ART"/k6/*/k6-series.csv.gz; do gzip -dc "$series"; done \
        | awk -v pass=count -v stages="$WORK/stages.txt" -v t0="$LOAD_T0" -v speed="$LOAD_SPEED" -v out="$WORK/latencies.tsv" \
            -f "$LOAD_SRC/stages.awk" > "$WORK/stage-counts.tsv" || return 1
    sort -t"$(printf '\t')" -k1,1 -k2,2 -k3,3g "$WORK/latencies.tsv" \
        | awk -v pass=report -v stages="$WORK/stages.txt" -v speed="$LOAD_SPEED" -v counts="$WORK/stage-counts.tsv" \
            -v ops="$(load_operations k6 | tr '\n' ' ')" -f "$LOAD_SRC/stages.awk" > "$LOAD_ART/k6/stages.tsv" || return 1
    rm -f "$WORK/latencies.tsv"
}

# load_keep_senders: the callback senders stopped and drained, and their records kept.
load_keep_senders() {
    touch "$WORK/load-feed-stop"
    [ -z "${LOAD_FEEDER:-}" ] || wait "$LOAD_FEEDER" 2>/dev/null || true
    touch "$CLIENT/stop-callbacks-load"
    : > "$LOAD_ART/callbacks.log"
    s=0
    while [ "$s" -lt "$LOAD_GENERATORS" ]; do
        dock wait "$CLUSTER-load-callbacks-$s" >/dev/null 2>&1 || true
        dock logs "$CLUSTER-load-callbacks-$s" >> "$LOAD_ART/callbacks.log" 2>&1 || true
        cp "$CLIENT/callbacks-load-$s.tsv" "$CLIENT/callbacks-load-$s.summary" "$LOAD_ART/" 2>/dev/null || true
        s=$((s + 1))
    done
    cp "$CLIENT/callbacks-feed-load.txt" "$LOAD_ART/" 2>/dev/null || true
}

# load_stopped: what a run a sample stopped had measured until then (P16-TSK-007) - the senders' records, the generators' logs, the
# per-stage tables, the saturation signals and the counts - kept before it fails, so a run that ends at its first limit still says where.
# Nothing here judges, and nothing here can turn the run's red into anything else.
load_stopped() {
    LOAD_ENDED=$(date +%s)
    load_clock end
    [ "$PROBE" != slow-statement ] || load_restore_slow_statement
    load_generators logs
    load_keep_senders
    touch "$WORK/load-stop"
    {
        load_stage_tables || echo "the per-stage tables could not be written"
        load_saturation || echo "saturation signals absent from Prometheus:$LOAD_MISSING"
        load_resources || echo "the per-stage resources could not be written"
        [ "${LOAD_CONTENTION_ON:-}" != true ] || load_contention_read || echo "the contention record could not be read"
    } >> "$LOAD_ART/stopped.log" 2>&1
    log "the load ran $((LOAD_ENDED - LOAD_STARTED)) s before a sample stopped it; what it measured is in load/k6/stages.tsv and load/stages-resources.tsv"
}

# load_clock <label>: the host's clock, the node's monotonic clock and the node's own wall clock, side by side (P16-TSK-007). k6 schedules
# arrivals and times requests on the monotonic clock of the machine's Linux VM, which on the reference machine runs faster than the
# host's clock (Docker Desktop on WSL2); the samples around the load say by how much, so a rate or a latency can be read in the host's
# seconds as well as in k6's.
load_clock() {
    printf '%s\t%s\t%s\n' "$1" "$(date +%s.%N)" "$(dock exec "$NODE" sh -c 'cut -d" " -f1 /proc/uptime; date +%s.%N' | tr '\n' '\t')" \
        >> "$LOAD_ART/clock.tsv"
}

load_judge() {
    report="$LOAD_ART/k6/report.tsv"
    secs=$(load_total_seconds)
    stress=$(load_profile_value stress)
    # The samples: the run's invariants read throughout, or the run is not a run.
    coverage=$(awk -F'\t' -v start="$LOAD_STARTED" -v end="$LOAD_ENDED" '
        NR > 1 && $1 != 0 { n++; if ($4 != "PASS") bad++; if (first == "" ) first = $2; if (last != "" && $2 - last > gap) gap = $2 - last; last = $2 }
        END { if (n == 0) { print "0 samples"; exit }
              if (first - start > 45) print "the first sample " first - start " s into the load"
              else if (end - last > 45) print "the last sample " end - last " s before its end"
              else if (gap > 45) print "a gap of " gap " s between samples"
              else if (bad > 0) print bad " samples not PASS"
              else print "ok " n }' "$LOAD_ART/samples.tsv")
    case $coverage in
        ok*) pass "the invariants were read throughout the load: ${coverage#ok } samples, every one PASS, none more than 45 s apart, the first and last within 45 s of its ends" ;;
        *) fail "the samples do not cover the load ($coverage): a run whose invariants were not read throughout cannot pass: see load/samples.tsv" ;;
    esac
    # The generator held its model.
    read -r dropped peak maxvus requests runms <<EOF
$(tail -1 "$LOAD_ART/k6/generator.tsv")
EOF
    # The stress profile runs past the platform's break by design (P16-TSK-007): an iteration dropped there is the queue outgrowing the
    # generator's bound of in-flight iterations, recorded per stage (k6/stages.tsv) and read with the break, never a pass of anything.
    if [ "$stress" = true ]; then
        [ "$dropped" = 0 ] || log "recorded stress: the generator dropped $dropped iterations, past the break: see load/k6/stages.tsv"
    else
        [ "$dropped" = 0 ] || fail "the generator dropped $dropped iterations: it could not hold the arrival rate, so this run under-reports latency and is invalid"
    fi
    cpu=$(awk -F'\t' -v c="$CLUSTER-load-k6-" 'index($2, c) == 1 {v = $3; sub(/%/, "", v); if (v + 0 > m) m = v + 0} END {print m + 0}' "$LOAD_ART/containers.tsv")
    [ "$(awk -v c="$cpu" 'BEGIN {print (c < 90) ? "ok" : "saturated"}')" = ok ] \
        || fail "the generator saturated: an instance's CPU reached $cpu% of its one-core limit, so the run is invalid"
    # Each operation's arrivals against the profile's: its rate times the duration, or, staged, the sum over the stages (load_expected);
    # under stress the dropped iterations are arrivals the generator could not start, so they count.
    bad=""
    for op in $(load_operations k6); do
        want=$(load_expected "$op")
        got=$(awk -F'\t' -v op="$op" '$1 == op {print $3}' "$report")
        lost=0
        [ "$stress" != true ] || lost=$(awk -F'\t' -v op="$op" '$1 == op {n += $17} END {print n + 0}' "$LOAD_ART/k6/stages.tsv")
        bad="$bad$(awk -v op="$op" -v got="${got:-0}" -v lost="$lost" -v want="$want" 'BEGIN {
            n = got + lost; if (n < want * 0.98 || n > want * 1.02 + 1) printf "%s %d of %.0f; ", op, n, want }')"
    done
    [ -z "$bad" ] || fail "arrivals off the profile's rates: $bad: see load/k6/report.tsv"
    if [ "$stress" = true ]; then held="$dropped iterations dropped past the break (recorded)"; else held="no iteration dropped"; fi
    pass "the generator held its open model: $held, every operation's arrivals within 2% of its rate, each instance's CPU at most $cpu% of its one core"
    # Money: every movement resolved by its key, every retried key answered alike, and nothing refused.
    for op in $(load_operations k6); do
        line=$(awk -F'\t' -v op="$op" '$1 == op' "$report")
        [ -n "$line" ] || fail "the report has no row for $op"
        IFS="$(printf '\t')" read -r _ _ iterations ok refused unresolved attempts failed conflicts retried agreed p50 p95 p99 max flow99 <<EOF
$line
EOF
        target=$(load_operation_value "$op" errorTarget)
        if [ "$(load_operation_value "$op" money)" = true ]; then
            [ "$unresolved" = 0 ] || fail "$op: $unresolved movements unresolved by their keys: see load/k6/report.tsv"
            [ "$refused" = 0 ] || fail "$op: $refused movements refused: see load/k6/report.tsv"
            [ "$retried" -ge 1 ] || fail "$op: no key was retried, so one effect per key was not tried"
            [ "$agreed" = "$retried" ] || fail "$op: $agreed of $retried retried keys answered alike"
            pass "$op: $iterations movements, every one resolved by its key and none refused, $agreed of $retried retried keys answered alike"
            # A checkout whose creating answer was lost (scenarios.js ABANDONED): known to have moved nothing, recorded with the platform's
            # answer that lost it, never a verdict; the census counts no payment for it.
            abandoned=$(awk -F'\t' -v op="$op" 'NR > 1 && $1 == op {n += $22} END {print n + 0}' "$LOAD_ART/k6/stages.tsv")
            [ "$abandoned" = 0 ] || log "recorded $op: $abandoned sessions abandoned, every answer under their keys a replay without the session token: see load/k6/stages.tsv"
        fi
        [ "$attempts" -gt 0 ] || fail "$op: no attempt was made"
        # The errors, recorded by status and never a verdict here (load_errors_chance says why); latency likewise.
        server=$(awk -F'\t' -v op="$op" '$1 == op && $2 ~ /^5/ {n += $3} END {print n + 0}' "$LOAD_ART/k6/statuses.tsv")
        others=$(awk -F'\t' -v op="$op" '$1 == op && $2 !~ /^5/ {s = s " " $2 "x" $3} END {print (s == "") ? " none" : s}' "$LOAD_ART/k6/statuses.tsv")
        chance=$(load_errors_chance "$server" "$attempts" "$target")
        log "recorded $op: 5xx $server of $attempts attempts (objective $target, a chance of $chance at it), other answers:$others; p50 $p50 ms, p95 $p95 ms, p99 $p99 ms, max $max ms"
    done
    # The callbacks: two per captured card payment, each answered, a duplicate accepted, their refusals recorded as the operations' errors
    # are; every sender's summary read, and a sender that wrote none is a sender nobody can vouch for.
    senders=$(ls "$LOAD_ART"/callbacks-load-*.summary 2>/dev/null | grep -c . || true)
    [ "$senders" = "$LOAD_GENERATORS" ] || fail "callbacks: $senders of $LOAD_GENERATORS senders wrote their summary: see load/callbacks.log"
    read -r fed unanswered first again first_all refused_callbacks malformed <<EOF
$(cat "$LOAD_ART"/callbacks-load-*.summary | awk '
    { for (f = 1; f <= NF; f++) { t = $f; gsub(/[{},]/, "", t); split(t, kv, "=")
        if (kv[1] == "fed") fed += kv[2]; else if (kv[1] == "unanswered") un += kv[2]
        else if (kv[1] == "malformed") bad += kv[2]
        else if (kv[1] ~ /^(first|again)\.-?[0-9]+$/) { split(kv[1], d, "."); n = kv[2] + 0
            if (d[1] == "first") fa += n
            if (d[2] == "204") { if (d[1] == "first") f204 += n; else a204 += n } else refused += n } } }
    END { print fed + 0, un + 0, f204 + 0, a204 + 0, fa + 0, refused + 0, bad + 0 }')
EOF
    [ "$fed" -ge 1 ] || fail "callbacks: no captured card payment was fed to the senders: see load/callbacks.log"
    [ "$malformed" = 0 ] || fail "callbacks: $malformed feed lines the senders could not read"
    [ "$unanswered" = 0 ] || fail "callbacks: $unanswered deliveries still unanswered when the senders stopped"
    [ "$first_all" = "$((fed * 2))" ] || fail "callbacks: $first_all first deliveries where $fed captures make $((fed * 2))"
    [ "$again" -ge 1 ] || fail "callbacks: no delivery was accepted again under its event id, so a duplicate was not tried"
    delivered=$((first_all + again + refused_callbacks - (first_all - first)))
    chance=$(load_errors_chance "$refused_callbacks" "$delivered" "$(load_operation_value callbacks errorTarget)")
    pass "callbacks: $fed captured card payments from $senders senders, every delivery answered, $first first deliveries and $again redeliveries accepted (204)"
    log "recorded callbacks: $refused_callbacks of $delivered deliveries not accepted (objective $(load_operation_value callbacks errorTarget), a chance of $chance at it): see load/callbacks-load-*.summary"
    # Spread: the generator reaches the replicas through the edge, never one pod.
    ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')
    window=$(( ($(date +%s) - LOAD_STARTED) / 60 + 1 ))m
    # Each route beside the operation that sends it; a route whose operation the profile does not drive (the concurrent profile drives no
    # balance read, P16-TSK-008) is recorded as such and not asked of the replicas.
    driven=" $(load_operations k6 | tr '\n' ' ')"
    for pair in 'transfer|uri="/v1/transfers",method="POST"' 'topup|uri=~"/v1/payments/.+/confirmation",method="POST"' \
        'checkout|uri="/v1/checkout/sessions/confirmation",method="POST"' 'read|uri=~"/v1/me/accounts/.+/balance",method="GET"'; do
        route=${pair#*|}
        case $driven in *" ${pair%%|*} "*) ;; *) printf '%s\tnot driven\n' "$route" >> "$LOAD_ART/spread.tsv"; continue ;; esac
        load_prom "count(sum by (instance) (increase(http_server_requests_seconds_count{job=\"finapp\",$route}[$window])) != 0)" > "$LOAD_ART/spread.json" 2>&1 || true
        served=$(sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)"\].*/\1/p' "$LOAD_ART/spread.json")
        # A fleet scaled during the load (E48, P16-TSK-013) was served by every replica it had, more than are ready at its end.
        [ -n "$served" ] && { [ "$served" = "$ready" ] || { [ -n "$PIPELINE_SCALE" ] && [ "$served" -ge "$ready" ]; }; } \
            || fail "spread: ${served:-no} of $ready ready replicas served $route during the load"
        printf '%s\t%s\n' "$route" "$served" >> "$LOAD_ART/spread.tsv"
    done
    pass "spread: every one of $ready ready replicas served each driven route of transfers, payment and checkout confirmations and balance reads through the edge"
    # Settlement: the file taken, every line an item of the reconciliation, and every capture allocated to its expectation during and
    # after the load. A record of the file is two lines once taken, its capture and the PSP's fee (the ninth run counted both against
    # the records and read 1972 of 986); the fee's line is an item reconciled as a fee, never allocated to a capture's expectation.
    if [ -n "${LOAD_SETTLEMENT_FILE:-}" ]; then
        deadline=$(( $(date +%s) + 600 ))
        while :; do
            counts=$(psql_query -c "SELECT count(DISTINCT l.id) FILTER (WHERE l.line_type = 'CAPTURE') || '|' || count(DISTINCT l.id) || '|' ||
                count(DISTINCT i.id) || '|' || count(DISTINCT a.external_item_id)
                FROM settlement.line l LEFT JOIN reconciliation.external_item i ON i.settlement_line_id = l.id
                LEFT JOIN reconciliation.allocation a ON a.external_item_id = i.id WHERE l.file_id = '$LOAD_SETTLEMENT_FILE'::uuid" | tr -d '[:space:]')
            IFS='|' read -r captures lines items allocated <<EOF
$counts
EOF
            [ "${allocated:-0}" = "$LOAD_SETTLEMENT_LINES" ] && break
            [ "$(date +%s)" -lt "$deadline" ] || break
            sleep 10
        done
        psql_query -c "SELECT status FROM settlement.file WHERE id = '$LOAD_SETTLEMENT_FILE'::uuid" > "$LOAD_ART/settlement-status.txt" 2>&1 || true
        printf 'capture lines\t%s\nlines\t%s\nitems\t%s\nallocated\t%s\nrecords in the file\t%s\n' "$captures" "$lines" "$items" "$allocated" \
            "$LOAD_SETTLEMENT_LINES" >> "$LOAD_ART/settlement.log"
        [ "${captures:-0}" = "$LOAD_SETTLEMENT_LINES" ] || fail "settlement: ${captures:-0} capture lines taken of the $LOAD_SETTLEMENT_LINES records the file holds"
        [ "${items:-0}" = "${lines:-0}" ] || fail "settlement: ${items:-0} of ${lines:-0} lines became reconciliation items"
        [ "${allocated:-0}" = "$LOAD_SETTLEMENT_LINES" ] || fail "settlement: ${allocated:-0} of $LOAD_SETTLEMENT_LINES captures allocated to their expectations within 10 min"
        pass "settlement: one PSP file of $LOAD_SETTLEMENT_LINES captures taken whole ($lines lines with their fees), every line a reconciliation item, every capture allocated to its expectation"
    fi
    # P16-TSK-008: the contended rows' waits and the deadlocks (E38); the soak's tables, log, plans and index build (E39).
    [ "$LOAD_CONTENTION_ON" != true ] || load_contention_judge
    [ "$LOAD_SOAK" != true ] || load_soak_judge
}

# The platform's side per stage (P16-TSK-007, load/resources.awk): every sampled signal as "kind name epoch value" - the pods' cgroups by
# group (the replicas, PostgreSQL, the broker, the edge, the telemetry stack, the provider simulator, the rest), the fleet's JVM and pool sums from Prometheus, the
# database's own counters - cut by the generator's stages in the host's seconds.
load_resources() {
    kube get pods -A -o jsonpath='{range .items[*]}{.metadata.uid}{"\t"}{.metadata.namespace}/{.metadata.name}{"\n"}{end}' 2>/dev/null \
        | awk -F'\t' '{gsub(/-/, "_", $1); print $1 "\t" $2}' > "$LOAD_ART/pods-names.tsv"
    {
        awk -F'\t' 'FNR == NR {name[$1] = $2; next}
            NF >= 7 {
                n = name[$3]; g = "other"
                if (n ~ /^finapp\/finapp-app-/) g = "app"; else if (n ~ /^finapp\/finapp-postgres-/) g = "postgres"
                else if (n ~ /^finapp\/finapp-kafka-/) g = "broker"; else if (n ~ /^finapp-edge\//) g = "edge"
                else if (n ~ /^finapp-observability\//) g = "telemetry"; else if (n ~ /^finapp-rehearsal\//) g = "simulator"
                cpu[$2 SUBSEP g] += $4; nthr[$2 SUBSEP g] += $5; thr[$2 SUBSEP g] += $6; mem[$2 SUBSEP g] += $7; per[$2 SUBSEP g] += $8
                seen[$2 SUBSEP g] = 1 }
            END { for (k in seen) { split(k, p, SUBSEP)
                print "c cpu_" p[2] "_cores " p[1] " " cpu[k] / 1e9
                print "c throttled_" p[2] "_task_seconds " p[1] " " thr[k] / 1e9
                print "c throttled_" p[2] "_periods " p[1] " " nthr[k]
                print "c limited_" p[2] "_periods " p[1] " " per[k]
                print "g memory_" p[2] "_mib " p[1] " " mem[k] / 1048576 } }' "$LOAD_ART/pods-names.tsv" "$LOAD_ART/pods.tsv" 2>/dev/null
        for json in "$LOAD_ART"/prometheus/fleet_*.json "$LOAD_ART/prometheus/outbox_oldest_seconds.json" "$LOAD_ART/prometheus/inbox_lag_records.json"; do
            [ -f "$json" ] || continue
            name=$(basename "$json" .json)
            grep -o '\[[0-9.]*,"[^"]*"\]' "$json" | tr -d '[]"' | awk -F, -v n="$name" '$2 != "NaN" {print "g " n " " int($1) " " $2}'
        done
        awk -F'\t' 'NF >= 4 {print "c db_statement_seconds " $2 " " $4 / 1000; print "c db_statements " $2 " " $3}' "$LOAD_ART/pg-statements_total.tsv" 2>/dev/null
        awk -F'\t' 'NF >= 13 {print "c db_commits " $2 " " $3; print "c db_rollbacks " $2 " " $4; print "c db_deadlocks " $2 " " $5
            print "c db_temp_bytes " $2 " " $7; print "c db_blocks_read " $2 " " $8; print "c db_rows_written " $2 " " $10 + $11 + $12}' \
            "$LOAD_ART/pg-database.tsv" 2>/dev/null
        awk -F'\t' 'NF >= 6 {print "c wal_bytes " $2 " " $5; print "c wal_full_page_images " $2 " " $4}' "$LOAD_ART/pg-wal.tsv" 2>/dev/null
        awk -F'\t' 'NF >= 7 {print "c checkpoint_write_seconds " $2 " " $6 / 1000; print "c checkpoint_sync_seconds " $2 " " $7 / 1000}' \
            "$LOAD_ART/pg-checkpointer.tsv" 2>/dev/null
        awk -F'\t' 'NF >= 8 && $3 == "finapp" { e = $2; seen[e] = 1
                if ($5 == "active") active[e] += $8
                if ($7 ~ /WAL/) wal[e] += $8
                if ($6 == "Lock") lock[e] += $8
                if ($6 == "IO") io[e] += $8 }
            END { for (e in seen) { print "g db_sessions_active " e " " active[e] + 0; print "g db_waiting_wal " e " " wal[e] + 0
                print "g db_waiting_lock " e " " lock[e] + 0; print "g db_waiting_io " e " " io[e] + 0 } }' "$LOAD_ART/pg-activity.tsv" 2>/dev/null
        awk -F'\t' 'NF >= 3 {print "g db_lock_waits " $2 " " $3}' "$LOAD_ART/pg-locks.tsv" 2>/dev/null
    } > "$WORK/signals.txt"
    awk -v stages="$WORK/stages.txt" -v t0="$LOAD_T0" -v speed="$LOAD_SPEED" -f "$LOAD_SRC/resources.awk" "$WORK/signals.txt" \
        > "$LOAD_ART/stages-resources.tsv" || return 1
    # A table with no replica CPU or no database time is no reading of the platform's side: the caller fails the run on it.
    [ "$(grep -c . "$LOAD_ART/stages-resources.tsv")" -ge 2 ] && grep -q 'cpu_app_cores_per_s' "$LOAD_ART/stages-resources.tsv" \
        && grep -q 'db_statement_seconds_per_s' "$LOAD_ART/stages-resources.tsv"
}

# load_saturation: the saturation signals as Prometheus holds them over the run, what the run wrote counted by minute, and the statements
# with their text, into the artefacts. LOAD_MISSING names the signals Prometheus held no series for; it returns non-zero then.
load_saturation() {
    mkdir -p "$LOAD_ART/prometheus"
    to=$(date +%s)
    for signal in \
        "cpu|process_cpu_usage{job=\"finapp\"}" \
        "system_cpu|system_cpu_usage{job=\"finapp\"}" \
        "heap|sum by (instance) (jvm_memory_used_bytes{job=\"finapp\",area=\"heap\"})" \
        "gc|sum by (instance) (rate(jvm_gc_pause_seconds_sum{job=\"finapp\"}[1m]))" \
        "threads|jvm_threads_live_threads{job=\"finapp\"}" \
        "tomcat_busy|tomcat_threads_busy_threads{job=\"finapp\"}" \
        "hikari_active|hikaricp_connections_active{job=\"finapp\"}" \
        "hikari_pending|hikaricp_connections_pending{job=\"finapp\"}" \
        "hikari_acquire_max|hikaricp_connections_acquire_seconds_max{job=\"finapp\"}" \
        "outbox_age|max(finapp_outbox_oldest_publishable_seconds)" \
        "inbox_lag|max(finapp_inbox_lag)" \
        "requests|sum by (instance) (rate(http_server_requests_seconds_count{job=\"finapp\"}[1m]))" \
        "fleet_heap_bytes|sum(jvm_memory_used_bytes{job=\"finapp\",area=\"heap\"})" \
        "fleet_gc_seconds_per_s|sum(rate(jvm_gc_pause_seconds_sum{job=\"finapp\"}[1m]))" \
        "fleet_gc_pauses_per_s|sum(rate(jvm_gc_pause_seconds_count{job=\"finapp\"}[1m]))" \
        "fleet_threads|sum(jvm_threads_live_threads{job=\"finapp\"})" \
        "fleet_busy_request_threads|sum(tomcat_threads_busy_threads{job=\"finapp\"})" \
        "fleet_pool_active|sum(hikaricp_connections_active{job=\"finapp\"})" \
        "fleet_pool_pending|sum(hikaricp_connections_pending{job=\"finapp\"})" \
        "fleet_pool_held_connections|sum(rate(hikaricp_connections_usage_seconds_sum{job=\"finapp\"}[1m]))" \
        "fleet_pool_wait_seconds|sum(rate(hikaricp_connections_acquire_seconds_sum{job=\"finapp\"}[1m])) / sum(rate(hikaricp_connections_acquire_seconds_count{job=\"finapp\"}[1m]))" \
        "fleet_pool_wait_max_seconds|max(hikaricp_connections_acquire_seconds_max{job=\"finapp\"})" \
        "fleet_requests_per_s|sum(rate(http_server_requests_seconds_count{job=\"finapp\"}[1m]))" \
        "outbox_oldest_seconds|max(finapp_outbox_oldest_publishable_seconds)" \
        "inbox_lag_records|max(finapp_inbox_lag)"; do
        # The name before the first bar, the query after it (no query holds a bar).
        telemetry_get "$LOAD_PROM" "/api/v1/query_range?query=$(urlencode "${signal#*|}")&start=$LOAD_STARTED&end=$to&step=10" \
            > "$LOAD_ART/prometheus/${signal%%|*}.json" 2>&1 || true
    done
    # What the run wrote, counted by minute (P16-TSK-007): the events and when they were published, the journal's entries and lines, the
    # events by type and the records each consumer took. Counts and times only, by the test-only server's superuser.
    psql_query -F "$(printf '\t')" -c "SELECT extract(epoch FROM date_trunc('minute', occurred_at))::bigint, count(*),
        count(*) FILTER (WHERE published_at IS NOT NULL), round(avg(extract(epoch FROM (coalesce(first_published_at, published_at) - occurred_at)))::numeric, 3),
        round(max(extract(epoch FROM (coalesce(first_published_at, published_at) - occurred_at)))::numeric, 3)
        FROM platform.outbox_event GROUP BY 1 ORDER BY 1" > "$LOAD_ART/written-events-per-minute.tsv" 2>&1 || true
    psql_query -F "$(printf '\t')" -c "SELECT extract(epoch FROM date_trunc('minute', e.created_at))::bigint, count(DISTINCT e.id), count(*)
        FROM ledger.journal_entry e JOIN ledger.journal_line l ON l.entry_id = e.id GROUP BY 1 ORDER BY 1" > "$LOAD_ART/written-entries-per-minute.tsv" 2>&1 || true
    psql_query -F "$(printf '\t')" -c "SELECT event_type, count(*) FROM platform.outbox_event GROUP BY 1 ORDER BY 2 DESC" \
        > "$LOAD_ART/written-events-by-type.tsv" 2>&1 || true
    psql_query -F "$(printf '\t')" -c "SELECT consumer, count(*) FROM platform.inbox_message GROUP BY 1 ORDER BY 2 DESC" \
        > "$LOAD_ART/consumed-by-consumer.tsv" 2>&1 || true
    # The settlement files and the reconciliation batches since the load began: when each arrived, was attested and last changed state.
    psql_query -F "$(printf '\t')" -c "SELECT 'file', id, status, extract(epoch FROM received_at)::bigint, extract(epoch FROM attested_at)::bigint,
        extract(epoch FROM status_changed_at)::bigint FROM settlement.file WHERE received_at >= to_timestamp($LOAD_FROM)
        UNION ALL SELECT 'batch', id, status, extract(epoch FROM created_at)::bigint, NULL, extract(epoch FROM status_changed_at)::bigint
        FROM reconciliation.reconciliation_batch WHERE created_at >= to_timestamp($LOAD_FROM) ORDER BY 4" \
        > "$LOAD_ART/written-settlement.tsv" 2>&1 || true
    pg_admin -c "SELECT s.queryid, s.calls, round(s.total_exec_time::numeric, 1), round(s.mean_exec_time::numeric, 3), s.rows,
        left(regexp_replace(s.query, '\s+', ' ', 'g'), 300) FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
        WHERE d.datname = 'finapp' ORDER BY s.total_exec_time DESC LIMIT 50" > "$LOAD_ART/pg-statements-final.txt" 2>&1 || true
    LOAD_MISSING=""
    for signal in cpu heap threads hikari_active outbox_age inbox_lag requests fleet_heap_bytes fleet_pool_held_connections; do
        grep -q '"values":\[\[' "$LOAD_ART/prometheus/$signal.json" || LOAD_MISSING="$LOAD_MISSING $signal"
    done
    [ -z "$LOAD_MISSING" ]
}

load_census() {
    report="$LOAD_ART/k6/report.tsv"
    ok_of() { awk -F'\t' -v op="$1" '$1 == op {print $4}' "$report"; }
    topup_minor=$(load_profile_value topUpMinor)
    LOAD_TRANSFERS=$(ok_of transfer)
    LOAD_CHECKOUTS=$(ok_of checkout)
    LOAD_TOPUPS=$((LOAD_FUNDED + $(ok_of topup)))
    LOAD_WALLET_MINOR=$((LOAD_FUNDED_MINOR + $(ok_of topup) * topup_minor))
    census load
    # The event pipeline's numbers and a scale's judgement (P16-TSK-013), after the census has counted one effect per record.
    pipeline_report
    # A last sample, after the census: nothing the load left behind pages afterwards.
    : > "$WORK/load-sample-failed"
    load_sample final
    [ ! -s "$WORK/load-sample-failed" ] || fail "the sample after the load failed: $(cat "$WORK/load-sample-failed")"
    pass "the sample after the load and its census passed"
    # The saturation signals, what the run wrote and the statements (load_saturation), and the per-stage table of the platform's side.
    load_saturation || fail "saturation signals absent from Prometheus over the run:$LOAD_MISSING: see load/prometheus"
    load_resources || fail "the per-stage resources hold no replica CPU or no database time: see load/stages-resources.tsv and load/pods.tsv"
    stats=$(cat "$LOAD_ART"/pg-database.tsv 2>/dev/null | grep -c . || true)
    [ "$stats" -ge $(( $(load_total_seconds) / 12 )) ] || fail "PostgreSQL's statistics were read $stats times over the load, not every 10 s: see load/statistics-errors.log"
    pass "the saturation signals are in the artefacts: Prometheus over the run, PostgreSQL's statistics $stats times, the containers' CPU and memory"
    # The workload and the machine it ran on (ADR-0126 decision 1).
    {
        printf 'profile\t%s\n' "$LOAD_PROFILE_NAME"
        if [ "$LOAD_DURATION" = "$(load_profile_value duration)" ] && [ "$LOAD_CUSTOMERS" = 20 ] && [ -z "$PROBE" ]; then
            printf 'workload\t%s\n' "$(load_profile_value name)"
        else
            printf 'workload\tnot the profile'"'"'s: duration %s, %s customers, probe %s\n' "$LOAD_DURATION" "$LOAD_CUSTOMERS" "${PROBE:-none}"
        fi
        printf 'duration\t%s\n' "$LOAD_DURATION"
        printf 'replicas\t%s\n' "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')"
        printf 'commit\t%s\n' "$(git -C "$(dpath "$ROOT")" rev-parse HEAD)"
        printf 'machine\t%s\n' "$(dock info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes, {{.OperatingSystem}}, {{.KernelVersion}}' 2>/dev/null)"
        printf 'generator\tk6 %s %s\n' "$K6_VERSION" "$K6_DIGEST"
        printf 'driven\t%s\n' "$(load_operations k6 | tr '\n' ' ')$(load_operations callbacks | tr '\n' ' ')$(load_operations harness | tr '\n' ' ')"
        printf 'not driven\t%s\n' "$(load_operations none | tr '\n' ' ')"
        # P16-TSK-007: the fleet asked for, the stages run and the machine's clocks.
        printf 'replicas asked\t%s\n' "$LOAD_REPLICAS"
        printf 'stages\t%s\n' "$(tr '\n' ';' < "$WORK/stages.txt")"
        printf 'k6 seconds a host second\t%s (the VM'"'"'s monotonic clock against the host'"'"'s, load/clock.tsv: k6 schedules and times by the first)\n' "$LOAD_SPEED"
    } > "$LOAD_ART/workload.txt"
}
