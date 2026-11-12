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
#   - the provider's callbacks (two per captured card payment) and one settlement file at mid-run were delivered and taken;
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
# REHEARSAL_LOAD_DURATION shortens a run (for a probe); such a run is not the profile's workload and load/workload.txt says so.

LOAD_PROFILE_NAME=${REHEARSAL_LOAD_PROFILE:-demonstration}
LOAD_CUSTOMERS=${REHEARSAL_LOAD_CUSTOMERS:-20}
LOAD_DURATION=${REHEARSAL_LOAD_DURATION:-}
# How many addresses the generator arrives from: each instance its own container, running its share of every rate.
LOAD_GENERATORS=${REHEARSAL_LOAD_GENERATORS:-4}
K6_IMAGE="$K6_REPOSITORY@$K6_DIGEST"
LOAD_PROM=finapp-prometheus:9090
# The census's expected counts take these from load_judge: zero in every other scope.
LOAD_TRANSFERS=0
LOAD_TOPUPS=0
LOAD_CHECKOUTS=0
LOAD_WALLET_MINOR=0

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
# in this many attempts (a one-sided test, Poisson at attempts x objective). Below 1% the errors are more than the objective explains and
# the run fails; above it a few errors are what an objective of 99.9% allows in a ten-minute sample, and they are recorded, not hidden.
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
    cp "$DIR/load/"*.js "$DIR/load/"*.json "$DIR/load/"*.sql "$LOAD_SRC/" || cannot "the load's scenarios could not be copied"
    [ -f "$LOAD_PROFILE_FILE" ] || cannot "no load profile $LOAD_PROFILE_NAME.json under kind-rehearsal/load"
    [ -n "$LOAD_DURATION" ] || LOAD_DURATION=$(load_profile_value duration)
    [ "$(load_seconds "$LOAD_DURATION")" -gt 0 ] || cannot "the load's duration '$LOAD_DURATION' is not a number of seconds, minutes or hours"
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
        -e "LOAD_PROFILE=/load/$LOAD_PROFILE_NAME.json" -e LOAD_FIXTURE=/fixture/fixture.json -e "LOAD_DURATION=$LOAD_DURATION" /load/scenarios.js \
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
        at=$(date -u +%H:%M:%S)
        {
            psql_query -f - < "$LOAD_SRC/statistics.sql"
            pg_admin -f - < "$LOAD_SRC/statements.sql"
        } 2>> "$LOAD_ART/statistics-errors.log" | awk -F'|' -v at="$at" -v dir="$LOAD_ART" \
            'NF > 1 {name = $1; line = at; for (f = 2; f <= NF; f++) line = line "\t" $f; print line >> (dir "/pg-" name ".tsv")}'
        for container in "$NODE" $(dock ps --format '{{.Names}}' --filter "name=$CLUSTER-load-"); do
            dock stats --no-stream --format "$at\t{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" "$container" >> "$LOAD_ART/containers.tsv" 2>/dev/null
        done
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
    log "settlement: $LOAD_SETTLEMENT_LINES captures in one PSP file, uploaded and attested at mid-run ($LOAD_SETTLEMENT_LEFT_OUT left out, their references shaped like a card number)"
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

load_run() {
    LOAD_RUN_TAG=$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')
    pg_admin -c "SELECT pg_stat_statements_reset()" >> "$LOAD_ART/postgres.log" 2>&1 || true
    : > "$CLIENT/callbacks-feed-load.txt"
    LOAD_FROM=$(db_epoch)
    LOAD_STARTED=$(date +%s)
    secs=$(load_seconds "$LOAD_DURATION")
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
            -e "LOAD_RUN=$LOAD_RUN_TAG" -e "LOAD_DURATION=$LOAD_DURATION" \
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
            fail "a sample failed during the load, which stopped it: $(cat "$WORK/load-sample-failed" | tr '\n' ' ' | cut -c1-600)see load/samples.tsv"
        fi
        elapsed=$(( $(date +%s) - LOAD_STARTED ))
        if [ "$PROBE" = unbalanced-entry ] && [ -z "$planted" ] && [ "$elapsed" -ge 60 ]; then
            load_plant_unbalanced
            planted=1
        fi
        if [ -z "$settled" ] && [ "$elapsed" -ge $((secs / 2)) ]; then
            load_settlement
            settled=1
        fi
        sleep 5
    done
    LOAD_ENDED=$(date +%s)
    k6_status=$(load_generators status)
    load_generators logs
    # The last captures fed, then the sender drained: everything fed before its stop is delivered and answered.
    sleep 15
    touch "$WORK/load-feed-stop"
    wait "$LOAD_FEEDER" 2>/dev/null || true
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
    head -1 "$LOAD_ART/k6/0/generator.tsv" > "$LOAD_ART/k6/generator.tsv"
    awk -F'\t' 'FNR == 2 {d += $1; if ($2 + 0 > p) p = $2 + 0; m += $3; r += $4; if ($5 + 0 > t) t = $5 + 0} END {print d + 0 "\t" p + 0 "\t" m + 0 "\t" r + 0 "\t" t + 0}' \
        "$LOAD_ART"/k6/*/generator.tsv >> "$LOAD_ART/k6/generator.tsv"
}

load_judge() {
    report="$LOAD_ART/k6/report.tsv"
    secs=$(load_seconds "$LOAD_DURATION")
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
    [ "$dropped" = 0 ] || fail "the generator dropped $dropped iterations: it could not hold the arrival rate, so this run under-reports latency and is invalid"
    cpu=$(awk -F'\t' -v c="$CLUSTER-load-k6-" 'index($2, c) == 1 {v = $3; sub(/%/, "", v); if (v + 0 > m) m = v + 0} END {print m + 0}' "$LOAD_ART/containers.tsv")
    [ "$(awk -v c="$cpu" 'BEGIN {print (c < 90) ? "ok" : "saturated"}')" = ok ] \
        || fail "the generator saturated: an instance's CPU reached $cpu% of its one-core limit, so the run is invalid"
    bad=$(awk -F'\t' -v secs="$secs" 'NR > 1 { want = $2 * secs; if ($3 < want * 0.98 || $3 > want * 1.02 + 1) printf "%s %d of %.0f; ", $1, $3, want }' "$report")
    [ -z "$bad" ] || fail "arrivals off the profile's rates: $bad: see load/k6/report.tsv"
    pass "the generator held its open model: no iteration dropped, every operation's arrivals within 2% of its rate, each instance's CPU at most $cpu% of its one core"
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
        fi
        ratio=$(awk -v f="$failed" -v a="$attempts" 'BEGIN {printf "%.5f", (a > 0) ? f / a : 1}')
        [ "$attempts" -gt 0 ] || fail "$op: no attempt was made"
        chance=$(load_errors_chance "$failed" "$attempts" "$target")
        [ "$(awk -v c="$chance" 'BEGIN {print (c >= 0.01) ? "ok" : "over"}')" = ok ] \
            || fail "$op: $failed errors of $attempts attempts ($ratio), more than its objective $target explains (a chance of $chance): see load/k6/report.tsv"
        pass "$op: $iterations arrivals, $ok resolved, errors $failed of $attempts ($ratio, objective $target, a chance of $chance at the objective), $conflicts answered 409, $agreed of $retried retried keys alike; p50 $p50 ms, p95 $p95 ms, p99 $p99 ms, max $max ms"
    done
    # The callbacks: two per captured card payment, each answered, their refusals no more than the objective explains; every sender's
    # summary read, and a sender that wrote none is a sender nobody can vouch for.
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
    [ "$(awk -v c="$chance" 'BEGIN {print (c >= 0.01) ? "ok" : "over"}')" = ok ] \
        || fail "callbacks: $refused_callbacks of $delivered deliveries not accepted, more than the objective explains (a chance of $chance)"
    pass "callbacks: $fed captured card payments from $senders senders, $first first deliveries and $again redeliveries accepted (204), $refused_callbacks not (a chance of $chance at the objective)"
    # Spread: the generator reaches the replicas through the edge, never one pod.
    ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')
    window=$(( ($(date +%s) - LOAD_STARTED) / 60 + 1 ))m
    for route in 'uri="/v1/transfers",method="POST"' 'uri=~"/v1/payments/.+/confirmation",method="POST"' \
        'uri="/v1/checkout/sessions/confirmation",method="POST"' 'uri=~"/v1/me/accounts/.+/balance",method="GET"'; do
        load_prom "count(sum by (instance) (increase(http_server_requests_seconds_count{job=\"finapp\",$route}[$window])) != 0)" > "$LOAD_ART/spread.json" 2>&1 || true
        served=$(sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)"\].*/\1/p' "$LOAD_ART/spread.json")
        [ -n "$served" ] && [ "$served" = "$ready" ] || fail "spread: ${served:-no} of $ready ready replicas served $route during the load"
        printf '%s\t%s\n' "$route" "$served" >> "$LOAD_ART/spread.tsv"
    done
    pass "spread: every one of $ready ready replicas served transfers, payment and checkout confirmations and balance reads through the edge"
    # Settlement: the file taken, every line an item of the reconciliation, and its lines allocated during and after the load.
    if [ -n "${LOAD_SETTLEMENT_FILE:-}" ]; then
        deadline=$(( $(date +%s) + 600 ))
        while :; do
            counts=$(psql_query -c "SELECT count(DISTINCT l.id) || '|' || count(DISTINCT i.id) || '|' || count(DISTINCT a.external_item_id)
                FROM settlement.line l LEFT JOIN reconciliation.external_item i ON i.settlement_line_id = l.id
                LEFT JOIN reconciliation.allocation a ON a.external_item_id = i.id WHERE l.file_id = '$LOAD_SETTLEMENT_FILE'::uuid" | tr -d '[:space:]')
            IFS='|' read -r lines items allocated <<EOF
$counts
EOF
            [ "${allocated:-0}" = "$LOAD_SETTLEMENT_LINES" ] && break
            [ "$(date +%s)" -lt "$deadline" ] || break
            sleep 10
        done
        psql_query -c "SELECT status FROM settlement.file WHERE id = '$LOAD_SETTLEMENT_FILE'::uuid" > "$LOAD_ART/settlement-status.txt" 2>&1 || true
        printf 'lines\t%s\nitems\t%s\nallocated\t%s\nin the file\t%s\n' "$lines" "$items" "$allocated" "$LOAD_SETTLEMENT_LINES" >> "$LOAD_ART/settlement.log"
        [ "${lines:-0}" = "$LOAD_SETTLEMENT_LINES" ] || fail "settlement: ${lines:-0} lines taken of the $LOAD_SETTLEMENT_LINES the file holds"
        [ "${items:-0}" = "$LOAD_SETTLEMENT_LINES" ] || fail "settlement: ${items:-0} of $LOAD_SETTLEMENT_LINES lines became reconciliation items"
        [ "${allocated:-0}" -ge 1 ] || fail "settlement: no line was allocated to an expectation"
        pass "settlement: one PSP file of $LOAD_SETTLEMENT_LINES captures taken whole, every line a reconciliation item, $allocated allocated to their expectations"
    fi
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
    # A last sample, after the census: nothing the load left behind pages afterwards.
    : > "$WORK/load-sample-failed"
    load_sample final
    [ ! -s "$WORK/load-sample-failed" ] || fail "the sample after the load failed: $(cat "$WORK/load-sample-failed")"
    pass "the sample after the load and its census passed"
    # The saturation signals, as Prometheus holds them over the run, and the statements with their text.
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
        "requests|sum by (instance) (rate(http_server_requests_seconds_count{job=\"finapp\"}[1m]))"; do
        telemetry_get "$LOAD_PROM" "/api/v1/query_range?query=$(urlencode "${signal#*|}")&start=$LOAD_STARTED&end=$to&step=10" \
            > "$LOAD_ART/prometheus/${signal%%|*}.json" 2>&1 || true
    done
    missing=""
    for signal in cpu heap threads hikari_active outbox_age inbox_lag requests; do
        grep -q '"values":\[\[' "$LOAD_ART/prometheus/$signal.json" || missing="$missing $signal"
    done
    [ -z "$missing" ] || fail "saturation signals absent from Prometheus over the run:$missing: see load/prometheus"
    pg_admin -c "SELECT s.queryid, s.calls, round(s.total_exec_time::numeric, 1), round(s.mean_exec_time::numeric, 3), s.rows,
        left(regexp_replace(s.query, '\s+', ' ', 'g'), 300) FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
        WHERE d.datname = 'finapp' ORDER BY s.total_exec_time DESC LIMIT 50" > "$LOAD_ART/pg-statements-final.txt" 2>&1 || true
    stats=$(cat "$LOAD_ART"/pg-database.tsv 2>/dev/null | grep -c . || true)
    [ "$stats" -ge $(( $(load_seconds "$LOAD_DURATION") / 10 / 2 )) ] || fail "PostgreSQL's statistics were read $stats times over the load, not every 10 s: see load/statistics-errors.log"
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
    } > "$LOAD_ART/workload.txt"
}
