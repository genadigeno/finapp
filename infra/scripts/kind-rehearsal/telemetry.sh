# The telemetry pipeline's steps of the kind rehearsal (P15-TSK-023, ADR-0124 decisions 1 and 8). Sourced by infra/scripts/kind-rehearsal.sh, whose
# helpers it uses (kube, log, pass, fail, cannot, psql_query, client, probe_pod, must_reach, must_not_reach, load_tagged, render_overlay); every
# result is in the run's artefacts, under telemetry-*.
#
#   telemetry_deploy    the collector, Tempo, Loki and Prometheus of deploy/observability/overlays/kind, before N rolls out, so N exports from its start
#   telemetry_policy    the namespace's default-deny from inside it: a pod labelled as the collector reaches Tempo and Loki and is refused PostgreSQL,
#                       Kafka, another namespace and the internet; an unlabelled pod there reaches nothing; the application reaches the collector
#   telemetry_trace     one card payment, its PSP settlement file and its reconciliation run, every leg looked up in Tempo and Loki, and the
#                       stored chain that joins the legs no span can (the run decides an item in the file's correlation, never the payment's)
#   telemetry_outage    the collector scaled to zero, then black-holed by its own namespace's policy, each in the middle of a stream of requests:
#                       none failed, none slower than the bound below, every replica ready throughout
#   telemetry_sweep     the needles - the traced payment's planted amount and every rehearsal customer's party - over everything Loki and Tempo
#                       hold of the run and every replica's console
#   telemetry_scrape    the deployed Prometheus: job finapp, every replica up, env="kind" and never env="local"

TELEMETRY_PROXY=/api/v1/namespaces/finapp-observability/services

telemetry_get() {
    # telemetry_get <service:port> <path and query>: through the API server's proxy, which is node traffic and so needs no policy.
    kube get --raw "$TELEMETRY_PROXY/http:$1/proxy$2"
}

urlencode() {
    printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/ /%20/g' -e 's/"/%22/g' -e 's/{/%7B/g' -e 's/}/%7D/g' -e 's/=/%3D/g' -e 's/|/%7C/g' -e 's/&/%26/g' \
        -e 's/+/%2B/g' -e 's/\[/%5B/g' -e 's/\]/%5D/g'
}

telemetry_deploy() {
    load_tagged "$COLLECTOR_REPOSITORY" "$COLLECTOR_VERSION" "$COLLECTOR_DIGEST"
    load_tagged "$TEMPO_REPOSITORY" "$TEMPO_VERSION" "$TEMPO_DIGEST"
    load_tagged "$LOKI_REPOSITORY" "$LOKI_VERSION" "$LOKI_DIGEST"
    load_tagged "$PROMETHEUS_REPOSITORY" "$PROMETHEUS_VERSION" "$PROMETHEUS_DIGEST"
    render_overlay observability observability/overlays/kind
    apply_files "$WORK/render/observability/all.yaml"
    for deployment in finapp-tempo finapp-loki finapp-prometheus finapp-collector; do
        kube -n finapp-observability rollout status "deployment/$deployment" --timeout=300s >> "$ART/apply.log" 2>&1 \
            || fail "$deployment did not become ready"
    done
    kube -n finapp-observability get pods -o wide > "$ART/telemetry-pods.txt" 2>&1
    pass "the telemetry pipeline is up: two collectors, Tempo, Loki and Prometheus, in a namespace closed by default"
}

telemetry_policy() {
    probe_pod "$WORK/pod-obs-collector.yaml" finapp-observability np-obs-collector '{finapp/role: telemetry-collector}' ""
    probe_pod "$WORK/pod-obs-plain.yaml" finapp-observability np-obs-plain '{finapp/role: rehearsal-plain}' ""
    apply_files "$WORK/pod-obs-collector.yaml" "$WORK/pod-obs-plain.yaml"
    kube -n finapp-observability wait --for=condition=Ready pod/np-obs-collector pod/np-obs-plain --timeout=120s >> "$ART/apply.log" 2>&1 \
        || fail "the probes in finapp-observability did not start"
    pg_ip=$(kube -n finapp get pod finapp-postgres-0 -o jsonpath='{.status.podIP}')
    kafka_ip=$(kube -n finapp get pod finapp-kafka-0 -o jsonpath='{.status.podIP}')
    tempo_ip=$(kube -n finapp-observability get pods -l app.kubernetes.io/name=finapp-tempo -o jsonpath='{.items[0].status.podIP}')
    loki_ip=$(kube -n finapp-observability get pods -l app.kubernetes.io/name=finapp-loki -o jsonpath='{.items[0].status.podIP}')
    collector_ip=$(kube -n finapp-observability get pods -l app.kubernetes.io/name=finapp-collector -o jsonpath='{.items[0].status.podIP}')
    outsider_ip=$(kube -n finapp-rehearsal get pod np-outsider -o jsonpath='{.status.podIP}')
    # The controls first: the backends are reachable from where the policy says, so each refusal below is the policy's.
    must_reach "telemetry control" finapp-observability np-obs-collector probe "$tempo_ip" 4317
    must_reach "telemetry control" finapp-observability np-obs-collector probe "$loki_ip" 3100
    must_reach "telemetry control" finapp-rehearsal np-outsider probe "$pg_ip" 5432
    # T15: a pod of the telemetry pipeline's namespace is refused the database, labelled as the collector or not; and T4: nothing else.
    must_not_reach "telemetry T15" finapp-observability np-obs-collector probe "$pg_ip" 5432
    must_not_reach "telemetry T15" finapp-observability np-obs-plain probe "$pg_ip" 5432
    must_not_reach "telemetry T4" finapp-observability np-obs-collector probe "$kafka_ip" 9092
    must_not_reach "telemetry T4" finapp-observability np-obs-collector probe "$outsider_ip" 8080
    must_not_reach "telemetry T4" finapp-observability np-obs-collector probe 1.1.1.1 443
    must_not_reach "telemetry T4" finapp-observability np-obs-plain probe "$tempo_ip" 4317
    must_not_reach "telemetry T4" finapp-observability np-obs-plain probe 1.1.1.1 443
    # The collector's door: the application's replicas only.
    must_not_reach "telemetry ingress" finapp-rehearsal np-outsider probe "$collector_ip" 4318
    if [ -n "${TARGET_POD:-}" ]; then
        must_reach "telemetry ingress" finapp "$TARGET_POD" np-debug "$collector_ip" 4318
    fi
    kube -n finapp-observability delete pod np-obs-collector np-obs-plain --wait=false >> "$ART/apply.log" 2>&1 || true
}

trace_ids_for() {
    # trace_ids_for <TraceQL>: the trace ids Tempo finds for it over the last hour.
    now=$(date +%s)
    telemetry_get finapp-tempo:3200 "/api/search?q=$(urlencode "$1")&start=$((now - 3600))&end=$((now + 60))&limit=100" \
        | tr ',' '\n' | sed -n 's/.*"traceID":"\([0-9a-f]*\)".*/\1/p' | sort -u
}

span_names_of() {
    # span_names_of <trace id>...: every span name in those traces.
    for trace in "$@"; do
        telemetry_get finapp-tempo:3200 "/api/traces/$trace" | tr ',' '\n' | sed -n 's/^"name":"\([^"]*\)"$/\1/p; s/.*"name":"\([^"]*\)"$/\1/p'
    done | sort | uniq -c
}

telemetry_trace() {
    tag=$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')
    C_PAY="e2e-payment-$tag"
    C_FILE="e2e-settlement-$tag"
    TRACE_AMOUNT=731.94
    client trace-payment "$C_PAY" "$TRACE_AMOUNT" > "$ART/telemetry-trace-payment.log" 2>&1 || fail "the traced payment could not run: see telemetry-trace-payment.log"
    payment=$(sed -n 's/^traced payment=\([^ ]*\) status=SUCCEEDED .*/\1/p' "$ART/telemetry-trace-payment.log")
    [ -n "$payment" ] || fail "the traced payment did not succeed"
    # The census counts it from here on: one more captured top-up, and its amount in the wallets.
    TELEMETRY_TOPUPS=1
    TELEMETRY_TOPUP_MINOR=73194
    # The capture's references, as the PSP would report them, read from what the provider answered and the platform stored.
    capture=$(psql_query -c "SELECT a.capture_provider_reference || '|' || a.captured_amount_minor FROM payments.payment_attempt a WHERE a.intent_id = '$payment'::uuid AND a.status = 'CAPTURED'" | tr -d '[:space:]')
    reference=${capture%%|*}
    minor=${capture##*|}
    [ -n "$reference" ] && [ -n "$minor" ] || fail "no captured attempt for the traced payment"
    # The PSP's published fee in rule set v1: round_half_up(1.5% of gross) + 0.25, so the line matches without a fee break.
    fee=$(( (minor * 15 + 500) / 1000 + 25 ))
    net=$(( minor - fee ))
    day=$(psql_query -c "SELECT current_date" | tr -d '[:space:]')
    decimal() { printf '%d.%02d' "$(( $1 / 100 ))" "$(( $1 % 100 ))"; }
    {
        printf 'H,SIM_PSP_CSV,1,PSPB-E2E-%s,EUR,%s\n' "$tag" "$day"
        printf 'D,1,SALE,%s,%s,EUR,%s,,,%s,,,,Card capture\n' "$(decimal "$minor")" "$(decimal "$fee")" "$day" "$reference"
        printf 'T,1,%s,PSP-REM-81%s\n' "$(decimal "$net")" "$(( 10000000 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') ))"
    } > "$CLIENT/e2e-report.csv"
    cp "$CLIENT/e2e-report.csv" "$ART/telemetry-e2e-report.csv"
    # The two persons the upload door needs: the operator uploads, the controller attests.
    psql_query <<SQL >> "$ART/telemetry-trace.log" 2>&1 || cannot "the reconciliation operators could not be seeded"
INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)
 SELECT uuidv7(), i.id, 'RECONCILIATION_OPERATOR', i.id, now() FROM identity.identity i
  WHERE i.login_identifier IN ('$(sed -n 's/^operator.login=//p' "$CLIENT/state.properties")', '$(sed -n 's/^controller.login=//p' "$CLIENT/state.properties")')
  AND NOT EXISTS (SELECT 1 FROM identity.role_assignment r WHERE r.identity_id = i.id AND r.role_name = 'RECONCILIATION_OPERATOR');
SQL
    client settle-file "$C_FILE" "$day" e2e-report.csv > "$ART/telemetry-settle-file.log" 2>&1 || fail "the settlement file could not be delivered: see telemetry-settle-file.log"
    file=$(sed -n 's/^settlement file=\([^ ]*\) .*/\1/p' "$ART/telemetry-settle-file.log")
    [ -n "$file" ] || fail "no settlement file id"
    # The intake and reconciliation schedules run on every replica; wait for the run to settle the payment's expectation.
    settled=""
    deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        settled=$(psql_query -c "SELECT e.status || '|' || a.correlation_id || '|' || i.run_id FROM reconciliation.expectation e JOIN reconciliation.allocation a ON a.expectation_id = e.id JOIN reconciliation.external_item i ON i.id = a.external_item_id WHERE e.correlation_id = '$C_PAY' LIMIT 1" | tr -d '[:space:]')
        [ -n "$settled" ] && break
        sleep 5
    done
    printf 'payment=%s file=%s chain=%s\n' "$payment" "$file" "$settled" > "$ART/telemetry-trace.txt"
    psql_query -c "SELECT 'file ' || f.id || ' ' || f.status FROM settlement.settlement_file f WHERE f.id = '$file'::uuid" >> "$ART/telemetry-trace.txt" 2>&1 || true
    # Every leg is looked up before any verdict, so one missing leg does not hide what the others show.
    missing=""
    [ -n "$settled" ] || missing="$missing; the reconciliation run never allocated the payment's expectation (telemetry-trace.txt)"
    run=${settled##*|}
    # Long enough for the tail policy's ten seconds, the load-balancing hop and Tempo's ingest.
    sleep 30

    # Each leg, by what joins it.
    pay_traces=$(trace_ids_for "{ span.finapp.correlation_id = \"$C_PAY\" }")
    file_traces=$(trace_ids_for "{ span.finapp.correlation_id = \"$C_FILE\" }")
    intake_traces=$(trace_ids_for "{ span.file.id = \"$file\" }")
    # shellcheck disable=SC2086
    span_names_of $pay_traces > "$ART/telemetry-trace-payment-spans.txt"
    # shellcheck disable=SC2086
    span_names_of $file_traces > "$ART/telemetry-trace-file-spans.txt"
    # shellcheck disable=SC2086
    span_names_of $intake_traces > "$ART/telemetry-trace-intake-spans.txt"
    chunk_traces=$(trace_ids_for "{ name = \"reconciliation.chunk\" }")
    # shellcheck disable=SC2086
    span_names_of $chunk_traces > "$ART/telemetry-trace-chunk-spans.txt"
    log "trace: payment $payment under $C_PAY in $(printf '%s\n' $pay_traces | grep -c .) traces; file $file under $C_FILE; run $run"
    for leg in "http post /v1/payments" "http post /v1/payments/{id}/confirmation" "outbox.publish" "inbox.handle"; do
        if grep -qF -- "$leg" "$ART/telemetry-trace-payment-spans.txt"; then
            pass "trace: '$leg' found under the payment's correlation"
        else
            missing="$missing; no '$leg' span under the payment's correlation"
        fi
    done
    for leg in "http post /v1/operator/settlement/files" "http post /v1/operator/settlement/files/{id}/attestation"; do
        if grep -qF -- "$leg" "$ART/telemetry-trace-file-spans.txt"; then
            pass "trace: '$leg' found under the file's correlation"
        else
            missing="$missing; no '$leg' span under the file's correlation"
        fi
    done
    for leg in settlement.parse settlement.accept; do
        if grep -qF -- "$leg" "$ART/telemetry-trace-intake-spans.txt"; then
            pass "trace: '$leg' found by the file's identifier"
        else
            missing="$missing; no '$leg' span carrying the file's identifier"
        fi
    done
    if grep -qF -- "reconciliation.chunk" "$ART/telemetry-trace-chunk-spans.txt"; then
        pass "trace: the reconciliation run's chunks are in Tempo; the run that settled the payment ($run) is joined to it by the stored chain"
    else
        missing="$missing; no reconciliation.chunk span in Tempo"
    fi
    if [ "${settled%%|*}" != "" ]; then
        pass "trace: the stored chain joins the legs: expectation under $C_PAY $(printf '%s' "$settled" | cut -d'|' -f1), allocated under $(printf '%s' "$settled" | cut -d'|' -f2) by run $run"
    fi
    # The logs of the same flow, from Loki: the payment's correlation is an indexed attribute of each record.
    now=$(date +%s)
    telemetry_get finapp-loki:3100 "/loki/api/v1/query_range?query=$(urlencode "{service_name=\"finapp\"} | finapp_correlation_id=\"$C_PAY\"")&start=$((now - 3600))000000000&end=$((now + 60))000000000&limit=1000" \
        > "$ART/telemetry-trace-logs.json" 2>&1 || true
    log "trace: Loki answered $(wc -c < "$ART/telemetry-trace-logs.json") bytes for the payment's correlation"
    if grep -qF -- "$C_PAY" "$ART/telemetry-trace-logs.json"; then
        pass "trace: the payment's log records are in Loki under its correlation"
    else
        missing="$missing; no log record of the payment's correlation in Loki"
    fi
    [ -z "$missing" ] || fail "trace:$missing (the span lists are telemetry-trace-*-spans.txt)"
}

latency_summary() {
    # latency_summary <label>: requests, failed, p50, p95 and max in milliseconds, from the client's own record.
    file="$CLIENT/latency-$1.tsv"
    count=$(grep -c . "$file" || true)
    failed=$(awk -F'\t' '$1 < 200 || $1 > 299 {f++} END {print f+0}' "$file")
    cut -f2 "$file" | sort -n > "$WORK/latency-sorted"
    p50=$(awk -v n="$count" 'NR >= n * 0.50 {print; exit}' "$WORK/latency-sorted")
    p95=$(awk -v n="$count" 'NR >= n * 0.95 {print; exit}' "$WORK/latency-sorted")
    max=$(tail -1 "$WORK/latency-sorted")
    printf '%s %s %s %s %s\n' "${count:-0}" "$failed" "${p50:-0}" "${p95:-0}" "${max:-0}"
}

all_ready() {
    [ "$(sample_fleet | awk -F'|' '$3 != "True" {n++} END {print n+0}')" = 0 ]
}

telemetry_outage() {
    for phase in up down blackholed; do
        case $phase in
            down)
                kube -n finapp-observability scale deployment/finapp-collector --replicas=0 >> "$ART/apply.log" 2>&1 || cannot "the collector could not be stopped"
                kube -n finapp-observability wait --for=delete pod -l app.kubernetes.io/name=finapp-collector --timeout=120s >> "$ART/apply.log" 2>&1 || true
                ;;
            blackholed)
                kube -n finapp-observability scale deployment/finapp-collector --replicas=2 >> "$ART/apply.log" 2>&1 || cannot "the collector could not be restarted"
                kube -n finapp-observability rollout status deployment/finapp-collector --timeout=180s >> "$ART/apply.log" 2>&1 || fail "the collector did not come back"
                # The collector's own allow rules removed: the namespace's default-deny now drops the application's packets, so a connection hangs
                # until the exporter's own timeout - the slow collector, which a refused connection is not.
                kube -n finapp-observability delete networkpolicy finapp-collector >> "$ART/apply.log" 2>&1 || cannot "the collector's policy could not be removed"
                sleep 8
                ;;
        esac
        client latency "$phase" 60 >> "$ART/telemetry-outage.log" 2>&1 &
        latency_pid=$!
        not_ready=0
        while kill -0 "$latency_pid" 2>/dev/null; do
            all_ready || not_ready=$((not_ready + 1))
            sleep 3
        done
        wait "$latency_pid" || fail "the latency client failed in phase $phase"
        # shellcheck disable=SC2046
        set -- $(latency_summary "$phase")
        printf '%s\trequests=%s\tfailed=%s\tp50=%s\tp95=%s\tmax=%s\tnot-ready-samples=%s\n' "$phase" "$1" "$2" "$3" "$4" "$5" "$not_ready" >> "$ART/telemetry-outage.tsv"
        log "outage $phase: requests=$1 failed=$2 p50=$3ms p95=$4ms max=$5ms, samples with a replica not ready: $not_ready"
        [ "$2" = 0 ] || fail "outage $phase: $2 requests failed"
        [ "$not_ready" = 0 ] || fail "outage $phase: a replica was not ready while the collector was $phase"
        [ "$1" -gt 0 ] || cannot "outage $phase: no request ran"
        eval "OUTAGE_P95_$phase=$4"
    done
    # Restored as rendered: the release's own file, never a copy read back from the cluster.
    apply_files "$WORK/render/observability/NetworkPolicy.finapp-collector.yaml"
    # Slower is judged against the collector up, with room for this machine's noise: twice the p95 plus 50 ms. A request that waited on the
    # exporter's connect timeout (2 s) or call timeout (5 s) would be far past it.
    for phase in down blackholed; do
        eval "p95=\$OUTAGE_P95_$phase"
        bound=$(( OUTAGE_P95_up * 2 + 50 ))
        if [ "$p95" -le "$bound" ]; then
            pass "outage $phase: p95 ${p95} ms within ${bound} ms of the collector up (${OUTAGE_P95_up} ms): no request waited on the collector"
        else
            fail "outage $phase: p95 ${p95} ms is past ${bound} ms: a request waited on the collector"
        fi
    done
}

telemetry_sweep() {
    now=$(date +%s)
    telemetry_get finapp-loki:3100 "/loki/api/v1/query_range?query=$(urlencode '{service_name="finapp"}')&start=$((now - 7200))000000000&end=$((now + 60))000000000&limit=5000" \
        > "$ART/telemetry-sweep-loki.json" 2>&1 || fail "Loki could not be read for the sweep"
    : > "$ART/telemetry-sweep-tempo.json"
    for trace in $(trace_ids_for "{ }") $(trace_ids_for "{ span.finapp.correlation_id = \"${C_PAY:-none}\" }"); do
        telemetry_get finapp-tempo:3200 "/api/traces/$trace" >> "$ART/telemetry-sweep-tempo.json" 2>&1 || true
        printf '\n' >> "$ART/telemetry-sweep-tempo.json"
    done
    : > "$ART/telemetry-sweep-console.txt"
    for pod in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name); do
        kube -n finapp logs "$pod" --all-containers >> "$ART/telemetry-sweep-console.txt" 2>&1 || true
    done
    psql_query -c "SELECT i.party_id FROM identity.identity i WHERE i.party_id IS NOT NULL" | tr -d ' ' | grep . > "$WORK/needles.txt" || true
    printf '%s\n' "${TRACE_AMOUNT:-731.94}" >> "$WORK/needles.txt"
    needles=$(grep -c . "$WORK/needles.txt")
    loki_lines=$(tr ',' '\n' < "$ART/telemetry-sweep-loki.json" | grep -c '"[0-9]\{19\}"')
    tempo_bytes=$(wc -c < "$ART/telemetry-sweep-tempo.json")
    [ "$loki_lines" -gt 0 ] || fail "the sweep read no log record from Loki: a sweep over nothing proves nothing"
    [ "$tempo_bytes" -gt 1000 ] || fail "the sweep read no trace from Tempo"
    found=$(grep -F -f "$WORK/needles.txt" -o "$ART/telemetry-sweep-loki.json" "$ART/telemetry-sweep-tempo.json" "$ART/telemetry-sweep-console.txt" | sort | uniq -c || true)
    printf '%s\n' "$found" > "$ART/telemetry-sweep-found.txt"
    if [ -n "$found" ]; then
        fail "sweep: needles exported or printed: $(printf '%s' "$found" | tr '\n' ';' | cut -c1-400)"
    else
        pass "sweep: none of $needles needles (the planted amount and every party) in $loki_lines log records, ${tempo_bytes} bytes of traces or any replica's console"
    fi
}

telemetry_scrape() {
    answer=$(telemetry_get finapp-prometheus:9090 "/api/v1/query?query=$(urlencode 'up{job="finapp"}')" 2>&1 || true)
    printf '%s\n' "$answer" > "$ART/telemetry-scrape.json"
    targets=$(printf '%s' "$answer" | tr '{' '\n' | grep -c '"job":"finapp"')
    up=$(printf '%s' "$answer" | tr '{' '\n' | grep '"job":"finapp"' | grep -c '"env":"kind"')
    local_env=$(printf '%s' "$answer" | grep -c '"env":"local"' || true)
    replicas=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name | grep -c .)
    ones=$(printf '%s' "$answer" | grep -o '"value":\[[0-9.]*,"1"\]' | grep -c . || true)
    log "scrape: $targets finapp targets, $up of them env=\"kind\", $ones up, $replicas replicas running"
    if [ "$targets" -ge "$replicas" ] && [ "$up" = "$targets" ] && [ "$ones" -ge "$replicas" ] && [ "$local_env" = 0 ]; then
        pass "scrape: the deployed Prometheus reads job finapp, every replica up, env=\"kind\" and no env=\"local\""
    else
        fail "scrape: job finapp $targets targets for $replicas replicas, $ones up, env=kind on $up, env=local on $local_env (telemetry-scrape.json)"
    fi
}
