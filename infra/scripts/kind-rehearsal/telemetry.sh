# The telemetry pipeline's steps of the kind rehearsal (P15-TSK-023, ADR-0124 decisions 1 and 8). Sourced by infra/scripts/kind-rehearsal.sh, whose
# helpers it uses (dock, kube, log, pass, fail, cannot, psql_query, client, probe_pod, must_reach, must_not_reach, load_tagged, normalized, render_overlay); every
# result is in the run's artefacts, under telemetry-*.
#
#   telemetry_deploy    the collector, Tempo, Loki and Prometheus of deploy/observability/overlays/kind, before N rolls out, so N exports from its start
#   telemetry_policy    the namespace's default-deny from inside it: a pod labelled as the collector reaches Tempo and Loki and is refused PostgreSQL,
#                       Kafka, another namespace and the internet; an unlabelled pod there reaches nothing; the application reaches the collector
#   telemetry_trace     one card payment, its PSP settlement file and its reconciliation run, every leg looked up in Tempo and Loki, and the
#                       stored chain that joins the legs no span can (the run decides an item in the file's correlation, never the payment's)
#   telemetry_outage    the collector scaled to zero (a refusal), then a stand-in that accepts and never answers (a hang), each in the middle
#                       of a stream of requests: none failed, none unresolved, none slower than the bound below, every replica ready
#   telemetry_sweep     the needles - the traced payment's amount, a record planted through the collector with an amount and a card number,
#                       and every rehearsal customer's party - over every record Loki holds of the run, the latest and the flow's traces
#                       and every replica's console; the planted record and a correlation a replica printed found in Loki as controls
#   telemetry_scrape    the deployed Prometheus: job finapp, every replica up, env="kind" and never env="local"
#   platform_signals_plant, platform_signals
#                       the platform's own signals (P16-TSK-006, THREAT_MODEL.md section 6 item 47 (c)), run by the load scope: a migrator
#                       Job planted to fail paging twice through the committed rules and routing, its lines in Loki scrubbed, and the edge's,
#                       the migrator's and the stack's streams swept for every needle beside their controls

TELEMETRY_PROXY=/api/v1/namespaces/finapp-observability/services

telemetry_get() {
    # telemetry_get <service:port> <path and query>: through the API server's proxy, which is node traffic and so needs no policy.
    kube get --raw "$TELEMETRY_PROXY/http:$1/proxy$2"
}

urlencode() {
    printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/ /%20/g' -e 's/"/%22/g' -e 's/{/%7B/g' -e 's/}/%7D/g' -e 's/=/%3D/g' -e 's/|/%7C/g' -e 's/&/%26/g' \
        -e 's/+/%2B/g' -e 's/\[/%5B/g' -e 's/\]/%5D/g'
}

telemetry_image() {
    # telemetry_image <repository> <version> <digest>: into the node as load_tagged puts it there, except an image whose configuration names no
    # platform (Loki's, built with Nix): `docker save --platform` refuses it, and saved whole it names the blobs of the platforms the host never
    # pulled, which the node's import cannot find. The node pulls that one itself, its own platform of the pinned digest, under both names.
    dock image inspect "$1:$2" >/dev/null 2>&1 || { dock pull --platform linux/amd64 "$1@$3" >/dev/null && dock tag "$1@$3" "$1:$2"; } \
        || cannot "could not pull $1@$3"
    if [ -n "$(dock image inspect --format '{{.Architecture}}' "$1:$2" 2>/dev/null)" ]; then
        load_tagged "$1" "$2" "$3"
        return
    fi
    dock exec "$NODE" ctr -n k8s.io images pull --platform linux/amd64 "$(normalized "$1")@$3" >> "$ART/images.log" 2>&1 \
        || cannot "the node could not pull $1@$3"
    dock exec "$NODE" ctr -n k8s.io images tag --force "$(normalized "$1")@$3" "$(normalized "$1:$2")" >> "$ART/images.log" 2>&1 \
        || cannot "could not name $1@$3 as $1:$2 in the node"
}

telemetry_deploy() {
    telemetry_image "$COLLECTOR_REPOSITORY" "$COLLECTOR_VERSION" "$COLLECTOR_DIGEST"
    telemetry_image "$TEMPO_REPOSITORY" "$TEMPO_VERSION" "$TEMPO_DIGEST"
    telemetry_image "$LOKI_REPOSITORY" "$LOKI_VERSION" "$LOKI_DIGEST"
    telemetry_image "$PROMETHEUS_REPOSITORY" "$PROMETHEUS_VERSION" "$PROMETHEUS_DIGEST"
    # Alertmanager (P15-TSK-032): loaded as the others are, never pulled by the node itself through an intercepted TLS.
    telemetry_image "$ALERTMANAGER_REPOSITORY" "$ALERTMANAGER_VERSION" "$ALERTMANAGER_DIGEST"
    render_overlay observability observability/overlays/kind
    # Server-side (P15-TSK-032): the rules' ConfigMap, every committed rule file, is past what client-side apply can keep in its
    # last-applied annotation (262,144 bytes) once it grows; server-side apply keeps none.
    kube apply --server-side --force-conflicts -f - < "$WORK/render/observability/all.yaml" >> "$ART/apply.log" 2>&1 \
        || cannot "could not apply the telemetry pipeline: see apply.log"
    for deployment in finapp-tempo finapp-loki finapp-prometheus finapp-alertmanager finapp-collector; do
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
    # The database admits the application's own namespace alone, so its control is the replica's debug container (the network-policy step's).
    must_reach "telemetry control" finapp "$TARGET_POD" np-debug "$pg_ip" 5432
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
    telemetry_debug_pod
    # The platform mints every request's correlation (ADR-0034), so each leg is looked up by the correlation it really ran in: each request's
    # own, read from its answer; the payment's events' and the expectation's, as the platform stored them; the file's legs by its identifier.
    tag=$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')
    TRACE_AMOUNT=731.94
    client trace-payment "$TRACE_AMOUNT" > "$ART/telemetry-trace-payment.log" 2>&1 || fail "the traced payment could not run: see telemetry-trace-payment.log"
    line=$(grep '^traced payment=' "$ART/telemetry-trace-payment.log" | tail -1)
    payment=$(printf '%s' "$line" | sed -n 's/^traced payment=\([^ ]*\) status=SUCCEEDED .*/\1/p')
    c_create=$(printf '%s' "$line" | sed -n 's/.* create=\([^ ]*\).*/\1/p')
    c_confirm=$(printf '%s' "$line" | sed -n 's/.* confirm=\([^ ]*\).*/\1/p')
    t_customer=$(printf '%s' "$line" | sed -n 's/.* trace=\([0-9a-f]*\).*/\1/p')
    [ -n "$payment" ] || fail "the traced payment did not succeed"
    # The census counts it from here on: one more captured top-up, and its amount in the wallets.
    TELEMETRY_TOPUPS=1
    TELEMETRY_TOPUP_MINOR=73194
    # The capture's references, as the PSP would report them, read from what the provider answered and the platform stored.
    capture=$(psql_query -c "SELECT a.capture_provider_reference || '|' || a.captured_amount_minor FROM payments.payment_attempt a WHERE a.intent_id = '$payment'::uuid AND a.status = 'CAPTURED'" | tr -d '[:space:]')
    reference=${capture%%|*}
    minor=${capture##*|}
    [ -n "$reference" ] && [ -n "$minor" ] || fail "no captured attempt for the traced payment"
    # The correlations the payment's events were written in: the outbox keeps each event's, under the payment's id.
    c_events=$(psql_query -c "SELECT DISTINCT correlation_id FROM platform.outbox_event WHERE aggregate_id = '$payment'::uuid" | tr -d ' ' | grep . | tr '\n' ' ')
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
    client settle-file "$day" e2e-report.csv > "$ART/telemetry-settle-file.log" 2>&1 || fail "the settlement file could not be delivered: see telemetry-settle-file.log"
    line=$(grep '^settlement file=' "$ART/telemetry-settle-file.log" | tail -1)
    file=$(printf '%s' "$line" | sed -n 's/^settlement file=\([^ ]*\) .*/\1/p')
    c_upload=$(printf '%s' "$line" | sed -n 's/.* upload=\([^ ]*\).*/\1/p')
    c_attest=$(printf '%s' "$line" | sed -n 's/.* attestation=\([^ ]*\).*/\1/p')
    [ -n "$file" ] || fail "no settlement file id"
    # The intake and reconciliation schedules run on every replica: wait for a run to allocate the file's line to an expectation. The chain is
    # read from the file's own line, so it cannot be another payment's.
    settled=""
    deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        settled=$(psql_query -c "SELECT e.status || '|' || e.correlation_id || '|' || a.correlation_id || '|' || i.run_id || '|' || e.amount_minor || '|' || coalesce((SELECT string_agg(k.key_value, ',') FROM reconciliation.expectation_key k WHERE k.expectation_id = e.id), '') FROM settlement.line l JOIN reconciliation.external_item i ON i.settlement_line_id = l.id JOIN reconciliation.allocation a ON a.external_item_id = i.id JOIN reconciliation.expectation e ON e.id = a.expectation_id WHERE l.file_id = '$file'::uuid LIMIT 1" | tr -d '[:space:]')
        [ -n "$settled" ] && break
        sleep 5
    done
    IFS='|' read -r e_status c_expectation c_allocation run e_minor e_keys <<EOF
$settled
EOF
    {
        printf 'payment=%s create=%s confirm=%s events=%s\n' "$payment" "$c_create" "$c_confirm" "$c_events"
        printf 'file=%s upload=%s attestation=%s\n' "$file" "$c_upload" "$c_attest"
        printf 'chain: expectation status=%s correlation=%s amount_minor=%s keys=%s; allocation correlation=%s; run=%s\n' \
            "${e_status:-}" "${c_expectation:-}" "${e_minor:-}" "${e_keys:-}" "${c_allocation:-}" "${run:-}"
    } > "$ART/telemetry-trace.txt"
    psql_query -c "SELECT 'file ' || f.id || ' ' || f.status || ' ' || f.correlation_id FROM settlement.file f WHERE f.id = '$file'::uuid" >> "$ART/telemetry-trace.txt" 2>&1 || true
    # Every leg is looked up before any verdict, so one missing leg does not hide what the others show.
    missing=""
    if [ -z "$settled" ]; then
        missing="$missing; no reconciliation run allocated the file's line (telemetry-trace.txt)"
    else
        case ",${e_keys:-}," in
            *",$reference,"*) pass "trace: the file's line was allocated to the payment's own expectation (its key is the capture's reference), by run $run" ;;
            *) missing="$missing; the file's line was allocated to an expectation not keyed by the payment's capture reference" ;;
        esac
    fi
    # Long enough for the tail policy's ten seconds, the load-balancing hop and Tempo's ingest.
    sleep 30

    : > "$ART/telemetry-trace-spans.txt"
    spans_under() {
        # spans_under <label> <correlation>...: every span name in the traces of those correlations, also kept in the artefact under the label.
        spans_label=$1
        shift
        for correlation in "$@"; do
            [ -n "$correlation" ] && [ "$correlation" != "-" ] || continue
            # shellcheck disable=SC2046
            span_names_of $(trace_ids_for "{ span.finapp.correlation_id = \"$correlation\" }")
        done | sed "s/^/$spans_label /" | tee -a "$ART/telemetry-trace-spans.txt"
    }
    has_span() {
        # has_span <listing> <span name>: the listing's lines are "<label> <count> <name>"; the name must match whole.
        printf '%s\n' "$1" | sed 's/^[^ ]* *[0-9]* //' | grep -qxF -- "$2"
    }
    leg() {
        # leg <where it ran> <span name> <listing>: found, or one more line of what is missing.
        if has_span "$3" "$2"; then
            pass "trace: '$2' found $1"
        else
            missing="$missing; no '$2' span $1"
        fi
    }
    # shellcheck disable=SC2086
    create_spans=$(spans_under create "$c_create")
    confirm_spans=$(spans_under confirm "$c_confirm")
    # shellcheck disable=SC2086
    event_spans=$(spans_under events $c_events)
    expectation_spans=$(spans_under expectation "${c_expectation:-}")
    upload_spans=$(spans_under upload "$c_upload")
    attest_spans=$(spans_under attestation "$c_attest")
    # shellcheck disable=SC2046
    file_spans=$(span_names_of $(trace_ids_for "{ span.file.id = \"$file\" }") | sed 's/^/file /' | tee -a "$ART/telemetry-trace-spans.txt")
    # shellcheck disable=SC2046
    chunk_spans=$(span_names_of $(trace_ids_for "{ name = \"reconciliation.chunk\" }") | sed 's/^/chunks /' | tee -a "$ART/telemetry-trace-spans.txt")
    log "trace: payment $payment (create $c_create, confirm $c_confirm, its events in: $c_events); file $file (upload $c_upload, attestation $c_attest); run ${run:-none}"
    leg "in the create request's correlation" "http post /v1/payments" "$create_spans"
    leg "in the confirm request's correlation" "http post /v1/payments/{id}/confirmation" "$confirm_spans"
    leg "in the correlation of the payment's events" "outbox.publish" "$event_spans"
    leg "in the correlation of the payment's events" "inbox.handle" "$event_spans"
    leg "in the upload's correlation" "http post /v1/operator/settlement/files" "$upload_spans"
    leg "in the attestation's correlation" "http post /v1/operator/settlement/files/{id}/attestation" "$attest_spans"
    leg "carrying the file's identifier" "settlement.parse" "$file_spans"
    leg "carrying the file's identifier" "settlement.accept" "$file_spans"
    leg "in Tempo for the reconciliation runs" "reconciliation.chunk" "$chunk_spans"
    # The client sent its own W3C traceparent on both requests, through the public edge, which removes it (edge-headers): a public caller
    # may not set the sampler's flag or choose the trace ids the collectors route by. So Tempo holds nothing under the client's trace id.
    customer_spans=$(span_names_of "$t_customer" | sed 's/^/customer-trace /' | tee -a "$ART/telemetry-trace-spans.txt")
    if [ -z "$customer_spans" ]; then
        pass "trace: the customer's own traceparent was removed at the public edge: Tempo holds nothing under its trace id ($t_customer)"
    else
        missing="$missing; the customer's own trace id $t_customer reached Tempo through the public edge"
    fi
    # The expectation was opened by the payment's own flow: its correlation is one the payment's requests or events ran in.
    case " $c_create $c_confirm $c_events " in
        *" ${c_expectation:-none} "*) pass "trace: the payment's expectation was opened in the payment's own flow ($c_expectation)" ;;
        *) log "trace: the expectation's correlation (${c_expectation:-none}) is none of the payment's requests' or events': its spans are listed under 'expectation'" ;;
    esac
    # The logs of the flow, from Loki: a record's correlation is an indexed attribute. A request that logs nothing has no record, so this is
    # reported, and the sweep below is what proves records arrive at all.
    now=$(date +%s)
    found_logs=0
    for correlation in "$c_create" "$c_confirm" $c_events "$c_upload" "$c_attest"; do
        [ -n "$correlation" ] && [ "$correlation" != "-" ] || continue
        telemetry_get finapp-loki:3100 "/loki/api/v1/query_range?query=$(urlencode "{service_name=\"finapp\"} | finapp_correlation_id=\"$correlation\"")&start=$((now - 3600))000000000&end=$((now + 60))000000000&limit=1000" \
            > "$WORK/trace-logs.json" 2>/dev/null || true
        records=$(tr ',' '\n' < "$WORK/trace-logs.json" | grep -c '"[0-9]\{19\}"' || true)
        found_logs=$((found_logs + records))
        printf '%s\t%s\n' "$correlation" "$records" >> "$ART/telemetry-trace-logs.tsv"
    done
    log "trace: Loki holds $found_logs log records under the flow's correlations (telemetry-trace-logs.tsv)"
    [ -z "$missing" ] || fail "trace:$missing (the span lists are in telemetry-trace-spans.txt)"
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

hung_collector() {
    # hung_collector <file>: a pod the collector's Service selects and its policy admits, on 4318, that accepts every connection and never
    # answers - the slow collector, which a refused connection is not. kube-router REJECTs traffic no policy allows, so removing the
    # collector's policy would only have refused faster.
    cat > "$1" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: np-obs-hung
  namespace: finapp-observability
  labels:
    app.kubernetes.io/name: finapp-collector
    finapp/role: telemetry-collector
spec:
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 0
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    runAsGroup: 101
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: hang
      image: $CURL_IMAGE
      imagePullPolicy: IfNotPresent
      command: ["/bin/sh", "-c", "while true; do nc -l -p 4318 >/dev/null 2>&1 || sleep 1; done"]
      ports:
        - name: otlp-http
          containerPort: 4318
      resources:
        requests: {cpu: 100m, memory: 128Mi}
        limits: {cpu: 100m, memory: 128Mi}
      securityContext:
        allowPrivilegeEscalation: false
        privileged: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
YAML
}

telemetry_outage() {
    # Three phases of a minute of one-cent transfers: the collector up; stopped, which kube-proxy answers with a refusal (no endpoint);
    # and hung, a stand-in that accepts and never answers, so the exporter waits out its own timeouts on its own threads.
    for phase in up down hung; do
        case $phase in
            down)
                kube -n finapp-observability scale deployment/finapp-collector --replicas=0 >> "$ART/apply.log" 2>&1 || cannot "the collector could not be stopped"
                kube -n finapp-observability wait --for=delete pod -l app.kubernetes.io/name=finapp-collector --timeout=120s >> "$ART/apply.log" 2>&1 || true
                ;;
            hung)
                hung_collector "$WORK/pod-obs-hung.yaml"
                apply_files "$WORK/pod-obs-hung.yaml"
                kube -n finapp-observability wait --for=condition=Ready pod/np-obs-hung --timeout=120s >> "$ART/apply.log" 2>&1 \
                    || cannot "the hung collector did not start"
                # The control: the Service's one endpoint is the stand-in, which takes a connection and answers nothing within five seconds.
                endpoints=$(kube -n finapp-observability get endpointslices -l kubernetes.io/service-name=finapp-collector \
                    -o jsonpath='{range .items[*].endpoints[*]}{.targetRef.name}{"\n"}{end}' 2>/dev/null | grep -c . || true)
                answered=$(kube -n finapp exec "$TELEMETRY_DEBUG_POD" -c np-obs-debug -- curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
                    -X POST http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/logs 2>/dev/null || true)
                log "outage hung: the collector Service has $endpoints endpoint(s), and a POST to it answered '${answered:-nothing}' within 5 s"
                [ "$endpoints" = 1 ] && [ "${answered:-000}" = 000 ] || cannot "the hung collector is not the Service's one endpoint, or it answered"
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
        unresolved=$(grep -c 'UNRESOLVED$' "$CLIENT/workload-telemetry-$phase.tsv" || true)
        # shellcheck disable=SC2046
        set -- $(latency_summary "$phase")
        printf '%s\trequests=%s\tfailed=%s\tp50=%s\tp95=%s\tmax=%s\tnot-ready-samples=%s\tunresolved=%s\n' "$phase" "$1" "$2" "$3" "$4" "$5" "$not_ready" "$unresolved" >> "$ART/telemetry-outage.tsv"
        log "outage $phase: requests=$1 failed=$2 p50=$3ms p95=$4ms max=$5ms, samples with a replica not ready: $not_ready, unresolved: $unresolved"
        [ "$2" = 0 ] || fail "outage $phase: $2 requests failed"
        [ "$unresolved" = 0 ] || fail "outage $phase: $unresolved transfers never answered, so the census cannot count them"
        [ "$not_ready" = 0 ] || fail "outage $phase: a replica was not ready while the collector was $phase"
        [ "$1" -gt 0 ] || cannot "outage $phase: no request ran"
        eval "OUTAGE_P95_$phase=$4"
    done
    kube -n finapp-observability delete pod np-obs-hung --wait=true --timeout=60s >> "$ART/apply.log" 2>&1 || true
    kube -n finapp-observability scale deployment/finapp-collector --replicas=2 >> "$ART/apply.log" 2>&1 || cannot "the collector could not be restarted"
    kube -n finapp-observability rollout status deployment/finapp-collector --timeout=180s >> "$ART/apply.log" 2>&1 || fail "the collector did not come back"
    # Slower is judged against the collector up, with room for this machine's noise: twice the p95 plus 50 ms. A request that waited on the
    # exporter's connect timeout (2 s) or call timeout (5 s) would be far past it.
    for phase in down hung; do
        eval "p95=\$OUTAGE_P95_$phase"
        bound=$(( OUTAGE_P95_up * 2 + 50 ))
        if [ "$p95" -le "$bound" ]; then
            pass "outage $phase: p95 ${p95} ms within ${bound} ms of the collector up (${OUTAGE_P95_up} ms): no request waited on the collector"
        else
            fail "outage $phase: p95 ${p95} ms is past ${bound} ms: a request waited on the collector"
        fi
    done
}

telemetry_debug_pod() {
    # A debug container on a replica of N, inside its network namespace: the one place the collector's policy admits on 4318 besides the
    # application itself. The outage's control and the sweep's planted record are sent from it.
    TELEMETRY_DEBUG_POD=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{.items[0].metadata.name}')
    kube -n finapp debug "$TELEMETRY_DEBUG_POD" --profile=restricted --image="$CURL_IMAGE" --container=np-obs-debug --target=app -- sleep 3600 \
        >> "$ART/apply.log" 2>&1 || cannot "the telemetry debug container could not be added to $TELEMETRY_DEBUG_POD"
    deadline=$(( $(date +%s) + 120 ))
    until kube -n finapp exec "$TELEMETRY_DEBUG_POD" -c np-obs-debug -- true >/dev/null 2>&1; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "the telemetry debug container did not start on $TELEMETRY_DEBUG_POD"
        sleep 2
    done
}

telemetry_sweep() {
    # The needles: every rehearsal party, the traced payment's amount, and what this step plants itself below - an amount after a JSON
    # escape and a card number, in a record sent to the collector as a replica sends one, so the sweep can go red on this cluster.
    PLANT_AMOUNT="6234.18 EUR"
    PLANT_PAN="5555 5555 5555 4444"
    control="sweep-control-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
    psql_query -c "SELECT i.party_id FROM identity.identity i WHERE i.party_id IS NOT NULL" | tr -d ' ' | grep . > "$WORK/needles.txt" || true
    printf '%s\n%s\n%s\n' "${TRACE_AMOUNT:-731.94}" "6234.18" "$PLANT_PAN" >> "$WORK/needles.txt"
    needles=$(grep -c . "$WORK/needles.txt" || true)
    now=$(date +%s)
    cat > "$WORK/planted-record.json" <<JSON
{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"finapp"}}]},"scopeLogs":[{"scope":{"name":"finapp"},"logRecords":[{"timeUnixNano":"${now}000000000","severityNumber":9,"severityText":"INFO","body":{"stringValue":"{\\"message\\":\\"planted by the sweep\\\\n$PLANT_AMOUNT for card $PLANT_PAN\\",\\"correlationId\\":\\"$control\\"}"},"attributes":[{"key":"finapp.correlation_id","value":{"stringValue":"$control"}}]}]}]}]}
JSON
    planted=$(kube -n finapp exec -i "$TELEMETRY_DEBUG_POD" -c np-obs-debug -- curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        -H 'Content-Type: application/json' --data-binary @- http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/logs \
        < "$WORK/planted-record.json" 2>/dev/null || true)
    log "sweep: the planted record was answered $planted by the collector"
    [ "$planted" = 200 ] || cannot "the collector did not take the planted record: $planted"
    # The record's way through the batch, the collector's own batch and Loki's ingest.
    sleep 20
    : > "$ART/telemetry-sweep-console.txt"
    for pod in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name); do
        kube -n finapp logs "$pod" --all-containers >> "$ART/telemetry-sweep-console.txt" 2>&1 || true
    done
    now=$(date +%s)
    loki_by() {
        # loki_by <correlation> <file>: the records Loki holds under that correlation, counted by their timestamps.
        telemetry_get finapp-loki:3100 "/loki/api/v1/query_range?query=$(urlencode "{service_name=\"finapp\"} | finapp_correlation_id=\"$1\"")&start=$((now - 7200))000000000&end=$((now + 60))000000000&limit=100" \
            > "$2" 2>/dev/null || true
        tr ',' '\n' < "$2" | grep -c '"[0-9]\{19\}"' || true
    }
    # The controls. The planted record arrived, scrubbed: it is in Loki under its correlation, with the scrub's mark and without either
    # needle. And a correlation a replica printed on its console is in Loki too: what a replica ships is found by its correlation.
    controls=$(loki_by "$control" "$ART/telemetry-sweep-planted.json")
    [ "$controls" -ge 1 ] || fail "sweep: the planted record never reached Loki, so the sweep could not have seen it leak"
    grep -qF '[scrubbed]' "$ART/telemetry-sweep-planted.json" || fail "sweep: the planted record reached Loki without the scrub's mark"
    pass "sweep: the planted record reached Loki under its correlation, scrubbed"
    shipped=0
    for correlation in $(grep -o '"correlationId":"[^"]*"' "$ART/telemetry-sweep-console.txt" | sed 's/.*:"\([^"]*\)"/\1/' | grep -v '^sweep-control' | sort -u | head -5); do
        records=$(loki_by "$correlation" "$WORK/console-correlation.json")
        printf '%s\t%s\n' "$correlation" "$records" >> "$ART/telemetry-sweep-console-correlations.tsv"
        shipped=$((shipped + records))
    done
    [ "$shipped" -ge 1 ] || fail "sweep: no correlation a replica printed was found in Loki: a record's correlation never arrives"
    pass "sweep: correlations the replicas printed are in Loki, $shipped records under five of them (telemetry-sweep-console-correlations.tsv)"
    # Loki is searched for the needles themselves, over every record of the last two hours rather than a sample of the latest; the count of
    # records in the same window is the control. A needle's dot is a class, so the pattern needs no escaping.
    pattern=$(sed 's/\./[.]/g' "$WORK/needles.txt" | paste -sd'|' -)
    telemetry_get finapp-loki:3100 "/loki/api/v1/query?query=$(urlencode 'sum(count_over_time({service_name="finapp"}[2h]))')&time=$((now + 1))000000000" \
        > "$ART/telemetry-sweep-loki-count.json" 2>&1 || fail "Loki could not be read for the sweep"
    loki_lines=$(sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)"\].*/\1/p' "$ART/telemetry-sweep-loki-count.json" | head -1)
    telemetry_get finapp-loki:3100 "/loki/api/v1/query_range?query=$(urlencode "{service_name=\"finapp\"} |~ \"$pattern\"")&start=$((now - 7200))000000000&end=$((now + 60))000000000&limit=1000" \
        > "$ART/telemetry-sweep-loki.json" 2>&1 || fail "Loki could not be searched for the needles"
    loki_found=$(tr ',' '\n' < "$ART/telemetry-sweep-loki.json" | grep -c '"[0-9]\{19\}"' || true)
    # Tempo: the latest hundred traces, and every trace of the traced flow's own correlations.
    : > "$ART/telemetry-sweep-tempo.json"
    flow_traces=""
    for correlation in ${c_create:-} ${c_confirm:-} ${c_events:-} ${c_expectation:-} ${c_upload:-} ${c_attest:-}; do
        flow_traces="$flow_traces $(trace_ids_for "{ span.finapp.correlation_id = \"$correlation\" }" | tr '\n' ' ')"
    done
    traces=0
    for trace in $(trace_ids_for "{ }") $flow_traces; do
        telemetry_get finapp-tempo:3200 "/api/traces/$trace" >> "$ART/telemetry-sweep-tempo.json" 2>&1 || true
        printf '\n' >> "$ART/telemetry-sweep-tempo.json"
        traces=$((traces + 1))
    done
    tempo_bytes=$(wc -c < "$ART/telemetry-sweep-tempo.json")
    [ "${loki_lines:-0}" -gt 0 ] || fail "the sweep read no log record from Loki: a sweep over nothing proves nothing"
    [ "$tempo_bytes" -gt 1000 ] || fail "the sweep read no trace from Tempo"
    found=$(grep -F -f "$WORK/needles.txt" -o "$ART/telemetry-sweep-tempo.json" "$ART/telemetry-sweep-console.txt" | sort | uniq -c || true)
    [ "$loki_found" = 0 ] || found="$found Loki: $loki_found records match (telemetry-sweep-loki.json)"
    printf '%s\n' "$found" > "$ART/telemetry-sweep-found.txt"
    if [ -n "$found" ]; then
        fail "sweep: needles exported or printed: $(printf '%s' "$found" | tr '\n' ';' | cut -c1-400)"
    else
        pass "sweep: none of $needles needles (the traced and the planted amounts, a planted card number and every party) in $loki_lines log records, $traces traces (${tempo_bytes} bytes) or any replica's console"
    fi
}

telemetry_scrape() {
    answer=$(telemetry_get finapp-prometheus:9090 "/api/v1/query?query=$(urlencode 'up{job="finapp"}')" 2>&1 || true)
    printf '%s\n' "$answer" > "$ART/telemetry-scrape.json"
    targets=$(printf '%s' "$answer" | tr '{' '\n' | grep -c '"job":"finapp"' || true)
    up=$(printf '%s' "$answer" | tr '{' '\n' | grep '"job":"finapp"' | grep -c '"env":"kind"' || true)
    local_env=$(printf '%s' "$answer" | grep -c '"env":"local"' || true)
    replicas=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name | grep -c . || true)
    ones=$(printf '%s' "$answer" | grep -o '"value":\[[0-9.]*,"1"\]' | grep -c . || true)
    log "scrape: $targets finapp targets, $up of them env=\"kind\", $ones up, $replicas replicas running"
    if [ "$targets" -ge "$replicas" ] && [ "$up" = "$targets" ] && [ "$ones" -ge "$replicas" ] && [ "$local_env" = 0 ]; then
        pass "scrape: the deployed Prometheus reads job finapp, every replica up, env=\"kind\" and no env=\"local\""
    else
        fail "scrape: job finapp $targets targets for $replicas replicas, $ones up, env=kind on $up, env=local on $local_env (telemetry-scrape.json)"
    fi
}

# ---------------------------------------------------------------------------------------------------
# The platform's own signals on the full cluster (P16-TSK-006, its entry widened by THREAT_MODEL.md section 6 item 47 (c); OPERATIONS_RUNBOOK.md
# sections 11.41 and 11.42). platform_signals_plant plants a failing migrator Job and sends the edge requests that carry needles;
# platform_signals then reads the two Job pages firing and routed, the failed run's lines in Loki, and sweeps the edge's, the migrator's and
# the telemetry stack's streams for every needle as telemetry_sweep sweeps the application's, each beside a count of what was read and a
# planted control found scrubbed; then it puts the release's own Job back and reads both pages clear. The load scope runs the plant before
# its load and the reading after its census, so MigratorJobOverdue's seventeen minutes pass under the load rather than after it.
# ---------------------------------------------------------------------------------------------------
SIGNALS_PROM=finapp-prometheus:9090
SIGNALS_AM=finapp-alertmanager:9093
# The needles no platform stream may carry: a card number in the two shapes the scrub reads, the planted run's own, and an amount.
SIGNALS_PAN=5555555555554444
SIGNALS_PAN_SPACED="5555 5555 5555 4444"
SIGNALS_MIGRATOR_PAN=4111111111111111
SIGNALS_AMOUNT=6234.18

signals_value() {
    telemetry_get "$SIGNALS_PROM" "/api/v1/query?query=$(urlencode "$1")" 2>/dev/null | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p' | head -1
}

# signals_state <alertname>: firing, pending or none, as the cluster's Prometheus evaluates the committed rule.
signals_state() {
    [ -n "$(signals_value "max(ALERTS{alertname=\"$1\",alertstate=\"firing\"})")" ] && { echo firing; return; }
    [ -n "$(signals_value "max(ALERTS{alertname=\"$1\",alertstate=\"pending\"})")" ] && { echo pending; return; }
    echo none
}

# signals_await <alertname> <firing|none> <deadline epoch>: 0 once the alert is in that state, 1 at the deadline; each change kept.
signals_await() {
    last=""
    while :; do
        state=$(signals_state "$1")
        if [ "$state" != "$last" ]; then
            printf '%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$1" "$state" >> "$ART/signals-alerts.tsv"
            last=$state
        fi
        if [ "$state" = "$2" ]; then
            return 0
        fi
        [ "$(date +%s)" -lt "$3" ] || return 1
        sleep 15
    done
}

# signals_routed <alertname>: Alertmanager holds the alert on the receiver a page routes to.
signals_routed() {
    deadline=$(( $(date +%s) + 120 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        telemetry_get "$SIGNALS_AM" "/api/v2/alerts?filter=$(urlencode "alertname=\"$1\"")" > "$ART/signals-am-$1.json" 2>/dev/null || true
        grep -q '"name":"placeholder-page"' "$ART/signals-am-$1.json" && return 0
        sleep 10
    done
    return 1
}

# signals_loki <file> <LogQL>: the records Loki holds for the query over the last two hours, kept in the file, and their count; "refused"
# when Loki did not answer it, which no caller may read as none found.
signals_loki() {
    now=$(date +%s)
    telemetry_get finapp-loki:3100 "/loki/api/v1/query_range?query=$(urlencode "$2")&start=$((now - 7200))000000000&end=$((now + 60))000000000&limit=1000" \
        > "$1" 2>&1 || true
    if grep -q '"status":"success"' "$1"; then
        tr ',' '\n' < "$1" | grep -c '"[0-9]\{19\}"' || true
    else
        echo refused
    fi
}

# signals_count <selector>: every record a stream holds over the last two hours, the sweep's control; empty when Loki did not answer.
signals_count() {
    now=$(date +%s)
    telemetry_get finapp-loki:3100 "/loki/api/v1/query?query=$(urlencode "sum(count_over_time($1[2h]))")&time=$((now + 1))000000000" 2>/dev/null \
        | sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)"\].*/\1/p' | head -1
}

platform_signals_plant() {
    : > "$ART/signals-alerts.tsv"
    # The control before the plant: neither page active once the release's own Job has succeeded, so what fires afterwards is the plant's
    # (on a reused cluster an earlier run's plant clears here, the deploy having run the release's Job again).
    for alert in MigratorJobFailed MigratorJobOverdue; do
        signals_await "$alert" none $(( $(date +%s) + 300 )) \
            || fail "signals: $alert still active 5 min after the release's own Job, before any plant: see signals-alerts.tsv"
    done
    pass "signals: before the plant, neither MigratorJobFailed nor MigratorJobOverdue active"
    SIGNALS_FROM=$(date +%s)
    # The migrator Job planted to fail: the release's own Job as rendered, its image the pinned Prometheus image's shell printing migrator-shaped
    # lines - a card number and an amount among them, for the sweep - and exiting 10, which the Job's podFailurePolicy fails at once. Nothing
    # else of the Job is changed. The release's own Job, completed, is removed first: the next one takes its name.
    planted_image="docker.io/$PROMETHEUS_REPOSITORY@$PROMETHEUS_DIGEST"
    current=$(sed -n 's/^ *image: \(finapp-migrator@sha256:[0-9a-f]*\)$/\1/p' "$A/Job.finapp-migrator.yaml" | head -1)
    [ -n "$current" ] || cannot "the rendered migrator Job names no migrator image to replace"
    sed "s#^\( *\)image: $current\$#\1image: $planted_image\n\1command: [\"/bin/sh\", \"-c\", \"echo finapp-migrator schema=platform applied=0 attempts=1; echo finapp-migrator schema=ledger attempts=1 failure=MIGRATION card $SIGNALS_MIGRATOR_PAN for $SIGNALS_AMOUNT EUR; echo finapp-migrator result=MIGRATION_FAILED schema=ledger; exit 10\"]#" \
        "$A/Job.finapp-migrator.yaml" > "$WORK/job-planted.yaml"
    # Counted by the planted line alone: under kube-router the Job also carries an init container whose command is a shell too.
    [ "$(grep -c 'result=MIGRATION_FAILED' "$WORK/job-planted.yaml")" = 1 ] && [ "$(grep -c "image: $planted_image" "$WORK/job-planted.yaml")" = 1 ] \
        || cannot "the planted Job's command could not be written"
    cp "$WORK/job-planted.yaml" "$ART/signals-job-planted.yaml"
    kube -n finapp delete job finapp-migrator --wait=true --timeout=180s >> "$ART/apply.log" 2>&1 || cannot "the release's migrator Job could not be removed"
    apply_files "$WORK/job-planted.yaml"
    log "signals: a migrator Job planted to fail with exit 10, its lines carrying a card number and an amount"
    # The edge's needles, through the real public edge: a routed path carrying a card number (the access line keeps a routed path, which the
    # scrub must cut) and an unrouted one (whose path the scrub drops), each with a query and a header carrying them too (dropped at the proxy).
    : > "$ART/signals-edge-requests.txt"
    for path in "/v1/me/accounts/$SIGNALS_PAN/balance" "/v1/nowhere/$SIGNALS_PAN"; do
        dock run --rm --network kind --add-host "public.finapp.test:$NODE_IP" -v "$(dpath "$CLIENT"):/work:ro" "$CURL_IMAGE" \
            -s -o /dev/null -w '%{http_code}\n' --max-time 20 --cacert /work/edge-ca.crt -H "X-Card: $SIGNALS_PAN_SPACED" \
            "https://public.finapp.test:30443$path?amount=$SIGNALS_AMOUNT%20EUR" >> "$ART/signals-edge-requests.txt" 2>&1 || true
    done
    [ "$(grep -c '^[0-9][0-9][0-9]$' "$ART/signals-edge-requests.txt")" = 2 ] || cannot "the edge's needle requests were not answered: see signals-edge-requests.txt"
    log "signals: two requests carrying a card number and an amount sent through the public edge, answered $(tr '\n' ' ' < "$ART/signals-edge-requests.txt")"
}

platform_signals() {
    # The two pages, as the committed rules decide them over the cluster watcher's series, and routed as the committed routing says.
    signals_await MigratorJobFailed firing $((SIGNALS_FROM + 900)) || fail "signals: MigratorJobFailed did not fire within 15 min of the planted failure: see signals-alerts.tsv"
    signals_routed MigratorJobFailed || fail "signals: MigratorJobFailed fired and Alertmanager did not route it to placeholder-page"
    pass "signals: MigratorJobFailed fired on the planted failure and reached placeholder-page"
    signals_await MigratorJobOverdue firing $((SIGNALS_FROM + 1800)) || fail "signals: MigratorJobOverdue did not fire within 30 min of the plant: see signals-alerts.tsv"
    signals_routed MigratorJobOverdue || fail "signals: MigratorJobOverdue fired and Alertmanager did not route it to placeholder-page"
    pass "signals: MigratorJobOverdue fired once no migrator Job had succeeded for its deadline, and reached placeholder-page"
    # The failed run's lines and the Job's events, in Loki under the migrator's stream, scrubbed.
    migrator=$(signals_loki "$ART/signals-loki-migrator.json" '{service_name="finapp-migrator"}')
    [ "$migrator" != refused ] || fail "signals: Loki refused the migrator stream's query: see signals-loki-migrator.json"
    grep -q 'MIGRATION_FAILED' "$ART/signals-loki-migrator.json" || fail "signals: the planted run's lines are not in Loki under finapp-migrator: see signals-loki-migrator.json"
    grep -q 'k8s_container_exit_code' "$ART/signals-loki-migrator.json" || fail "signals: the migrator's records carry no exit code: see signals-loki-migrator.json"
    grep -qF '[scrubbed]' "$ART/signals-loki-migrator.json" || fail "signals: the planted run's card number and amount reached Loki without the scrub's mark"
    pass "signals: the failed run's lines and the Job's events in Loki under finapp-migrator ($migrator records), the card number and the amount scrubbed"
    # The stack's own streams carry only what goes wrong, so each is given a control: a record planted on the collector's platform receiver from
    # a pod labelled as an edge controller (the receiver's one door besides the watcher), carrying a card number and an amount.
    : > "$WORK/signals-markers.txt"
    for stream in finapp-collector finapp-cluster-watch; do
        marker="signals-control-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
        printf '{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"%s"}}]},"scopeLogs":[{"scope":{"name":"%s"},"logRecords":[{"timeUnixNano":"%s000000000","severityNumber":13,"severityText":"WARN","body":{"stringValue":"%s planted by the sweep: card %s for %s EUR"}}]}]}]}\n' \
            "$stream" "$stream" "$(date +%s)" "$marker" "$SIGNALS_PAN_SPACED" "$SIGNALS_AMOUNT" > "$WORK/signals-plant.json"
        code=$(kube -n finapp-edge exec -i np-edge -c probe -- curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Content-Type: application/json' \
            --data-binary @- http://finapp-collector.finapp-observability.svc.cluster.local:4319/v1/logs < "$WORK/signals-plant.json" 2>/dev/null || true)
        [ "$code" = 200 ] || cannot "the collector's platform receiver did not take the control for $stream: $code"
        printf '%s %s\n' "$stream" "$marker" >> "$WORK/signals-markers.txt"
    done
    sleep 25
    while read -r stream marker; do
        found=$(signals_loki "$ART/signals-control-$stream.json" "{service_name=\"$stream\"} |= \"$marker\"")
        [ "$found" != refused ] && [ "$found" -ge 1 ] || fail "signals: the control planted on $stream never reached Loki, so the sweep could not have seen it leak"
        grep -qF '[scrubbed]' "$ART/signals-control-$stream.json" || fail "signals: the control planted on $stream reached Loki without the scrub's mark"
    done < "$WORK/signals-markers.txt"
    # The sweep, stream by stream, as telemetry_sweep reads the application's: every record of the last two hours searched for every needle, in
    # its line and, for the edge, in the request path its records keep, beside the count of what was read.
    psql_query -c "SELECT i.party_id FROM identity.identity i WHERE i.party_id IS NOT NULL" | tr -d ' ' | grep . > "$WORK/signals-needles.txt" || true
    printf '%s\n%s\n%s\n%s\n' "$SIGNALS_PAN" "$SIGNALS_PAN_SPACED" "$SIGNALS_MIGRATOR_PAN" "$SIGNALS_AMOUNT" >> "$WORK/signals-needles.txt"
    pattern=$(sed 's/\./[.]/g' "$WORK/signals-needles.txt" | paste -sd'|' -)
    needles=$(grep -c . "$WORK/signals-needles.txt")
    : > "$ART/signals-sweep.tsv"
    for stream in finapp-edge finapp-migrator finapp-collector finapp-cluster-watch; do
        read_count=$(signals_count "{service_name=\"$stream\"}")
        in_lines=$(signals_loki "$ART/signals-sweep-$stream.json" "{service_name=\"$stream\"} |~ \"$pattern\"")
        in_paths=0
        if [ "$stream" = finapp-edge ]; then
            in_paths=$(signals_loki "$ART/signals-sweep-$stream-paths.json" "{service_name=\"$stream\"} | RequestPath=~\".*($pattern).*\"")
        fi
        printf '%s\t%s\t%s\t%s\n' "$stream" "${read_count:-0}" "$in_lines" "$in_paths" >> "$ART/signals-sweep.tsv"
        [ "$in_lines" != refused ] && [ "$in_paths" != refused ] || fail "signals: Loki refused the sweep of $stream: see signals-sweep-$stream*.json"
        [ "${read_count:-0}" -gt 0 ] || fail "signals: the sweep read no record of $stream: a sweep over nothing proves nothing"
        [ "$in_lines" = 0 ] && [ "$in_paths" = 0 ] || fail "signals: a needle in $stream: $in_lines lines and $in_paths request paths: see signals-sweep-$stream*.json"
    done
    # The edge's control: the routed request carrying the card number is in its stream with the path scrubbed, so the path search could have seen it.
    scrubbed_paths=$(signals_loki "$ART/signals-edge-scrubbed.json" '{service_name="finapp-edge"} | RequestPath=~".*scrubbed.*"')
    [ "$scrubbed_paths" != refused ] && [ "$scrubbed_paths" -ge 1 ] \
        || fail "signals: the edge's planted request is not in Loki with its path scrubbed, so the path search could not have seen it"
    pass "signals: none of $needles needles in the edge's, the migrator's, the collectors' or the watcher's streams ($(awk -F'\t' '{printf "%s %s records; ", $1, $2}' "$ART/signals-sweep.tsv")), each stack stream's planted control there scrubbed, the edge's planted request there with its path scrubbed"
    # Put back: the planted Job removed and the release's own run again, which succeeds on a schema already current; both pages then clear.
    kube -n finapp delete job finapp-migrator --wait=true --timeout=180s >> "$ART/apply.log" 2>&1 || cannot "the planted migrator Job could not be removed"
    apply_files "$A/Job.finapp-migrator.yaml"
    wait_job finapp finapp-migrator 900 signals-migrator
    signals_await MigratorJobFailed none $(( $(date +%s) + 600 )) || fail "signals: MigratorJobFailed still $(signals_state MigratorJobFailed) 10 min after the release's Job succeeded"
    signals_await MigratorJobOverdue none $(( $(date +%s) + 600 )) || fail "signals: MigratorJobOverdue still $(signals_state MigratorJobOverdue) 10 min after the release's Job succeeded"
    pass "signals: the release's own migrator Job run again and succeeded, and both pages cleared"
}
